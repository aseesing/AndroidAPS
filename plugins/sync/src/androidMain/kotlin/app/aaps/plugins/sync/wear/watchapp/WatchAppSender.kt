package app.aaps.plugins.sync.wear.watchapp

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 */
data class EmbeddedWatchApp(val label: String, val bodySize: Long)

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
    data class Sending(val watchName: String, val percent: Int) : WatchAppUpdateState()

    /** All sent, the watch is writing the APK to its package installer */
    data class Installing(val watchName: String) : WatchAppUpdateState()

    /** The watch took the update. It installs it now, or asks the user to confirm first. */
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
 * Reads and writes on a channel block and cannot be cancelled. A watchdog closes the channel
 * when nothing moves for too long, which ends the blocked call with an [IOException].
 *
 * @param answerTimeoutMs how long the watch may take to say it is ready
 * @param stallTimeoutMs how long a running transfer may stand still
 * @param installTimeoutMs how long the watch may take to give the APK to its installer
 */
class WatchAppSender(
    private val source: EmbeddedWatchAppSource,
    private val opener: WatchChannelOpener,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val answerTimeoutMs: Long = 30_000,
    private val stallTimeoutMs: Long = 60_000,
    private val installTimeoutMs: Long = 120_000
) {

    /**
     * @param onState gets every step, the last one is also returned
     * @return [WatchAppUpdateState.Sent] or [WatchAppUpdateState.Failed]
     */
    suspend fun send(onState: (WatchAppUpdateState) -> Unit): WatchAppUpdateState {
        fun finish(state: WatchAppUpdateState) = state.also(onState)

        val app = source.info() ?: return finish(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NOT_EMBEDDED))
        onState(WatchAppUpdateState.Connecting)
        val channel = try {
            opener.open(WatchAppUpdateProtocol.PATH)
        } catch (e: Exception) {
            return finish(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_WATCH, e.message ?: ""))
        } ?: return finish(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_WATCH))

        val watchdog = Watchdog(channel)
        return try {
            coroutineScope {
                val watchdogJob = launch { watchdog.run() }
                try {
                    finish(withContext(ioDispatcher) { transfer(app, channel, watchdog, onState) })
                } finally {
                    watchdogJob.cancel()
                }
            }
        } catch (e: IOException) {
            finish(
                when {
                    watchdog.fired && !watchdog.answered -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_ANSWER)
                    watchdog.fired                       -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.TIMEOUT)
                    else                                 -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.ERROR, e.message ?: e.javaClass.simpleName)
                }
            )
        } finally {
            runCatching { channel.close() }
        }
    }

    private fun transfer(app: EmbeddedWatchApp, channel: WatchChannel, watchdog: Watchdog, onState: (WatchAppUpdateState) -> Unit): WatchAppUpdateState {
        watchdog.extend(answerTimeoutMs)
        WatchAppUpdateProtocol.writeHeader(channel.output, WatchAppUpdateProtocol.Header(app.label, app.bodySize))
        when (val reply = WatchAppUpdateProtocol.readReply(channel.input)) {
            Reply.Ready     -> watchdog.answered = true
            is Reply.Failed -> return WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.WATCH_ERROR, reply.message)
            else            -> throw IOException("Unexpected reply $reply")
        }

        var percent = -1
        watchdog.extend(stallTimeoutMs)
        source.open().use { body ->
            WatchAppUpdateProtocol.copyBody(body, channel.output, app.bodySize) { copied ->
                watchdog.extend(stallTimeoutMs)
                val now = (copied * 100 / app.bodySize).toInt()
                if (now != percent) {
                    percent = now
                    onState(WatchAppUpdateState.Sending(channel.watchName, now))
                }
            }
        }

        onState(WatchAppUpdateState.Installing(channel.watchName))
        watchdog.extend(installTimeoutMs)
        return when (val reply = WatchAppUpdateProtocol.readReply(channel.input)) {
            Reply.Committed -> WatchAppUpdateState.Sent(channel.watchName)
            is Reply.Failed -> WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.WATCH_ERROR, reply.message)
            else            -> throw IOException("Unexpected reply $reply")
        }
    }

    /** Closes [channel] when the deadline passes without [extend] */
    private class Watchdog(private val channel: WatchChannel) {

        @Volatile private var deadline = Long.MAX_VALUE
        @Volatile var fired = false
            private set

        /** Whether the watch said it is ready; a timeout before that means it does not know the update */
        @Volatile var answered = false

        fun extend(timeoutMs: Long) {
            deadline = now() + timeoutMs
        }

        suspend fun run() {
            while (now() < deadline) delay(CHECK_INTERVAL_MS)
            fired = true
            runCatching { channel.close() }
        }

        private fun now() = System.nanoTime() / 1_000_000

        companion object {

            const val CHECK_INTERVAL_MS = 100L
        }
    }
}
