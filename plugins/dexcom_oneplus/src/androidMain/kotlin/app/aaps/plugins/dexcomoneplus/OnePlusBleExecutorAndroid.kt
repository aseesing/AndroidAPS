package app.aaps.plugins.dexcomoneplus

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal actual fun onePlusBleExecutor(): OnePlusBleExecutor = OnePlusAndroidBleExecutor()

/**
 * The single-threaded executor the driver has always used, behind [OnePlusBleExecutor].
 *
 * Daemon, so a stuck session cannot keep the process alive, and named so a thread dump says which
 * thread is blocked in the handshake.
 */
private class OnePlusAndroidBleExecutor : OnePlusBleExecutor {

    private val delegate: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OnePlusBleExecutor").apply { isDaemon = true }
    }

    /** Throws `RejectedExecutionException` after [shutdownNow], exactly as before. */
    override fun execute(block: () -> Unit) {
        // PERSONAL PATCH: an exception that escapes a cycle would reach the default handler of the
        // thread, which ends the whole app and with it the loop. Log it and keep the app running.
        delegate.execute {
            try {
                block()
            } catch (e: Throwable) {
                Log.e("OnePlusBleExecutor", "BLE cycle failed, the app goes on", e)
            }
        }
    }

    /** Also interrupts the running cycle, which is what cuts `onePlusSleep` short. */
    override fun shutdownNow() {
        delegate.shutdownNow()
    }
}
