// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.plugins.sync.wear.WearPlugin
import app.aaps.plugins.sync.wear.cwfsettings.CwfWatchSettingsStore
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Feeds the live watch face preview and hands out its frames.
 *
 * - The design is the one the watch has, as the wear screen already shows it.
 * - The values are what the phone last built for the watch, then every new message to the watch.
 *   So the preview shows what the watch shows, without asking the watch.
 * - The watch settings (which values to show, seconds, wake-up animation, ...) are the ones the phone
 *   keeps in [CwfWatchSettingsStore]. A change shows in the next frame, drawn at once.
 *
 * Nothing is drawn or stored while no preview is on screen: new values and settings are only kept
 * here, the newest of each kind, and handed to the renderer just before it draws a frame.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class CwfLivePreviewViewModel(
    private val engine: CwfPreviewEngine,
    private val wearPlugin: WearPlugin,
    dataHandlerMobile: DataHandlerMobile,
    rxBus: RxBus,
    settingsStore: CwfWatchSettingsStore
) : ViewModel() {

    /** A tap or a new setting asks for the next frame now, instead of after the delay. */
    private val nudge = Channel<Unit>(Channel.CONFLATED)

    /** The newest value of each kind that the renderer has not had yet. Guarded by itself. */
    private val waitingValues = LinkedHashMap<String, EventData>()

    /** The newest watch settings that the renderer has not had yet, or null. */
    private val waitingSettings = AtomicReference<Map<String, String>?>(null)

    init {
        dataHandlerMobile.lastSentToWear().forEach(::keep)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            rxBus.toFlow(EventMobileToWear::class).collect { event -> keep(event.payload) }
        }
        viewModelScope.launch {
            settingsStore.values.collect { settings ->
                waitingSettings.set(settings.mapValues { it.value.value })
                nudge.trySend(Unit)
            }
        }
    }

    /** Keeps [event] until the next frame, in place of an older value of the same kind. */
    private fun keep(event: EventData) {
        val kind = when (event) {
            is EventData.SingleBg      -> "SingleBg${event.dataset}"
            is EventData.Status        -> "Status${event.dataset}"
            is EventData.GraphData     -> "GraphData"
            is EventData.TreatmentData -> "TreatmentData"
            is EventData.Weather       -> "Weather"
            is EventData.Preferences   -> "Preferences"
            else                       -> return
        }
        synchronized(waitingValues) {
            waitingValues.remove(kind)
            waitingValues[kind] = event
        }
    }

    private fun takeWaitingValues(): List<EventData> = synchronized(waitingValues) {
        waitingValues.values.toList().also { waitingValues.clear() }
    }

    /**
     * Frames of [sizePx] x [sizePx] pixels for as long as the flow is collected: once a second, and
     * at the speed of the animation while it plays. Gives null and ends when the preview fails; the
     * caller then shows the static picture.
     */
    fun frames(sizePx: Int): Flow<ImageBitmap?> = flow {
        // Never a frame of the built-in default design: wait for the real one
        wearPlugin.savedCustomWatchface.filterNotNull().first()
        while (currentCoroutineContext().isActive) {
            val design = wearPlugin.savedCustomWatchface.value
            val values = takeWaitingValues()
            val settings = waitingSettings.getAndSet(null)
            val next = engine.use { renderer ->
                design?.let(renderer::setDesign)
                values.forEach(renderer::apply)
                // A new length of the wake-up animation is shown by playing it
                if (settings != null && renderer.applyWatchSettings(settings)) renderer.wake()
                val now = System.currentTimeMillis()
                renderer.frame(sizePx, now) to renderer.frameDelayMs(MS_PER_SECOND - now % MS_PER_SECOND)
            }
            if (next == null) {
                emit(null)
                return@flow
            }
            emit(next.first.asImageBitmap())
            withTimeoutOrNull(next.second) { nudge.receive() }
        }
    }.catch { emit(null) }

    /** Plays the wake-up animation again, as a tap on the watch does. */
    fun wake() {
        viewModelScope.launch {
            if (engine.use { it.wake() } == true) nudge.trySend(Unit)
        }
    }

    /** The screen is left: the face and its pictures are freed, the values and settings stay. */
    override fun onCleared() {
        engine.release()
    }

    private companion object {

        const val MS_PER_SECOND = 1000L
    }
}
