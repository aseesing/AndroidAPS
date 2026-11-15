// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecondSpinTest {

    @Test
    fun withoutTheKeyNothingTurns() {
        assertThat(SecondSpin.rotation(null, 5)).isNull()
        assertThat(SecondSpin.rotation(JSONObject().put("rotation", 10), 5)).isNull()
    }

    @Test
    fun theViewTurnsAStepEachSecondFromItsOwnRotation() {
        val view = JSONObject().put(SecondSpin.JSON_KEY, 6).put("rotation", 10)
        assertThat(SecondSpin.rotation(view, 0)).isEqualTo(10f)
        assertThat(SecondSpin.rotation(view, 1)).isEqualTo(16f)
        assertThat(SecondSpin.rotation(view, 59)).isEqualTo((10 + 354) % 360f)
    }
}
