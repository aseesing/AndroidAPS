// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class WffTextPlaceTest {

    private val roboto = WffFontMetrics.ROBOTO

    @Test
    fun aTextHigherThanItsViewStartsAtTheTopOfTheView() {
        // 80 px Roboto in a view of 100 px: the classic face draws it from the top, WFF centres it,
        // about 7 px higher (seen on the emulator). The top of the font is rounded down: 84.48 -> 85.
        assertThat(WffTextPlace.viewBaseline(roboto, 80.0, 100, centred = true)).isEqualTo(85)
        assertThat(WffTextPlace.viewShift(roboto, 80.0, 100, centred = true)).isWithin(0.01).of(7.64)
    }

    @Test
    fun aCentredTextThatFitsMovesOnlyALittle() {
        assertThat(WffTextPlace.viewShift(roboto, 40.0, 200, centred = true)).isWithin(0.01).of(2.32)
    }

    @Test
    fun aCentredTextKeepsWholePixels() {
        // 90.8 px in a view of 124 px: the font is 96 + 25 = 121 px high, the 3 px left give 1 px above
        assertThat(WffTextPlace.viewBaseline(roboto, 90.8, 124, centred = true)).isEqualTo(97)
    }

    @Test
    fun aTextOfALeftViewStaysAtTheTop() {
        // A view with gravity left or right keeps its text at the top
        assertThat(WffTextPlace.viewBaseline(roboto, 21.0, 33, centred = false)).isEqualTo(23)
    }

    @Test
    fun wffCentresByAscentAndDescent() {
        assertThat(WffTextPlace.wffBaseline(roboto, 100.0, 117.2)).isWithin(0.01).of(92.8)
    }
}
