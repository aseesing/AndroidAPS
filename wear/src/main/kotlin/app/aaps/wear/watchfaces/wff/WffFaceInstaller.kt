package app.aaps.wear.watchfaces.wff

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.PushedWatchfaceId
import app.aaps.wear.R
import app.aaps.wear.watchfaces.PushedFace
import app.aaps.wear.watchfaces.WatchFacePushHelper
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Installs the face zip that the phone sent (see [WffFace]).
 *
 * - Wear OS 6 and newer: with Watch Face Push, in the one slot of the app, as the [PushedFace.DESIGN]
 *   face. The face becomes the active face where the system allows it.
 * - Older watches have no Watch Face Push. There the face is installed as an ordinary app, and the
 *   wearer confirms the install on the watch (a notification to tap). The wearer then picks the face
 *   in the watch's own face picker.
 */
@SingleIn(AppScope::class)
@Inject
class WffFaceInstaller(
    private val context: Context,
    private val wffFace: WffFace,
    private val watchFacePushHelper: WatchFacePushHelper,
    private val aapsLogger: AAPSLogger
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiverRegistered = false

    /** Keeps [zip] from the phone and installs the face in it. */
    suspend fun install(zip: ByteArray) {
        wffFace.store(zip)
        aapsLogger.debug(LTag.WEAR, "WffFaceInstaller: face '${wffFace.name}' received, ${zip.size} bytes")
        // A face without a token is one for an older watch: installed as an app here too
        if (watchFacePushHelper.isSupported() && wffFace.token != null) {
            watchFacePushHelper.selectFace(PushedWatchfaceId.DESIGN)
            watchFacePushHelper.installOrUpdate(activate = true)
        } else {
            val apk = wffFace.apk() ?: return aapsLogger.error(LTag.WEAR, "WffFaceInstaller: no face.apk in the zip")
            installPackage(apk)
        }
    }

    private fun installPackage(apk: ByteArray) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("face.apk", 0, apk.size.toLong()).use { it.write(apk) }
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(context, resultReceiver, IntentFilter(ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            }
            val result = Intent(ACTION_RESULT).setPackage(context.packageName)
            session.commit(PendingIntent.getBroadcast(context, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
        }
    }

    /** The answers of the package installer: a confirmation to ask for, then the end result. */
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            aapsLogger.debug(LTag.WEAR, "WffFaceInstaller: install status $status $message")
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let { notify(context.getString(R.string.wff_face_confirm, wffFace.name), it) }

                PackageInstaller.STATUS_SUCCESS             -> {
                    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                    scope.launch { watchFacePushHelper.reportStatus() }
                }

                else                                        -> notify(context.getString(R.string.wff_face_failed, message ?: status.toString()), null)
            }
        }
    }

    // Without the notification permission the wearer cannot confirm; the log still says why
    @SuppressLint("MissingPermission")
    private fun notify(text: String, confirm: Intent?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.wff_face_channel), NotificationManager.IMPORTANCE_HIGH))
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_icon)
            .setContentTitle(context.getString(R.string.wff_face_channel))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        confirm?.let {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    private companion object {

        const val ACTION_RESULT = "app.aaps.wear.WFF_FACE_INSTALL_RESULT"
        const val CHANNEL_ID = "wff_face_install"
        const val NOTIFICATION_ID = 556681
    }
}
