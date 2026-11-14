// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.colour

import app.aaps.wear.R
import app.aaps.wear.watchfaces.daynight.DayOverlay
import app.aaps.wear.watchfaces.utils.WatchFaceSettingRow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The colours a wearer can choose for a Custom watch face design: in the settings of the watch face
 * on the watch, or in the AAPS app, which sends them to the watch (`CwfSyncedSettings`). Private patch.
 *
 * A design offers up to two colours, in the object [JSON_KEY] (`colourLooks`):
 *
 * ```
 * "colourLooks": {
 *   "colour1": {"hour": {"fontColor": "@colour"}, "cover_chart": {"color": "@colour"}},
 *   "colour2": {"day": {"fontColor": "@colour"}}
 * }
 * ```
 *
 * Each one is a look like the day look (see [DayOverlay]): it is merged into the design, key by key.
 * Before that every text [PLACEHOLDER] in it becomes the colour the wearer chose, so `@colour` becomes
 * `#00E5FF`. `#80@colour` becomes `#8000E5FF`: the same colour, half see-through.
 *
 * The watch and the app show the row "Main colour" for `colour1` and "Second colour" for `colour2`,
 * only for a design that has them. The first value of each list, [AS_DESIGNED], leaves the design as it is.
 *
 * The colours are laid over the design itself, before the day look and the weather looks, so a day
 * look can still change them.
 *
 * Holds the last result, so the watch does not merge again on every frame: one instance per watch face.
 */
class ColourLooks {

    private var lastSource: JSONObject? = null
    private var lastChoices: List<String>? = null
    private var lastResult: JSONObject? = null

    /**
     * [design] with the [choices] (one per slot, see [SLOTS]) laid over it. The same object as last time
     * while [design] and [choices] stay the same, and [design] itself when nothing is chosen.
     */
    fun apply(design: JSONObject, choices: List<String>): JSONObject {
        lastResult?.let { if (lastSource === design && lastChoices == choices) return it }
        return lookOf(design, choices).also {
            lastSource = design
            lastChoices = choices
            lastResult = it
        }
    }

    companion object {

        /** The key of the colour choices in the design json. */
        const val JSON_KEY = "colourLooks"

        /** The text in a look that becomes the chosen colour. */
        const val PLACEHOLDER = "@colour"

        /** The value that leaves the design as it is. */
        const val AS_DESIGNED = "design"

        /** The colour slots a design can offer, in the order of [rows] and of the choices. */
        val SLOTS = listOf("colour1", "colour2")

        private val PREF_KEYS = listOf(R.string.key_cwf_colour_1, R.string.key_cwf_colour_2)
        private val TITLES = listOf(R.string.pref_cwf_colour_1, R.string.pref_cwf_colour_2)

        /** True when [design] offers the colour [slot]. */
        fun has(design: JSONObject, slot: String): Boolean = design.optJSONObject(JSON_KEY)?.optJSONObject(slot) != null

        /** The rows of the watch face settings for [design]: one per colour it offers. All of them without a design. */
        internal fun rows(design: JSONObject?): List<WatchFaceSettingRow.Choice> = SLOTS.indices
            .filter { design == null || has(design, SLOTS[it]) }
            .map {
                WatchFaceSettingRow.Choice(
                    key = PREF_KEYS[it], title = TITLES[it],
                    entries = R.array.cwf_colour_names, entryValues = R.array.cwf_colour_values, defaultValue = AS_DESIGNED
                )
            }

        /** The stored choice of every slot, read with [stored] (preference key, default value). */
        fun choices(stored: (key: Int, default: String) -> String): List<String> = PREF_KEYS.map { stored(it, AS_DESIGNED) }

        /**
         * [design] with [choices] laid over it, as a new object; [design] itself is not changed. [design]
         * itself when no slot it offers has a colour chosen. A choice that is not a colour (`#RRGGBB`)
         * counts as [AS_DESIGNED].
         */
        fun lookOf(design: JSONObject, choices: List<String>): JSONObject {
            val looks = design.optJSONObject(JSON_KEY) ?: return design
            val chosen = SLOTS.indices.mapNotNull { i ->
                val colour = choices.getOrNull(i)?.takeIf { COLOUR.matches(it) } ?: return@mapNotNull null
                looks.optJSONObject(SLOTS[i])?.let { it to colour }
            }
            if (chosen.isEmpty()) return design
            val result = JSONObject(design.toString())
            chosen.forEach { (look, colour) -> DayOverlay.merge(result, filledIn(look, colour.substring(1)) as JSONObject) }
            return result
        }

        private val COLOUR = Regex("#[0-9A-Fa-f]{6}")

        /** A copy of [value] with every [PLACEHOLDER] in its texts replaced by [rgb] (`RRGGBB`, the `#` is in the text). */
        private fun filledIn(value: Any?, rgb: String): Any? = when (value) {
            is JSONObject -> JSONObject().also { copy -> value.keys().forEach { copy.put(it, filledIn(value.opt(it), rgb)) } }
            is JSONArray  -> JSONArray().also { copy -> for (i in 0 until value.length()) copy.put(filledIn(value.opt(i), rgb)) }
            is String     -> if (value.endsWith(PLACEHOLDER)) value.removeSuffix(PLACEHOLDER).ifEmpty { "#" } + rgb else value
            else          -> value
        }
    }
}
