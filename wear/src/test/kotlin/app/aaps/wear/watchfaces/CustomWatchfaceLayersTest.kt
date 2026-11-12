// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.children
import app.aaps.wear.databinding.ActivityCustomBinding
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

    @Test
    fun theSecondHandIsTheTopLayerAndAlone() {
        val face = CustomWatchface()
        val last = CustomWatchface.RenderLayer.entries.last()
        assertThat(last).isEqualTo(CustomWatchface.RenderLayer.SECOND_HAND)
        assertThat(face.layerViewIds(last)).hasSize(1)
    }
}
