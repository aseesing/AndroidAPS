package app.aaps.wear.watchfaces.animation

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.hypot

/** The rain is the same for the same seed, never leaves the round screen or its region, and never runs out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RainFieldTest {

    private data class Streak(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val alpha: Float)

    private fun streaks(field: RainField, elapsedMs: Long): List<Streak> {
        val list = mutableListOf<Streak>()
        field.forEachStreak(elapsedMs) { x0, y0, x1, y1, a -> list += Streak(x0, y0, x1, y1, a) }
        return list
    }

    @Test
    fun theSameSeedGivesTheSameRain() {
        val a = RainField(RainSpec())
        val b = RainField(RainSpec())
        for (t in listOf(0L, 41L, 1234L, 5999L)) assertThat(streaks(a, t)).isEqualTo(streaks(b, t))
        assertThat(streaks(RainField(RainSpec(seed = 8)), 1234)).isNotEqualTo(streaks(a, 1234))
    }

    @Test
    fun theRainFallsAndSlants() {
        val field = RainField(RainSpec(count = 1, seed = 3, angle = 20f, lengthVariation = 0f))
        val out = FloatArray(4)
        // find a moment where the streak is fully in view, then look 40 ms later
        val t = (0L until 3000L step 10).first { field.streak(0, it, out) && hypot(out[2] - out[0], out[3] - out[1]) > 21f }
        val before = out.copyOf()
        assertThat(field.streak(0, t + 40, out)).isTrue()
        assertThat(out[3]).isGreaterThan(before[3])                     // down
        assertThat(out[2]).isGreaterThan(before[2])                     // and to the right
        assertThat(before[2] - before[0]).isGreaterThan(0f)            // head right of the tail
    }

    @Test
    fun streaksStayInsideTheRoundScreen() {
        val spec = RainSpec(count = 300, thickness = 2f)
        val field = RainField(spec)
        val limit = DESIGN_SIZE / 2 - spec.thickness + 0.01f
        for (t in 0L until 6000L step 41) streaks(field, t).forEach { s ->
            assertThat(hypot(s.x0 - 200f, s.y0 - 200f)).isAtMost(limit)
            assertThat(hypot(s.x1 - 200f, s.y1 - 200f)).isAtMost(limit)
        }
    }

    @Test
    fun streaksStayInsideTheirRegion() {
        val region = DesignRect(100f, 50f, 150f, 120f)
        val field = RainField(RainSpec(count = 120, angle = -30f, region = region))
        var seen = 0
        for (t in 0L until 6000L step 41) streaks(field, t).forEach { s ->
            seen++
            for ((x, y) in listOf(s.x0 to s.y0, s.x1 to s.y1)) {
                assertThat(x).isAtLeast(region.left - 0.01f)
                assertThat(x).isAtMost(region.right + 0.01f)
                assertThat(y).isAtLeast(region.top - 0.01f)
                assertThat(y).isAtMost(region.bottom + 0.01f)
            }
        }
        assertThat(seen).isGreaterThan(0)
    }

    @Test
    fun theRainNeverRunsOut() {
        val field = RainField(RainSpec(count = 90))
        val counts = (0L until 6000L step 41).map { streaks(field, it).size }
        println("RAIN visible streaks per frame: min ${counts.min()}, max ${counts.max()} of 90")
        assertThat(counts.min()).isAtLeast(30)
    }
}
