// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

/**
 * The values of the watch setting "Day / night look", as the phone sends them in
 * `EventData.Preferences.dayNightLook` (see `CwfNight`).
 */
object DayNightLook {

    /** Day face from sunrise to sunset, night face at night */
    const val AUTO = "auto"
    const val DAY = "day"
    const val NIGHT = "night"
}

/**
 * The values of the watch setting "Always-on look", as the phone sends them in
 * `EventData.Preferences.alwaysOnLook` (see `CwfNight`).
 */
object AlwaysOnLook {

    const val NIGHT = "night"
    const val DAY = "day"

    /** The always-on screen shows the same face as the active screen */
    const val ACTIVE = "active"
}

/**
 * The phone's choice for the two day / night settings of the Custom watch face. They only matter
 * when the watch has a night face. Sent to the watch in `EventData.Preferences`; the defaults are
 * the defaults of the watch.
 */
enum class WearDayNightKey(
    override val key: String,
    override val defaultValue: String,
    override val title: TextRef,
    override val entries: Map<String, TextRef>,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false
) : StringPreferenceKey {

    DayNightLookSetting(
        key = "wear_cwf_day_night_look",
        defaultValue = DayNightLook.AUTO,
        title = SyncStrings.wear_day_night_look,
        entries = mapOf(
            DayNightLook.AUTO to SyncStrings.wear_day_night_look_auto,
            DayNightLook.DAY to SyncStrings.wear_day_night_face_day,
            DayNightLook.NIGHT to SyncStrings.wear_day_night_face_night
        )
    ),
    AlwaysOnLookSetting(
        key = "wear_cwf_always_on_look",
        defaultValue = AlwaysOnLook.NIGHT,
        title = SyncStrings.wear_day_night_always_on_look,
        entries = mapOf(
            AlwaysOnLook.NIGHT to SyncStrings.wear_day_night_face_night,
            AlwaysOnLook.DAY to SyncStrings.wear_day_night_face_day,
            AlwaysOnLook.ACTIVE to SyncStrings.wear_day_night_always_on_look_active
        )
    ),
    ;

    override val preferenceType: PreferenceType get() = PreferenceType.LIST
}

/**
 * The night face the phone last chose or the watch last reported, the same as `StringNonKey`
 * `WearCwfWatchfaceName`, `WearCwfAuthorVersion` and `WearCwfFileName` do for the main face.
 */
enum class WearNightFaceKey(
    override val key: String,
    override val defaultValue: String
) : StringNonPreferenceKey {

    NightWatchfaceName(key = "wear_cwf_night_watchface_name", defaultValue = ""),
    NightAuthorVersion(key = "wear_cwf_night_author_version", defaultValue = ""),
    NightFileName(key = "wear_cwf_night_filename", defaultValue = ""),
    ;
}
