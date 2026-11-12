// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.os.Handler
import android.os.Looper
import androidx.wear.watchface.WatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the first frame after a wake cheap.
 *
 * When the wrist is raised or the button is pressed, the watch waits for the first frame of the face.
 * Some watches show their own low-power face when that frame comes too late: the OnePlus Watch 2R in
 * Smart mode then stays on its own face until the button is pressed twice. So the first frame after a
 * wake only brings the clock up to date and draws the views as they are. The full refresh - data,
 * colours, chart - runs right after that frame is on the screen, and the next frame shows it.
 *
 * A wake is: out of ambient, or shown again after hidden. Only an interactive frame can be the wake
 * frame; a wake seen during ambient waits for the first interactive frame.
 *
 * All calls come from the main thread, where the watch face renders.
 *
 * Hooks in upstream files: `WatchFace` calls [watch], [beginFrame], [endFrame] and [release] and
 * draws the wake frame the plain way; `BaseWatchFace` asks [isActive] where it would refresh
 * everything, and uses [refreshAfterFrame] instead.
 *
 * @param post runs a task on the main thread after the current frame
 */
class FirstFrameAfterWake(private val post: (Runnable) -> Unit = { task -> mainHandler.post(task) }) {

    /** True while the first frame after a wake renders, between [beginFrame] and [endFrame]. */
    var isActive = false
        private set

    private var wakeAsked = false
    private var wasHidden = false
    private var refreshPending = false
    private var released = false
    private var scope: CoroutineScope? = null

    /** Follows whether the face is shown. Does nothing for a headless face (editor, preview). */
    fun watch(watchState: WatchState) {
        if (watchState.isHeadless) return
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch { watchState.isVisible.collect { visible -> if (visible != null) onVisibility(visible) } }
    }

    /** The face was shown ([visible] true) or hidden. Shown after hidden is a wake. */
    fun onVisibility(visible: Boolean) {
        if (visible && wasHidden) wakeAsked = true
        wasHidden = !visible
    }

    /**
     * Called at the start of every frame of the watch face, before the mode change is handled.
     *
     * @param leftAmbient true when the frame before was ambient and this one is not
     * @param interactive true for an interactive frame of a face on the screen (not ambient, not headless)
     */
    fun beginFrame(leftAmbient: Boolean, interactive: Boolean) {
        isActive = interactive && (leftAmbient || wakeAsked)
        if (interactive) wakeAsked = false
    }

    /** Called at the end of every frame. A frame that failed is reset by the next [beginFrame]. */
    fun endFrame() {
        isActive = false
    }

    /**
     * Runs [refresh] once after the current frame, however often it is asked for during the frame.
     * Does nothing after [release].
     */
    fun refreshAfterFrame(refresh: () -> Unit) {
        if (refreshPending || released) return
        refreshPending = true
        post(Runnable {
            refreshPending = false
            if (!released) refresh()
        })
    }

    /** The watch face is gone: stops watching, and a refresh that is still waiting is dropped. */
    fun release() {
        released = true
        isActive = false
        scope?.cancel()
        scope = null
    }

    private companion object {

        val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    }
}
