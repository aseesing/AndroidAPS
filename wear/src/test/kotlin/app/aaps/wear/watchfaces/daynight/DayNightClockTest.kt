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
}
