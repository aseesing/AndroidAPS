// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import app.aaps.wear.R
import app.aaps.wear.watchfaces.utils.WatchFaceSettingRow

/**
 * The two watch settings of the day look (see [DayOverlay]). Private patch. Pure functions only.
 *
 * - "Day / night look", for the active screen: [AUTO] (from sunrise to sunset, the default), [DAY]
 *   or [NIGHT].
 * - "Always-on look", for the dim always-on screen: [NIGHT] (the default, a light screen costs
 *   battery there), [DAY], or [AS_ACTIVE] to follow the first setting.
 */
internal object DayNightChoice {

    const val AUTO = "auto"
    const val DAY = "day"
    const val NIGHT = "night"
    const val AS_ACTIVE = "active"

    /** The values "Day / night look" can have. */
    val ACTIVE_VALUES = setOf(AUTO, DAY, NIGHT)

    /** The values "Always-on look" can have. */
    val ALWAYS_ON_VALUES = setOf(NIGHT, DAY, AS_ACTIVE)

    /**
     * The value to store for a setting the phone sent (see `CwfNight`), or null to leave the watch
     * setting as it is.
     *
     * A value is taken only when it differs from the one the phone sent last time ([lastSent]), so a
     * change made on the watch stays until the phone setting changes. Null and unknown values are
     * never taken: an older phone sends none.
     */
    fun fromPhone(sent: String?, lastSent: String?, allowed: Set<String>): String? =
        sent?.takeIf { it in allowed && it != lastSent }

    /**
     * True when the day look shows.
     *
     * @param active the stored "Day / night look"; null or unknown counts as [AUTO]
     * @param alwaysOn the stored "Always-on look"; null or unknown counts as [NIGHT]
     * @param ambient true on the dim always-on screen
     * @param isDayNow whether it is between sunrise and sunset now; only asked for [AUTO]
     */
    fun showDay(active: String?, alwaysOn: String?, ambient: Boolean, isDayNow: () -> Boolean): Boolean {
        val chosen = if (!ambient) active else when (alwaysOn) {
            DAY       -> DAY
            AS_ACTIVE -> active
            else      -> NIGHT
        }
        return when (chosen) {
            DAY   -> true
            NIGHT -> false
            else  -> isDayNow()
        }
    }

    /** The row "Day / night look" on the Custom watch face's settings screen. */
    fun activeRow(): WatchFaceSettingRow.Choice = WatchFaceSettingRow.Choice(
        key = R.string.key_day_night_look,
        title = R.string.pref_day_night_look,
        entries = R.array.day_night_look_names,
        entryValues = R.array.day_night_look_values,
        defaultValue = AUTO
    )

    /** The row "Always-on look" on the Custom watch face's settings screen. */
    fun alwaysOnRow(): WatchFaceSettingRow.Choice = WatchFaceSettingRow.Choice(
        key = R.string.key_day_night_always_on,
        title = R.string.pref_day_night_always_on,
        entries = R.array.day_night_always_on_names,
        entryValues = R.array.day_night_always_on_values,
        defaultValue = NIGHT
    )
}
