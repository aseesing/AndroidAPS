// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DayNightClockTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private fun ms(instant: String) = Instant.parse(instant).toEpochMilli()

    @Test
    fun withAPlaceItFollowsTheSun() {
        val clock = DayNightClock(location = { GeoPoint(52.37, 4.90) }, zone = { amsterdam })
        // 21 December: sunrise about 07:48 UTC, sunset about 15:29 UTC
        assertThat(clock.isDay(ms("2024-12-21T07:30:00Z"))).isFalse()
        assertThat(clock.isDay(ms("2024-12-21T08:10:00Z"))).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T15:10:00Z"))).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T15:50:00Z"))).isFalse()
        // 21 June at 20:00 UTC (22:00 local) the sun has not yet set
        assertThat(clock.isDay(ms("2024-06-21T20:00:00Z"))).isTrue()
    }

    @Test
    fun withoutAPlaceTheDayIsSevenToSevenLocal() {
        val clock = DayNightClock(location = { null }, zone = { amsterdam })
        // Winter time is UTC+1
        assertThat(clock.isDay(ms("2024-12-21T05:59:00Z"))).isFalse()
        assertThat(clock.isDay(ms("2024-12-21T06:00:00Z"))).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T17:59:00Z"))).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T18:00:00Z"))).isFalse()
    }

    @Test
    fun anImpossiblePlaceCountsAsNoPlace() {
        val clock = DayNightClock(location = { GeoPoint(Double.NaN, 4.9) }, zone = { amsterdam })
        assertThat(clock.isDay(ms("2024-06-21T19:00:00Z"))).isFalse()     // 21:00 local, after the fixed day
    }

    @Test
    fun polarSummerAndWinter() {
        val tromso = GeoPoint(69.65, 18.96)
        val clock = DayNightClock(location = { tromso }, zone = { ZoneId.of("Europe/Oslo") })
        assertThat(clock.isDay(ms("2025-06-21T23:00:00Z"))).isTrue()
        assertThat(clock.isDay(ms("2025-12-21T11:00:00Z"))).isFalse()
    }

    @Test
    fun aNewPlaceIsFollowed() {
        var place = GeoPoint(52.37, 4.90)
        val clock = DayNightClock(location = { place }, zone = { ZoneId.of("UTC") })
        // 07:00 UTC on 21 December: still night in Amsterdam, already day far to the east
        assertThat(clock.isDay(ms("2024-12-21T07:00:00Z"))).isFalse()
        place = GeoPoint(52.0, 40.0)
        assertThat(clock.isDay(ms("2024-12-21T07:00:00Z"))).isTrue()
    }

    @Test
    fun sunProgressRunsFromSunriseToSunset() {
        val clock = DayNightClock(location = { GeoPoint(52.37, 4.90) }, zone = { amsterdam })
        // 21 December: sunrise about 07:48 UTC, sunset about 15:29 UTC, noon about 11:39 UTC
        assertThat(clock.sunProgress(ms("2024-12-21T05:00:00Z"))).isEqualTo(0.0)
        assertThat(clock.sunProgress(ms("2024-12-21T11:39:00Z"))).isWithin(0.03).of(0.5)
        assertThat(clock.sunProgress(ms("2024-12-21T09:43:00Z"))).isWithin(0.03).of(0.25)
        assertThat(clock.sunProgress(ms("2024-12-21T20:00:00Z"))).isEqualTo(1.0)
    }

    @Test
    fun sunProgressWithoutAPlaceUsesTheFixedHours() {
        val clock = DayNightClock(location = { null }, zone = { amsterdam })
        // 07:00 to 19:00 local is 06:00 to 18:00 UTC in winter; 13:00 local is the middle
        assertThat(clock.sunProgress(ms("2024-12-21T06:00:00Z"))).isEqualTo(0.0)
        assertThat(clock.sunProgress(ms("2024-12-21T12:00:00Z"))).isWithin(1e-9).of(0.5)
        assertThat(clock.sunProgress(ms("2024-12-21T18:00:00Z"))).isEqualTo(1.0)
    }

    @Test
    fun sunProgressInPolarSummerAndWinter() {
        val clock = DayNightClock(location = { GeoPoint(69.65, 18.96) }, zone = { ZoneId.of("Europe/Oslo") })
        // The sun does not set: the whole local day, so noon local (10:00 UTC in summer) is the middle
        assertThat(clock.sunProgress(ms("2025-06-21T10:00:00Z"))).isWithin(0.01).of(0.5)
        // The sun does not rise
        assertThat(clock.sunProgress(ms("2025-12-21T11:00:00Z"))).isEqualTo(0.0)
    }

    @Test
    fun nightProgressRunsFromSunsetToSunrise() {
        val clock = DayNightClock(location = { null }, zone = { amsterdam })
        // Fixed hours: night from 19:00 to 07:00 local, which is 18:00 to 06:00 UTC in winter
        assertThat(clock.nightProgress(ms("2024-12-21T12:00:00Z"))).isEqualTo(0.0)
        assertThat(clock.nightProgress(ms("2024-12-21T18:00:00Z"))).isWithin(1e-9).of(0.0)
        assertThat(clock.nightProgress(ms("2024-12-22T00:00:00Z"))).isWithin(1e-9).of(0.5)
        // Also after midnight, from the sunset of the day before
        assertThat(clock.nightProgress(ms("2024-12-22T03:00:00Z"))).isWithin(1e-9).of(0.75)
        assertThat(clock.nightProgress(ms("2024-12-22T05:59:00Z"))).isGreaterThan(0.99)
    }

    @Test
    fun nightProgressFollowsTheSunWithAPlace() {
        val clock = DayNightClock(location = { GeoPoint(52.37, 4.90) }, zone = { amsterdam })
        // Sunset 21 December about 15:29 UTC, sunrise 22 December about 07:48 UTC: middle about 23:39 UTC
        assertThat(clock.nightProgress(ms("2024-12-21T23:39:00Z"))).isWithin(0.02).of(0.5)
        assertThat(clock.nightProgress(ms("2024-12-21T15:35:00Z"))).isLessThan(0.02)
    }
}
