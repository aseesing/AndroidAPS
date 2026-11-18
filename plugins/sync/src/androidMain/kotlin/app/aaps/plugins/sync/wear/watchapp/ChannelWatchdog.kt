// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.watchapp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/**
 * Closes [channel] when the deadline passes without [extend].
 *
 * Reads and writes on a Wear OS channel block and cannot be cancelled. Closing the channel ends
 * them with an `IOException`. Used by the watch app update and by the watch log download.
 */
internal class ChannelWatchdog(private val channel: Closeable, private val nowMs: () -> Long) {

    @Volatile private var deadline = Long.MAX_VALUE
    @Volatile var fired = false
        private set

    /** Whether the watch answered; a timeout before that means it does not know the request */
    @Volatile var answered = false

    fun extend(timeoutMs: Long) {
        deadline = nowMs() + timeoutMs
    }

    suspend fun run() {
        while (nowMs() < deadline) delay(CHECK_INTERVAL_MS)
        fired = true
        runCatching { channel.close() }
    }

    companion object {

        const val CHECK_INTERVAL_MS = 100L
    }
}

/** How a transfer under a [ChannelWatchdog] ended with an `IOException` */
internal enum class ChannelStop {

    /** The watch never answered: its app does not know the request, or it is not running */
    NO_ANSWER,

    /** The watch answered, but then nothing moved for too long */
    TIMEOUT,

    /** Something else; see the exception */
    ERROR
}

/**
 * Runs [block] on [ioDispatcher] while a [ChannelWatchdog] guards [channel], and closes the channel
 * at the end. An `IOException` becomes [onStop], with why the transfer stopped.
 */
internal suspend fun <T> runWithWatchdog(
    channel: WatchChannel,
    nowMs: () -> Long,
    ioDispatcher: CoroutineDispatcher,
    onStop: (ChannelStop, IOException) -> T,
    block: suspend (ChannelWatchdog) -> T
): T {
    val watchdog = ChannelWatchdog(channel, nowMs)
    return try {
        coroutineScope {
            val watchdogJob = launch { watchdog.run() }
            try {
                withContext(ioDispatcher) { block(watchdog) }
            } finally {
                watchdogJob.cancel()
            }
        }
    } catch (e: IOException) {
        onStop(
            when {
                watchdog.fired && !watchdog.answered -> ChannelStop.NO_ANSWER
                watchdog.fired                       -> ChannelStop.TIMEOUT
                else                                 -> ChannelStop.ERROR
            }, e
        )
    } finally {
        runCatching { channel.close() }
    }
}
