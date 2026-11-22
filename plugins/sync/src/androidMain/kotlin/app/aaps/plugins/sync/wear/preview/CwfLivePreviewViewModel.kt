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
import app.aaps.plugins.sync.wear.wearintegration.LastWatchFaceValues
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Feeds the live watch face preview and hands out its frames.
 *
 * - The design is the one the watch has, as the wear screen already shows it, drawn with the whole
 *   design from the phone (see [CwfFullDesigns]).
 * - The values are what the phone last built for the watch and the last glucose it has, read each
 *   time the preview opens, then every new message to the watch. That is what a connected watch
 *   shows; the watch itself is not asked, and without a watch the preview still shows the values the
 *   phone would send.
 * - The watch settings (which values to show, seconds, ...) are the ones the phone keeps in
 *   [CwfWatchSettingsStore]. A change shows in the next frame, drawn at once.
 *
 * Nothing is drawn or stored while no preview is on screen: new values and settings are only kept
 * here, the newest of each kind ([PreviewPendingValues]), and handed to the renderer just before it
 * draws a frame. When the screen stops showing the preview (left, or the app goes to the background),
 * the kept layer pictures and the thread are freed; they come back when the preview is shown again.
 * The last face stays reachable through static fields of the watch code, one face at most, see
 * [CwfPreviewEngine.release].
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class CwfLivePreviewViewModel(
    private val engine: CwfPreviewEngine,
    private val wearPlugin: WearPlugin,
    private val fullDesigns: CwfFullDesigns,
    private val dataHandlerMobile: DataHandlerMobile,
    rxBus: RxBus,
    settingsStore: CwfWatchSettingsStore
) : ViewModel() {

    /** The values the renderer has not had yet, the newest of each kind. */
    private val values = PreviewPendingValues()

    /** The newest watch settings the renderer has not had yet, or null. */
    private val settings = AtomicReference<Map<String, String>?>(null)

    /** A new setting asks for the next frame now, instead of after the delay. */
    private val nudge = Channel<Unit>(Channel.CONFLATED)

    init {
        // The values the phone has now are read each time the preview opens, see [keepStartValues]
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            rxBus.toFlow(EventMobileToWear::class).collect { event -> keep(event.payload) }
        }
        viewModelScope.launch {
            settingsStore.values.collect { stored ->
                settings.set(stored.mapValues { it.value.value })
                nudge.trySend(Unit)
            }
        }
    }

    /** Keeps [event] until the next frame, in place of an older value of the same kind. */
    private fun keep(event: EventData) {
        LastWatchFaceValues.kindOf(event)?.let { kind -> values.put(kind, event) }
    }

    /**
     * Gives the preview what the phone has now: the values last sent to the watch and the last
     * glucose, see `previewStartValues`. Each time the preview opens, so it shows the last glucose at
     * once and does not wait for the next message to the watch. A value that comes from the event bus
     * while these are read is newer, and stays.
     *
     * Never fails: without them the preview shows what the next messages bring.
     */
    private suspend fun keepStartValues() {
        val mark = values.mark()
        val start = try {
            // Not on the main thread: building the glucose message, and the graph when there is none, takes a moment
            withContext(Dispatchers.Default) { dataHandlerMobile.previewStartValues() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        start.forEach { event -> LastWatchFaceValues.kindOf(event)?.let { kind -> values.putUnlessNewer(kind, event, mark) } }
    }

    /**
     * Frames of the face, [sizePx] x [sizePx] pixels, once a second for as long as the flow is collected.
     * When the collection ends, the kept layer pictures and the thread are freed, see [CwfPreviewEngine.release].
     *
     * Gives null while the face can not be drawn right: no design, or no whole design on the phone (see
     * [CwfFullDesigns]); it goes on when the design changes. Gives null and ends when the preview
     * failed. The caller shows the static picture for null.
     */
    fun frames(sizePx: Int): Flow<ImageBitmap?> = flow {
        keepStartValues()
        var shortCopy: CwfData? = null
        var design: CwfData? = null
        while (currentCoroutineContext().isActive) {
            val now = wearPlugin.savedCustomWatchface.value
            if (now !== shortCopy) {
                shortCopy = now
                design = now?.let { watchDesign -> wholeDesign(watchDesign) }
            }
            val drawn = design
            if (drawn == null) {
                emit(null)
                wearPlugin.savedCustomWatchface.first { it !== now }
                continue
            }
            val frame = engine.use { renderer ->
                renderer.setDesign(drawn)
                // Taken here, where they are surely given to the renderer: a preview that closes while
                // it waits for the renderer keeps them for the next time, see PreviewPendingValues
                values.take().forEach(renderer::apply)
                settings.getAndSet(null)?.let(renderer::applyWatchSettings)
                renderer.frame(sizePx, System.currentTimeMillis())
            }
            if (frame == null) {
                emit(null)
                return@flow
            }
            emit(frame.asImageBitmap())
            // The next second, or at once for a new setting
            withTimeoutOrNull(MS_PER_SECOND - System.currentTimeMillis() % MS_PER_SECOND) { nudge.receive() }
        }
    }.catch { emit(null) }.onCompletion { engine.release() }

    /**
     * The design the watch has, with its pictures and fonts: from its zip on the phone, or as it is
     * when it is the default design of the watch, which has no zip. Null when neither.
     */
    private suspend fun wholeDesign(watchDesign: CwfData): CwfData? =
        fullDesigns.complete(watchDesign) ?: watchDesign.takeIf { engine.use { it.isDefaultDesign(watchDesign) } == true }

    /** The screen is left: the kept layer pictures and the thread are freed, the values and settings stay. */
    override fun onCleared() {
        engine.release()
    }

    private companion object {

        const val MS_PER_SECOND = 1000L
    }
}
