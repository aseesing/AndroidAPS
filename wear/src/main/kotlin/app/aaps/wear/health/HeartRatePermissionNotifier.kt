package app.aaps.wear.health

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.wear.R

/**
 * The notification on the watch that asks to allow the heart rate, when the design of the Custom watch
 * face uses `bpm` and the permission is missing. A tap opens [HealthPermissionActivity]. Without the
 * permission the face beats at the `fallbackBpm` of the design.
 */
class HeartRatePermissionNotifier(private val context: Context) {

    // Without the notification permission nothing is shown, and the face beats at its fallbackBpm
    @SuppressLint("MissingPermission")
    fun show() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.health_heart_rate_channel), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, HealthPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_health_heart)
            .setContentTitle(context.getString(R.string.health_heart_rate_channel))
            .setContentText(context.getString(R.string.health_heart_rate_allow))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.health_heart_rate_allow)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    companion object {

        const val CHANNEL_ID = "heart_rate_permission"
        const val NOTIFICATION_ID = 556681
    }
}
