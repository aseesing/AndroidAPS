// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.shared.impl.weardata.CwfPlaceNeeds
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A place on earth, in degrees: north and east positive. */
data class GeoPoint(val latitude: Double, val longitude: Double) {

    /** Without the coordinates, like [DayNightRule.Place]: a place never goes into a log. */
    override fun toString() = "GeoPoint"
}

/** The place of [rule] for the sun and the moon, or null when the rule has none or it makes no sense. */
fun placeOf(rule: DayNightRule?): GeoPoint? =
    (rule as? DayNightRule.Place)?.takeIf { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }?.let { GeoPoint(it.latitude, it.longitude) }

/**
 * The sun and the moon of the personal designs (`sun_progress`, `night_progress`, `sun_cycle`,
 * `moon_cycle`, the next sunrise or sunset), from the same [DayNightRule] that says when it is day
 * (see [DayNightClock.isDay]). So the sun is up in a design exactly while it is day.
 *
 * - [DayNightRule.Place]: the real sun of that place, from [SunTimes] (the reviewed sums, which also
 *   work near the date line and when the sun sets after local midnight), and the real moon.
 * - [DayNightRule.FixedTimes]: the day is from the day start to the night start. There is no real
 *   sunrise or sunset ([nextSunEvent] is null), and the moon follows the sun, late by its phase.
 * - No rule, or a rule that makes no sense: like fixed times from 07:00 to 19:00.
 *
 * @param rule the rule now; read on every question
 * @param zone the local time zone; read on every question
 * @param isDay the answer of [DayNightClock.isDay] for a moment and a rule
 */
internal class SkyTimes(
    private val rule: () -> DayNightRule?,
    private val zone: () -> ZoneId,
    private val isDay: (Long, DayNightRule?) -> Boolean
) {

    /** From when to when the sun is up (or, with fixed times, when it is day). */
    private data class Span(val start: Long, val end: Long) {

        operator fun contains(epochMs: Long) = epochMs in start until end
    }

    /** What is known about the days around one moment, kept for that day and rule. */
    private class Days(val rule: DayNightRule?, val day: LocalDate, val spans: List<Span>, val sunEvents: List<Long>)

    private var cachedDays: Days? = null

    /**
     * How far the sun is through its day at [epochMs]: 0 at sunrise, 0.5 around noon, 1 at sunset.
     * At night it is 1 after the sunset of this local day, else 0 (before sunrise, or when the sun
     * does not rise).
     */
    fun sunProgress(epochMs: Long): Double {
        val rule = rule()
        val days = daysAround(epochMs, rule)
        if (isDay(epochMs, rule)) days.spans.firstOrNull { epochMs in it }?.let { return part(epochMs, it.start, it.end) }
        val lastEnd = days.spans.filter { it.end <= epochMs }.maxOfOrNull { it.end } ?: return 0.0
        return if (localDate(lastEnd) == localDate(epochMs)) 1.0 else 0.0
    }

    /**
     * How far the night is at [epochMs]: 0 at sunset, 0.5 in the middle of the night, 1 at the next
     * sunrise. In the day it is 0. In a polar night the night is the whole local day.
     */
    fun nightProgress(epochMs: Long): Double {
        val rule = rule()
        if (isDay(epochMs, rule)) return 0.0
        val days = daysAround(epochMs, rule)
        val date = localDate(epochMs)
        val start = days.spans.filter { it.end <= epochMs }.maxOfOrNull { it.end } ?: date.atStartOfDay(zone()).toInstant().toEpochMilli()
        val end = days.spans.filter { it.start > epochMs }.minOfOrNull { it.start } ?: date.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        return part(epochMs, start, end)
    }

    /**
     * Where the sun is in its round at [epochMs] (see [SkyCycle]): 0 at sunrise, 0.5 at sunset, 1 at
     * the next sunrise. The day is the first half, the night the second.
     */
    fun sunCycle(epochMs: Long): Double =
        if (isDay(epochMs, rule())) sunProgress(epochMs) / 2 else 0.5 + nightProgress(epochMs) / 2

    private var cachedMoonMinute = Long.MIN_VALUE
    private var cachedMoonPlace: GeoPoint? = null
    private var cachedMoon = 0.0

    /**
     * Where the moon is in its round at [epochMs] (see [SkyCycle]): 0 at moonrise, 0.5 at moonset, 1
     * at the next moonrise. Worked out for the place of the rule, once per minute.
     *
     * Without a place the moon follows the sun, late by its phase: a day of 24 hours for a whole cycle
     * of the moon. That is close to the real moon at a place near the equator.
     */
    fun moonCycle(epochMs: Long): Double {
        val place = placeOf(rule()) ?: return sunCycle(epochMs - (MoonPhase.at(epochMs) * DAY_MS).toLong())
        val minute = epochMs / MINUTE_MS
        if (minute == cachedMoonMinute && place == cachedMoonPlace) return cachedMoon
        return SkyCycle.at(epochMs) { MoonPosition.heightAboveRise(it, place.latitude, place.longitude) }.also {
            cachedMoonMinute = minute
            cachedMoonPlace = place
            cachedMoon = it
        }
    }

    /**
     * The time of the next sunrise or sunset after [epochMs] at the place of the rule. Null without a
     * place (fixed times are no real sun times), and in a polar day or night when the sun does not rise
     * or set in the next days.
     */
    fun nextSunEvent(epochMs: Long): Long? = daysAround(epochMs, rule()).sunEvents.firstOrNull { it > epochMs }

    /** The spans of the days around [epochMs] by [rule], made once per day and rule. */
    private fun daysAround(epochMs: Long, rule: DayNightRule?): Days {
        val place = placeOf(rule)
        val day = if (place != null) Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toLocalDate() else localDate(epochMs)
        cachedDays?.let { if (it.rule == rule && it.day == day) return it }
        val around = (-DAYS_AROUND..DAYS_AROUND).map { day.plusDays(it) }
        val days = if (place != null) sunDays(rule, day, around, place) else fixedDays(rule, day, around)
        cachedDays = days
        return days
    }

    /** The real sun: the UTC days around, as [DayNightClock.isDay] looks at them. */
    private fun sunDays(rule: DayNightRule?, day: LocalDate, around: List<LocalDate>, place: GeoPoint): Days {
        val spans = ArrayList<Span>()
        val events = ArrayList<Long>()
        for (date in around) {
            when (val sun = SunTimes.of(date, place.latitude, place.longitude)) {
                is SunTimes.Result.RiseAndSet -> {
                    spans += Span(sun.rise.toEpochMilli(), sun.set.toEpochMilli())
                    events += sun.rise.toEpochMilli()
                    events += sun.set.toEpochMilli()
                }
                // The same 24 hours around the solar noon that DayNightClock counts as day
                is SunTimes.Result.AlwaysUp   -> spans += Span(sun.noon.minus(POLAR_DAY_HALF).toEpochMilli(), sun.noon.plus(POLAR_DAY_HALF).toEpochMilli())
                SunTimes.Result.AlwaysDown    -> Unit
            }
        }
        return Days(rule, day, spans, events.sorted())
    }

    /** Fixed times, also the 07:00 to 19:00 without a rule: the local days around. */
    private fun fixedDays(rule: DayNightRule?, day: LocalDate, around: List<LocalDate>): Days {
        val times = (rule as? DayNightRule.FixedTimes)?.takeIf { it.dayStartMinute in 0 until MINUTES_PER_DAY && it.nightStartMinute in 0 until MINUTES_PER_DAY }
        val dayStart = times?.dayStartMinute ?: DayNightClock.FALLBACK_DAY_START
        val nightStart = times?.nightStartMinute ?: DayNightClock.FALLBACK_NIGHT_START
        val zone = zone()
        fun at(date: LocalDate, minute: Int) = date.atStartOfDay(zone).toLocalDateTime().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()
        val spans = around.map { date ->
            when {
                // Always day: the whole local day
                dayStart == nightStart -> Span(at(date, 0), at(date.plusDays(1), 0))
                dayStart < nightStart  -> Span(at(date, dayStart), at(date, nightStart))
                // The day runs over midnight
                else                   -> Span(at(date, dayStart), at(date.plusDays(1), nightStart))
            }
        }
        return Days(rule, day, spans, emptyList())
    }

    private fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone()).toLocalDate()

    private fun part(epochMs: Long, start: Long, end: Long): Double =
        if (end <= start) 0.0 else ((epochMs - start).toDouble() / (end - start)).coerceIn(0.0, 1.0)

    companion object {

        /** The value of [nextSunEvent] for a design (`dynData`): a time, shown with `@time` (see `DesignTimeText`). */
        const val NEXT_SUN_EVENT_KEY = CwfPlaceNeeds.SUN_NEXT_TIME

        /** A time later than any [nextSunEvent], in epoch ms (1 January 2100): the end of its value range. */
        const val LAST_SUN_EVENT_MS = 4_102_444_800_000.0

        private const val DAYS_AROUND = 2L
        private const val MINUTES_PER_DAY = 24 * 60
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24 * 60 * MINUTE_MS

        /** As in `DayNightClock`: half of a polar day around its solar noon. */
        private val POLAR_DAY_HALF: Duration = Duration.ofHours(12).plusMinutes(5)
    }
}
