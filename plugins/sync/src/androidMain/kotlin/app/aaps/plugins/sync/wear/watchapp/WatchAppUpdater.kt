package app.aaps.plugins.sync.wear.watchapp

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobile
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * Updates the watch app from the phone, without adb on the watch.
 *
 * Only a phone app built with `-PembedWearApk=<signed watch APK>` carries the watch app; without it
 * [embedded] is null and the Wear screen shows nothing. One app-wide instance, so an update keeps
 * running when the user leaves the screen.
 */
@SingleIn(AppScope::class)
@Inject
class WatchAppUpdater(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = AssetWatchAppSource(context)
    private val sender = WatchAppSender(source, WearableChannelOpener(context))

    private val _state = MutableStateFlow<WatchAppUpdateState>(WatchAppUpdateState.Idle)
    val state: StateFlow<WatchAppUpdateState> = _state.asStateFlow()

    /** The built-in watch app, or null when this build has none */
    val embedded: EmbeddedWatchApp? by lazy { source.info() }

    /** Starts sending the built-in watch app, unless an update is running already */
    fun start() {
        if (_state.value.isBusy) return
        _state.value = WatchAppUpdateState.Connecting
        scope.launch {
            val result = sender.send { _state.value = it }
            aapsLogger.info(LTag.WEAR, "Watch app update: $result")
        }
    }
}

/** The watch APK in the assets, put there by the `embedWearApk` build property */
internal class AssetWatchAppSource(private val context: Context) : EmbeddedWatchAppSource {

    override fun info(): EmbeddedWatchApp? =
        try {
            context.assets.open(INFO_ASSET).use { stream ->
                val properties = Properties().apply { load(stream) }
                EmbeddedWatchApp(
                    label = properties.getProperty("label") ?: "",
                    bodySize = properties.getProperty("size")?.toLongOrNull() ?: return null
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
internal class WearableChannelOpener(private val context: Context) : WatchChannelOpener {

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
