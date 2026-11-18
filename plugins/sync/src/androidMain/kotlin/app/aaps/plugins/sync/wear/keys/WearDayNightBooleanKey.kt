package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

/**
 * The "Day and night" screen of the Wear plugin, see `WearDayNightSettings`. Not exported: the
 * switch is a consent to use the location of this phone, and the permission belongs to this phone too.
 */
enum class WearDayNightBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = false
) : BooleanNonPreferenceKey {

    /** Day and night from the phone's location. Off by default: then the location is never read. */
    UsePhoneLocation(key = "wear_day_night_use_phone_location", defaultValue = false),
    ;
}
