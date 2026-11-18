package app.aaps.plugins.sync.wear.preview

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.phonepreview.CwfPreviewRenderer
import app.aaps.wear.phonepreview.PreviewThread
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the one [CwfPreviewRenderer] of the phone app and the thread it runs on.
 *
 * - One renderer for the whole app: the watch face's storage allows one owner per file.
 * - One thread, and only while a preview is shown: the renderer is not thread safe, and drawing off
 *   the main thread keeps the screen smooth. [release] frees the kept layer pictures and stops the thread.
 * - Never a crash: the preview is a nice-to-have in an app that doses insulin. If the renderer fails,
 *   for any reason, out of memory included, it is logged once and switched off ([failed]), and the
 *   screen shows the static picture instead.
 */
@SingleIn(AppScope::class)
@Inject
class CwfPreviewEngine(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    /** Held while the renderer or the thread is used, so they are used by one caller at a time. */
    private val lock = Mutex()

    /** Only touched while holding [lock]. Kept after [release]: it holds the values, the design and the settings. */
    private var renderer: CwfPreviewRenderer? = null

    /** The thread with a `Looper` the face's views need, see [PreviewThread]. Null while no preview is shown. Only touched while holding [lock]. */
    @Volatile private var thread: PreviewThread? = null

    /** For [release], which is called when the caller's scope is already cancelled. */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True after the renderer failed once. Then it stays off until the app restarts. */
    @Volatile var failed = false
        private set

    /** True while the thread of the renderer runs. */
    val isRunning: Boolean get() = thread != null

    /** Runs [block] with the renderer, on its own thread. Null when the preview is off or [block] failed. */
    suspend fun <T> use(block: (CwfPreviewRenderer) -> T): T? = lock.withLock {
        if (failed) return@withLock null
        val running = thread ?: PreviewThread().also { thread = it }
        withContext(running.dispatcher) {
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
    }

    /**
     * Frees the kept layer pictures and stops the thread, while no preview is shown. The last face stays
     * reachable through static fields of the watch code, one face at most, see
     * [CwfPreviewRenderer.release]. The values, the design and the settings stay. The next [use]
     * starts again.
     */
    fun release() {
        releaseScope.launch {
            lock.withLock {
                val running = thread ?: return@withLock
                try {
                    withContext(running.dispatcher) { renderer?.release() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    stop(e)
                } finally {
                    running.quit()
                    thread = null
                }
            }
        }
    }

    private fun stop(cause: Throwable): Nothing? {
        failed = true
        aapsLogger.error(LTag.WEAR, "Live watch face preview stopped, the static picture is shown instead", cause)
        return null
    }
}
