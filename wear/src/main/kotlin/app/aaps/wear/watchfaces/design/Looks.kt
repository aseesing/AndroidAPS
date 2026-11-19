package app.aaps.wear.watchfaces.design

import app.aaps.core.interfaces.rx.weardata.CwfResDataMap
import app.aaps.wear.watchfaces.animation.Formula
import app.aaps.wear.watchfaces.animation.FormulaException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The looks of a Custom watch face design: changes laid over the design while a condition is true, for
 * example another background in the rain. In the design json:
 *
 * ```
 * "looks": [
 *   {"when": "v_is_day == 1", "patch": {"hour": {"fontColor": "#202020"}}, "images": {"Background": "BackgroundDay"}},
 *   {"when": "v_weather_kind == 3", "patch": {...}}
 * ]
 * ```
 *
 * - `when` is a formula over the values of the face (`v_<key>`, see `FaceValues`), for example
 *   `v_is_day` (1 while the face shows the day) and `v_weather_kind` (0 clear, 1 partly cloudy,
 *   2 cloudy, 3 rain; no value while the weather is unknown). A look without `when` always applies.
 * - `patch` is laid over the design ([merge]): an object is merged key by key into the object of the
 *   same key, as deep as it goes; `null` removes the key; every other value (a list too) replaces it.
 * - `images` shows another picture of the zip in the place of one: `{"Background": "BackgroundRain"}`.
 *   A swap to a picture the zip does not have is left out.
 *
 * The looks whose condition is true are laid over the design in their order. The result is kept while
 * the design, its pictures and the set of true conditions stay the same, so the watch does not merge
 * again every minute. One instance per design that the face shows.
 */
class Looks {

    /** A design in its look: the json the views use, which looks are on, and the picture swaps of those. */
    class Look(val json: JSONObject, val active: List<Boolean>, private val swaps: List<JSONObject>) {

        /** The pictures the views use: [resData] (those of the zip) with the swaps of the looks that are on. [resData] itself without swaps. */
        fun pictures(resData: CwfResDataMap): CwfResDataMap = if (swaps.isEmpty()) resData else swapped(resData)

        /** A copy of [pictures] (by name) with the swaps of the looks that are on. */
        fun <T> swapped(pictures: Map<String, T>): MutableMap<String, T> {
            val result = pictures.toMutableMap()
            for (swap in swaps) swap.keys().forEach { target -> pictures[swap.optString(target)]?.let { result[target] = it } }
            return result
        }
    }

    private class Compiled(val condition: Formula?, val patch: JSONObject?, val images: JSONObject?)

    private var source: JSONObject? = null
    private var looks: List<Compiled> = emptyList()
    private var valueKeys: List<String> = emptyList()
    private var values = DoubleArray(0)
    private var last: Look? = null

    /**
     * [design] with the looks laid over it whose condition is true for the values of now ([value]: the
     * value of a key, or null); [design] itself when no look applies. The same object while the design
     * and the set of true conditions stay the same.
     */
    fun apply(design: JSONObject, value: (String) -> Double?): Look {
        if (design !== source) read(design)
        for (i in valueKeys.indices) values[i] = value(valueKeys[i]) ?: Double.NaN
        val active = looks.map { look -> look.condition?.eval(values)?.let { it != 0.0 && !it.isNaN() } ?: true }
        last?.let { if (it.active == active) return it }
        val look = if (active.none { it }) Look(design, active, emptyList()) else lookOf(design, active)
        last = look
        return look
    }

    private fun lookOf(design: JSONObject, active: List<Boolean>): Look {
        val json = JSONObject(design.toString())
        json.remove(JSON_KEY)
        val swaps = ArrayList<JSONObject>()
        looks.forEachIndexed { i, look ->
            if (!active[i]) return@forEachIndexed
            look.patch?.let { merge(json, it) }
            look.images?.let { swaps += it }
        }
        return Look(json, active, swaps)
    }

    /** Reads the looks of [design]; a look whose condition is not a formula never applies. */
    private fun read(design: JSONObject) {
        source = design
        last = null
        val keys = ArrayList<String>()
        val list = design.optJSONArray(JSON_KEY) ?: JSONArray()
        looks = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { look ->
            val text = look.optString(WHEN).trim()
            val condition = if (text.isEmpty()) null else try {
                Formula.parse(text) { name ->
                    if (name.startsWith(VALUE_PREFIX) && name.length > VALUE_PREFIX.length) {
                        val key = name.removePrefix(VALUE_PREFIX)
                        keys.indexOf(key).takeIf { it >= 0 } ?: keys.size.also { keys += key }
                    } else null
                }
            } catch (_: FormulaException) {
                NEVER
            }
            Compiled(condition, look.optJSONObject(PATCH), look.optJSONObject(IMAGES))
        }
        valueKeys = keys
        values = DoubleArray(keys.size)
    }

    companion object {

        /** The key of the looks in the design json. */
        const val JSON_KEY = "looks"

        const val WHEN = "when"
        const val PATCH = "patch"
        const val IMAGES = "images"

        private const val VALUE_PREFIX = "v_"

        /** The condition of a look that cannot be read: never true. */
        private val NEVER = Formula.constant(0.0)

        /** True when a look of [design] has a condition that reads the value [key]. */
        fun reads(design: JSONObject, key: String): Boolean {
            val list = design.optJSONArray(JSON_KEY) ?: return false
            val name = Regex("""\b${Regex.escape(VALUE_PREFIX + key)}\b""")
            return (0 until list.length()).any { list.optJSONObject(it)?.optString(WHEN)?.let(name::containsMatchIn) == true }
        }

        /**
         * Merges [overlay] into [target]: an object is merged key by key into the object of the same key,
         * as deep as it goes; `null` removes the key; every other value replaces it. Also used for the
         * colour looks (`ColourLooks`).
         */
        fun merge(target: JSONObject, overlay: JSONObject) {
            overlay.keys().forEach { key ->
                val value = overlay.opt(key)
                val current = target.optJSONObject(key)
                when {
                    value == null || value == JSONObject.NULL -> target.remove(key)
                    value is JSONObject && current != null    -> merge(current, value)
                    value is JSONObject                       -> target.put(key, JSONObject(value.toString()))
                    else                                      -> target.put(key, value)
                }
            }
        }
    }
}
