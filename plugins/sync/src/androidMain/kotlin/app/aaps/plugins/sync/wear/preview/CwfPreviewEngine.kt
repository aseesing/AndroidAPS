// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.phonepreview.CwfPreviewRenderer
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Owns the one [CwfPreviewRenderer] of the phone app and the one thread it runs on.
 *
 * - One renderer for the whole app: the watch face's storage allows one owner per file.
 * - One thread: the renderer is not thread safe, and drawing off the main thread keeps the screen smooth.
 * - Never a crash: the preview is a nice-to-have in an app that doses insulin. If the renderer fails,
 *   for any reason, out of memory included, it is logged once and switched off ([failed]), and the
 *   screen shows the static picture instead.
 *
 * The renderer is made on first use, so nothing is loaded until someone opens the wear screen, and
 * its drawing part is freed again by [release] when the screen is left.
 */
@SingleIn(AppScope::class)
@Inject
class CwfPreviewEngine(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "CwfPreview").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    private var renderer: CwfPreviewRenderer? = null

    /** For [release], on the renderer's thread. */
    private val releaseScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** True after the renderer failed once. Then it stays off until the app restarts. */
    @Volatile var failed = false
        private set

    /** Runs [block] with the renderer, on its own thread. Null when the preview is off or [block] failed. */
    suspend fun <T> use(block: (CwfPreviewRenderer) -> T): T? = withContext(dispatcher) {
        if (failed) return@withContext null
        try {
            block(renderer ?: CwfPreviewRenderer(context, aapsLogger).also { renderer = it })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable on purpose, not only Exception: an OutOfMemoryError from a picture, or a
            // LinkageError from the watch libraries, must switch the preview off, not end AAPS
            stop(e)
        }
    }

    /**
     * Frees the face and its pictures while no preview is shown, on the renderer's thread. Never
     * makes a renderer. Runs in its own scope: it is called when the screen's scope is already cancelled.
     */
    fun release() {
        releaseScope.launch {
            try {
                renderer?.release()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                stop(e)
            }
        }
    }

    private fun stop(cause: Throwable): Nothing? {
        failed = true
        aapsLogger.error(LTag.WEAR, "Live watch face preview stopped, the static picture is shown instead", cause)
        return null
    }
}
