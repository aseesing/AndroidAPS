package app.aaps.wear.watchfaces.daynight

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class MoonLightTest {

    private fun at(time: String) = Instant.parse(time).toEpochMilli()

    private val hoofddorp = GeoPoint(52.30, 4.69)
    private val sydney = GeoPoint(-33.87, 151.21)

    /** Meeus, Astronomical Algorithms, example 48.a: 12 April 1992 at 0h. */
    @Test
    fun theBookExampleIsRight() {
        val look = MoonLight.at(at("1992-04-12T00:00:00Z"), null)
        assertThat(look.litFraction).isWithin(0.005).of(0.6786)
        assertThat(look.limbAngle).isWithin(1.0).of(285.0)
        assertThat(look.northAngle).isEqualTo(0.0)
    }

    /** timeanddate.com for Hoofddorp: 43.1 % lit at 19:55 on 16 November 2026, the moon low in the south west. */
    @Test
    fun theLitPartIsWhatTheSkyShows() {
        val look = MoonLight.at(at("2026-11-16T18:55:18Z"), hoofddorp)
        assertThat(look.litFraction).isWithin(0.01).of(0.431)
        // The sun has set in the west: the lit side points down to the right
        assertThat(look.limbAngle).isIn(Range.open(200.0, 260.0))
    }

    @Test
    fun southOfTheEquatorTheEveningMoonIsLitOnTheLeft() {
        // 20:00 in Sydney the same evening: the sun sets in the west, which is on the left when you face the moon in the north
        val look = MoonLight.at(at("2026-11-16T09:00:00Z"), sydney)
        assertThat(look.limbAngle).isIn(Range.open(90.0, 160.0))
        // and the moon's north points down, more or less
        assertThat(look.northAngle).isIn(Range.open(180.0, 270.0))
    }

    @Test
    fun highInTheSouthNorthIsUp() {
        // The moon passes the south in Hoofddorp at 18:12
        val look = MoonLight.at(at("2026-11-16T17:12:00Z"), hoofddorp)
        val northAngle = if (look.northAngle > 180) look.northAngle - 360 else look.northAngle
        assertThat(northAngle).isWithin(2.0).of(0.0)
    }

    @Test
    fun newAndFullMoon() {
        // 9 November 2026 is new moon, 24 November full moon (timeanddate.com)
        assertThat(MoonLight.at(at("2026-11-09T07:00:00Z"), null).litFraction).isLessThan(0.01)
        assertThat(MoonLight.at(at("2026-11-24T14:53:00Z"), null).litFraction).isGreaterThan(0.99)
    }

    @Test
    fun theSunIsWhereTheBookSaysItIs() {
        // Meeus example 25.a: 13 October 1992 at 0h, right ascension 198.38, declination -7.785
        val (ra, dec) = MoonLight.sun(at("1992-10-13T00:00:00Z"))
        assertThat(ra).isWithin(0.05).of(198.38)
        assertThat(dec).isWithin(0.05).of(-7.785)
    }
}
