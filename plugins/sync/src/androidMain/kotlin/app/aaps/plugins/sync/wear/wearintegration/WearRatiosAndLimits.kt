// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.objects.constraints.ConstraintObject
import kotlinx.coroutines.CancellationException

/**
 * The ratios and limits of the wearer for the value keys `ic`, `isf`, `max_iob` and `max_carbs` of the
 * Custom watch face. A design uses them for scales, for example a bar of COB as insulin next to a bar of IOB.
 * Each is null when it is not known; a design divides by them, so 0 is never sent.
 */
class WearRatiosAndLimits(
    private val activePlugin: ActivePlugin,
    private val constraintChecker: ConstraintsChecker,
    private val config: Config,
    private val aapsLogger: AAPSLogger
) {

    /**
     * [numbers] with the ratios of [profile] now (null when there is no profile) and the limits.
     * The ratios follow the time of day and the percentage of the profile; with dynamic ISF the ISF is the
     * one the loop uses now, as for the BGI.
     *
     * Never throws: on an error the status goes out without these numbers, so the watch still gets the rest.
     */
    suspend fun addTo(numbers: StatusNumbers, profile: Profile?, caller: String): StatusNumbers =
        try {
            numbers.copy(
                ic = profile?.getIc()?.let(::positive),
                isfMgdl = profile?.getIsfMgdl(caller)?.let(::positive),
                maxIob = maxIob(),
                maxCarbs = positive(constraintChecker.getMaxCarbsAllowed().value().toDouble())
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Ratios and limits for the watch failed", e)
            numbers
        }

    /**
     * The max IOB of the loop in U: the setting of the active APS (OpenAPS SMB, AMA or autoISF), within its
     * hard limit. Not the max IOB allowed now, which is 0 in a mode without the loop: the setting is the
     * wearer's usual maximum. Null on AAPSClient: the loop runs on the master, the setting here is not used.
     */
    private suspend fun maxIob(): Double? {
        if (config.AAPSCLIENT) return null
        val aps = activePlugin.activeAPS as? PluginConstraints ?: return null
        return positive(aps.applyMaxIOBConstraints(ConstraintObject(Double.MAX_VALUE, aapsLogger)).value())
    }

    /** Only a real number above 0, so a design never divides by 0. */
    private fun positive(value: Double): Double? = value.takeIf { it > 0.0 && it < Double.MAX_VALUE && it.isFinite() }
}
