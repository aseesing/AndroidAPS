// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class WffResourcesTest {

    @Test
    fun picturesGoToDrawableNeverToNodpi() {
        // Pack does not read res/drawable-nodpi
        assertThat(WffResources.picture("loop0_000", WffPictures.Format.PNG)).isEqualTo("res/drawable/loop0_000.png")
        assertThat(WffResources.picture("static_0", WffPictures.Format.WEBP)).isEqualTo("res/drawable/static_0.webp")
    }

    @Test
    fun fontsGoToFont() {
        assertThat(WffResources.font("font_0", "ttf")).isEqualTo("res/font/font_0.ttf")
    }
}
