package app.aaps.wear.selfupdate

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.wear.R
import dev.zacsweers.metro.Inject

/** The notification on the watch that shows how an update of the watch app from the phone goes */
@Inject
class WatchAppUpdateNotifier(private val context: Context) {

    fun showProgress(percent: Int) =
        notify(
            builder(context.getString(R.string.watch_app_update_receiving))
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        )

    fun showInstalling() =
        notify(builder(context.getString(R.string.watch_app_update_installing)).setOngoing(true).setOnlyAlertOnce(true))

    /** The watch wants the user to confirm the install. [confirmIntent] comes from the package installer. */
    fun showConfirm(confirmIntent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            context, 0, confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            builder(context.getString(R.string.watch_app_update_confirm))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
        )
    }

    fun showDone() = notify(builder(context.getString(R.string.watch_app_update_done)).setAutoCancel(true))

    fun showFailed(message: String) =
        notify(
            builder(context.getString(R.string.watch_app_update_failed, message))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.watch_app_update_failed, message)))
                .setAutoCancel(true)
        )

    private fun builder(text: String): NotificationCompat.Builder {
        createChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_icon)
            .setContentTitle(context.getString(R.string.watch_app_update_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }

    // Without the notification permission the update still runs; only the progress is not shown
    @SuppressLint("MissingPermission")
    private fun notify(builder: NotificationCompat.Builder) {
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.watch_app_update_title), NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {

        const val CHANNEL_ID = "watch_app_update"
        const val NOTIFICATION_ID = 556680
    }
}
