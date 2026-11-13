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
import app.aaps.core.interfaces.rx.weardata.CwfData
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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
 * - The day face and the night face each have their own preview ([PreviewSlot]), drawn with the
 *   whole design from the phone, see [CwfFullDesigns].
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
    private val fullDesigns: CwfFullDesigns,
    dataHandlerMobile: DataHandlerMobile,
    rxBus: RxBus,
    settingsStore: CwfWatchSettingsStore
) : ViewModel() {

    /** What each preview has not had yet, and how to wake it. */
    private class Waiting {

        /** The newest value of each kind. Guarded by itself. */
        val values = LinkedHashMap<String, EventData>()

        /** The newest watch settings, or null. */
        val settings = AtomicReference<Map<String, String>?>(null)

        /** A tap or a new setting asks for the next frame now, instead of after the delay. */
        val nudge = Channel<Unit>(Channel.CONFLATED)
    }

    private val waiting = PreviewSlot.entries.associateWith { Waiting() }

    init {
        dataHandlerMobile.lastSentToWear().forEach(::keep)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            rxBus.toFlow(EventMobileToWear::class).collect { event -> keep(event.payload) }
        }
        viewModelScope.launch {
            settingsStore.values.collect { settings ->
                val values = settings.mapValues { it.value.value }
                waiting.values.forEach { slot ->
                    slot.settings.set(values)
                    slot.nudge.trySend(Unit)
                }
            }
        }
    }

    /** Keeps [event] until the next frame of each preview, in place of an older value of the same kind. */
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
        waiting.values.forEach { slot ->
            synchronized(slot.values) {
                slot.values.remove(kind)
                slot.values[kind] = event
            }
        }
    }

    private fun takeValues(slot: Waiting): List<EventData> = synchronized(slot.values) {
        slot.values.values.toList().also { slot.values.clear() }
    }

    /** The design the watch has for [slot], as the short copy it sends. */
    private fun watchDesign(slot: PreviewSlot): StateFlow<CwfData?> = when (slot) {
        PreviewSlot.DAY   -> wearPlugin.savedCustomWatchface
        PreviewSlot.NIGHT -> wearPlugin.savedNightWatchface
    }

    /**
     * Frames of the face of [slot], [sizePx] x [sizePx] pixels, for as long as the flow is collected:
     * once a second, and at the speed of the animation while it plays.
     *
     * Gives null while the face can not be drawn right: no design, or no whole design on the phone (see
     * [CwfFullDesigns]); it goes on when the design changes. Gives null and ends when the preview
     * failed. The caller shows the static picture for null.
     */
    fun frames(sizePx: Int, slot: PreviewSlot): Flow<ImageBitmap?> = flow {
        val waits = waiting.getValue(slot)
        var shortCopy: CwfData? = null
        var design: CwfData? = null
        while (currentCoroutineContext().isActive) {
            val now = watchDesign(slot).value
            if (now !== shortCopy) {
                shortCopy = now
                design = now?.let { fullDesigns.complete(it) }
            }
            val drawn = design
            if (drawn == null) {
                emit(null)
                watchDesign(slot).first { it !== now }
                continue
            }
            val values = takeValues(waits)
            val settings = waits.settings.getAndSet(null)
            val next = engine.use(slot) { renderer ->
                renderer.setDesign(drawn)
                values.forEach(renderer::apply)
                // A new length of the wake-up animation is shown by playing it
                if (settings != null && renderer.applyWatchSettings(settings)) renderer.wake()
                val time = System.currentTimeMillis()
                renderer.frame(sizePx, time) to renderer.frameDelayMs(MS_PER_SECOND - time % MS_PER_SECOND)
            }
            if (next == null) {
                emit(null)
                return@flow
            }
            emit(next.first.asImageBitmap())
            withTimeoutOrNull(next.second) { waits.nudge.receive() }
        }
    }.catch { emit(null) }

    /** Plays the wake-up animation of [slot] again, as a tap on the watch does. */
    fun wake(slot: PreviewSlot) {
        viewModelScope.launch {
            if (engine.use(slot) { it.wake() } == true) waiting.getValue(slot).nudge.trySend(Unit)
        }
    }

    /** The screen is left: the faces and their pictures are freed, the values and settings stay. */
    override fun onCleared() {
        engine.release()
    }

    private companion object {

        const val MS_PER_SECOND = 1000L
    }
}
