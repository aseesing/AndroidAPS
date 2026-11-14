// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A place on earth, in degrees: north and east positive. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Says whether it is day or night: from sunrise to sunset it is day. The sun times come from
 * [location]; without a location the day runs from [FALLBACK_DAY_START] to [FALLBACK_DAY_END] local time.
 *
 * @param location the place to use, or null when it is not known; read on every question
 * @param zone the local time zone; read on every question, so a change of zone is followed
 */
class DayNightClock(
    private val location: () -> GeoPoint?,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    private data class Key(val date: LocalDate, val place: GeoPoint)

    private var cachedKey: Key? = null
    private var cachedSun: SunTimes.Result? = null

    /** True when [epochMs] is between sunrise and sunset. */
    fun isDay(epochMs: Long): Boolean {
        val instant = Instant.ofEpochMilli(epochMs)
        val day = daylightAround(instant) ?: return false
        return instant >= day.start && instant < day.end
    }

    /**
     * How far the sun is through its day at [epochMs]: 0 at sunrise, 0.5 around noon, 1 at sunset.
     * Before sunrise it is 0 and after sunset 1. When the sun does not rise at all on that day: 0.
     */
    fun sunProgress(epochMs: Long): Double {
        val instant = Instant.ofEpochMilli(epochMs)
        val day = daylightAround(instant) ?: return 0.0
        val length = day.end.toEpochMilli() - day.start.toEpochMilli()
        if (length <= 0) return 0.0
        return ((epochMs - day.start.toEpochMilli()).toDouble() / length).coerceIn(0.0, 1.0)
    }

    /**
     * How far the night is at [epochMs]: 0 at sunset, 0.5 in the middle of the night, 1 at the next
     * sunrise. In the day it is 0. When the sun does not rise at all on that day, the night is the
     * whole local day.
     */
    fun nightProgress(epochMs: Long): Double {
        val instant = Instant.ofEpochMilli(epochMs)
        val date = instant.atZone(zone()).toLocalDate()
        val today = daylightOn(date)
        if (today != null && instant >= today.start && instant < today.end) return 0.0
        val (start, end) = if (today != null && instant >= today.end)
            today.end to (daylightOn(date.plusDays(1))?.start ?: date.plusDays(2).atStartOfDay(zone()).toInstant())
        else
            (daylightOn(date.minusDays(1))?.end ?: date.atStartOfDay(zone()).toInstant()) to (today?.start ?: date.plusDays(1).atStartOfDay(zone()).toInstant())
        val length = end.toEpochMilli() - start.toEpochMilli()
        if (length <= 0) return 0.0
        return ((epochMs - start.toEpochMilli()).toDouble() / length).coerceIn(0.0, 1.0)
    }

    /**
     * Where the sun is in its round at [epochMs] (see [SkyCycle]): 0 at sunrise, 0.5 at sunset, 1 at
     * the next sunrise. The day is the first half, the night the second, so a picture of the sun can go
     * over the horizon by day and under it at night.
     */
    fun sunCycle(epochMs: Long): Double =
        if (isDay(epochMs)) sunProgress(epochMs) / 2 else 0.5 + nightProgress(epochMs) / 2

    private var cachedMoonMinute = Long.MIN_VALUE
    private var cachedMoonPlace: GeoPoint? = null
    private var cachedMoon = 0.0

    /**
     * Where the moon is in its round at [epochMs] (see [SkyCycle]): 0 at moonrise, 0.5 at moonset, 1
     * at the next moonrise. Worked out for the stored place, once per minute. The real moon rises at
     * any hour: a full moon around sunset, a new moon around sunrise.
     *
     * Without a place the moon follows the sun, late by its phase: a day of 24 hours for a whole cycle
     * of the moon. That is close to the real moon at a place near the equator.
     */
    fun moonCycle(epochMs: Long): Double {
        val place = location()?.takeIf { it.isValid() }
            ?: return sunCycle(epochMs - (MoonPhase.at(epochMs) * DAY_MS).toLong())
        val minute = epochMs / MINUTE_MS
        if (minute == cachedMoonMinute && place == cachedMoonPlace) return cachedMoon
        return SkyCycle.at(epochMs) { MoonPosition.heightAboveRise(it, place.latitude, place.longitude) }.also {
            cachedMoonMinute = minute
            cachedMoonPlace = place
            cachedMoon = it
        }
    }

    /** From when to when the sun is up. */
    private data class Daylight(val start: Instant, val end: Instant)

    private fun daylightAround(instant: Instant): Daylight? = daylightOn(instant.atZone(zone()).toLocalDate())

    /**
     * The daylight of the local day [date]: from sunrise to sunset at the stored place, the whole
     * day when the sun does not set, null when it does not rise. Without a place: the fixed hours.
     */
    private fun daylightOn(date: LocalDate): Daylight? {
        val zone = zone()
        val place = location()?.takeIf { it.isValid() }
            ?: return Daylight(date.atTime(FALLBACK_DAY_START).atZone(zone).toInstant(), date.atTime(FALLBACK_DAY_END).atZone(zone).toInstant())
        return when (val sun = sunOn(date, place)) {
            is SunTimes.Result.RiseAndSet -> Daylight(sun.rise, sun.set)
            SunTimes.Result.AlwaysUp      -> Daylight(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
            SunTimes.Result.AlwaysDown    -> null
        }
    }

    /** The sun times of [date] at [place], computed once per day and place. */
    private fun sunOn(date: LocalDate, place: GeoPoint): SunTimes.Result {
        val key = Key(date, place)
        cachedSun?.let { if (key == cachedKey) return it }
        return SunTimes.of(date, place.latitude, place.longitude).also {
            cachedKey = key
            cachedSun = it
        }
    }

    private fun GeoPoint.isValid() = latitude in -90.0..90.0 && longitude in -180.0..180.0

    companion object {

        val FALLBACK_DAY_START: LocalTime = LocalTime.of(7, 0)
        val FALLBACK_DAY_END: LocalTime = LocalTime.of(19, 0)

        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24 * 60 * MINUTE_MS
    }
}
