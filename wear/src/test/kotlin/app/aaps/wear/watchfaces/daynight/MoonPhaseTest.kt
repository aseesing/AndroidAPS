// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class MoonPhaseTest {

    private fun phaseAt(instant: String) = MoonPhase.at(Instant.parse(instant).toEpochMilli())

    /** Distance between two phases on the circle 0..1. */
    private fun distance(a: Double, b: Double): Double = abs(a - b).let { minOf(it, 1 - it) }

    @Test
    fun knownNewAndFullMoons() {
        // Real new and full moons; the mean cycle may be off by about half a day (0.017)
        assertThat(distance(phaseAt("2024-04-08T18:21:00Z"), 0.0)).isLessThan(0.03)
        assertThat(distance(phaseAt("2024-04-23T23:49:00Z"), 0.5)).isLessThan(0.03)
    }

    @Test
    fun alwaysBetweenZeroAndOne() {
        var ms = Instant.parse("1990-01-01T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2060-01-01T00:00:00Z").toEpochMilli()
        while (ms < end) {
            assertThat(MoonPhase.at(ms)).isAtLeast(0.0)
            assertThat(MoonPhase.at(ms)).isLessThan(1.0)
            ms += 37 * 3_600_000L
        }
    }

    @Test
    fun growsByOneCycleInASynodicMonth() {
        val start = Instant.parse("2026-01-10T00:00:00Z").toEpochMilli()
        val quarter = (MoonPhase.SYNODIC_DAYS / 4 * 86_400_000).toLong()
        val a = MoonPhase.at(start)
        val b = MoonPhase.at(start + quarter)
        assertThat(distance((a + 0.25) % 1.0, b)).isLessThan(1e-6)
    }
}
