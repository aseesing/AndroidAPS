package app.aaps.wear.watchfaces.daynight

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset for one day at one place, with the NOAA formulas (the simple form, good to a
 * few minutes between the polar circles). Pure functions only: no clock, no state.
 */
object SunTimes {

    /** What the sun does on one day. */
    sealed class Result {

        /** The sun rises at [rise] and sets at [set]. */
        data class RiseAndSet(val rise: Instant, val set: Instant) : Result()

        /**
         * The sun does not set on this day (summer near the poles). This day is the 24 hours around
         * its solar [noon].
         */
        data class AlwaysUp(val noon: Instant) : Result()

        /** The sun does not rise on this day (winter near the poles). */
        data object AlwaysDown : Result()
    }

    /** The angle from straight up to the sun's centre at sunrise and sunset: refraction and the sun's own size, in degrees. */
    private const val SUNRISE_ZENITH = 90.833

    private const val MINUTE_MS = 60_000L

    /**
     * Sunrise and sunset around the solar noon of the UTC day [date], at [latitude] and [longitude],
     * in degrees, north and east positive.
     *
     * The solar noon is counted from midnight UTC of [date], so it falls inside that UTC day, and
     * sunrise and sunset are at most 12 hours from it. A caller that asks about one instant must look
     * at the UTC days around it too, see [DayNightClock.isDay].
     */
    fun of(date: LocalDate, latitude: Double, longitude: Double): Result {
        // The year as an angle, at noon of the day
        val daysInYear = if (date.isLeapYear) 366 else 365
        val gamma = 2 * PI / daysInYear * (date.dayOfYear - 1 + 0.5)
        // Equation of time in minutes, and the sun's declination in radians
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val lat = Math.toRadians(latitude)
        val cosHourAngle = cos(Math.toRadians(SUNRISE_ZENITH)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        // Minutes after midnight UTC
        val solarNoon = 720 - 4 * longitude - eqTime
        val midnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        fun at(minutes: Double) = Instant.ofEpochMilli(midnight + (minutes * MINUTE_MS).toLong())
        if (cosHourAngle < -1) return Result.AlwaysUp(noon = at(solarNoon))
        if (cosHourAngle > 1) return Result.AlwaysDown
        val hourAngle = Math.toDegrees(acos(cosHourAngle))
        return Result.RiseAndSet(rise = at(solarNoon - 4 * hourAngle), set = at(solarNoon + 4 * hourAngle))
    }
}
