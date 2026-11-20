package app.aaps.plugins.sync.wear.watchapp

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobile
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Updates the watch app from the phone, without adb on the watch.
 *
 * Only a phone app built with `-PembedWearApk=<signed watch APK>` carries the watch app; without it
 * [embedded] is null and the Wear screen shows nothing. One app-wide instance, so an update keeps
 * running when the user leaves the screen.
 *
 * @param rxBus pings the watch after an update, see [askNewWatchAppToReport]
 * @param scope runs the updates; the state may be read from any thread
 */
@SingleIn(AppScope::class)
class WatchAppUpdater internal constructor(
    private val source: EmbeddedWatchAppSource,
    opener: WatchChannelOpener,
    wifiServers: WifiBodyServerFactory,
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val scope: CoroutineScope
) {

    @Inject
    constructor(source: EmbeddedWatchAppSource, opener: WatchChannelOpener, wifiServers: WifiBodyServerFactory, aapsLogger: AAPSLogger, rxBus: RxBus) :
        this(source, opener, wifiServers, aapsLogger, rxBus, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    private val sender = WatchAppSender(source, opener, wifiServers)

    private val _state = MutableStateFlow<WatchAppUpdateState>(WatchAppUpdateState.Idle)
    val state: StateFlow<WatchAppUpdateState> = _state.asStateFlow()

    /** The built-in watch app, or null when this build has none */
    val embedded: EmbeddedWatchApp? by lazy { source.info() }

    /** Starts sending the built-in watch app, unless an update is running already */
    fun start() {
        if (_state.value.isBusy) return
        _state.value = WatchAppUpdateState.Connecting
        scope.launch {
            try {
                val result = sender.send { _state.value = it }
                aapsLogger.info(LTag.WEAR, "Watch app update: $result")
                if (result is WatchAppUpdateState.Sent) scope.launch { askNewWatchAppToReport() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never crash the app, and never leave the button disabled
                aapsLogger.error(LTag.WEAR, "Watch app update failed", e)
                _state.value = WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.ERROR, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Pings the watch a few times after an update. The phone still keeps what the old watch app told
     * it, for example a Watch Face Push status without the watch's Android API level. A ping makes the
     * new watch app tell its status again. Android installs the new app some time after
     * [WatchAppUpdateState.Sent], or only after the user confirms on the watch, so one ping is not enough.
     */
    internal suspend fun askNewWatchAppToReport() {
        for (wait in PING_WAITS) {
            delay(wait)
            rxBus.send(EventMobileToWear(EventData.ActionPing(System.currentTimeMillis())))
        }
    }

    companion object {

        /** The waits before each ping, one after the other: pings at 10 s, 30 s and 90 s */
        internal val PING_WAITS: List<Duration> = listOf(10.seconds, 20.seconds, 60.seconds)
    }
}

/** The watch APK in the assets, put there by the `embedWearApk` build property */
@ContributesBinding(AppScope::class)
@Inject
class AssetWatchAppSource(private val context: Context) : EmbeddedWatchAppSource {

    override fun info(): EmbeddedWatchApp? =
        try {
            context.assets.open(INFO_ASSET).use { stream ->
                val properties = Properties().apply { load(stream) }
                EmbeddedWatchApp(
                    label = properties.getProperty("label") ?: "",
                    bodySize = properties.getProperty("size")?.toLongOrNull() ?: return null,
                    apkSize = properties.getProperty("apkSize")?.toLongOrNull() ?: return null
                )
            }
        } catch (_: FileNotFoundException) {
            null
        }

    override fun open(): InputStream = context.assets.open(APK_ASSET)

    companion object {

        /** Must match the names the gradle task `EmbedWatchAppTask` writes */
        const val INFO_ASSET = "watch_app/watch_app.properties"
        const val APK_ASSET = "watch_app/wear.apk.gzip"
    }
}

/** Opens a Wear OS channel to the nearest watch that runs the AAPS watch app */
@ContributesBinding(AppScope::class)
@Inject
class WearableChannelOpener(private val context: Context) : WatchChannelOpener {

    override suspend fun open(path: String): WatchChannel? = withContext(Dispatchers.IO) {
        val capability = Tasks.await(
            Wearable.getCapabilityClient(context).getCapability(DataLayerListenerServiceMobile.WEAR_CAPABILITY, CapabilityClient.FILTER_REACHABLE),
            TIMEOUT_S, TimeUnit.SECONDS
        )
        val node = capability.nodes.firstOrNull { it.isNearby } ?: capability.nodes.firstOrNull() ?: return@withContext null
        val client = Wearable.getChannelClient(context)
        val channel = Tasks.await(client.openChannel(node.id, path), TIMEOUT_S, TimeUnit.SECONDS)
        try {
            val output = Tasks.await(client.getOutputStream(channel), TIMEOUT_S, TimeUnit.SECONDS)
            val input = Tasks.await(client.getInputStream(channel), TIMEOUT_S, TimeUnit.SECONDS)
            object : WatchChannel {
                override val watchName: String = node.displayName
                override val input: InputStream = input
                override val output: OutputStream = output

                override fun close() {
                    runCatching { output.close() }
                    runCatching { input.close() }
                    client.close(channel)
                }
            }
        } catch (e: Exception) {
            client.close(channel)
            throw e
        }
    }

    companion object {

        const val TIMEOUT_S = 30L
    }
}
