// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import androidx.core.content.ContextCompat

/**
 * Keeps the app from starting before the watch is unlocked after a boot.
 *
 * The watch face library has a service that Android may start before the unlock
 * (`androidx.wear.watchface.control.WatchFaceControlService` is direct boot aware). The app's
 * settings are not readable then, so a normal start crashed, again and again until the unlock.
 * With this the app waits, and starts once, right after the unlock.
 *
 * @param isUnlocked true when the settings of the user can be read
 * @param listenForUnlock tells the given function when the unlock comes; returns a function that
 *   stops the listening
 */
class StartAfterUnlock(
    private val isUnlocked: () -> Boolean,
    private val listenForUnlock: (onUnlock: () -> Unit) -> () -> Unit
) {

    constructor(context: Context) : this(
        // No user manager: start as without this patch
        isUnlocked = { context.getSystemService(UserManager::class.java)?.isUserUnlocked != false },
        listenForUnlock = { onUnlock -> listenWithReceiver(context, onUnlock) }
    )

    /**
     * Call first in the start code, and return from it when this is true: then [start] runs later,
     * once, right after the unlock. False when the watch is unlocked already: go on with the start.
     *
     * Must be called on the main thread, where the unlock is also told.
     */
    fun postpones(start: () -> Unit): Boolean {
        if (isUnlocked()) return false
        var stopListening: (() -> Unit)? = null
        var isStarted = false
        val startOnce = {
            if (!isStarted) {
                isStarted = true
                stopListening?.invoke()
                start()
            }
        }
        stopListening = listenForUnlock(startOnce)
        // The unlock may have come between the check and the listening
        if (isUnlocked()) startOnce()
        return true
    }
}

/** Listens for the unlock of the user with a receiver on [context]. */
private fun listenWithReceiver(context: Context, onUnlock: () -> Unit): () -> Unit {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) = onUnlock()
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
    return { context.unregisterReceiver(receiver) }
}
