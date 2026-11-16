package app.aaps.wear.selfupdate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Updates this watch app with an APK that the phone app sends, so the user does not need adb on
 * the watch. See [WatchAppUpdateProtocol].
 *
 * Android only takes an APK signed with the same key as the installed app, and the session is
 * limited to this app's package, so the channel cannot install anything else. The watch may ask the
 * user to confirm, see [WatchAppInstallStatusReceiver].
 */
@SingleIn(AppScope::class)
@Inject
class WatchAppSelfUpdate(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val notifier = WatchAppUpdateNotifier(context)

    /** Called for every channel the phone opens; takes only the update channel */
    fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchAppUpdateProtocol.PATH) return
        val client = Wearable.getChannelClient(context)
        if (!running.compareAndSet(false, true)) {
            aapsLogger.warn(LTag.WEAR, "Watch app update: one is running already")
            client.close(channel)
            return
        }
        scope.launch {
            try {
                val input = client.getInputStream(channel).await()
                val output = client.getOutputStream(channel).await()
                notifier.showProgress(0)
                val task = WatchAppReceiveTask { PackageInstallerSession(context) }
                when (val result = task.receive(input, output) { notifier.showProgress(it) }) {
                    is WatchAppReceiveTask.Result.Committed -> {
                        aapsLogger.info(LTag.WEAR, "Watch app update: ${result.label} given to the installer")
                        notifier.showInstalling()
                    }

                    is WatchAppReceiveTask.Result.Failed    -> {
                        aapsLogger.error(LTag.WEAR, "Watch app update failed: ${result.message}")
                        notifier.showFailed(result.message)
                    }
                }
                // The phone closes the channel after it read the answer; give it that time
                delay(CLOSE_DELAY_MS)
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "Watch app update failed", e)
                notifier.showFailed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { client.close(channel) }
                running.set(false)
            }
        }
    }

    companion object {

        private const val CLOSE_DELAY_MS = 10_000L
    }
}

/** A `PackageInstaller` session that may only update this app */
internal class PackageInstallerSession(private val context: Context) : WatchAppInstallSession {

    private val installer = context.packageManager.packageInstaller
    private val sessionId: Int
    private val session: PackageInstaller.Session
    override val output: OutputStream

    init {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Allowed for an app that updates itself; where the watch still wants a confirmation,
            // the result says so and WatchAppInstallStatusReceiver asks the user
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        sessionId = installer.createSession(params)
        session = installer.openSession(sessionId)
        output = session.openWrite("base.apk", 0, -1)
    }

    override fun commit() {
        session.fsync(output)
        output.close()
        val intent = Intent(context, WatchAppInstallStatusReceiver::class.java)
        // Mutable: the installer adds the result to it. The intent is explicit, so no other app gets it.
        val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        session.commit(pendingIntent.intentSender)
        session.close()
    }

    override fun abandon() {
        runCatching { output.close() }
        session.abandon()
    }
}
