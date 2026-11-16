// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.view.View
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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

    @Test
    fun with12HoursTheViewIsDrawnInItsOwnBox() {
        // A view of 60 x 60 at (300, 170); with 12 hours a box of 30 wide at (315, 160)
        val view = JSONObject().put("width", 60).put("height", 60).put("leftmargin", 300).put("topmargin", 170)
            .put(SecondSpin.BOX_12H_KEY, JSONObject().put("left", 315).put("top", 160).put("width", 30))
        assertThat(SecondSpin.placement(view, is24h = true)).isEqualTo(SecondSpin.AS_DESIGNED)
        val twelve = SecondSpin.placement(view, is24h = false)!!
        assertThat(twelve.scale).isEqualTo(0.5f)
        // The middle moves from (330, 200) to (330, 175)
        assertThat(twelve.dx).isEqualTo(0f)
        assertThat(twelve.dy).isEqualTo(-25f)
        // Without the key nothing changes
        assertThat(SecondSpin.placement(JSONObject().put("width", 60).put("height", 60), is24h = false)).isNull()
        assertThat(SecondSpin.placement(null, is24h = false)).isNull()
    }

    @Test
    fun theViewIsMovedInScreenPixels() {
        val view = View(RuntimeEnvironment.getApplication())
        SecondSpin.place(view, SecondSpin.Placement(0.5f, 10f, -20f), zoom = 1.5)
        assertThat(view.scaleX).isEqualTo(0.5f)
        assertThat(view.scaleY).isEqualTo(0.5f)
        assertThat(view.translationX).isEqualTo(15f)
        assertThat(view.translationY).isEqualTo(-30f)
        // Back to 24 hours
        SecondSpin.place(view, SecondSpin.AS_DESIGNED, zoom = 1.5)
        assertThat(view.scaleX).isEqualTo(1f)
        assertThat(view.translationY).isEqualTo(0f)
    }
}
