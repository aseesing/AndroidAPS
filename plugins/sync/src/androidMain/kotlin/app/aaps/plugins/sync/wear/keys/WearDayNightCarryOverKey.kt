// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

/** The one-time carry-over of day and night from the personal build, see `WearDayNightCarryOver`. */
enum class WearDayNightCarryOverKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = false
) : BooleanNonPreferenceKey {

    /** True once the carry-over has run on this phone; it never runs again. */
    Done(key = "wear_day_night_carried_over", defaultValue = false),
    ;
}
