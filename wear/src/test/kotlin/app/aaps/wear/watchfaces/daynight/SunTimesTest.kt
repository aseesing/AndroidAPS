// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class SunTimesTest {

    private fun riseAndSet(date: String, latitude: Double, longitude: Double): SunTimes.Result.RiseAndSet {
        val result = SunTimes.of(LocalDate.parse(date), latitude, longitude)
        assertThat(result).isInstanceOf(SunTimes.Result.RiseAndSet::class.java)
        return result as SunTimes.Result.RiseAndSet
    }

    private fun assertNear(actual: Instant, expected: String) {
        val difference = Duration.between(Instant.parse(expected), actual).abs()
        assertThat(difference).isAtMost(Duration.ofMinutes(5))
    }

    @Test
    fun amsterdamInSummer() {
        // Published times: 05:18 and 22:06 summer time
        val sun = riseAndSet("2024-06-21", AMSTERDAM_LAT, AMSTERDAM_LON)
        assertNear(sun.rise, "2024-06-21T03:18:00Z")
        assertNear(sun.set, "2024-06-21T20:06:00Z")
    }

    @Test
    fun amsterdamInWinter() {
        // Published times: 08:48 and 16:29 winter time
        val sun = riseAndSet("2024-12-21", AMSTERDAM_LAT, AMSTERDAM_LON)
        assertNear(sun.rise, "2024-12-21T07:48:00Z")
        assertNear(sun.set, "2024-12-21T15:29:00Z")
    }

    @Test
    fun theDayIsAboutTwelveHoursAtTheEquinoxEverywhere() {
        for (latitude in listOf(-40.0, 0.0, 52.0)) {
            val sun = riseAndSet("2025-03-20", latitude, 0.0)
            assertThat(Duration.between(sun.rise, sun.set).toMinutes()).isIn(715L..735L)
        }
    }

    @Test
    fun beyondThePolarCircleTheSunStaysUpOrDown() {
        assertThat(SunTimes.of(LocalDate.parse("2025-06-21"), TROMSO_LAT, TROMSO_LON)).isEqualTo(SunTimes.Result.AlwaysUp)
        assertThat(SunTimes.of(LocalDate.parse("2025-12-21"), TROMSO_LAT, TROMSO_LON)).isEqualTo(SunTimes.Result.AlwaysDown)
    }

    private companion object {

        const val AMSTERDAM_LAT = 52.37
        const val AMSTERDAM_LON = 4.90
        const val TROMSO_LAT = 69.65
        const val TROMSO_LON = 18.96
    }
}
