// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.data

import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.shared.impl.weardata.CwfPlaceNeeds

/**
 * How long the watch keeps the rule for when it is day (`DayNightRule`, which can hold the user's
 * place): only while something on the watch needs it.
 *
 * Upstream keeps the rule only with a night face. The personal designs also need it without one: the
 * real sun and moon, the day look, the next sunrise or sunset, the weather, or a design that asks for
 * the place (see [CwfPlaceNeeds]). So the rule is kept while there is a night face OR the main design
 * needs the place, and dropped when neither holds any more: a new main design, the night face
 * removed, or the phone says "no rule". The phone sends a rule for the main design only when the
 * user allowed the place (see `WearPlaceUse` on the phone).
 *
 * The answer for the main design is kept until another design comes, so the json is not read again
 * with every rule the phone sends.
 */
class PlaceRuleKeeping {

    private var lastJson: String? = null
    private var lastNeeds = false

    /** True when [mainDesign] needs the place. */
    @Synchronized
    fun mainNeedsPlace(mainDesign: CwfData?): Boolean {
        val json = mainDesign?.json
        if (json != lastJson) {
            lastNeeds = CwfPlaceNeeds.needsPlace(json)
            lastJson = json
        }
        return lastNeeds
    }

    /** True when the watch keeps a rule: it has a [nightFace], or its [mainDesign] needs the place. */
    fun keepsRule(nightFace: CwfData?, mainDesign: CwfData?): Boolean = nightFace != null || mainNeedsPlace(mainDesign)
}
