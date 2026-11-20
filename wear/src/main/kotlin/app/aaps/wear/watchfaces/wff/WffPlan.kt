// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.DesignRect
import java.util.Locale

/**
 * How one part of a design goes to a Watch Face Format (WFF) face (design doc section 4).
 *
 * - [LIVE]: WFF draws and updates it (clock, date, hands, options, user slots, watch values)
 * - [EXPAND]: worked out when the face is built (a fixed place, a copy, a setting)
 * - [BAKE]: drawn by our engine on the phone into a picture or a frame loop
 * - [COMPLICATION]: drawn or sent by an AAPS complication on the watch (AAPS data, the sky)
 * - [OMIT]: left out, and the lint says so
 */
enum class WffTier(val code: String) { LIVE("live"), EXPAND("expand"), BAKE("bake"), COMPLICATION("complication"), OMIT("omit") }

/** How bad a line of the lint is: an [ERROR] stops the build, a [WARNING] means something visible is lost or changes. */
enum class WffSeverity { INFO, WARNING, ERROR }

/**
 * One part of the design in the plan, in paint order.
 *
 * @param name what the designer knows it by: a view key (`chart`) or a layer (`L11 Santa`)
 * @param where the slot or block it goes to, or the static run, when it has one
 * @param box where it lies on the face, in design pixels (400 x 400), when known
 * @param view the key of the classic view, for a view
 * @param layer the index of the layer in the read animation, for a layer
 */
data class WffPlanItem(
    val name: String,
    val tier: WffTier,
    val note: String,
    val severity: WffSeverity = WffSeverity.INFO,
    val where: String? = null,
    val box: DesignRect? = null,
    val view: String? = null,
    val layer: Int? = null
)

/** One complication slot of the face. [id] follows the fixed role table, so an update keeps the wearer's settings. */
data class WffPlanSlot(val id: Int, val role: String, val type: String, val box: DesignRect)

/**
 * The plan of a converted face. It is also the lint report, see [lint].
 *
 * @param frames all baked wake frames together
 */
data class WffPlan(
    val title: String,
    val version: Int,
    val items: List<WffPlanItem>,
    val slots: List<WffPlanSlot>,
    val staticRuns: Int,
    val frames: Int,
    val maxFrames: Int,
    val notes: List<String> = emptyList()
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
            append(mark).append(' ').append(item.name.padEnd(NAME_WIDTH)).append(' ').append(item.tier.code.padEnd(TIER_WIDTH))
            item.where?.let { append(' ').append(it).append(':') }
            append(' ').append(item.note).append('\n')
        }
        append(String.format(Locale.ROOT, "Slots %d / %d   static runs %d   frames %d / %d\n", slots.size, WffPlanner.MAX_SLOTS, staticRuns, frames, maxFrames))
        for (slot in slots) append("   slot ").append(slot.id).append(' ').append(slot.role).append(" (").append(slot.type).append(")\n")
        append("Errors ").append(errors).append("   Warnings ").append(warnings).append('\n')
        notes.forEach { append(" note: ").append(it).append('\n') }
    }

    private companion object {

        const val NAME_WIDTH = 22
        const val TIER_WIDTH = 12
    }
}
