// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchlog

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.shared.impl.weardata.WatchLogProtocol
import app.aaps.wear.selfupdate.NetworkWifiBodyFetcher
import ch.qos.logback.classic.LoggerContext
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the log of this watch app to the phone app when it asks. See [WatchLogProtocol].
 *
 * Only the paired phone can open a Wear OS channel to this app, so only it gets the log.
 */
@SingleIn(AppScope::class)
@Inject
class WatchLogUpload(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    /** Called for every channel the phone opens; takes only the log channel */
    fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchLogProtocol.PATH) return
        val client = Wearable.getChannelClient(context)
        if (!running.compareAndSet(false, true)) {
            aapsLogger.warn(LTag.WEAR, "Watch log: a download is running already")
            scope.launch { refuseBusy(client, channel) }
            return
        }
        val zipFile = File(context.cacheDir, ZIP_NAME)
        scope.launch {
            var released = false
            // Once only: after the release a new download may already use the zip file
            fun release() {
                if (released) return
                released = true
                zipFile.delete()
                running.set(false)
            }
            try {
                val input = client.getInputStream(channel).await()
                val output = client.getOutputStream(channel).await()
                val task = WatchLogSendTask(
                    zipper = WatchLogZipper(::logDirectory, zipFile),
                    wifi = NetworkWifiBodyFetcher(context),
                    log = { aapsLogger.warn(LTag.WEAR, "Watch log: $it") }
                )
                when (val result = task.send(input, output)) {
                    is WatchLogSendTask.Result.Sent   ->
                        aapsLogger.info(LTag.WEAR, "Watch log: sent ${result.fileCount} files, ${result.size} bytes, over ${if (result.overWifi) "Wi-Fi" else "the channel"}")

                    is WatchLogSendTask.Result.Failed -> aapsLogger.error(LTag.WEAR, "Watch log: ${result.message}")
                }
                // The work is done: a new download may start while this channel waits to close
                release()
                // The phone closes the channel after it read everything; give it that time
                delay(CLOSE_DELAY_MS)
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "Watch log failed", e)
            } finally {
                runCatching { client.close(channel) }
                release()
            }
        }
    }

    /** Tells the phone that a download is running, so it does not wait for nothing */
    private suspend fun refuseBusy(client: ChannelClient, channel: ChannelClient.Channel) {
        try {
            val output = client.getOutputStream(channel).await()
            WatchLogProtocol.writeReply(output, WatchLogProtocol.Reply.Failed(BUSY))
            delay(CLOSE_DELAY_MS)
        } catch (e: Exception) {
            aapsLogger.warn(LTag.WEAR, "Watch log: cannot refuse a second download: ${e.message}")
        } finally {
            runCatching { client.close(channel) }
        }
    }

    /** Where logback writes, the same as `LoggerUtilsImpl.logDirectory` on the phone */
    private fun logDirectory(): File? =
        runCatching { (LoggerFactory.getILoggerFactory() as LoggerContext).getProperty("EXT_FILES_DIR") }
            .getOrNull()?.let { File(it) }

    companion object {

        private const val ZIP_NAME = "watch_log.zip"
        private const val CLOSE_DELAY_MS = 10_000L
        private const val BUSY = "The watch is still sending a log. Try again in a minute."
    }
}
