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
 * Owns the [CwfPreviewRenderer]s of the phone app and the thread they run on.
 *
 * - One renderer per face ([PreviewSlot]) for the whole app: the watch face's storage allows one
 *   owner per file, so each face has its own.
 * - One thread, and only while a preview is shown: the renderer is not thread safe, and drawing off
 *   the main thread keeps the screen smooth. [release] frees the kept layer pictures of the faces no
 *   screen shows, and stops the thread when no screen shows any.
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

    /** Held while a renderer or the thread is used, so they are used by one caller at a time. */
    private val lock = Mutex()

    /** Only touched while holding [lock]. Kept after [release]: they hold the values, the designs and the settings. */
    private val renderers = HashMap<PreviewSlot, CwfPreviewRenderer>() // one per face

    /** The thread with a `Looper` the face's views need, see [PreviewThread]. Null while no preview is shown. Only touched while holding [lock]. */
    @Volatile private var thread: PreviewThread? = null

    /** How many screens show the face of each slot now, see [shown]. Guarded by itself. */
    private val showing = HashMap<PreviewSlot, Int>()

    /** For [release], which is called when the caller's scope is already cancelled. */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True after the renderer failed once. Then it stays off until the app restarts. */
    @Volatile var failed = false
        private set

    /** True while the thread of the renderers runs. */
    val isRunning: Boolean get() = thread != null

    /** Runs [block] with the renderer of [slot], on its own thread. Null when the preview is off or [block] failed. */
    suspend fun <T> use(slot: PreviewSlot, block: (CwfPreviewRenderer) -> T): T? = lock.withLock {
        if (failed) return@withLock null
        val running = thread ?: PreviewThread().also { thread = it }
        withContext(running.dispatcher) {
            try {
                block(renderers.getOrPut(slot) { CwfPreviewRenderer(context, aapsLogger, slot.storageName) })
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
     * A screen starts showing the face of [slot]. Each call is paired with one call of
     * [hidden]. While a face is shown, [release] keeps it, so a second preview of the other face, or a
     * screen that ends while another one starts, does not make the shown face start again.
     */
    fun shown(slot: PreviewSlot) {
        synchronized(showing) { showing[slot] = (showing[slot] ?: 0) + 1 }
    }

    /** A screen stops showing the face of [slot]. Frees what no screen shows any more, see [release]. */
    fun hidden(slot: PreviewSlot) {
        synchronized(showing) { showing[slot] = ((showing[slot] ?: 0) - 1).coerceAtLeast(0) }
        release()
    }

    /**
     * Frees the kept layer pictures of the faces no screen shows, and stops the thread when no screen
     * shows any face. The last face of each renderer stays reachable through static fields of the
     * watch code, one face at most, see [CwfPreviewRenderer.release]. The values, the designs and the
     * settings stay. The next [use] starts again.
     */
    fun release() {
        releaseScope.launch {
            lock.withLock {
                val running = thread ?: return@withLock
                val shownNow = synchronized(showing) { showing.filterValues { it > 0 }.keys.toSet() }
                try {
                    withContext(running.dispatcher) { renderers.filterKeys { it !in shownNow }.values.forEach { it.release() } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    stop(e)
                } finally {
                    if (shownNow.isEmpty() || failed) {
                        running.quit()
                        thread = null
                    }
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
