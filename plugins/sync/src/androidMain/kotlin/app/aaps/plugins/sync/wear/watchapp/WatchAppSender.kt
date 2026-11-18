package app.aaps.plugins.sync.wear.watchapp

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The watch APK built into this phone app.
 *
 * @param label for the user: the version and the file name of the watch APK
 * @param bodySize size of the gzip compressed APK in bytes
 * @param apkSize size of the APK itself in bytes
 */
data class EmbeddedWatchApp(val label: String, val bodySize: Long, val apkSize: Long)

/** Where the built-in watch APK comes from: the assets in the app, a fake in tests */
interface EmbeddedWatchAppSource {

    /** The built-in watch app, or null when this build has none */
    fun info(): EmbeddedWatchApp?

    /** The gzip compressed APK, [EmbeddedWatchApp.bodySize] bytes */
    fun open(): InputStream
}

/** An open channel to the watch. [close] must also stop a read or write that is waiting. */
interface WatchChannel : Closeable {

    val watchName: String
    val input: InputStream
    val output: OutputStream
}

/** Opens a channel to the connected watch */
fun interface WatchChannelOpener {

    /** @return null when no watch is reachable */
    suspend fun open(path: String): WatchChannel?
}

/** What a watch app update is doing, for the screen */
sealed class WatchAppUpdateState {

    data object Idle : WatchAppUpdateState()
    data object Connecting : WatchAppUpdateState()
    /** @param secondsLeft estimate from the speed so far; null while too little is sent to tell */
    data class Sending(val watchName: String, val percent: Int, val secondsLeft: Int? = null) : WatchAppUpdateState()

    /** All sent, the watch is writing the APK to its package installer */
    data class Installing(val watchName: String) : WatchAppUpdateState()

    /**
     * The watch handed the APK to its package installer. Android installs it now, or asks the user
     * on the watch to confirm first. The phone does not learn the end result.
     */
    data class Sent(val watchName: String) : WatchAppUpdateState()
    data class Failed(val reason: Reason, val detail: String = "") : WatchAppUpdateState()

    enum class Reason {

        /** This build has no watch app inside */
        NOT_EMBEDDED,

        /** No watch is connected */
        NO_WATCH,

        /** The watch does not answer: its app is older than this feature, or it is not running */
        NO_ANSWER,

        /** The watch answered first, but then stopped */
        TIMEOUT,

        /** All was sent, but the watch did not say whether the installer took it: the user must look on the watch */
        CHECK_WATCH,

        /** The watch app knows an older update format: it needs one install with adb */
        OLD_WATCH_APP,

        /** The watch app knows a newer update format: the phone app is older than the watch app */
        OLD_PHONE_APP,

        /** The watch is still busy with another update */
        BUSY,

        /** The watch refused the update, see the detail */
        WATCH_ERROR,

        /** Anything else, see the detail */
        ERROR
    }

    val isBusy: Boolean get() = this is Connecting || this is Sending || this is Installing
}

/**
 * Sends the built-in watch APK to the watch, see [WatchAppUpdateProtocol].
 *
 * The body goes over the Wear OS channel, which is Bluetooth: a few minutes for a whole APK.
 *
 * Reads and writes on a channel block and cannot be cancelled. A watchdog closes the channel
 * when nothing moves for too long, which ends the blocked call with an [IOException].
 *
 * @param answerTimeoutMs how long the watch may take to say it is ready
 * @param stallTimeoutMs how long a running transfer may stand still
 * @param installTimeoutMs how long the watch may take to give the APK to its installer
 * @param nowMs a clock in milliseconds, for the time estimate and the watchdog
 */
class WatchAppSender(
    private val source: EmbeddedWatchAppSource,
    private val opener: WatchChannelOpener,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val answerTimeoutMs: Long = 30_000,
    private val stallTimeoutMs: Long = 60_000,
    private val installTimeoutMs: Long = 120_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    /**
     * @param onState gets every step, the last one is also returned
     * @return [WatchAppUpdateState.Sent] or [WatchAppUpdateState.Failed]
     */
    suspend fun send(onState: (WatchAppUpdateState) -> Unit): WatchAppUpdateState {
        fun finish(state: WatchAppUpdateState) = state.also(onState)

        val app = source.info() ?: return finish(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NOT_EMBEDDED))
        onState(WatchAppUpdateState.Connecting)
        val sha256 = try {
            withContext(ioDispatcher) { source.open().use { WatchAppUpdateProtocol.sha256(it) } }
        } catch (e: IOException) {
            return finish(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.ERROR, e.message ?: e.javaClass.simpleName))
        }
        return finish(attempt(app, WatchAppUpdateProtocol.Header(app.label, app.bodySize, app.apkSize, sha256), onState))
    }

    /** Opens the channel to the watch and runs the transfer under a [ChannelWatchdog] */
    private suspend fun attempt(app: EmbeddedWatchApp, header: WatchAppUpdateProtocol.Header, onState: (WatchAppUpdateState) -> Unit): WatchAppUpdateState {
        val channel = try {
            opener.open(WatchAppUpdateProtocol.PATH)
        } catch (e: Exception) {
            return WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_WATCH, e.message ?: "")
        } ?: return WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_WATCH)

        // Set once all is sent: from then on a lost channel does not tell whether the installer took the APK
        var installing = false
        val track: (WatchAppUpdateState) -> Unit = { state ->
            installing = state is WatchAppUpdateState.Installing
            onState(state)
        }
        return runWithWatchdog(channel, nowMs, ioDispatcher, onStop = { stop, e ->
            when {
                installing                  -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.CHECK_WATCH)
                stop == ChannelStop.NO_ANSWER -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_ANSWER)
                stop == ChannelStop.TIMEOUT   -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.TIMEOUT)
                else                        -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.ERROR, e.message ?: e.javaClass.simpleName)
            }
        }) { watchdog -> transfer(app, header, channel, watchdog, track) }
    }

    private fun transfer(
        app: EmbeddedWatchApp,
        header: WatchAppUpdateProtocol.Header,
        channel: WatchChannel,
        watchdog: ChannelWatchdog,
        onState: (WatchAppUpdateState) -> Unit
    ): WatchAppUpdateState {
        watchdog.extend(answerTimeoutMs)
        WatchAppUpdateProtocol.writeHeader(channel.output, header)
        when (val reply = WatchAppUpdateProtocol.readReply(channel.input)) {
            Reply.Ready     -> watchdog.answered = true
            is Reply.Failed -> return refusal(reply.message)
            else            -> throw IOException("Unexpected reply $reply")
        }
        return sendOverChannel(app, channel, watchdog, onState)
    }

    private fun sendOverChannel(app: EmbeddedWatchApp, channel: WatchChannel, watchdog: ChannelWatchdog, onState: (WatchAppUpdateState) -> Unit): WatchAppUpdateState {
        watchdog.extend(stallTimeoutMs)
        onState(WatchAppUpdateState.Sending(channel.watchName, 0))
        val progress = sendingProgress(channel.watchName, app.bodySize, onState)
        source.open().use { body ->
            WatchAppUpdateProtocol.copyBody(body, channel.output, app.bodySize) { copied ->
                watchdog.extend(stallTimeoutMs)
                progress.report(copied)
            }
        }

        onState(WatchAppUpdateState.Installing(channel.watchName))
        watchdog.extend(installTimeoutMs)
        return when (val reply = WatchAppUpdateProtocol.readReply(channel.input)) {
            Reply.Committed -> WatchAppUpdateState.Sent(channel.watchName)
            is Reply.Failed -> refusal(reply.message)
            else            -> throw IOException("Unexpected reply $reply")
        }
    }

    /** Turns bytes sent into a [WatchAppUpdateState.Sending] for each new percent, with the time left */
    private fun sendingProgress(watchName: String, size: Long, onState: (WatchAppUpdateState) -> Unit) =
        TransferProgress(size, nowMs) { percent, secondsLeft -> onState(WatchAppUpdateState.Sending(watchName, percent, secondsLeft)) }

    companion object {

        /** What a refusal of the watch means for the user */
        internal fun refusal(message: String): WatchAppUpdateState.Failed =
            when {
                message == WatchAppUpdateProtocol.BUSY                  -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.BUSY)
                message.contains(WatchAppUpdateProtocol.UNKNOWN_FORMAT) -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.OLD_WATCH_APP, message)
                message.contains(WatchAppUpdateProtocol.OLD_FORMAT)     -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.OLD_PHONE_APP, message)
                else                                                    -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.WATCH_ERROR, message)
            }

        /** No estimate before this much is sent: the start of a transfer is not a good measure of its speed */
        const val ESTIMATE_AFTER_PERCENT = 3
        const val ESTIMATE_AFTER_MS = 3_000L

        /**
         * The time left at the speed so far, or null while too little is sent to tell.
         *
         * @param elapsedMs time since the first block of the body
         */
        fun secondsLeft(copied: Long, size: Long, elapsedMs: Long): Int? {
            if (copied <= 0 || copied * 100 < size * ESTIMATE_AFTER_PERCENT || elapsedMs < ESTIMATE_AFTER_MS) return null
            return ((size - copied) * elapsedMs / copied / 1000).toInt()
        }
    }
}
