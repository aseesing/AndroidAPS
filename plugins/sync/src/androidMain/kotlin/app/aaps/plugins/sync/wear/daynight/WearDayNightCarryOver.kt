// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.daynight

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightCarryOverKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Day and night as the personal build had it, carried over once to the "Day and night" screen.
 *
 * The personal build always read the phone's location for day and night, without a switch. So that a
 * night face keeps following the real sunrise and sunset after the update, the switch "Use the phone's
 * location" is set on once, when all of this is true at the first start of this build:
 * - a night face is set (the phone remembers its name),
 * - the switch was never stored (the user never set it on the new screen).
 *
 * The same for a design that needs the place (the real sun and moon, the weather, see `CwfPlaceNeeds`):
 * the personal build read the location for it too. The phone learns the design only when the watch
 * sends it, so this part runs once, with the first design the watch reports after the update, see
 * [designKnown].
 *
 * The switch is then stored, so the screen shows it on and the user can turn it off. Without a night
 * face and without such a design nothing changes: the defaults of the screen apply (fixed times 07:00
 * to 19:00, location off). The location permission is still needed: without it the fixed times or the
 * chosen city are used, as the screen says. Each part runs once per phone, never again.
 */
@SingleIn(AppScope::class)
@Inject
class WearDayNightCarryOver(
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {

    fun runOnce() {
        if (preferences.get(WearDayNightCarryOverKey.Done)) return
        if (preferences.getIfExists(WearDayNightBooleanKey.UsePhoneLocation) == null && preferences.get(WearNightFaceKey.NightWatchfaceName).isNotEmpty()) {
            preferences.put(WearDayNightBooleanKey.UsePhoneLocation, true)
            aapsLogger.info(LTag.WEAR, "Day and night: the night face keeps following the phone's location, as in the personal build")
        }
        preferences.put(WearDayNightCarryOverKey.Done, true)
    }

    /**
     * The phone learned the main design of the watch, for the first time since the update or later.
     * Only the first time counts: when that design [needsPlace] and the switch was never stored, the
     * switch is set on, as the old build used the location for it.
     */
    fun designKnown(needsPlace: Boolean) {
        if (preferences.get(WearDayNightCarryOverKey.DesignDone)) return
        if (needsPlace && preferences.getIfExists(WearDayNightBooleanKey.UsePhoneLocation) == null) {
            preferences.put(WearDayNightBooleanKey.UsePhoneLocation, true)
            aapsLogger.info(LTag.WEAR, "Location: the design keeps the phone's location, as in the personal build")
        }
        preferences.put(WearDayNightCarryOverKey.DesignDone, true)
    }
}
