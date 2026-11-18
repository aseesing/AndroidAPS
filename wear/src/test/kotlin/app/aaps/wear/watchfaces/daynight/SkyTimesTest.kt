// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.DayNightRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** The sun and the moon of the personal designs, see [SkyTimes], through [DayNightClock]. */
class SkyTimesTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private fun ms(instant: String) = Instant.parse(instant).toEpochMilli()

    @Test
    fun theSunIsUpExactlyWhileItIsDay() {
        // The sun of the designs and the choice of the night face must never disagree
        val cases = listOf(
            DayNightRule.Place(52.37, 4.90) to amsterdam,
            DayNightRule.Place(-13.83, -171.76) to ZoneId.of("Pacific/Apia"),
            DayNightRule.Place(69.65, 18.96) to ZoneId.of("Europe/Oslo"),
            DayNightRule.Place(64.15, -21.94) to ZoneId.of("Atlantic/Reykjavik"),
            DayNightRule.Place(-33.87, 151.21) to ZoneId.of("Australia/Sydney"),
            DayNightRule.FixedTimes(dayStartMinute = 6 * 60 + 30, nightStartMinute = 22 * 60) to amsterdam,
            DayNightRule.FixedTimes(dayStartMinute = 20 * 60, nightStartMinute = 8 * 60) to amsterdam,
            null to amsterdam
        )
        for ((rule, zone) in cases) {
            val clock = DayNightClock(zone = { zone }, skyRule = { rule })
            var t = ms("2025-01-01T00:00:00Z")
            while (t < ms("2026-01-01T00:00:00Z")) {
                val day = clock.isDay(t, rule)
                assertWithRule(rule, t).that(clock.sunCycle(t) < 0.5).isEqualTo(day)
                if (!day) assertWithRule(rule, t).that(clock.sunProgress(t)).isAnyOf(0.0, 1.0)
                t += 97 * 60_000L // not a whole hour, so every time of day is met
            }
        }
    }

    private fun assertWithRule(rule: DayNightRule?, t: Long) = assertWithMessage("$rule at ${Instant.ofEpochMilli(t)}")

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
    fun westOfTheDateLineTheSunIsTheSunOfThatDay() {
        // Apia, Samoa (UTC+13), 22 December local: sunrise about 05:57, sunset about 18:53, so the
        // middle of the day is about 12:25 local, 23:25 UTC the day before. The personal build took the
        // sun times of the wrong day there, and showed the night all day.
        val clock = DayNightClock(location = { GeoPoint(-13.83, -171.76) }, zone = { ZoneId.of("Pacific/Apia") })
        assertThat(clock.sunCycle(ms("2024-12-21T23:00:00Z"))).isLessThan(0.5)
        assertThat(clock.sunProgress(ms("2024-12-21T23:25:00Z"))).isWithin(0.03).of(0.5)
        assertThat(abs(clock.nextSunEvent(ms("2024-12-21T23:00:00Z"))!! - ms("2024-12-22T05:53:00Z"))).isLessThan(10 * 60_000L)
        assertThat(clock.sunProgress(ms("2024-12-22T08:00:00Z"))).isEqualTo(1.0)     // 21:00 local
    }

    @Test
    fun aSunsetAfterLocalMidnightIsStillTheSameDay() {
        // Reykjavik in June (UTC, no summer time): the sun sets a few minutes after midnight
        val clock = DayNightClock(location = { GeoPoint(64.15, -21.94) }, zone = { ZoneId.of("Atlantic/Reykjavik") })
        val sunset = clock.nextSunEvent(ms("2025-06-21T20:00:00Z"))!!
        assertThat(Instant.ofEpochMilli(sunset)).isGreaterThan(Instant.parse("2025-06-21T23:45:00Z"))
        // Just before that sunset, already on the next local day, it is still day, near its end
        assertThat(clock.sunCycle(sunset - 60_000L)).isLessThan(0.5)
        assertThat(clock.sunProgress(sunset - 60_000L)).isGreaterThan(0.99)
        assertThat(clock.nightProgress(sunset + 60_000L)).isLessThan(0.01)
    }

    @Test
    fun theNextSunEventIsTheNextSunriseOrSunset() {
        val clock = DayNightClock(location = { GeoPoint(52.37, 4.90) }, zone = { amsterdam })
        fun minutesTo(from: String, expected: String) = (clock.nextSunEvent(ms(from))!! - ms(expected)) / 60_000.0
        // 21 December: sunrise about 07:48 UTC, sunset about 15:29 UTC
        assertThat(minutesTo("2024-12-21T03:00:00Z", "2024-12-21T07:48:00Z")).isWithin(5.0).of(0.0)
        assertThat(minutesTo("2024-12-21T12:00:00Z", "2024-12-21T15:29:00Z")).isWithin(5.0).of(0.0)
        // After sunset: the sunrise of the next day
        assertThat(minutesTo("2024-12-21T20:00:00Z", "2024-12-22T07:48:00Z")).isWithin(5.0).of(0.0)
    }

    @Test
    fun withoutAPlaceWithFixedTimesOrInAPolarNightThereIsNoSunEvent() {
        assertThat(DayNightClock(location = { null }, zone = { amsterdam }).nextSunEvent(ms("2024-12-21T12:00:00Z"))).isNull()
        val fixed = DayNightClock(zone = { amsterdam }, skyRule = { DayNightRule.FixedTimes(7 * 60, 19 * 60) })
        assertThat(fixed.nextSunEvent(ms("2024-12-21T12:00:00Z"))).isNull()
        // Tromsø in December: the sun does not rise
        val tromso = DayNightClock(location = { GeoPoint(69.65, 18.96) }, zone = { ZoneId.of("Europe/Oslo") })
        assertThat(tromso.nextSunEvent(ms("2024-12-21T12:00:00Z"))).isNull()
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
    fun theFixedTimesOfTheRuleAreTheDayOfTheDesigns() {
        val rule = DayNightRule.FixedTimes(dayStartMinute = 6 * 60, nightStartMinute = 22 * 60)
        val clock = DayNightClock(zone = { amsterdam }, skyRule = { rule })
        // 06:00 to 22:00 local is 05:00 to 21:00 UTC in winter; 14:00 local is the middle
        assertThat(clock.sunProgress(ms("2024-12-21T13:00:00Z"))).isWithin(1e-9).of(0.5)
        // The night from 22:00 to 06:00 local: 02:00 local is the middle
        assertThat(clock.nightProgress(ms("2024-12-22T01:00:00Z"))).isWithin(1e-9).of(0.5)
        assertThat(clock.sunCycle(ms("2024-12-22T01:00:00Z"))).isWithin(1e-9).of(0.75)
    }

    @Test
    fun aDayOverMidnight() {
        val rule = DayNightRule.FixedTimes(dayStartMinute = 20 * 60, nightStartMinute = 8 * 60)
        val clock = DayNightClock(zone = { ZoneId.of("UTC") }, skyRule = { rule })
        assertThat(clock.sunProgress(ms("2024-12-22T02:00:00Z"))).isWithin(1e-9).of(0.5)
        assertThat(clock.nightProgress(ms("2024-12-22T14:00:00Z"))).isWithin(1e-9).of(0.5)
    }

    @Test
    fun sunProgressInPolarSummerAndWinter() {
        val clock = DayNightClock(location = { GeoPoint(69.65, 18.96) }, zone = { ZoneId.of("Europe/Oslo") })
        // The sun does not set: the 24 hours around the solar noon (about 10:46 UTC) are the day
        assertThat(clock.sunProgress(ms("2025-06-21T10:46:00Z"))).isWithin(0.01).of(0.5)
        // The sun does not rise
        assertThat(clock.sunProgress(ms("2025-12-21T11:00:00Z"))).isEqualTo(0.0)
        assertThat(clock.nightProgress(ms("2025-12-21T11:00:00Z"))).isWithin(0.01).of(0.5)
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

    @Test
    fun theMoonUsesThePlaceOfTheRuleOnly() {
        assertThat(placeOf(DayNightRule.Place(52.37, 4.90))).isEqualTo(GeoPoint(52.37, 4.90))
        assertThat(placeOf(DayNightRule.FixedTimes(7 * 60, 19 * 60))).isNull()
        assertThat(placeOf(null)).isNull()
        assertThat(placeOf(DayNightRule.Place(Double.NaN, 4.9))).isNull()
        // A place never goes into a log
        assertThat(GeoPoint(52.37, 4.90).toString()).doesNotContain("52")
    }
}
