package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/** The "Day and night" screen of the Wear plugin, see `WearDayNightSettings`. */
enum class WearDayNightStringKey(
    override val key: String,
    override val defaultValue: String
) : StringNonPreferenceKey {

    /** A `DayNightMode` value: fixed times or a chosen city. */
    Mode(key = "wear_day_night_mode", defaultValue = "fixed"),

    /** The time zone id of the chosen city, for example "Europe/Amsterdam". Empty: none chosen. */
    City(key = "wear_day_night_city", defaultValue = ""),
    ;
}
