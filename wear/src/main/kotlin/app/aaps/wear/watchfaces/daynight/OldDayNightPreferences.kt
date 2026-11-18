// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R

/**
 * What the personal build kept in the watch's shared preferences before the reviewed night face came:
 * the phone's rounded place, and the last day / night looks the phone sent. Auto Backup copies these
 * preferences, so the place must not stay there. Now the place is only in the rule the watch keeps
 * with its night face, in the no-backup folder (see `NightWatchfaceData`), and the two looks are
 * watch settings only.
 */
internal object OldDayNightPreferences {

    private val KEYS = listOf(
        R.string.key_location_latitude,
        R.string.key_location_longitude,
        R.string.key_day_night_look_phone,
        R.string.key_day_night_always_on_phone
    )

    /** Removes the old keys from [sp]; writes only when one is still there. */
    fun remove(sp: SP) {
        KEYS.filter { sp.contains(it) }.forEach { sp.remove(it) }
    }
}
