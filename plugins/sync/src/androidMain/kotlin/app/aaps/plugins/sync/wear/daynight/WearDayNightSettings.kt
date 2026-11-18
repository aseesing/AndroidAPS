package app.aaps.plugins.sync.wear.daynight

import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightIntKey
import app.aaps.plugins.sync.wear.keys.WearDayNightStringKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** How day and night are set when the phone's location is not used. */
enum class DayNightMode(val value: String) {

    /** From a day start to a night start, the default */
    FIXED_TIMES("fixed"),

    /** From sunrise to sunset in a city the user chose */
    CITY("city");

    companion object {

        /** The mode stored as [value]; an unknown value counts as [FIXED_TIMES]. */
        fun of(value: String): DayNightMode = entries.firstOrNull { it.value == value } ?: FIXED_TIMES
    }
}

/** Where the rule for the watch comes from, after the fallbacks: also what leaves the phone. */
enum class DayNightSource {

    /** The phone's rough place */
    PHONE_LOCATION,

    /** The place of the chosen city: public data */
    CITY,

    /** The day start and the night start */
    FIXED_TIMES
}

/**
 * The settings of the "Day and night" screen, and the [DayNightRule] they make for the watch (see
 * `CwfNight`). The phone is the only place where this is set; the watch only gets the rule.
 *
 * 1. "Use the phone's location" (off by default): the phone's rough place. Used only with the
 *    location permission and a known place of the last day; else the choice below.
 * 2. Else a [DayNightMode]: fixed times (the default, 07:00 to 19:00), or a chosen city. A city
 *    that is not in [TimeZoneCities] falls back to the fixed times.
 *
 * The location is read only in [rule] and [source], only when the switch is on and the permission
 * is granted.
 */
@SingleIn(AppScope::class)
@Inject
class WearDayNightSettings(
    private val preferences: Preferences,
    private val location: PhoneRoughLocation,
    private val cities: TimeZoneCities
) {

    /** The switch as stored. It counts only with the permission, see [source]. */
    fun usePhoneLocationStored(): Boolean = preferences.get(WearDayNightBooleanKey.UsePhoneLocation)

    fun mode(): DayNightMode = DayNightMode.of(preferences.get(WearDayNightStringKey.Mode))

    fun dayStart(): Int = preferences.get(WearDayNightIntKey.DayStart)

    fun nightStart(): Int = preferences.get(WearDayNightIntKey.NightStart)

    /** The chosen city, or null when none is chosen or it is not in the table. */
    fun city(): TimeZoneCity? = preferences.get(WearDayNightStringKey.City).takeIf { it.isNotEmpty() }?.let { cities.byZoneId(it) }

    /** The switch is on and the permission is granted, so the phone's place is tried first. Reads no location. */
    fun usesPhoneLocation(): Boolean = usePhoneLocationStored() && location.hasPermission()

    /** The choice below the switch: a known chosen city, else the fixed times. Reads no location. */
    fun fallbackSource(): DayNightSource =
        if (mode() == DayNightMode.CITY && city() != null) DayNightSource.CITY else DayNightSource.FIXED_TIMES

    /**
     * Where [rule] takes the rule from now. Reads the phone's location only when [usesPhoneLocation]:
     * without a recent known place it is the [fallbackSource].
     */
    fun source(): DayNightSource = when {
        usesPhoneLocation() && location.read() != null -> DayNightSource.PHONE_LOCATION
        else                                           -> fallbackSource()
    }

    /**
     * The rule for the watch. Reads the phone's location only when [usesPhoneLocation]; without a
     * recent known place it uses the choice below the switch.
     */
    fun rule(): DayNightRule {
        if (usesPhoneLocation()) location.read()?.let { return it }
        val city = if (fallbackSource() == DayNightSource.CITY) city() else null
        return if (city != null) DayNightRule.Place(city.latitude, city.longitude)
        else DayNightRule.FixedTimes(dayStartMinute = dayStart(), nightStartMinute = nightStart())
    }
}
