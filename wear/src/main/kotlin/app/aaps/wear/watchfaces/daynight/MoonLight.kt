// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * How the moon looks at a moment: how much of it is lit, where the lit side points and how the moon
 * is turned, as you see it in your sky. No phone and no internet needed, only the time and the place.
 *
 * Low precision (about a degree), which is plenty for a small picture. Pure functions only.
 * The formulas are from Meeus, Astronomical Algorithms, chapters 14 and 48.
 */
object MoonLight {

    /**
     * The moon on the screen. Angles are in degrees, counted from straight up and against the clock
     * (90 is to the left), like a picture of the sky.
     *
     * @param terminator where the line between light and dark lies: 1 at new moon (all dark), 0 at
     *   a quarter (half lit), -1 at full moon. The part that is lit is (1 - terminator) / 2.
     * @param limbAngle the direction the lit side points to.
     * @param northAngle the direction of the moon's north. Without a place this is 0: north up.
     */
    data class Look(val terminator: Double, val limbAngle: Double, val northAngle: Double) {

        /** The part of the moon that is lit, 0 to 1. */
        val litFraction: Double get() = (1 - terminator) / 2
    }

    /**
     * The moon at [epochMs]. With a [place] the picture is turned the way the moon stands in the sky
     * there (up is straight up from the ground). Without one, north is up.
     */
    fun at(epochMs: Long, place: GeoPoint?): Look {
        val moon = MoonPosition.equatorial(epochMs)
        val (sunRa, sunDec) = sun(epochMs)
        val ra = Math.toRadians(moon.rightAscension)
        val dec = Math.toRadians(moon.declination)
        val ra0 = Math.toRadians(sunRa)
        val dec0 = Math.toRadians(sunDec)
        // The angle between the sun and the moon, seen from the earth
        val elongationCos = sin(dec0) * sin(dec) + cos(dec0) * cos(dec) * cos(ra0 - ra)
        // Where the lit side points, from the north of the sky over the east (Meeus 48.5)
        val limb = Math.toDegrees(atan2(cos(dec0) * sin(ra0 - ra), sin(dec0) * cos(dec) - cos(dec0) * sin(dec) * cos(ra0 - ra)))
        // How far the north of the sky is turned away from straight up (Meeus 14.1)
        val parallactic = place?.let {
            val hourAngle = Math.toRadians(siderealDegrees(epochMs) + it.longitude - moon.rightAscension)
            val phi = Math.toRadians(it.latitude)
            Math.toDegrees(atan2(sin(hourAngle), tan(phi) * cos(dec) - sin(dec) * cos(hourAngle)))
        } ?: 0.0
        return Look(elongationCos, normalize(limb - parallactic), normalize(-parallactic))
    }

    /** The sun's right ascension and declination at [epochMs], in degrees (good to about 0.01 degree). */
    fun sun(epochMs: Long): Pair<Double, Double> {
        val n = julianDay(epochMs) - J2000
        val meanLongitude = 280.460 + 0.9856474 * n
        val anomaly = Math.toRadians(357.528 + 0.9856003 * n)
        val longitude = Math.toRadians(meanLongitude + 1.915 * sin(anomaly) + 0.020 * sin(2 * anomaly))
        val tilt = Math.toRadians(23.439 - 0.0000004 * n)
        val ra = Math.toDegrees(atan2(cos(tilt) * sin(longitude), cos(longitude)))
        val dec = Math.toDegrees(asin(sin(tilt) * sin(longitude)))
        return Pair(normalize(ra), dec)
    }

    private const val J2000 = 2451545.0

    private fun julianDay(epochMs: Long): Double = epochMs / 86_400_000.0 + 2440587.5

    /** The sidereal time at Greenwich, in degrees. */
    private fun siderealDegrees(epochMs: Long): Double = 280.46061837 + 360.98564736629 * (julianDay(epochMs) - J2000)

    private fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
}
