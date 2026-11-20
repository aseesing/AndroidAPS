// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.AnimLayerSpec
import app.aaps.wear.watchfaces.animation.Formula
import app.aaps.wear.watchfaces.animation.FormulaException
import app.aaps.wear.watchfaces.animation.LayerProp
import app.aaps.wear.watchfaces.animation.LayerVariables
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import org.json.JSONObject
import java.time.LocalDate
import java.time.MonthDay

/**
 * The days of the year on which a wake layer shows, for a layer that only shows on some dates (Aurora
 * Town's Santa shows at Christmas). WFF can show its frames by a condition on the date; its frames
 * are made on such a day.
 *
 * It works out the layer's `visible` with the engine's own formulas, with its `let` names written
 * out, at moments of the wake, for each day of a leap year. The options of the design count with their
 * default choice: WFF cannot read the options of the watch app.
 */
object WffShowDays {

    /**
     * The days [layer] of [animation] shows on, or null when it does not depend on the date alone (it
     * also reads another value, or its `visible` cannot be worked out here). An empty set: it never
     * shows with the default options.
     */
    fun of(design: JSONObject, animation: WakeAnimationSpec, layer: AnimLayerSpec): Set<MonthDay>? {
        val visible = layer.props[LayerProp.VISIBLE] ?: return null
        // The layer's own names come first, as in the engine (`LayerVariables.index`)
        val lets = animation.lets.toMap() + layer.lets.toMap()
        val text = inline(visible, lets) ?: return null
        if (!DATE.containsMatchIn(text)) return null
        val variables = LayerVariables()
        val formula = try {
            Formula.parse(text) { name -> variables.index(name) }
        } catch (_: FormulaException) {
            return null
        }
        val values = DoubleArray(variables.size + 1)
        val options = defaults(design)
        // A layer that also reads another value is not a layer of some days: it has no days
        if (variables.valueKeys.any { !it.startsWith(OPTION) && it !in DATE_KEYS }) return null
        variables.valueKeys.forEachIndexed { i, key -> values[variables.valueIndices[i]] = if (key.startsWith(OPTION)) options[key.removePrefix(OPTION)] ?: 0.0 else 0.0 }
        fun set(key: String, value: Double) = variables.valueKeys.indexOf(key).takeIf { it >= 0 }?.let { values[variables.valueIndices[it]] = value }
        val lengthMs = animation.durationMs.toDouble()
        val days = HashSet<MonthDay>()
        var all = true
        var date = LocalDate.of(LEAP_YEAR, 1, 1)
        while (date.year == LEAP_YEAR) {
            set("month", date.monthValue.toDouble())
            set("day", date.dayOfMonth.toDouble())
            set("day_name", date.dayOfWeek.value.toDouble())
            values[LayerVariables.LEN] = lengthMs
            values[LayerVariables.HOUR] = 12.0
            val shows = (0..SAMPLES).any { k ->
                val t = lengthMs * k / SAMPLES
                values[LayerVariables.T] = t / 1000
                values[LayerVariables.MS] = t
                values[LayerVariables.ENV] = 1.0
                formula.eval(values).let { it != 0.0 && !it.isNaN() }
            }
            if (shows) days += MonthDay.from(date) else all = false
            date = date.plusDays(1)
        }
        return if (all) null else days
    }

    /** [days] as a WFF expression on `[MONTH]` and `[DAY]`, one range of days after another. */
    fun condition(days: Set<MonthDay>): String = days.sorted().fold(ArrayList<Pair<MonthDay, MonthDay>>()) { ranges, day ->
        val last = ranges.lastOrNull()
        if (last != null && last.second.month == day.month && last.second.dayOfMonth + 1 == day.dayOfMonth) ranges[ranges.size - 1] = last.first to day
        else ranges += day to day
        ranges
    }.joinToString(" || ") { (from, to) ->
        if (from == to) "([MONTH] == ${from.monthValue} && [DAY] == ${from.dayOfMonth})"
        else "([MONTH] == ${from.monthValue} && [DAY] >= ${from.dayOfMonth} && [DAY] <= ${to.dayOfMonth})"
    }

    /** [text] with the names of [lets] written out, in brackets; null when they refer to each other in a circle. */
    private fun inline(text: String, lets: Map<String, String>, depth: Int = 0): String? {
        if (depth > MAX_DEPTH) return null
        var failed = false
        val out = NAME.replace(text) { m ->
            val body = lets[m.value]
            if (body == null) m.value else inline(body, lets, depth + 1)?.let { "($it)" } ?: run { failed = true; m.value }
        }
        return if (failed) null else out
    }

    /** The default choice of each option of [design], by option name. */
    private fun defaults(design: JSONObject): Map<String, Double> {
        val options = design.optJSONArray("options") ?: return emptyMap()
        return (0 until options.length()).mapNotNull { options.optJSONObject(it) }.associate { it.optString("name") to it.optInt("default", 0).toDouble() }
    }

    private const val LEAP_YEAR = 2028
    private const val SAMPLES = 60
    private const val MAX_DEPTH = 10
    private const val OPTION = "opt_"
    private val DATE_KEYS = setOf("month", "day", "day_name")
    private val DATE = Regex("\\bv_(month|day|day_name)\\b")

    /** A name, not right after a dot or a quote (a part of a number or a text). */
    private val NAME = Regex("(?<![.'\"\\w])[A-Za-z_][A-Za-z0-9_]*")
}
