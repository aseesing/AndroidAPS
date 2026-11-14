// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SkyCycleTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    /** A body that rises at 00:00 and sets at 12:00, plus [shift] to make the day longer or shorter. */
    private fun body(shift: Double = 0.0): (Long) -> Double = { t -> sin(2 * PI * t / day) + shift }

    private fun ms(instant: String) = Instant.parse(instant).toEpochMilli()

    @Test
    fun aQuarterAndThreeQuartersAreTheTopAndTheBottom() {
        val start = 100 * day
        assertThat(SkyCycle.at(start + 6 * hour, body())).isWithin(0.002).of(0.25)
        assertThat(SkyCycle.at(start + 18 * hour, body())).isWithin(0.002).of(0.75)
        assertThat(SkyCycle.at(start + 3 * hour, body())).isWithin(0.002).of(0.125)
        assertThat(SkyCycle.at(start + 15 * hour, body())).isWithin(0.002).of(0.625)
    }

    @Test
    fun theTimeUpAndTheTimeDownEachGetHalfTheRound() {
        // Up for 16 hours, down for 8: just after rising it is near 0, just after setting near 0.5
        val long = body(0.5)
        val start = 100 * day
        // sin + 0.5 is 0 at -30 degrees: the body rises at 22:00 and sets at 14:00
        assertThat(SkyCycle.at(start + 22 * hour + 5 * 60_000, long)).isWithin(0.01).of(0.0)
        assertThat(SkyCycle.at(start + 14 * hour + 5 * 60_000, long)).isWithin(0.01).of(0.5)
        // Halfway the time up it is at the top, whatever the length of the day
        assertThat(SkyCycle.at(start + 6 * hour, long)).isWithin(0.005).of(0.25)
        assertThat(SkyCycle.at(start + 18 * hour, long)).isWithin(0.005).of(0.75)
    }

    @Test
    fun aBodyThatDoesNotSetIsHighAndOneThatDoesNotRiseIsLow() {
        assertThat(SkyCycle.at(0, { 1.0 })).isEqualTo(SkyCycle.ALWAYS_UP)
        assertThat(SkyCycle.at(0, { -1.0 })).isEqualTo(SkyCycle.ALWAYS_DOWN)
    }

    @Test
    fun theMoonIsWhereTheAlmanacPutsIt() {
        // Meeus, Astronomical Algorithms, example 47.a: 12 April 1992 at 0h: longitude 133.16, latitude -3.23, declination 13.77
        val moment = ms("1992-04-12T00:00:00Z")
        val moon = MoonPosition.ecliptic(moment)
        assertThat(moon.longitude).isWithin(0.4).of(133.163)
        assertThat(moon.latitude).isWithin(0.3).of(-3.229)
        assertThat(moon.parallax).isWithin(0.01).of(0.992)
        assertThat(MoonPosition.equatorial(moment).declination).isWithin(0.4).of(13.768)
        assertThat(MoonPosition.equatorial(moment).rightAscension).isWithin(0.5).of(134.688)
    }

    private val amsterdam = GeoPoint(52.37, 4.90)
    private fun clock(place: GeoPoint? = amsterdam) = DayNightClock(location = { place }, zone = { ZoneId.of("Europe/Amsterdam") })
    private fun up(value: Double) = value < 0.5
    private fun high(value: Double) = abs(value - 0.25) < 0.15

    @Test
    fun theFullMoonRisesAtSunsetAndIsUpAtMidnight() {
        // Full moon on 15 December 2024 at 09:02 UTC
        val full = ms("2024-12-15T09:02:00Z")
        assertThat(MoonPhase.at(full)).isWithin(0.02).of(0.5)
        val clock = clock()
        // Up and high at midnight, down at noon (local time is UTC + 1)
        assertThat(high(clock.moonCycle(ms("2024-12-15T23:00:00Z")))).isTrue()
        assertThat(up(clock.moonCycle(ms("2024-12-15T11:00:00Z")))).isFalse()
        // It rises within an hour and a half of sunset (15:27 UTC)
        var t = ms("2024-12-15T12:00:00Z")
        while (!up(clock.moonCycle(t))) t += 5 * 60_000
        assertThat(abs(t - ms("2024-12-15T15:27:00Z"))).isLessThan(90 * 60_000L)
    }

    @Test
    fun theNewMoonIsUpAtNoonAndDownAtMidnight() {
        // New moon on 30 December 2024 at 22:27 UTC
        val phase = MoonPhase.at(ms("2024-12-30T22:27:00Z"))
        assertThat(minOf(phase, 1 - phase)).isLessThan(0.02)
        val clock = clock()
        assertThat(up(clock.moonCycle(ms("2024-12-30T11:00:00Z")))).isTrue()
        assertThat(up(clock.moonCycle(ms("2024-12-30T23:00:00Z")))).isFalse()
    }

    @Test
    fun theFirstQuarterIsUpInTheEveningAndDownAtDawn() {
        // First quarter on 8 December 2024 at 15:27 UTC: in the sky in the evening, set before morning
        val clock = clock()
        assertThat(up(clock.moonCycle(ms("2024-12-08T17:00:00Z")))).isTrue()
        assertThat(up(clock.moonCycle(ms("2024-12-09T05:00:00Z")))).isFalse()
    }

    @Test
    fun withoutAPlaceTheMoonFollowsTheSunLateByItsPhase() {
        val clock = clock(place = null)
        // Full moon: twelve hours after the sun, so high at midnight and down at noon
        assertThat(high(clock.moonCycle(ms("2024-12-15T23:00:00Z")))).isTrue()
        assertThat(up(clock.moonCycle(ms("2024-12-15T11:00:00Z")))).isFalse()
    }

    @Test
    fun theSunGoesOverTheHorizonByDayAndUnderItAtNight() {
        val clock = clock()
        // 21 December: sunrise about 07:48 UTC, noon about 11:38 UTC, sunset about 15:29 UTC
        assertThat(clock.sunCycle(ms("2024-12-21T08:10:00Z"))).isIn(Range.open(0.0, 0.05))
        assertThat(clock.sunCycle(ms("2024-12-21T11:38:00Z"))).isWithin(0.01).of(0.25)
        assertThat(clock.sunCycle(ms("2024-12-21T15:10:00Z"))).isIn(Range.open(0.45, 0.5))
        assertThat(clock.sunCycle(ms("2024-12-21T15:50:00Z"))).isIn(Range.open(0.5, 0.55))
        // Midnight is near the bottom
        assertThat(clock.sunCycle(ms("2024-12-21T23:38:00Z"))).isWithin(0.02).of(0.75)
    }
}
