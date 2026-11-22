// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.complications.wff

import android.graphics.Bitmap
import android.graphics.Color
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.ResFileMap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
internal class DesignArrowsTest {

    private val resources = RuntimeEnvironment.getApplication().resources

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun design() = CwfData(
        json = "{}",
        metadata = mutableMapOf(),
        resData = mutableMapOf(
            ResFileMap.ARROW_FLAT.fileName to ResData(png(), ResFormat.PNG),
            ResFileMap.ARROW_SINGLE_UP.fileName to ResData(png(), ResFormat.PNG)
        )
    )

    @Test
    fun `the arrows of a design are made once, not for every glucose update`() {
        val arrows = DesignArrows()
        val design = design()

        val first = arrows.of(design, resources)
        val second = arrows.of(design, resources)

        assertThat(first.keys).containsExactly("→", "↑")
        assertThat(second).isSameInstanceAs(first)
        assertThat(second["→"]).isSameInstanceAs(first["→"])
    }

    @Test
    fun `another design gets its own arrows`() {
        val arrows = DesignArrows()
        val first = arrows.of(design(), resources)

        val second = arrows.of(design(), resources)

        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second["→"]).isNotSameInstanceAs(first["→"])
    }

    @Test
    fun `without a design there are no arrows`() {
        val arrows = DesignArrows()
        arrows.of(design(), resources)

        assertThat(arrows.of(null, resources)).isEmpty()
    }
}
