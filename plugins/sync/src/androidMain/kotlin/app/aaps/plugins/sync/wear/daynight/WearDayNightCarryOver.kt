// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.daynight

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightCarryOverKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Day and night as the old personal build had it, carried over once to the "Day and night" screen.
 *
 * The old personal build always read the phone's location for day and night and for the designs that
 * need the place, without a switch. So that the watch keeps what it showed after the update, the
 * switch "Use the phone's location" is set on once, but only on a phone that ran the old build, and
 * only when ALL of this is true:
 * - the switch was never stored (the user never set it, on or off, on the new screen),
 * - the location permission is already granted (a refusal on the screen stores nothing, so the
 *   permission is the only sign of what the user wanted; without it nothing is turned on),
 * - a night face is set (at the first start of this build, see [runOnce]), or the first main design
 *   the watch reports after the update needs the place (see [designKnown]).
 *
 * The sign of the old build is a design the phone knows of at the first start of this build: the name
 * of the main design (`StringNonKey.WearCwfWatchfaceName`, which the phone keeps when the watch reports
 * a design) or of the night face (`WearNightFaceKey.NightWatchfaceName`). A fresh install, or a phone
 * that never had a design, has neither: then both parts are done at once and nothing changes.
 *
 * The design part ends at the latest after one request round (see `WearPlaceUse.designRequest`): a
 * watch with only the default design does not answer, and then nothing changes. It also ends when the
 * phone sends a new design to the watch: that is a choice made in the new build. So it cannot fire
 * months later, when the user loads another design.
 *
 * The switch is then stored, so the screen shows it on and the user can turn it off. Each part runs at
 * most once per phone, never again.
 */
@SingleIn(AppScope::class)
@Inject
class WearDayNightCarryOver(
    private val preferences: Preferences,
    private val location: PhoneRoughLocation,
    private val aapsLogger: AAPSLogger
) {

    /** At every start of the plugin; does something only the first time. */
    fun runOnce() {
        val oldBuild = hasDesignOfOldBuild()
        if (!preferences.get(WearDayNightCarryOverKey.Done)) {
            if (preferences.get(WearNightFaceKey.NightWatchfaceName).isNotEmpty() && mayTurnOn())
                turnOn("Day and night: the night face keeps following the phone's location, as in the personal build")
            preferences.put(WearDayNightCarryOverKey.Done, true)
        }
        if (!oldBuild) closeDesignPart("no design known, nothing to carry over")
    }

    /** True while the design part can still run, see [designKnown]. */
    fun designPartOpen(): Boolean = !preferences.get(WearDayNightCarryOverKey.DesignDone)

    /**
     * The phone learned the main design of the watch. Only the first time after the update counts:
     * when that design [needsPlace], the switch was never stored and the permission is granted, the
     * switch is set on, as the old build used the location for it.
     */
    fun designKnown(needsPlace: Boolean) {
        if (!designPartOpen()) return
        if (needsPlace && mayTurnOn()) turnOn("Location: the design keeps the phone's location, as in the personal build")
        closeDesignPart(null)
    }

    /** The request for the main design is over and brought no design: the watch has only the default design. */
    fun designRoundOver() = closeDesignPart("the watch reported no design")

    /** The phone sent a new main design to the watch: a choice in this build, nothing to carry over. */
    fun designSentByPhone() = closeDesignPart("the phone sent a new design")

    private fun hasDesignOfOldBuild(): Boolean =
        preferences.get(StringNonKey.WearCwfWatchfaceName).isNotEmpty() || preferences.get(WearNightFaceKey.NightWatchfaceName).isNotEmpty()

    /** Never stored by the user, and the permission is already granted. */
    private fun mayTurnOn(): Boolean =
        preferences.getIfExists(WearDayNightBooleanKey.UsePhoneLocation) == null && location.hasPermission()

    private fun turnOn(message: String) {
        preferences.put(WearDayNightBooleanKey.UsePhoneLocation, true)
        aapsLogger.info(LTag.WEAR, message)
    }

    private fun closeDesignPart(reason: String?) {
        if (!designPartOpen()) return
        preferences.put(WearDayNightCarryOverKey.DesignDone, true)
        reason?.let { aapsLogger.debug(LTag.WEAR, "Location: no carry-over for the design, $it") }
    }
}
