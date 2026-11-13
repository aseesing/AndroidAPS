// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.Executor

/**
 * The one thread the [CwfPreviewRenderer] must run on.
 *
 * An Android thread with its own `Looper`, not a plain Java thread: building the face makes views that
 * need one. The chart view of the face makes a `Handler` when it is built, and on a thread without a
 * `Looper` that fails with "Can't create handler inside thread ... that has not called
 * Looper.prepare()". That stopped the first preview on a real phone.
 *
 * Not the main thread either: drawing the face takes too long for it.
 */
class PreviewThread {

    private val thread = HandlerThread(NAME).apply { start() }
    private val handler = Handler(thread.looper)

    /** Runs each task on this thread, one after the other. */
    val executor: Executor = Executor { task -> handler.post(task) }

    private companion object {

        const val NAME = "CwfPreview"
    }
}
