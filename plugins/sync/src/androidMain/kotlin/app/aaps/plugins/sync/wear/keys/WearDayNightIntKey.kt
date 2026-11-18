package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

/** The "Day and night" screen of the Wear plugin, see `WearDayNightSettings`. In minutes after midnight. */
enum class WearDayNightIntKey(
    override val key: String,
    override val defaultValue: Int
) : IntNonPreferenceKey {

    DayStart(key = "wear_day_night_day_start", defaultValue = 7 * 60),
    NightStart(key = "wear_day_night_night_start", defaultValue = 19 * 60),
    ;
}
