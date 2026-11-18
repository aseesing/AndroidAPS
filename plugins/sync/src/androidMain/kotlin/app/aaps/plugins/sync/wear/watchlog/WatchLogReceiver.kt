// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.watchlog

import app.aaps.plugins.sync.wear.watchapp.ChannelStop
import app.aaps.plugins.sync.wear.watchapp.ChannelWatchdog
import app.aaps.plugins.sync.wear.watchapp.TransferProgress
import app.aaps.plugins.sync.wear.watchapp.WatchAppSender
import app.aaps.plugins.sync.wear.watchapp.WatchChannel
import app.aaps.plugins.sync.wear.watchapp.WatchChannelOpener
import app.aaps.plugins.sync.wear.watchapp.WifiBodyServer
import app.aaps.plugins.sync.wear.watchapp.WifiBodyServerFactory
import app.aaps.plugins.sync.wear.watchapp.runWithWatchdog
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
import app.aaps.shared.impl.weardata.WatchLogProtocol
import app.aaps.shared.impl.weardata.WatchLogProtocol.Reply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** What a watch log download is doing, for the screen */
sealed class WatchLogState {

    data object Idle : WatchLogState()
    data object Connecting : WatchLogState()

    /** The watch is putting its log files in a zip */
    data class Preparing(val watchName: String) : WatchLogState()

    /**
     * @param secondsLeft estimate from the speed so far; null while too little is received to tell
     * @param overWifi the zip comes over Wi-Fi; otherwise over the Wear OS channel, which is slower
     */
    data class Receiving(val watchName: String, val percent: Int, val secondsLeft: Int? = null, val overWifi: Boolean = false) : WatchLogState()

    /** All received and checked; the phone saves it now */
    data class Received(val watchName: String, val overWifi: Boolean) : WatchLogState()

    /** Saved in the Downloads folder of the phone as [fileName] */
    data class Saved(val fileName: String) : WatchLogState()
    data class Failed(val reason: Reason, val detail: String = "") : WatchLogState()

    enum class Reason {

        /** No watch is connected */
        NO_WATCH,

        /** The watch does not answer: its app is older than this feature, or it is not running */
        NO_ANSWER,

        /** The watch answered first, but then stopped */
        TIMEOUT,

        /** The watch could not send its log, see the detail */
        WATCH_ERROR,

        /** Anything else, see the detail */
        ERROR
    }

    val isBusy: Boolean get() = this is Connecting || this is Preparing || this is Receiving || this is Received
}

/**
 * Downloads the log zip of the watch app into a file, see [WatchLogProtocol].
 *
 * The zip comes over Wi-Fi when the phone has a Wi-Fi address and the watch can reach it, and
 * otherwise over the Wear OS channel, which is Bluetooth and much slower. That is why the channel
 * gets only the newest [filesOverChannel] log files.
 *
 * Reads on a channel block and cannot be cancelled. A [ChannelWatchdog] closes the channel when
 * nothing moves for too long, which ends the blocked call with an [IOException].
 *
 * @param wifiServers starts the Wi-Fi server; null for the channel only
 * @param filesOverWifi how many log files, newest first, the watch sends over Wi-Fi
 * @param filesOverChannel how many log files, newest first, the watch sends over the channel
 * @param answerTimeoutMs how long the watch may take to accept the request; a watch app without
 *   this feature never does
 * @param prepareTimeoutMs how long the watch may take after that to connect over Wi-Fi, or to give
 *   up on it, and to zip its log
 * @param stallTimeoutMs how long a running transfer may stand still
 * @param nowMs a clock in milliseconds, for the time estimate and the watchdog
 */
class WatchLogReceiver(
    private val opener: WatchChannelOpener,
    private val wifiServers: WifiBodyServerFactory? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val filesOverWifi: Int = WatchLogProtocol.ALL_FILES,
    private val filesOverChannel: Int = FILES_OVER_CHANNEL,
    private val answerTimeoutMs: Long = 30_000,
    private val prepareTimeoutMs: Long = 150_000,
    private val stallTimeoutMs: Long = 60_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    /**
     * @param target where the zip goes; overwritten
     * @param onState gets every step, the last one is also returned
     * @return [WatchLogState.Received] with the checked zip in [target], or [WatchLogState.Failed]
     */
    suspend fun receive(target: File, onState: (WatchLogState) -> Unit): WatchLogState {
        fun finish(state: WatchLogState) = state.also(onState)

        onState(WatchLogState.Connecting)
        // No Wi-Fi server is no error: the zip then comes over the channel
        val server = withContext(ioDispatcher) { runCatching { wifiServers?.start() }.getOrNull() }
        try {
            val channel = try {
                opener.open(WatchLogProtocol.PATH)
            } catch (e: Exception) {
                return finish(WatchLogState.Failed(WatchLogState.Reason.NO_WATCH, e.message ?: ""))
            } ?: return finish(WatchLogState.Failed(WatchLogState.Reason.NO_WATCH))

            return finish(runWithWatchdog(channel, nowMs, ioDispatcher, onStop = { stop, e ->
                when (stop) {
                    ChannelStop.NO_ANSWER -> WatchLogState.Failed(WatchLogState.Reason.NO_ANSWER)
                    ChannelStop.TIMEOUT   -> WatchLogState.Failed(WatchLogState.Reason.TIMEOUT)
                    ChannelStop.ERROR     -> WatchLogState.Failed(WatchLogState.Reason.ERROR, e.message ?: e.javaClass.simpleName)
                }
            }) { watchdog -> transfer(channel, server, target, watchdog, onState) })
        } finally {
            runCatching { server?.close() }
        }
    }

    private suspend fun transfer(
        channel: WatchChannel,
        server: WifiBodyServer?,
        target: File,
        watchdog: ChannelWatchdog,
        onState: (WatchLogState) -> Unit
    ): WatchLogState {
        watchdog.extend(answerTimeoutMs)
        val offer = server?.let { WifiOffer(it.addresses, it.port, WatchAppSender.newToken()) }
        WatchLogProtocol.writeRequest(channel.output, WatchLogProtocol.Request(filesOverWifi, filesOverChannel, offer))
        when (val first = WatchLogProtocol.readReply(channel.input)) {
            Reply.Accepted  -> watchdog.answered = true
            is Reply.Failed -> return WatchLogState.Failed(WatchLogState.Reason.WATCH_ERROR, first.message)
            else            -> throw IOException("Unexpected reply $first")
        }

        // The watch turns on its Wi-Fi, or gives up on it, and zips its log
        onState(WatchLogState.Preparing(channel.watchName))
        watchdog.extend(prepareTimeoutMs)
        val reply =
            if (offer != null && server != null)
                receiveOverWifi(offer, server, channel, target, watchdog, onState) ?: return WatchLogState.Received(channel.watchName, overWifi = true)
            else WatchLogProtocol.readReply(channel.input)

        return when (reply) {
            is Reply.ChannelBody -> {
                watchdog.extend(stallTimeoutMs)
                onState(WatchLogState.Receiving(channel.watchName, 0))
                val progress = receivingProgress(channel.watchName, reply.zip.size, overWifi = false, onState)
                receiveZip(channel.input, reply.zip, target) { received ->
                    watchdog.extend(stallTimeoutMs)
                    progress.report(received)
                }
                WatchLogState.Received(channel.watchName, overWifi = false)
            }

            is Reply.Failed      -> WatchLogState.Failed(WatchLogState.Reason.WATCH_ERROR, reply.message)
            else                 -> throw IOException("Unexpected reply $reply")
        }
    }

    /**
     * Waits for the watch on the Wi-Fi server while the channel says what happens.
     *
     * @return null when the zip came over Wi-Fi and is checked; otherwise the reply that says what comes next
     */
    private suspend fun receiveOverWifi(
        offer: WifiOffer,
        server: WifiBodyServer,
        channel: WatchChannel,
        target: File,
        watchdog: ChannelWatchdog,
        onState: (WatchLogState) -> Unit
    ): Reply? {
        // Size and hash come over the channel, after the watch connected and made the zip
        val zipInfo = CompletableDeferred<WatchLogProtocol.Zip>()
        return coroutineScope {
            val receiving = async {
                try {
                    server.accept(offer.token).use { socket ->
                        // The watchdog only closes the channel; a stalled socket must end by itself
                        socket.soTimeout = stallTimeoutMs.toInt()
                        val zip = zipInfo.await()
                        val progress = receivingProgress(channel.watchName, zip.size, overWifi = true, onState)
                        receiveZip(socket.getInputStream(), zip, target) { received ->
                            watchdog.extend(stallTimeoutMs)
                            progress.report(received)
                        }
                    }
                    null
                } catch (e: IOException) {
                    // The watch gave up on Wi-Fi, or the server is closed: its reply says what comes next
                    e
                }
            }
            try {
                var next = WatchLogProtocol.readReply(channel.input)
                if (next is Reply.WifiZip) {
                    watchdog.extend(stallTimeoutMs)
                    onState(WatchLogState.Receiving(channel.watchName, 0, overWifi = true))
                    zipInfo.complete(next.zip)
                    next = WatchLogProtocol.readReply(channel.input)
                    // All is written on the watch side; the last bytes may still be on their way
                    if (next == Reply.WifiDone) {
                        receiving.await()?.let { throw IOException("The log over Wi-Fi is not complete: ${it.message}") }
                        return@coroutineScope null
                    }
                }
                next
            } finally {
                // Ends a waiting server or read, and a wait for the zip that will not come; the scope
                // then waits for it, so no Wi-Fi state comes after the result
                server.close()
                receiving.cancel()
            }
        }
    }

    /** Writes exactly [WatchLogProtocol.Zip.size] bytes of [input] to [target], and checks them */
    private fun receiveZip(input: InputStream, zip: WatchLogProtocol.Zip, target: File, onProgress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedOutputStream(DigestOutputStream(FileOutputStream(target), digest)).use { output ->
            WatchAppUpdateProtocol.copyBody(input, output, zip.size, onProgress)
        }
        if (WatchAppUpdateProtocol.hex(digest.digest()) != zip.sha256) throw IOException("The log zip has a wrong SHA-256")
    }

    /** Turns bytes received into a [WatchLogState.Receiving] for each new percent, with the time left */
    private fun receivingProgress(watchName: String, size: Long, overWifi: Boolean, onState: (WatchLogState) -> Unit) =
        TransferProgress(size, nowMs) { percent, secondsLeft -> onState(WatchLogState.Receiving(watchName, percent, secondsLeft, overWifi)) }

    companion object {

        /**
         * Over Bluetooth only the newest log files: the current log and the parts before it. One
         * part is at most 5 MB of text, which zips to a small part of that.
         */
        const val FILES_OVER_CHANNEL = 10
    }
}
