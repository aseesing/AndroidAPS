// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for the real org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrbitTest {

    @Test
    fun theDefaultArcGoesFromTheLeftOverTheTopToTheRight() {
        val orbit = Orbit("sun_progress")
        val (x0, y0) = orbit.point(0.0)
        val (xm, ym) = orbit.point(0.5)
        val (x1, y1) = orbit.point(1.0)
        // Start left of the middle and a little above it, top in the middle, end mirrored on the right
        assertThat(x0).isLessThan(30f)
        assertThat(y0).isLessThan(200f)
        assertThat(xm).isWithin(0.01f).of(200f)
        assertThat(ym).isWithin(0.01f).of(12f)
        assertThat(x1).isWithin(0.01f).of(400f - x0)
        assertThat(y1).isWithin(0.01f).of(y0)
        // Values outside 0..1 stay at the ends
        assertThat(orbit.point(-1.0)).isEqualTo(orbit.point(0.0))
        assertThat(orbit.point(3.0)).isEqualTo(orbit.point(1.0))
    }

    @Test
    fun readFromTheView() {
        val view = JSONObject("""{"orbit": {"valueKey": "night_progress", "radius": 180, "startAngle": 190}}""")
        assertThat(Orbit.read(view)).isEqualTo(Orbit("night_progress", radius = 180f, startAngle = 190f))
        assertThat(Orbit.read(JSONObject("""{"orbit": {}}"""))).isNull()
        assertThat(Orbit.read(JSONObject("{}"))).isNull()
        assertThat(Orbit.read(null)).isNull()
    }
}
