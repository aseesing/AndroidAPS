package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.DayNightRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Says whether it is day or night, by the [DayNightRule] the phone sent (see `CwfNight`):
 *
 * - [DayNightRule.FixedTimes]: from the day start to the night start, local time of the watch. When
 *   the day starts after the night starts, the day runs over midnight. When both are the same it is
 *   always day.
 * - [DayNightRule.Place]: from sunrise to sunset at that place.
 * - No rule, or a rule that makes no sense: from [FALLBACK_DAY_START] to [FALLBACK_NIGHT_START].
 *
 * @param zone the local time zone; read on every question, so a change of zone is followed
 * @param skyRule PERSONAL PATCH: the rule for the sun and the moon of the personal designs, see [SkyTimes]
 * @param location PERSONAL PATCH: in place of [skyRule], a place for the sun and the moon, as the design tests give it
 */
class DayNightClock(
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    skyRule: () -> DayNightRule? = { null }, // PERSONAL PATCH: the sun and the moon of the personal designs
    location: (() -> GeoPoint?)? = null // PERSONAL PATCH: the design tests give a place
) {

    // ---- PERSONAL PATCH: the sun and the moon of the personal designs, see SkyTimes.kt ----

    private val sky = SkyTimes(
        rule = location?.let { place -> { place()?.let { DayNightRule.Place(it.latitude, it.longitude) } } } ?: skyRule,
        zone = zone
    ) { epochMs, rule -> isDay(epochMs, rule) }

    fun sunProgress(epochMs: Long): Double = sky.sunProgress(epochMs)
    fun nightProgress(epochMs: Long): Double = sky.nightProgress(epochMs)
    fun sunCycle(epochMs: Long): Double = sky.sunCycle(epochMs)
    fun moonCycle(epochMs: Long): Double = sky.moonCycle(epochMs)
    fun nextSunEvent(epochMs: Long): Long? = sky.nextSunEvent(epochMs)

    // ---- end of PERSONAL PATCH ----

    /** The sun times per UTC day of [cachedPlace], the last few days asked for. */
    private val cachedSun = LinkedHashMap<LocalDate, SunTimes.Result>()
    private var cachedPlace: DayNightRule.Place? = null

    /** True when it is day at [epochMs] by [rule]. */
    fun isDay(epochMs: Long, rule: DayNightRule?): Boolean = when {
        rule is DayNightRule.FixedTimes && rule.isValid() -> isDayByTimes(epochMs, rule.dayStartMinute, rule.nightStartMinute)
        rule is DayNightRule.Place && rule.isValid()      -> isDayBySun(epochMs, rule)
        else                                              -> isDayByTimes(epochMs, FALLBACK_DAY_START, FALLBACK_NIGHT_START)
    }

    private fun isDayByTimes(epochMs: Long, dayStart: Int, nightStart: Int): Boolean {
        val time = Instant.ofEpochMilli(epochMs).atZone(zone()).toLocalTime()
        val minute = time.hour * 60 + time.minute
        return when {
            dayStart == nightStart -> true
            dayStart < nightStart  -> minute in dayStart until nightStart
            else                   -> minute >= dayStart || minute < nightStart
        }
    }

    /**
     * True when [epochMs] is between a sunrise and the next sunset at [place].
     *
     * The sun times of the UTC day of the instant, of the day before and of the day after are all
     * checked: [SunTimes.of] counts from midnight UTC, so the sunrise and sunset of a neighbouring
     * UTC day can be the ones around the instant. In a polar day (the sun does not set) the 24 hours
     * around that day's solar noon are day (see [POLAR_DAY_HALF]); in a polar night (the sun does not rise) it is night.
     * The local date plays no part, so a place where the local date differs from the UTC date works.
     */
    private fun isDayBySun(epochMs: Long, place: DayNightRule.Place): Boolean {
        val instant = Instant.ofEpochMilli(epochMs)
        val date = instant.atOffset(ZoneOffset.UTC).toLocalDate()
        for (day in listOf(date.minusDays(1), date, date.plusDays(1))) {
            when (val sun = sunOn(day, place)) {
                is SunTimes.Result.RiseAndSet -> if (instant >= sun.rise && instant < sun.set) return true
                is SunTimes.Result.AlwaysUp   -> if (instant >= sun.noon.minus(POLAR_DAY_HALF) && instant < sun.noon.plus(POLAR_DAY_HALF)) return true
                SunTimes.Result.AlwaysDown    -> Unit
            }
        }
        return false
    }

    /** The sun times of the UTC day [date] at [place], computed once per day and place. */
    private fun sunOn(date: LocalDate, place: DayNightRule.Place): SunTimes.Result {
        if (place != cachedPlace) {
            cachedSun.clear()
            cachedPlace = place
        }
        cachedSun[date]?.let { return it }
        return SunTimes.of(date, place.latitude, place.longitude).also {
            cachedSun[date] = it
            // Three days are asked for each time; a few more cover the change to the next day
            if (cachedSun.size > CACHED_DAYS) cachedSun.remove(cachedSun.keys.first())
        }
    }

    private fun DayNightRule.Place.isValid() = latitude in -90.0..90.0 && longitude in -180.0..180.0
    private fun DayNightRule.FixedTimes.isValid() = dayStartMinute in 0 until MINUTES_PER_DAY && nightStartMinute in 0 until MINUTES_PER_DAY

    companion object {

        /** The day without a rule: 07:00 to 19:00, in minutes after midnight. */
        const val FALLBACK_DAY_START = 7 * 60
        const val FALLBACK_NIGHT_START = 19 * 60

        /** PERSONAL PATCH: the value of [DayNightClock.nextSunEvent] for a design, see [SkyTimes.NEXT_SUN_EVENT_KEY]. */
        const val NEXT_SUN_EVENT_KEY = SkyTimes.NEXT_SUN_EVENT_KEY

        private const val MINUTES_PER_DAY = 24 * 60
        private const val CACHED_DAYS = 5
        /**
         * Half of a polar day around its solar noon. A little more than 12 hours: the solar noon moves
         * a few seconds from one day to the next, and no gap may open between two polar days.
         */
        private val POLAR_DAY_HALF: Duration = Duration.ofHours(12).plusMinutes(5)
    }
}
