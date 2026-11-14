// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the moon is in the sky, from the time and the place alone: no phone and no internet needed.
 *
 * The low precision formulas of the Astronomical Almanac (good to about 0.3 degree between 1900
 * and 2100). That is a few minutes for moonrise and moonset, which is fine for a picture of the sky.
 * Pure functions only: no clock, no state.
 */
object MoonPosition {

    /** The moon's place on the sky: [longitude] and [latitude] on the ecliptic, and its [parallax], all in degrees. */
    data class Ecliptic(val longitude: Double, val latitude: Double, val parallax: Double)

    /** The moon's place on the sky: right ascension and declination, in degrees. */
    data class Equatorial(val rightAscension: Double, val declination: Double)

    /** The moon at [epochMs], seen from the middle of the earth. */
    fun ecliptic(epochMs: Long): Ecliptic {
        val t = (julianDay(epochMs) - J2000) / 36525.0
        fun s(a: Double, b: Double) = sin(Math.toRadians(a + b * t))
        fun c(a: Double, b: Double) = cos(Math.toRadians(a + b * t))
        val longitude = 218.32 + 481267.881 * t +
            6.29 * s(135.0, 477198.87) - 1.27 * s(259.3, -413335.36) + 0.66 * s(235.7, 890534.22) +
            0.21 * s(269.9, 954397.74) - 0.19 * s(357.5, 35999.05) - 0.11 * s(186.5, 966404.03)
        val latitude = 5.13 * s(93.3, 483202.02) + 0.28 * s(228.0, 960400.89) - 0.28 * s(318.3, 6003.15) - 0.17 * s(217.6, -407332.21)
        val parallax = 0.9508 + 0.0518 * c(135.0, 477198.87) + 0.0095 * c(259.3, -413335.36) + 0.0078 * c(235.7, 890534.22) + 0.0028 * c(269.9, 954397.74)
        return Ecliptic(normalize(longitude), latitude, parallax)
    }

    /** The moon at [epochMs] in right ascension and declination. */
    fun equatorial(epochMs: Long): Equatorial {
        val moon = ecliptic(epochMs)
        val lambda = Math.toRadians(moon.longitude)
        val beta = Math.toRadians(moon.latitude)
        // The tilt of the earth's axis, 23.44 degrees: its cosine and sine
        val l = cos(beta) * cos(lambda)
        val m = 0.9175 * cos(beta) * sin(lambda) - 0.3978 * sin(beta)
        val n = 0.3978 * cos(beta) * sin(lambda) + 0.9175 * sin(beta)
        return Equatorial(normalize(Math.toDegrees(atan2(m, l))), Math.toDegrees(asin(n)))
    }

    /**
     * How high the moon is above the height where it rises and sets, in degrees, at [epochMs] seen
     * from [latitude] and [longitude] (degrees, north and east positive). Above 0 the moon is up.
     *
     * The height of rising (Meeus: 0.7275 x parallax - 0.5667) takes the bending of the light by the
     * air, the size of the moon and the moon's parallax into account.
     */
    fun heightAboveRise(epochMs: Long, latitude: Double, longitude: Double): Double {
        val moon = equatorial(epochMs)
        val parallax = ecliptic(epochMs).parallax
        val siderealTime = 280.46061837 + 360.98564736629 * (julianDay(epochMs) - J2000) + longitude
        val hourAngle = Math.toRadians(siderealTime - moon.rightAscension)
        val phi = Math.toRadians(latitude)
        val delta = Math.toRadians(moon.declination)
        val altitude = Math.toDegrees(asin(sin(phi) * sin(delta) + cos(phi) * cos(delta) * cos(hourAngle)))
        return altitude - (0.7275 * parallax - 0.5667)
    }

    private const val J2000 = 2451545.0

    private fun julianDay(epochMs: Long): Double = epochMs / 86_400_000.0 + 2440587.5

    private fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
}
