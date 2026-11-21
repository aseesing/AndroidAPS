// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.DesignRect

/**
 * How one part of a design goes to a Watch Face Format (WFF) face, see [WffPlanner].
 *
 * - [LIVE]: WFF draws and updates it (clock, date, hands, the wearer's slots)
 * - [BAKE]: drawn by our engine on a dev machine into a picture or into frames
 * - [COMPLICATION]: an AAPS complication on the watch feeds or draws it ([WffPlanItem.where] says which)
 * - [OMIT]: left out, and the lint says so
 */
enum class WffTier(val code: String) { LIVE("live"), BAKE("bake"), COMPLICATION("complication"), OMIT("omit") }

/** How bad a line of the lint is: an [ERROR] stops the build, a [WARNING] means something visible is lost or changes. */
enum class WffSeverity { INFO, WARNING, ERROR }

/**
 * One part of the design in the plan, in paint order.
 *
 * @param name what the designer knows it by: a view key (`chart`) or a layer (`L11 Santa`)
 * @param where the slot it goes to: `glucose`, `age`, `data` or `complication1` to `5`
 * @param box where it lies on the face, in design pixels (400 x 400), when known
 * @param view the key of the classic view, for a view
 * @param layer the index of the layer in the read animation, for a layer
 * @param run for a baked part that is not [loop]: which static picture it is in, 0 at the bottom
 * @param loop a baked layer that moves in the wake: frames, or its rest pose when it also shows at rest
 * @param slot for a layer: `back`, `views` or `front`
 * @param blends a layer that mixes its colours with what lies below it (`add`, `screen`, a group): it
 *   must be in the same picture as the parts below it
 */
data class WffPlanItem(
    val name: String,
    val tier: WffTier,
    val note: String,
    val severity: WffSeverity = WffSeverity.INFO,
    val where: String? = null,
    val box: DesignRect? = null,
    val view: String? = null,
    val layer: Int? = null,
    val run: Int? = null,
    val loop: Boolean = false,
    val slot: String? = null,
    val blends: Boolean = false
)

/** One complication slot of the face. [id] is fixed per role, so an update keeps the wearer's settings. */
data class WffPlanSlot(val id: Int, val role: String, val type: String, val box: DesignRect)

/** The plan of a converted face. It is also the lint report, see [lint]. */
data class WffPlan(
    val title: String,
    val version: Int,
    val items: List<WffPlanItem>,
    val slots: List<WffPlanSlot>,
    val staticRuns: Int
) {

    val errors: Int get() = items.count { it.severity == WffSeverity.ERROR }
    val warnings: Int get() = items.count { it.severity == WffSeverity.WARNING }

    /** True when the face can be built: no error. Otherwise the wearer keeps the face they have. */
    val canBuild: Boolean get() = errors == 0

    /** The lint as text: one line per part in paint order, then the totals. */
    fun lint(): String = buildString {
        append(title).append(" -> WFF ").append(version).append('\n')
        for (item in items) {
            val mark = when (item.severity) {
                WffSeverity.ERROR   -> "!!"
                WffSeverity.WARNING -> " !"
                WffSeverity.INFO    -> "  "
            }
            val place = when {
                item.where != null -> item.where
                item.loop          -> "loop"
                item.run != null   -> "picture ${item.run}"
                else               -> ""
            }
            append(mark).append(' ').append(item.name.padEnd(NAME_WIDTH)).append(' ').append(item.tier.code.padEnd(TIER_WIDTH)).append(' ')
            append(place.padEnd(PLACE_WIDTH)).append(' ').append(item.note).append('\n')
        }
        append("Slots ").append(slots.size).append(" / ").append(WffPlanner.MAX_SLOTS).append("   static pictures ").append(staticRuns).append('\n')
        for (slot in slots) append("   slot ").append(slot.id).append(' ').append(slot.role).append(" (").append(slot.type).append(")\n")
        append("Errors ").append(errors).append("   Warnings ").append(warnings).append('\n')
    }

    private companion object {

        const val NAME_WIDTH = 22
        const val TIER_WIDTH = 12
        const val PLACE_WIDTH = 13
    }
}
