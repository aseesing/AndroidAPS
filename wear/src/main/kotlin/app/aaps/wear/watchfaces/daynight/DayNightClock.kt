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

    /**
     * The sun times of the last few local days at [cachedPlace], newest use last. A question asks for
     * up to three days (yesterday, today, tomorrow; the next sun event also the day after), so one kept
     * day would be worked out again on every question.
     */
    private val cachedSun = object : LinkedHashMap<LocalDate, SunTimes.Result>(KEPT_DAYS * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, SunTimes.Result>?): Boolean = size > KEPT_DAYS
    }
    private var cachedPlace: GeoPoint? = null

    /** True when [epochMs] is between sunrise and sunset. */
    fun isDay(epochMs: Long): Boolean = isDay(epochMs, place(), zone())

    /**
     * How far the sun is through its day at [epochMs]: 0 at sunrise, 0.5 around noon, 1 at sunset.
     * Before sunrise it is 0 and after sunset 1. When the sun does not rise at all on that day: 0.
     */
    fun sunProgress(epochMs: Long): Double = sunProgress(epochMs, place(), zone())

    /**
     * How far the night is at [epochMs]: 0 at sunset, 0.5 in the middle of the night, 1 at the next
     * sunrise. In the day it is 0. When the sun does not rise at all on that day, the night is the
     * whole local day.
     */
    fun nightProgress(epochMs: Long): Double = nightProgress(epochMs, place(), zone())

    /**
     * Where the sun is in its round at [epochMs] (see [SkyCycle]): 0 at sunrise, 0.5 at sunset, 1 at
     * the next sunrise. The day is the first half, the night the second, so a picture of the sun can go
     * over the horizon by day and under it at night.
     */
    fun sunCycle(epochMs: Long): Double = sunCycle(epochMs, place(), zone())

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
        val place = place() ?: return sunCycle(epochMs - (MoonPhase.at(epochMs) * DAY_MS).toLong(), null, zone())
        val minute = epochMs / MINUTE_MS
        if (minute == cachedMoonMinute && place == cachedMoonPlace) return cachedMoon
        return SkyCycle.at(epochMs) { MoonPosition.heightAboveRise(it, place.latitude, place.longitude) }.also {
            cachedMoonMinute = minute
            cachedMoonPlace = place
            cachedMoon = it
        }
    }

    /**
     * The time of the next sunrise or sunset after [epochMs] at the stored place, whichever comes
     * first. Null without a place (the fixed hours are no real sun times), and in a polar day or night
     * when the sun does not rise or set in the next days.
     */
    fun nextSunEvent(epochMs: Long): Long? {
        val place = place() ?: return null
        val instant = Instant.ofEpochMilli(epochMs)
        val today = instant.atZone(zone()).toLocalDate()
        for (days in 0L..2L) {
            val sun = sunOn(today.plusDays(days), place) as? SunTimes.Result.RiseAndSet ?: continue
            listOf(sun.rise, sun.set).sorted().firstOrNull { it > instant }?.let { return it.toEpochMilli() }
        }
        return null
    }

    // Each question reads the place and the zone once and hands them on: the place is two stored
    // numbers, and the zone is looked up again on every read.

    /** The stored place, or null when there is none or it is not a place on earth. */
    private fun place(): GeoPoint? = location()?.takeIf { it.isValid() }

    private fun isDay(epochMs: Long, place: GeoPoint?, zone: ZoneId): Boolean {
        val instant = Instant.ofEpochMilli(epochMs)
        val day = daylightOn(instant.atZone(zone).toLocalDate(), place, zone) ?: return false
        return instant >= day.start && instant < day.end
    }

    private fun sunProgress(epochMs: Long, place: GeoPoint?, zone: ZoneId): Double {
        val instant = Instant.ofEpochMilli(epochMs)
        val day = daylightOn(instant.atZone(zone).toLocalDate(), place, zone) ?: return 0.0
        val length = day.end.toEpochMilli() - day.start.toEpochMilli()
        if (length <= 0) return 0.0
        return ((epochMs - day.start.toEpochMilli()).toDouble() / length).coerceIn(0.0, 1.0)
    }

    private fun nightProgress(epochMs: Long, place: GeoPoint?, zone: ZoneId): Double {
        val instant = Instant.ofEpochMilli(epochMs)
        val date = instant.atZone(zone).toLocalDate()
        val today = daylightOn(date, place, zone)
        if (today != null && instant >= today.start && instant < today.end) return 0.0
        val (start, end) = if (today != null && instant >= today.end)
            today.end to (daylightOn(date.plusDays(1), place, zone)?.start ?: date.plusDays(2).atStartOfDay(zone).toInstant())
        else
            (daylightOn(date.minusDays(1), place, zone)?.end ?: date.atStartOfDay(zone).toInstant()) to (today?.start ?: date.plusDays(1).atStartOfDay(zone).toInstant())
        val length = end.toEpochMilli() - start.toEpochMilli()
        if (length <= 0) return 0.0
        return ((epochMs - start.toEpochMilli()).toDouble() / length).coerceIn(0.0, 1.0)
    }

    private fun sunCycle(epochMs: Long, place: GeoPoint?, zone: ZoneId): Double =
        if (isDay(epochMs, place, zone)) sunProgress(epochMs, place, zone) / 2 else 0.5 + nightProgress(epochMs, place, zone) / 2

    /** From when to when the sun is up. */
    private data class Daylight(val start: Instant, val end: Instant)

    /**
     * The daylight of the local day [date]: from sunrise to sunset at [place], the whole day when the
     * sun does not set, null when it does not rise. Without a place: the fixed hours.
     */
    private fun daylightOn(date: LocalDate, place: GeoPoint?, zone: ZoneId): Daylight? {
        place ?: return Daylight(date.atTime(FALLBACK_DAY_START).atZone(zone).toInstant(), date.atTime(FALLBACK_DAY_END).atZone(zone).toInstant())
        return when (val sun = sunOn(date, place)) {
            is SunTimes.Result.RiseAndSet -> Daylight(sun.rise, sun.set)
            SunTimes.Result.AlwaysUp      -> Daylight(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
            SunTimes.Result.AlwaysDown    -> null
        }
    }

    /** The sun times of [date] at [place], computed once per day and place. */
    private fun sunOn(date: LocalDate, place: GeoPoint): SunTimes.Result {
        if (place != cachedPlace) {
            cachedSun.clear()
            cachedPlace = place
        }
        return cachedSun.getOrPut(date) { SunTimes.of(date, place.latitude, place.longitude) }
    }

    private fun GeoPoint.isValid() = latitude in -90.0..90.0 && longitude in -180.0..180.0

    companion object {

        /** The value of [nextSunEvent] for a design (`dynData`): a time, shown with `@time` (see `DesignTimeText`). */
        const val NEXT_SUN_EVENT_KEY = "sun_next_time"

        /** A time later than any [nextSunEvent], in epoch ms (1 January 2100): the end of its value range. */
        const val LAST_SUN_EVENT_MS = 4_102_444_800_000.0

        val FALLBACK_DAY_START: LocalTime = LocalTime.of(7, 0)
        val FALLBACK_DAY_END: LocalTime = LocalTime.of(19, 0)

        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24 * 60 * MINUTE_MS

        /** The days of sun times kept: yesterday up to the day after tomorrow. */
        private const val KEPT_DAYS = 4
    }
}
