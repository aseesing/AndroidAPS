package app.aaps.wear.selfupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * The result of an update of this app that the phone sent, see [WatchAppSelfUpdate].
 *
 * When the watch wants a confirmation, the confirmation screen is opened at once where Android
 * allows that, and a notification opens it too. After a successful update Android has already
 * restarted the app, so the "done" notification comes from the new version.
 */
class WatchAppInstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notifier = WatchAppUpdateNotifier(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "Watch app update status $status $message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                notifier.showConfirm(confirm)
                try {
                    context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    // Android may block an activity start from the background: the notification stays
                    Log.w(TAG, "Cannot open the confirmation", e)
                }
            }

            PackageInstaller.STATUS_SUCCESS             -> notifier.showDone()
            else                                        -> notifier.showFailed(message ?: "status $status")
        }
    }

    companion object {

        private const val TAG = "WatchAppUpdate"
    }
}
