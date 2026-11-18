package app.aaps.wear.phonepreview

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The one thread the [CwfPreviewRenderer] must run on, while a preview is shown.
 *
 * An Android thread with its own `Looper`, not a plain Java thread: building the face makes views that
 * need one. The chart view of the face makes a `Handler` when it is built, and on a thread without a
 * `Looper` that fails with "Can't create handler inside thread ... that has not called
 * Looper.prepare()".
 *
 * Not the main thread either: drawing the face takes too long for it.
 */
class PreviewThread {

    private val thread = HandlerThread(NAME).apply { start() }
    private val handler = Handler(thread.looper)

    /** Runs each task on this thread, one after the other. A task after [quit] is refused, not lost. */
    val executor: Executor = Executor { task -> if (!handler.post(task)) throw RejectedExecutionException("$NAME has stopped") }

    /** [executor] for coroutines. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /** Stops the thread after the tasks already given to it. */
    fun quit() {
        thread.quitSafely()
    }

    private companion object {

        const val NAME = "CwfPreview"
    }
}
