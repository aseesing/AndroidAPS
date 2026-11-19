package app.aaps.plugins.sync.wear.preview

import android.graphics.Bitmap
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
import app.aaps.plugins.sync.wear.wearintegration.LastWatchFaceValues
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Feeds the live watch face preview and hands out its frames.
 *
 * - The design is the one the watch has, as the wear screen already shows it.
 * - The values are what the phone last sent to the watch, then every new message to the watch. That
 *   is what a connected watch shows; the watch itself is not asked, and without a watch the preview
 *   still shows the values the phone would send.
 * - The watch settings (which values to show, seconds, wake-up animation, ...) are the ones the phone
 *   keeps in [CwfWatchSettingsStore]. A change shows in the next frame, drawn at once.
 *
 * - The day face and the night face each have their own preview ([PreviewSlot]), drawn with the
 *   whole design from the phone, see [CwfFullDesigns].
 *
 * Nothing is drawn or stored while no preview is on screen: new values and settings are only kept
 * here, the newest of each kind, and handed to the renderer just before it draws a frame. When the
 * screen stops showing a preview (left, or the app goes to the background), the kept layer pictures
 * of its face are freed, and the thread too when no preview is shown; they come back when the
 * preview is shown again, see [CwfPreviewEngine.release].
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
        // The kinds of the watch face, the weather a design can draw, and the rule for its sun and moon
        val kind = LastWatchFaceValues.kindOf(event) ?: when (event) {
            is EventData.Weather                                                       -> "Weather"
            is EventData.ActionSetDayNightRule, is EventData.ActionClearDayNightRule -> "DayNightRule"
            else                                                                       -> return
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
     * once a second, and at the speed of the animation while it plays. When the collection ends, what
     * no screen shows any more is freed, see [CwfPreviewEngine.hidden].
     *
     * Gives null while the face can not be drawn right: no design, or no whole design on the phone (see
     * [CwfFullDesigns]); it goes on when the design changes. Gives null and ends when the preview
     * failed. The caller shows the static picture for null.
     *
     * The renderer draws into a few bitmaps in turn (see `CwfPreviewRenderer.frame`), and only into one
     * that the screen does not show and will not show again ([FrameHandOff]). Each frame is a new
     * [PreviewFrame], so the screen draws every one; each bitmap is wrapped for Compose once.
     */
    fun frames(sizePx: Int, slot: PreviewSlot): Flow<PreviewFrame?> = flow {
        val waits = waiting.getValue(slot)
        var shortCopy: CwfData? = null
        var design: CwfData? = null
        val handOff = FrameHandOff<Bitmap>()
        val wrapped = HashMap<Bitmap, ImageBitmap>()
        while (currentCoroutineContext().isActive) {
            val now = watchDesign(slot).value
            if (now !== shortCopy) {
                shortCopy = now
                design = now?.let { watchDesign -> wholeDesign(watchDesign, slot) }
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
                renderer.frame(sizePx, time, handOff::isFree) to renderer.frameDelayMs(MS_PER_SECOND - time % MS_PER_SECOND)
            }
            if (next == null) {
                emit(null)
                return@flow
            }
            val bitmap = next.first
            if (bitmap == null) {
                // Every bitmap is still on the screen or about to be: wait for the screen to draw
                delay(BUSY_WAIT_MS)
                continue
            }
            // Wrapped once per bitmap; a bitmap made new (another size) gets its own
            if (wrapped.size > MAX_WRAPPED) wrapped.keys.retainAll { it === bitmap }
            emit(PreviewFrame(wrapped.getOrPut(bitmap) { bitmap.asImageBitmap() }, handOff.handedOut(bitmap), handOff))
            withTimeoutOrNull(next.second) { waits.nudge.receive() }
        }
    }.catch { emit(null) }
        .onStart { engine.shown(slot) }
        .onCompletion { engine.hidden(slot) }

    /**
     * The design the watch has, with its pictures and fonts: from its zip on the phone, or as it is
     * when it is the default design of the watch, which has no zip. Null when neither.
     */
    private suspend fun wholeDesign(watchDesign: CwfData, slot: PreviewSlot): CwfData? =
        fullDesigns.complete(watchDesign) ?: watchDesign.takeIf { engine.use(slot) { it.isDefaultDesign(watchDesign) } == true }

    /** Plays the wake-up animation of [slot] again, as a tap on the watch does. */
    fun wake(slot: PreviewSlot) {
        viewModelScope.launch {
            if (engine.use(slot) { it.wake() } == true) waiting.getValue(slot).nudge.trySend(Unit)
        }
    }

    /** The screen is left: the kept layer pictures and the thread are freed, the values and settings stay. */
    override fun onCleared() {
        engine.release()
    }

    private companion object {

        const val MS_PER_SECOND = 1000L

        /** How long to wait when no bitmap is free to draw into: about one screen frame. */
        const val BUSY_WAIT_MS = 8L

        /** More wrapped bitmaps than the renderer has means some are of an old size. */
        const val MAX_WRAPPED = 3
    }
}
