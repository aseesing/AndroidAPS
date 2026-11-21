// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.options

import app.aaps.wear.R
import app.aaps.wear.watchfaces.utils.WatchFaceSettingRow
import org.json.JSONObject

/**
 * The texts of a setting row that come from the design and not from the app resources: the label of
 * the row, and the names and values of its choices. Used for the [DesignOptions] rows on the watch and
 * in the AAPS app.
 */
data class ChoiceLabels(val title: String, val entries: List<String>, val entryValues: List<String>)

/** One option of a design: the formulas read the chosen choice as `v_opt_<name>`. */
data class DesignOption(val name: String, val title: String, val choices: List<String>, val default: Int)

/**
 * Options that a Custom watch face design offers the wearer, for example "Santa: at Christmas, always,
 * never". Private patch.
 *
 * A design lists them in [JSON_KEY] (`options`):
 *
 * ```
 * "options": [
 *   {"name": "santa", "title": "Santa", "choices": ["At Christmas", "Always", "Never"], "default": 0}
 * ]
 * ```
 *
 * The watch and the AAPS app show one row per option, with the title and the choices of the design.
 * A formula and the `when` of a look read the choice as the value `v_opt_<name>`: the number of the
 * chosen choice, from 0. The [default] is used while the wearer has not chosen.
 *
 * The rows have fixed preference keys, one per place in the list ([PREF_KEYS]), so the phone can
 * send them like the other settings. A choice stays when the wearer loads another design; a number
 * that the new design does not have counts as its default.
 *
 * Holds the options of the last design it read, so the watch does not read them on every frame:
 * one instance per watch face.
 */
class DesignOptions {

    private var lastSource: JSONObject? = null
    private var lastOptions: List<DesignOption?> = emptyList()

    /** The options of [design], read again only when [design] is another object than last time. */
    fun of(design: JSONObject): List<DesignOption?> {
        if (lastSource !== design) {
            lastOptions = read(design)
            lastSource = design
        }
        return lastOptions
    }

    companion object {

        /** The key of the options in the design json. */
        const val JSON_KEY = "options"

        /** The start of the value key of an option: `v_opt_santa` reads the value `opt_santa`. */
        const val VALUE_PREFIX = "opt_"

        /** The stored value while the wearer has not chosen: the default of the design. */
        const val AS_DESIGNED = "design"

        /** How many options a design can offer; more are left out. */
        const val MAX_OPTIONS = 8

        /** How many choices one option can have, at least and at most. */
        const val MIN_CHOICES = 2
        const val MAX_CHOICES = 8

        private val PREF_KEYS = listOf(
            R.string.key_cwf_option_1, R.string.key_cwf_option_2, R.string.key_cwf_option_3, R.string.key_cwf_option_4,
            R.string.key_cwf_option_5, R.string.key_cwf_option_6, R.string.key_cwf_option_7, R.string.key_cwf_option_8
        )
        private val TITLES = listOf(
            R.string.pref_cwf_option_1, R.string.pref_cwf_option_2, R.string.pref_cwf_option_3, R.string.pref_cwf_option_4,
            R.string.pref_cwf_option_5, R.string.pref_cwf_option_6, R.string.pref_cwf_option_7, R.string.pref_cwf_option_8
        )
        private val NAME = Regex("[A-Za-z0-9_]+")

        /**
         * The options of [design], one per place in the list (at most [MAX_OPTIONS]). An option that
         * cannot be read is null, so the places of the others stay the same.
         */
        fun read(design: JSONObject?): List<DesignOption?> {
            val list = design?.optJSONArray(JSON_KEY) ?: return emptyList()
            return (0 until minOf(list.length(), MAX_OPTIONS)).map { i -> list.optJSONObject(i)?.let(::option) }
        }

        private fun option(json: JSONObject): DesignOption? {
            val name = json.optString("name").takeIf { NAME.matches(it) } ?: return null
            val title = json.optString("title").ifBlank { return null }
            val array = json.optJSONArray("choices") ?: return null
            val choices = (0 until array.length()).map { array.optString(it) }
            if (choices.size !in MIN_CHOICES..MAX_CHOICES || choices.any { it.isBlank() }) return null
            val default = json.optInt("default", 0).takeIf { it in choices.indices } ?: 0
            return DesignOption(name, title, choices, default)
        }

        /**
         * The rows of the watch face settings for [design]: one per option it offers, with its texts.
         * Without a design: one plain row per place, so the phone knows every key ([CwfSyncedSettings]).
         */
        internal fun rows(design: JSONObject?): List<WatchFaceSettingRow.Choice> {
            if (design == null) return PREF_KEYS.indices.map { plainRow(it) }
            return read(design).mapIndexedNotNull { i, option ->
                option ?: return@mapIndexedNotNull null
                plainRow(i).copy(
                    defaultValue = option.default.toString(),
                    labels = ChoiceLabels(option.title, option.choices, option.choices.indices.map { it.toString() })
                )
            }
        }

        private fun plainRow(i: Int) = WatchFaceSettingRow.Choice(
            key = PREF_KEYS[i], title = TITLES[i],
            entries = R.array.cwf_option_names, entryValues = R.array.cwf_option_values, defaultValue = AS_DESIGNED
        )

        /**
         * The value `opt_<name>` of [key] for [options]: the number of the choice in [stored] (preference
         * key, default value), or the default of the option. Null when [key] is not an option.
         */
        fun value(options: List<DesignOption?>, key: String, stored: (key: Int, default: String) -> String): Double? {
            if (!key.startsWith(VALUE_PREFIX)) return null
            val name = key.removePrefix(VALUE_PREFIX)
            val i = options.indexOfFirst { it?.name == name }
            if (i < 0) return null
            val option = options[i]!!
            val chosen = stored(PREF_KEYS[i], AS_DESIGNED).toIntOrNull()?.takeIf { it in option.choices.indices } ?: option.default
            return chosen.toDouble()
        }
    }
}
