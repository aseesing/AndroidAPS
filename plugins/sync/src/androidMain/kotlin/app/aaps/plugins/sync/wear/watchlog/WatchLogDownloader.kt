// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.watchlog

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.sync.wear.watchapp.AndroidWifiBodyServerFactory
import app.aaps.plugins.sync.wear.watchapp.WearableChannelOpener
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Downloads the log of the watch app and saves it in the Downloads folder of the phone.
 *
 * One app-wide instance, so a download keeps running when the user leaves the screen.
 */
@SingleIn(AppScope::class)
@Inject
class WatchLogDownloader(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val receiver = WatchLogReceiver(WearableChannelOpener(context), AndroidWifiBodyServerFactory(context))

    private val _state = MutableStateFlow<WatchLogState>(WatchLogState.Idle)
    val state: StateFlow<WatchLogState> = _state.asStateFlow()

    /** Starts a download, unless one is running already */
    fun start() {
        if (_state.value.isBusy) return
        _state.value = WatchLogState.Connecting
        scope.launch {
            val temp = File(context.cacheDir, TEMP_NAME)
            try {
                val result = receiver.receive(temp) { _state.value = it }
                if (result is WatchLogState.Received) {
                    val name = fileName(System.currentTimeMillis())
                    _state.value = try {
                        withContext(Dispatchers.IO) { saveToDownloads(temp, name) }
                        WatchLogState.Saved(name)
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.WEAR, "Watch log: cannot save to Downloads", e)
                        WatchLogState.Failed(WatchLogState.Reason.ERROR, e.message ?: e.javaClass.simpleName)
                    }
                }
                aapsLogger.info(LTag.WEAR, "Watch log download: ${_state.value}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never crash the app, and never leave the button disabled
                aapsLogger.error(LTag.WEAR, "Watch log download failed", e)
                _state.value = WatchLogState.Failed(WatchLogState.Reason.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                withContext(Dispatchers.IO) { temp.delete() }
            }
        }
    }

    private fun saveToDownloads(source: File, name: String) {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            // Hidden from other apps until the copy is complete
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending) ?: throw IOException("Cannot create $name in Downloads")
        try {
            source.inputStream().use { input ->
                (resolver.openOutputStream(target) ?: throw IOException("Cannot write $name")).use { output -> input.copyTo(output) }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            // Do not leave a broken, half written zip behind
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
    }

    companion object {

        private const val TEMP_NAME = "watch_log_download.zip"

        /** Like the log export of the phone (`AndroidAPS_LOG_...`), but with a readable time */
        fun fileName(timeMs: Long): String =
            "AndroidAPS_WEAR_LOG_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(timeMs)) + ".zip"
    }
}
