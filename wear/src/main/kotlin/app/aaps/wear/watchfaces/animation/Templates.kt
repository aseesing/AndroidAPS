// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Layer templates: a few layers with `$name` parameters, used in the `layers` of an animation as
 * `{"use": "rain", "count": 90, "color": "#A6C8FF"}`. Expanded to plain layers when the design is
 * read, so the engine itself only knows plain layers.
 *
 * The app has no templates of its own: a design brings every template it uses, in the `templates` of
 * its animation. A `use` of a name the design does not define is left out, with an error.
 *
 * A template is `{"params": {...}, "layers": [...]}`:
 * - `params` gives each parameter its default value (a number, a text, an object or a list), or
 *   `{"default": 90, "min": 0, "max": 300, "int": true}` with limits: the value is cut to them, and made
 *   a whole number (cut towards 0) with `int`. A limit may be a formula of the parameters before it,
 *   like `"max": "$periodMs"`. An object value is laid over an object default key by key, so
 *   `"region": {"top": 100}` keeps the other sides of the default.
 * - in the `layers`, a text that is exactly `$name` becomes the value itself (any type); `$name` in a
 *   longer text becomes the value as text (a number as a plain decimal, in brackets when negative).
 *   `$name.key` is the entry `key` of an object parameter.
 * - a template layer may `use` another template, at most [MAX_DEPTH] deep, never itself.
 * - a template layer with `"if": "<formula of the parameters>"` is left out when the formula is 0, so a
 *   part that cannot show is not a layer at all.
 * - a `use` may carry `wff` (how the layer is made in the Watch Face Format). It is no parameter: each
 *   layer the template makes gets it, unless that layer has its own. The engine itself never reads it.
 *
 * @param own the templates of the design (the `templates` of the animation), by name, or null
 */
class Templates(private val own: JSONObject?) {

    /**
     * The plain layers of [layers]: a layer with `use` is replaced by the layers of its template, the
     * others are kept as they are. What cannot be expanded is left out and said in [errors].
     */
    fun expand(layers: JSONArray, errors: MutableList<String>): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        for (i in 0 until layers.length()) {
            val entry = layers.optJSONObject(i) ?: continue
            expand(entry, emptyList(), result, errors, "layer $i")
        }
        return result
    }

    private fun expand(entry: JSONObject, using: List<String>, into: MutableList<JSONObject>, errors: MutableList<String>, where: String) {
        if (!entry.has(USE)) {
            into += entry
            return
        }
        val name = entry.optString(USE)
        val template = own?.optJSONObject(name)
        when {
            template == null         -> return errors.plusAssign("$where: no template '$name' in the templates of the design")
            name in using            -> return errors.plusAssign("$where: template '$name' uses itself")
            using.size >= MAX_DEPTH  -> return errors.plusAssign("$where: templates nested more than $MAX_DEPTH deep")
        }
        val spec = template.optJSONObject(PARAMS) ?: JSONObject()
        val params = bind(spec, entry, name, errors, where)
        // A template may still have a parameter of that name; then it is that parameter
        val wff = entry.opt(WFF)?.takeIf { !spec.has(WFF) }
        val layers = template.optJSONArray(LAYERS) ?: return
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            val filled = fill(layer, params, name, errors, where) as JSONObject
            // A layer that is only there for some values of the parameters, like the bright line of a trace
            if (filled.has(IF)) {
                val condition = filled.remove(IF)
                val keep = when (condition) {
                    is Boolean -> condition
                    is Number  -> condition.toDouble() != 0.0
                    is String  -> runCatching { Formula.parse(condition) { null }.takeIf { it.isConstant }?.eval(DoubleArray(0)) }.getOrNull()
                        ?.let { it != 0.0 && !it.isNaN() } ?: false.also { errors += "$where ($name): 'if' is not a formula of fixed numbers: $condition" }
                    else       -> true
                }
                if (!keep) continue
            }
            if (wff != null && !filled.has(WFF)) filled.put(WFF, copyOf(wff))
            expand(filled, using + name, into, errors, "$where ($name)")
        }
    }

    /** The values of the parameters of template [name] for [entry], in the order the template gives them. */
    private fun bind(spec: JSONObject, entry: JSONObject, name: String, errors: MutableList<String>, where: String): Map<String, Any?> {
        val params = LinkedHashMap<String, Any?>()
        entry.keys().forEach { key -> if (key != USE && key != WFF && !spec.has(key)) errors += "$where: '$name' has no parameter '$key'" }
        spec.keys().forEach { key ->
            val declared = spec.opt(key)
            val limits = (declared as? JSONObject)?.takeIf { it.has(DEFAULT) }
            val default = fill(limits?.opt(DEFAULT) ?: declared, params, name, errors, where)
            val given = entry.opt(key)?.takeIf { it != JSONObject.NULL }
            var value: Any? = when {
                given is JSONObject && default is JSONObject -> JSONObject(default.toString()).also { merged -> given.keys().forEach { merged.put(it, given.opt(it)) } }
                given != null                                -> given
                else                                         -> default
            }
            if (limits != null) {
                // A number parameter: a text that is a number counts; anything else keeps the default
                val number = (value as? Number)?.toDouble() ?: (value as? String)?.trim()?.toDoubleOrNull()
                    ?: (default as? Number)?.toDouble().also { errors += "$where: '$name' parameter '$key' is not a number: $value" }
                value = number?.let { limited(it, limits, params, name, errors, where) }
            }
            params[key] = value
        }
        return params
    }

    /** [value] cut to the limits of [limits], whole when it says `int`. */
    private fun limited(value: Double, limits: JSONObject, params: Map<String, Any?>, name: String, errors: MutableList<String>, where: String): Number {
        var v = value
        fun bound(key: String): Double? = when (val limit = fill(limits.opt(key), params, name, errors, where)) {
            is Number -> limit.toDouble()
            is String -> runCatching { Formula.parse(limit) { null }.takeIf { it.isConstant }?.eval(DoubleArray(0)) }.getOrNull()
            else      -> null
        }
        val isInt = limits.optBoolean(INT)
        if (isInt) v = v.toLong().toDouble()
        bound(MIN)?.let { if (v < it) v = it }
        bound(MAX)?.let { if (v > it) v = it }
        return if (isInt) v.toLong() else v
    }

    /** [value] with the parameters filled in, see the class notes. A new object: [value] is not changed. */
    private fun fill(value: Any?, params: Map<String, Any?>, name: String, errors: MutableList<String>, where: String): Any? = when (value) {
        is JSONObject -> JSONObject().also { copy -> value.keys().forEach { copy.put(it, fill(value.opt(it), params, name, errors, where)) } }
        is JSONArray  -> JSONArray().also { copy -> for (i in 0 until value.length()) copy.put(fill(value.opt(i), params, name, errors, where)) }
        is String     -> fillText(value, params, name, errors, where)
        else          -> value
    }

    private fun fillText(text: String, params: Map<String, Any?>, name: String, errors: MutableList<String>, where: String): Any? {
        if ('$' !in text) return text
        WHOLE.matchEntire(text)?.let { match ->
            return lookUp(match.groupValues[1], match.groupValues[2], params)?.let { copyOf(it) } ?: text.also { errors += "$where: '$name' has no parameter '${match.value}'" }
        }
        return PART.replace(text) { match ->
            val found = lookUp(match.groupValues[1], match.groupValues[2], params)
            if (found == null) {
                errors += "$where: '$name' has no parameter '${match.value}'"
                match.value
            } else textOf(found)
        }
    }

    /** The parameter [key], or its entry [field] when given; null when there is none. */
    private fun lookUp(key: String, field: String, params: Map<String, Any?>): Any? {
        if (!params.containsKey(key)) return null
        val value = params[key]
        return if (field.isEmpty()) value else (value as? JSONObject)?.opt(field)
    }

    private fun copyOf(value: Any): Any = when (value) {
        is JSONObject -> JSONObject(value.toString())
        is JSONArray  -> JSONArray(value.toString())
        else          -> value
    }

    private fun textOf(value: Any): String = when (value) {
        is Number -> numberText(value)
        else      -> value.toString()
    }

    companion object {

        const val USE = "use"
        const val PARAMS = "params"
        const val LAYERS = "layers"
        const val DEFAULT = "default"
        const val MIN = "min"
        const val MAX = "max"
        const val INT = "int"
        const val IF = "if"
        const val WFF = "wff"

        /** How deep a template may use other templates. */
        const val MAX_DEPTH = 4

        private val WHOLE = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)(?:\.([A-Za-z_][A-Za-z0-9_]*))?""")
        private val PART = WHOLE

        /** [value] as a plain decimal, in brackets when it is negative, so it can stand anywhere in a formula. */
        fun numberText(value: Number): String {
            if (value is Double && !value.isFinite() || value is Float && !value.isFinite()) return "0"
            val plain = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            return if (plain.startsWith("-")) "($plain)" else plain
        }
    }
}
