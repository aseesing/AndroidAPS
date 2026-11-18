package app.aaps.wear.selfupdate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import com.google.android.gms.wearable.ChannelClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Updates this watch app with an APK that the phone app sends, so the user does not need adb on
 * the watch. See [WatchAppUpdateProtocol].
 *
 * Android only takes an APK signed with the same key as the installed app, and the session is
 * limited to this app's package, so the channel cannot install anything else. The watch may ask the
 * user to confirm, see [WatchAppInstallStatusReceiver].
 *
 * @param scope runs the updates, off the main thread
 * @param closeDelayMs how long a channel stays open after the last answer, so the phone can read it
 */
@SingleIn(AppScope::class)
class WatchAppSelfUpdate internal constructor(
    private val aapsLogger: AAPSLogger,
    private val streams: PhoneChannelStreams,
    private val sessions: WatchAppInstallSessionFactory,
    private val wifi: WifiBodyFetcher,
    private val notifier: WatchAppUpdateNotifier,
    private val scope: CoroutineScope,
    private val closeDelayMs: Long
) {

    @Inject
    constructor(
        aapsLogger: AAPSLogger,
        streams: PhoneChannelStreams,
        sessions: WatchAppInstallSessionFactory,
        wifi: WifiBodyFetcher,
        notifier: WatchAppUpdateNotifier
    ) : this(aapsLogger, streams, sessions, wifi, notifier, CoroutineScope(SupervisorJob() + Dispatchers.IO), CLOSE_DELAY_MS)

    private val running = AtomicBoolean(false)

    /**
     * Called for every channel the phone opens; takes only the update channel.
     *
     * @return the work on the channel, or null for another channel
     */
    fun onChannelOpened(channel: ChannelClient.Channel): Job? {
        if (channel.path != WatchAppUpdateProtocol.PATH) return null
        if (!running.compareAndSet(false, true)) {
            aapsLogger.warn(LTag.WEAR, "Watch app update: one is running already")
            return scope.launch { refuseBusy(channel) }
        }
        return scope.launch {
            var released = false
            // Once only: after the release a new update may already hold the lock
            fun release() {
                if (released) return
                released = true
                running.set(false)
            }
            try {
                val input = streams.input(channel)
                val output = streams.output(channel)
                notifier.showProgress(0)
                val task = WatchAppReceiveTask(
                    wifi = wifi,
                    log = { aapsLogger.warn(LTag.WEAR, "Watch app update: $it") },
                    sessionFactory = sessions
                )
                when (val result = task.receive(input, output) { notifier.showProgress(it) }) {
                    is WatchAppReceiveTask.Result.Committed -> {
                        aapsLogger.info(LTag.WEAR, "Watch app update: ${result.label} given to the installer, over ${if (result.overWifi) "Wi-Fi" else "the channel"}")
                        notifier.showInstalling()
                    }

                    is WatchAppReceiveTask.Result.Failed    -> {
                        aapsLogger.error(LTag.WEAR, "Watch app update failed: ${result.message}")
                        notifier.showFailed(result.message)
                    }
                }
                // The work is done: a new update may start while this channel waits to close
                release()
                // The phone closes the channel after it read the answer; give it that time
                delay(closeDelayMs)
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "Watch app update failed", e)
                notifier.showFailed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { streams.close(channel) }
                release()
            }
        }
    }

    /** Tells the phone that an update is running, so it shows that instead of a broken channel */
    private suspend fun refuseBusy(channel: ChannelClient.Channel) {
        try {
            WatchAppUpdateProtocol.writeReply(streams.output(channel), WatchAppUpdateProtocol.Reply.Failed(WatchAppUpdateProtocol.BUSY))
            delay(closeDelayMs)
        } catch (e: Exception) {
            aapsLogger.warn(LTag.WEAR, "Watch app update: cannot refuse a second update: ${e.message}")
        } finally {
            runCatching { streams.close(channel) }
        }
    }

    companion object {

        private const val CLOSE_DELAY_MS = 10_000L
    }
}

/** Starts a [PackageInstallerSession] for every update */
@ContributesBinding(AppScope::class)
@Inject
class PackageInstallerSessionFactory(private val context: Context) : WatchAppInstallSessionFactory {

    override fun create(apkSize: Long): WatchAppInstallSession = PackageInstallerSession(context, apkSize)
}

/** A `PackageInstaller` session that may only update this app */
internal class PackageInstallerSession(private val context: Context, apkSize: Long) : WatchAppInstallSession {

    private val installer = context.packageManager.packageInstaller
    private val sessionId: Int
    private val session: PackageInstaller.Session
    override val output: OutputStream

    init {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setSize(apkSize)
            // Android 12 and newer: allowed for an app that updates itself; where the watch still wants
            // a confirmation, the result says so and WatchAppInstallStatusReceiver asks the user.
            // Wear OS 3 (Android 11) does not have this call and always asks.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        sessionId = installer.createSession(params)
        session = installer.openSession(sessionId)
        output = try {
            session.openWrite("base.apk", 0, apkSize)
        } catch (e: Exception) {
            // Do not leave an empty session behind
            runCatching { session.abandon() }
            throw e
        }
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
