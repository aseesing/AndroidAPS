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
        val local = instant.atZone(zone())
        val place = location()?.takeIf { it.isValid() }
            ?: return local.toLocalTime().let { it >= FALLBACK_DAY_START && it < FALLBACK_DAY_END }
        return when (val sun = sunOn(local.toLocalDate(), place)) {
            is SunTimes.Result.RiseAndSet -> instant >= sun.rise && instant < sun.set
            SunTimes.Result.AlwaysUp      -> true
            SunTimes.Result.AlwaysDown    -> false
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
    }
}
