package app.aaps.wear.watchlog

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.shared.impl.weardata.WatchLogProtocol
import app.aaps.wear.selfupdate.PhoneChannelStreams
import app.aaps.wear.selfupdate.WifiBodyFetcher
import ch.qos.logback.classic.LoggerContext
import com.google.android.gms.wearable.ChannelClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the log of this watch app to the phone app when it asks. See [WatchLogProtocol].
 *
 * Only the paired phone app with the same signing key can open a Wear OS channel to this app, so
 * only it gets the log. Over Wi-Fi the log is encrypted with a key from that channel.
 *
 * @param zipper makes the zip; it writes [zipFile], which is deleted after every download
 * @param scope runs the downloads, off the main thread
 * @param closeDelayMs how long a channel stays open after the last answer, so the phone can read it
 */
@SingleIn(AppScope::class)
class WatchLogUpload internal constructor(
    private val aapsLogger: AAPSLogger,
    private val streams: PhoneChannelStreams,
    private val wifi: WifiBodyFetcher,
    private val zipper: LogZipSource,
    private val zipFile: File,
    private val scope: CoroutineScope,
    private val closeDelayMs: Long
) {

    @Inject
    constructor(context: Context, aapsLogger: AAPSLogger, streams: PhoneChannelStreams, wifi: WifiBodyFetcher) : this(
        aapsLogger, streams, wifi, File(context.cacheDir, ZIP_NAME), CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private constructor(aapsLogger: AAPSLogger, streams: PhoneChannelStreams, wifi: WifiBodyFetcher, zipFile: File, scope: CoroutineScope) :
        this(aapsLogger, streams, wifi, WatchLogZipper(::logbackDirectory, zipFile), zipFile, scope, CLOSE_DELAY_MS)

    private val running = AtomicBoolean(false)

    /**
     * Called for every channel the phone opens; takes only the log channel.
     *
     * @return the work on the channel, or null for another channel
     */
    fun onChannelOpened(channel: ChannelClient.Channel): Job? {
        if (channel.path != WatchLogProtocol.PATH) return null
        if (!running.compareAndSet(false, true)) {
            aapsLogger.warn(LTag.WEAR, "Watch log: a download is running already")
            return scope.launch { refuseBusy(channel) }
        }
        return scope.launch {
            var released = false
            // Once only: after the release a new download may already use the zip file
            fun release() {
                if (released) return
                released = true
                zipFile.delete()
                running.set(false)
            }
            try {
                val input = streams.input(channel)
                val output = streams.output(channel)
                val task = WatchLogSendTask(zipper = zipper, wifi = wifi, log = { aapsLogger.warn(LTag.WEAR, "Watch log: $it") })
                when (val result = task.send(input, output)) {
                    is WatchLogSendTask.Result.Sent   ->
                        aapsLogger.info(LTag.WEAR, "Watch log: sent ${result.fileCount} files, ${result.size} bytes, over ${if (result.overWifi) "Wi-Fi" else "the channel"}")

                    is WatchLogSendTask.Result.Failed -> aapsLogger.error(LTag.WEAR, "Watch log: ${result.message}")
                }
                // The work is done: a new download may start while this channel waits to close
                release()
                // The phone closes the channel after it read everything; give it that time
                delay(closeDelayMs)
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "Watch log failed", e)
            } finally {
                runCatching { streams.close(channel) }
                release()
            }
        }
    }

    /** Tells the phone that a download is running, so it does not wait for nothing */
    private suspend fun refuseBusy(channel: ChannelClient.Channel) {
        try {
            WatchLogProtocol.writeReply(streams.output(channel), WatchLogProtocol.Reply.Failed(WatchLogProtocol.BUSY))
            delay(closeDelayMs)
        } catch (e: Exception) {
            aapsLogger.warn(LTag.WEAR, "Watch log: cannot refuse a second download: ${e.message}")
        } finally {
            runCatching { streams.close(channel) }
        }
    }

    companion object {

        private const val ZIP_NAME = "watch_log.zip"
        private const val CLOSE_DELAY_MS = 10_000L

        /** Where logback writes (`EXT_FILES_DIR` in `logback.xml`), the same as `LoggerUtilsImpl.logDirectory` on the phone */
        private fun logbackDirectory(): File? =
            runCatching { (LoggerFactory.getILoggerFactory() as LoggerContext).getProperty("EXT_FILES_DIR") }
                .getOrNull()?.let { File(it) }
    }
}
