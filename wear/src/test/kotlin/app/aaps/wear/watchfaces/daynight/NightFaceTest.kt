// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NightFaceTest {

    @Test
    fun withoutANightFaceTheMainFaceShowsDayAndNight() {
        assertThat(NightFace.choose("main", null) { true }).isEqualTo("main")
        assertThat(NightFace.choose("main", null) { false }).isEqualTo("main")
    }

    @Test
    fun withANightFaceTheMainFaceIsTheDayFace() {
        assertThat(NightFace.choose("main", "night") { true }).isEqualTo("main")
        assertThat(NightFace.choose("main", "night") { false }).isEqualTo("night")
    }

    @Test
    fun theTimeOfDayIsOnlyAskedWithANightFace() {
        var asked = 0
        NightFace.choose("main", null) { asked++; false }
        assertThat(asked).isEqualTo(0)
        NightFace.choose("main", "night") { asked++; false }
        assertThat(asked).isEqualTo(1)
    }
}
