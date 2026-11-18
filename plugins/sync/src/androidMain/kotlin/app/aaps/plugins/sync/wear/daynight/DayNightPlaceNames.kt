package app.aaps.plugins.sync.wear.daynight

import android.icu.text.TimeZoneNames
import android.icu.util.TimeZone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.util.Locale

/** Names of countries and cities in the user's language, and the phone's time zone. */
interface DayNightPlaceNames {

    /** The name of the country [countryCode] (ISO 3166), for example "Netherlands". */
    fun country(countryCode: String): String

    /** The name of the main city of the time zone [zoneId], for example "Amsterdam". */
    fun city(zoneId: String): String

    /** The phone's time zone, as its id and the other ids of the same zone (older names). */
    fun phoneZoneIds(): List<String>
}

/**
 * [DayNightPlaceNames] from the platform: the country names of [Locale] and the city names of the
 * time zone names (ICU), so the names come in the user's language without any translation in the
 * app.
 */
@ContributesBinding(AppScope::class)
@Inject
class AndroidDayNightPlaceNames() : DayNightPlaceNames {

    override fun country(countryCode: String): String =
        Locale.Builder().setRegion(countryCode).build().getDisplayCountry(Locale.getDefault()).ifEmpty { countryCode }

    override fun city(zoneId: String): String =
        TimeZoneNames.getInstance(Locale.getDefault()).getExemplarLocationName(zoneId)
            // A zone without a name in this language: its id says the city in English
            ?: zoneId.substringAfterLast('/').replace('_', ' ')

    override fun phoneZoneIds(): List<String> {
        val id = TimeZone.getDefault().id
        return listOf(id) + (0 until TimeZone.countEquivalentIDs(id)).map { TimeZone.getEquivalentID(id, it) }.filter { it != id }
    }
}
