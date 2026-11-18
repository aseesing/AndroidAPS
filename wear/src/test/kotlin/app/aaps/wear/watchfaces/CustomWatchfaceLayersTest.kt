package app.aaps.wear.watchfaces

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.children
import androidx.wear.watchface.CanvasComplication
import app.aaps.wear.R
import app.aaps.wear.databinding.ActivityCustomBinding
import app.aaps.wear.watchfaces.utils.WatchFaceComplications
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The second hand frame pastes the face together from its layers ([CustomWatchface.RenderLayer]).
 * That gives the same picture as the whole face only if the layers, one after another, hold every
 * view of the layout exactly once and in the order the layout paints them. A view added to the
 * layout but forgotten in the layers would be missing from the watch while seconds are shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomWatchfaceLayersTest {

    @Test
    fun layersHoldEveryViewOfTheLayoutOnceInPaintOrder() {
        val context = RuntimeEnvironment.getApplication()
        val layout = ActivityCustomBinding.inflate(LayoutInflater.from(context)).mainLayout
        val paintOrder = layout.children.map { it.id }.toList()

        val face = CustomWatchface()
        val layered = CustomWatchface.RenderLayer.entries
            .flatMap { face.layerViewIds(it) }
            .filter { it != View.NO_ID }       // the background, which is not in the layout

        assertThat(layered).containsExactlyElementsIn(paintOrder).inOrder()
    }

    /**
     * The kept pictures of the second hand frame must be drawn again when the face changes, or an old
     * glucose value stays on the screen. New data and a preference change reach the face through
     * `invalidate()` (see `BaseWatchFace.onCreate`), a mode change through `invalidate()` in
     * `onWatchModeChanged` and, in any case, through the ambient frames, which are drawn the normal way.
     */
    @Test
    fun everyChangeOfTheFaceGivesNewContentForTheKeptPictures() {
        val face = CustomWatchface()
        val start = face.layeredFrameContent()

        face.invalidate()                         // new data, a preference change, a mode change
        val afterInvalidate = face.layeredFrameContent()
        assertThat(afterInvalidate).isNotEqualTo(start)

        face.frameDrawn(layered = false)          // an ambient frame, an editor frame, a wake frame
        val afterNormalFrame = face.layeredFrameContent()
        assertThat(afterNormalFrame).isNotEqualTo(afterInvalidate)

        face.frameDrawn(layered = true)           // a second of the running clock: nothing changed
        assertThat(face.layeredFrameContent()).isEqualTo(afterNormalFrame)
    }

    @Test
    fun aComplicationThatAsksToBeDrawnAgainIsCounted() {
        val complications = WatchFaceComplications(
            context = RuntimeEnvironment.getApplication(),
            slotIds = listOf(1),
            supportedTypesFor = { emptyList() },
            layoutSettingLabel = R.string.cwf_complication_layout,
            layoutSettingDescription = R.string.cwf_complication_layout_description
        )
        var libraryCalls = 0
        val callback = complications.countingRedrawRequests(object : CanvasComplication.InvalidateCallback {
            override fun onInvalidate() {
                libraryCalls++
            }
        })

        callback.onInvalidate()

        assertThat(complications.redrawRequestCount).isEqualTo(1)
        assertThat(libraryCalls).isEqualTo(1)     // the library still gets its own call
    }

    @Test
    fun theSecondHandIsTheTopLayerAndAlone() {
        val face = CustomWatchface()
        val last = CustomWatchface.RenderLayer.entries.last()
        assertThat(last).isEqualTo(CustomWatchface.RenderLayer.SECOND_HAND)
        assertThat(face.layerViewIds(last)).hasSize(1)
    }
}
