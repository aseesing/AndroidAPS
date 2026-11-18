package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.DayNightRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DayNightClockTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private fun ms(instant: String) = Instant.parse(instant).toEpochMilli()
    private fun place(latitude: Double, longitude: Double) = DayNightRule.Place(latitude, longitude)

    // Fixed times

    @Test
    fun fixedTimesFollowTheLocalClock() {
        val clock = DayNightClock(zone = { amsterdam })
        val rule = DayNightRule.FixedTimes(dayStartMinute = 6 * 60 + 30, nightStartMinute = 22 * 60)
        // Winter time is UTC+1
        assertThat(clock.isDay(ms("2024-12-21T05:29:00Z"), rule)).isFalse()   // 06:29 local
        assertThat(clock.isDay(ms("2024-12-21T05:30:00Z"), rule)).isTrue()    // 06:30 local
        assertThat(clock.isDay(ms("2024-12-21T20:59:00Z"), rule)).isTrue()    // 21:59 local
        assertThat(clock.isDay(ms("2024-12-21T21:00:00Z"), rule)).isFalse()   // 22:00 local
    }

    @Test
    fun fixedTimesCanWrapOverMidnight() {
        // A night shift worker: day from 20:00 to 08:00
        val clock = DayNightClock(zone = { ZoneId.of("UTC") })
        val rule = DayNightRule.FixedTimes(dayStartMinute = 20 * 60, nightStartMinute = 8 * 60)
        assertThat(clock.isDay(ms("2024-12-21T19:59:00Z"), rule)).isFalse()
        assertThat(clock.isDay(ms("2024-12-21T20:00:00Z"), rule)).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T23:59:00Z"), rule)).isTrue()
        assertThat(clock.isDay(ms("2024-12-22T00:00:00Z"), rule)).isTrue()
        assertThat(clock.isDay(ms("2024-12-22T07:59:00Z"), rule)).isTrue()
        assertThat(clock.isDay(ms("2024-12-22T08:00:00Z"), rule)).isFalse()
        assertThat(clock.isDay(ms("2024-12-22T12:00:00Z"), rule)).isFalse()
    }

    @Test
    fun equalTimesAreAlwaysDay() {
        val clock = DayNightClock(zone = { ZoneId.of("UTC") })
        val rule = DayNightRule.FixedTimes(dayStartMinute = 7 * 60, nightStartMinute = 7 * 60)
        for (hour in 0 until 24) {
            assertThat(clock.isDay(ms("2024-12-21T%02d:00:00Z".format(hour)), rule)).isTrue()
        }
    }

    @Test
    fun fixedTimesOutOfRangeCountAsNoRule() {
        val clock = DayNightClock(zone = { ZoneId.of("UTC") })
        val rule = DayNightRule.FixedTimes(dayStartMinute = -5, nightStartMinute = 24 * 60)
        assertThat(clock.isDay(ms("2024-12-21T06:59:00Z"), rule)).isFalse()
        assertThat(clock.isDay(ms("2024-12-21T12:00:00Z"), rule)).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T19:00:00Z"), rule)).isFalse()
    }

    // No rule

    @Test
    fun withoutARuleTheDayIsSevenToSevenLocal() {
        val clock = DayNightClock(zone = { amsterdam })
        // Winter time is UTC+1
        assertThat(clock.isDay(ms("2024-12-21T05:59:00Z"), null)).isFalse()
        assertThat(clock.isDay(ms("2024-12-21T06:00:00Z"), null)).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T17:59:00Z"), null)).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T18:00:00Z"), null)).isFalse()
    }

    @Test
    fun anImpossiblePlaceCountsAsNoRule() {
        val clock = DayNightClock(zone = { amsterdam })
        assertThat(clock.isDay(ms("2024-06-21T19:00:00Z"), place(Double.NaN, 4.9))).isFalse()   // 21:00 local, after the fixed day
        assertThat(clock.isDay(ms("2024-06-21T10:00:00Z"), place(Double.NaN, 4.9))).isTrue()    // 12:00 local
        assertThat(clock.isDay(ms("2024-06-21T10:00:00Z"), place(95.0, 4.9))).isTrue()
    }

    // A place

    @Test
    fun withAPlaceItFollowsTheSun() {
        val clock = DayNightClock(zone = { amsterdam })
        val rule = place(52.37, 4.90)
        // 21 December: sunrise about 07:48 UTC, sunset about 15:29 UTC
        assertThat(clock.isDay(ms("2024-12-21T07:30:00Z"), rule)).isFalse()
        assertThat(clock.isDay(ms("2024-12-21T08:10:00Z"), rule)).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T15:10:00Z"), rule)).isTrue()
        assertThat(clock.isDay(ms("2024-12-21T15:50:00Z"), rule)).isFalse()
        // 21 June at 20:00 UTC (22:00 local) the sun has not yet set
        assertThat(clock.isDay(ms("2024-06-21T20:00:00Z"), rule)).isTrue()
    }

    @Test
    fun theSunDoesNotDependOnTheWatchZone() {
        // A city far away: the watch's own zone plays no part
        val rule = place(52.37, 4.90)
        val inAmsterdam = DayNightClock(zone = { amsterdam })
        val inTokyo = DayNightClock(zone = { ZoneId.of("Asia/Tokyo") })
        for (instant in listOf("2024-12-21T07:30:00Z", "2024-12-21T12:00:00Z", "2024-12-21T23:30:00Z", "2024-06-21T20:00:00Z")) {
            assertThat(inTokyo.isDay(ms(instant), rule)).isEqualTo(inAmsterdam.isDay(ms(instant), rule))
        }
    }

    @Test
    fun polarSummerAndWinter() {
        val tromso = place(69.65, 18.96)
        val clock = DayNightClock(zone = { ZoneId.of("Europe/Oslo") })
        assertThat(clock.isDay(ms("2025-06-21T23:00:00Z"), tromso)).isTrue()
        assertThat(clock.isDay(ms("2025-06-22T00:30:00Z"), tromso)).isTrue()
        assertThat(clock.isDay(ms("2025-12-21T11:00:00Z"), tromso)).isFalse()
    }

    @Test
    fun theLastPolarDayIsDayAllAroundItsNoon() {
        // Tromso: the midnight sun ends in late July. Find the last UTC day where the sun stays up,
        // then check the hours around its solar noon, also those in the next or the last UTC day.
        val tromso = place(69.65, 18.96)
        var day = LocalDate.parse("2025-07-01")
        while (SunTimes.of(day.plusDays(1), tromso.latitude, tromso.longitude) is SunTimes.Result.AlwaysUp) day = day.plusDays(1)
        val last = SunTimes.of(day, tromso.latitude, tromso.longitude) as SunTimes.Result.AlwaysUp
        // A zone a day ahead of UTC at that moment, so the local date is not the UTC date
        val clock = DayNightClock(zone = { ZoneId.of("Pacific/Kiritimati") })
        for (hours in listOf(-11L, -6L, 0L, 6L, 11L)) {
            assertThat(clock.isDay(last.noon.plusSeconds(hours * 3600).toEpochMilli(), tromso)).isTrue()
        }
    }

    @Test
    fun aNewPlaceIsFollowed() {
        val clock = DayNightClock(zone = { ZoneId.of("UTC") })
        // 07:00 UTC on 21 December: still night in Amsterdam, already day far to the east
        assertThat(clock.isDay(ms("2024-12-21T07:00:00Z"), place(52.37, 4.90))).isFalse()
        assertThat(clock.isDay(ms("2024-12-21T07:00:00Z"), place(52.0, 40.0))).isTrue()
    }

    @Test
    fun farFromGreenwich() {
        // Sydney, 21 December (summer): sunrise about 05:40 and sunset about 20:05 local (UTC+11)
        val clock = DayNightClock(zone = { ZoneId.of("Australia/Sydney") })
        val rule = place(-33.87, 151.21)
        assertThat(clock.isDay(ms("2024-12-20T18:00:00Z"), rule)).isFalse()   // 05:00 local
        assertThat(clock.isDay(ms("2024-12-21T01:00:00Z"), rule)).isTrue()    // 12:00 local
        assertThat(clock.isDay(ms("2024-12-21T08:30:00Z"), rule)).isTrue()    // 19:30 local
        assertThat(clock.isDay(ms("2024-12-21T09:30:00Z"), rule)).isFalse()   // 20:30 local
    }

    @Test
    fun westOfTheDateLineWithUtcPlus13() {
        // Apia, Samoa (UTC+13): 22 December local: sunrise about 05:57, sunset about 18:53.
        val clock = DayNightClock(zone = { ZoneId.of("Pacific/Apia") })
        val rule = place(-13.83, -171.76)
        assertThat(clock.isDay(ms("2024-12-21T15:00:00Z"), rule)).isFalse()   // 04:00 local
        assertThat(clock.isDay(ms("2024-12-21T23:00:00Z"), rule)).isTrue()    // 12:00 local
        assertThat(clock.isDay(ms("2024-12-22T05:00:00Z"), rule)).isTrue()    // 18:00 local
        assertThat(clock.isDay(ms("2024-12-22T08:00:00Z"), rule)).isFalse()   // 21:00 local
    }

    @Test
    fun inTheAmericas() {
        val clock = DayNightClock(zone = { ZoneId.of("America/New_York") })
        val rule = place(40.71, -74.01)
        // New York, 21 December (UTC-5): sunrise about 07:16, sunset about 16:31 local
        assertThat(clock.isDay(ms("2024-12-21T12:00:00Z"), rule)).isFalse()   // 07:00 local
        assertThat(clock.isDay(ms("2024-12-21T17:00:00Z"), rule)).isTrue()    // 12:00 local
        assertThat(clock.isDay(ms("2024-12-21T22:00:00Z"), rule)).isFalse()   // 17:00 local
        // 21 June (UTC-4): sunset about 20:30 local, which is 00:30 UTC of the next day
        assertThat(clock.isDay(ms("2024-06-22T00:15:00Z"), rule)).isTrue()    // 20:15 local
        assertThat(clock.isDay(ms("2024-06-22T00:45:00Z"), rule)).isFalse()   // 20:45 local
    }

    @Test
    fun aSunsetAfterLocalMidnight() {
        // Reykjavik (UTC+0) in June: the sun of 21 June sets about 00:04 on 22 June and rises again about 02:54
        val clock = DayNightClock(zone = { ZoneId.of("Atlantic/Reykjavik") })
        val rule = place(64.15, -21.94)
        assertThat(clock.isDay(ms("2024-06-21T23:50:00Z"), rule)).isTrue()
        assertThat(clock.isDay(ms("2024-06-22T00:01:00Z"), rule)).isTrue()    // after midnight, the sun is still up
        assertThat(clock.isDay(ms("2024-06-22T00:30:00Z"), rule)).isFalse()
        assertThat(clock.isDay(ms("2024-06-22T03:30:00Z"), rule)).isTrue()
    }

    @Test
    fun sunriseIsDayAndSunsetIsNight() {
        val rule = place(52.37, 4.90)
        val clock = DayNightClock(zone = { amsterdam })
        val sun = SunTimes.of(LocalDate.parse("2024-12-21"), rule.latitude, rule.longitude) as SunTimes.Result.RiseAndSet
        assertThat(clock.isDay(sun.rise.toEpochMilli() - 1, rule)).isFalse()
        assertThat(clock.isDay(sun.rise.toEpochMilli(), rule)).isTrue()
        assertThat(clock.isDay(sun.set.toEpochMilli() - 1, rule)).isTrue()
        assertThat(clock.isDay(sun.set.toEpochMilli(), rule)).isFalse()
    }
}
