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
        // about 7 px higher (seen on the emulator)
        assertThat(WffTextPlace.viewBaseline(roboto, 80.0, 100.0, centred = true)).isWithin(0.01).of(84.48)
        assertThat(WffTextPlace.viewShift(roboto, 80.0, 100.0, centred = true)).isWithin(0.01).of(7.12)
    }

    @Test
    fun aCentredTextThatFitsMovesOnlyALittle() {
        assertThat(WffTextPlace.viewShift(roboto, 40.0, 200.0, centred = true)).isWithin(0.1).of(2.0)
    }

    @Test
    fun aTextOfALeftViewStaysAtTheTop() {
        // A view with gravity left or right keeps its text at the top
        assertThat(WffTextPlace.viewBaseline(roboto, 21.0, 33.0, centred = false)).isWithin(0.01).of(22.18)
    }

    @Test
    fun wffCentresByAscentAndDescent() {
        assertThat(WffTextPlace.wffBaseline(roboto, 100.0, 117.2)).isWithin(0.01).of(92.8)
    }
}
