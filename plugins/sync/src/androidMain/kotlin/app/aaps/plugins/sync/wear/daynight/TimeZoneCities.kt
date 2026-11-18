package app.aaps.plugins.sync.wear.daynight

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The main city of one time zone in one country.
 *
 * @param zoneId the time zone id, for example "Europe/Amsterdam"; the name shown comes from the
 *   platform, in the user's language (see [DayNightPlaceNames])
 * @param countryCode ISO 3166 country code, for example "NL"
 */
data class TimeZoneCity(
    val zoneId: String,
    val countryCode: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * The cities a user can choose for day and night on the watch: the main city of every time zone,
 * from [TIME_ZONE_TABLE]. It ships with the app, so choosing a city needs no network, no geocoder
 * and no permission.
 */
@SingleIn(AppScope::class)
@Inject
class TimeZoneCities() {

    /** All cities, sorted by zone id. */
    val all: List<TimeZoneCity> by lazy { parse(TIME_ZONE_TABLE) }

    private val index: Map<String, TimeZoneCity> by lazy { all.associateBy { it.zoneId } }

    /** The city of [zoneId], or null when the table has no such zone. */
    fun byZoneId(zoneId: String): TimeZoneCity? = index[zoneId]

    /** The cities of the country [countryCode]. */
    fun inCountry(countryCode: String): List<TimeZoneCity> = all.filter { it.countryCode == countryCode }

    /** The country codes that have at least one city. */
    fun countryCodes(): Set<String> = all.mapTo(mutableSetOf()) { it.countryCode }

    /**
     * The first of [zoneIds] that is in the table, or null when none is. Give a zone id and the
     * other ids of the same zone (old names like "Asia/Calcutta" for "Asia/Kolkata"), so the
     * phone's zone is found under any of its names.
     */
    fun firstKnown(zoneIds: List<String>): TimeZoneCity? = zoneIds.firstNotNullOfOrNull { byZoneId(it) }

    companion object {

        /** The rows of [table]: "zone country latitude longitude", one per line. Empty lines are skipped. */
        fun parse(table: String): List<TimeZoneCity> =
            table.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                val parts = line.split(' ')
                require(parts.size == 4) { "Bad time zone row: $line" }
                TimeZoneCity(zoneId = parts[0], countryCode = parts[1], latitude = parts[2].toDouble(), longitude = parts[3].toDouble())
            }.toList()
    }
}
