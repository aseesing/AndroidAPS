package app.aaps.wear.watchfaces.daynight

import app.aaps.wear.R
import app.aaps.wear.watchfaces.utils.WatchFaceSettingRow

/**
 * The two watch settings that choose between the day face and the night face of the Custom watch
 * face (see `CwfNight`). They exist only on the watch, like the other view settings of the Custom
 * watch face. Pure functions only.
 *
 * - "Day / night look", for the active screen: [AUTO] (the default: day and night as set on the
 *   phone, see `DayNightClock`), [DAY] or [NIGHT].
 * - "Always-on look", for the dim always-on screen: [AS_ACTIVE] (the default: the same face as the
 *   active screen, so raising the wrist does not change the design), [NIGHT] or [DAY].
 *
 * The values are also in `R.array.day_night_look_values` and `R.array.day_night_always_on_values`.
 */
internal object DayNightChoice {

    const val AUTO = "auto"
    const val DAY = "day"
    const val NIGHT = "night"
    const val AS_ACTIVE = "active"

    /**
     * True when the day face shows.
     *
     * @param active the stored "Day / night look"; null or unknown counts as [AUTO]
     * @param alwaysOn the stored "Always-on look"; null or unknown counts as [AS_ACTIVE]
     * @param ambient true on the dim always-on screen
     * @param isDayNow whether it is day now by the rule from the phone; only asked for [AUTO]
     */
    fun showDay(active: String?, alwaysOn: String?, ambient: Boolean, isDayNow: () -> Boolean): Boolean {
        val chosen = if (!ambient) active else when (alwaysOn) {
            DAY   -> DAY
            NIGHT -> NIGHT
            else  -> active
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
        defaultValue = AS_ACTIVE
    )
}
