// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.Formula
import app.aaps.wear.watchfaces.animation.FormulaVisitor
import app.aaps.wear.watchfaces.animation.LayerVariables
import java.math.BigDecimal
import kotlin.math.abs

/**
 * A formula of a design as Watch Face Format (WFF) expressions: [value] is its number wherever
 * [valid] is true.
 *
 * [valid] is false where our engine gives no number (NaN) or an endless one (Infinity), for example
 * at `x / 0`, `mod(x, 0)` or `sqrt(-1)`. WFF has neither: it gives 0 for `x / 0` (design doc 11.1),
 * and 0 is a real place, angle or alpha. So the writer must use [valid] to do what our engine does
 * with such a value: not draw a layer at that `x` or `y` (a `Condition` on [valid]), no turn for a
 * `rotation`, no scale for a `scale` and 0 for an `alpha` ([orElse]), hidden for `visible` ([truth]).
 * A null [valid]: the formula always gives a number.
 *
 * Our engine goes on with Infinity in a few places where this treats it as no number: an endless
 * `alpha` or `visible` shows the layer, `clamp(x / 0, 0, 1)` is 1, `1 / (x / 0)` is 0, and a compare
 * with it can be true. Here all of these are no number. When drawn, both are mostly the same.
 */
class WffExpression(val value: String, val valid: String?) {

    /** The value, or [fallback] where ours gives no number: for example 0 for a `rotation` or an `alpha`, 1 for a `scale`. */
    fun orElse(fallback: String): String = if (valid == null) value else "($valid ? $value : $fallback)"

    /** 1 where ours counts the formula as true (a number that is not 0), else 0: for `visible` or a `Condition`. */
    val truth: String get() = if (valid == null) "($value != 0)" else "($valid && ($value != 0))"
}

/**
 * Writes a [Formula] of a design as WFF expressions with the same value, for a live `Transform` or
 * `Condition` of a converted face. Null when the formula cannot be written: then the converter bakes
 * that part.
 *
 * The meanings follow the WFF runtime as measured on the Wear OS 6 emulator (design doc 11.1):
 * WFF `%` keeps the sign of the left side, so ours is written out with `floor`. Functions WFF lacks
 * (`min`, `max`, `mix`, `step`, `atan2`, ...) are written out. `noise`, `hash`, `missing` and all
 * text parts cannot be written.
 *
 * Where ours can give no number (a division, `mod`, `sqrt`, `log`, `asin`, `acos`, `pow`), the
 * result says when, see [WffExpression.valid]. A function with no number in its domain gets a safe
 * value in WFF instead, so WFF never works out a NaN itself. Parts that turn no number into a number,
 * as ours does, write that out: a compare with it is 0 (also `!=`), `!` of it is 1, `if` and `&&`
 * count it as false, and `step` gives 0.
 *
 * Every part is put in brackets, so no WFF operator strength can change the meaning.
 *
 * @param variable the WFF text of the variable at an index of the formula's name lookup, or null
 *   when that variable cannot be live (for example `t`). [liveName] gives it for the names WFF has.
 */
class WffExpressionPrinter(private val variable: (Int) -> String?) {

    /** [formula] as WFF expressions, or null when it cannot be written. */
    fun print(formula: Formula): WffExpression? = formula.visit(Writer())

    private inner class Writer : FormulaVisitor<WffExpression?> {

        override fun number(value: Double): WffExpression = Companion.number(value)?.let { WffExpression(it, null) } ?: NO_NUMBER
        override fun variable(index: Int): WffExpression? = this@WffExpressionPrinter.variable(index)?.let { WffExpression(it, null) }
        override fun negate(a: WffExpression?): WffExpression? = a?.let { WffExpression("(-${it.value})", it.valid) }

        // Ours: `!x` is 1 when x is 0 or no number
        override fun not(a: WffExpression?): WffExpression? = a?.let { WffExpression("(!${test(it)})", null) }

        // Ours takes `b` when `c` is 0 or no number
        override fun choose(c: WffExpression?, a: WffExpression?, b: WffExpression?): WffExpression? {
            if (c == null || a == null || b == null) return null
            val test = test(c)
            val valid = if (a.valid == null && b.valid == null) null else "($test ? ${a.valid ?: "1"} : ${b.valid ?: "1"})"
            return WffExpression("($test ? ${a.value} : ${b.value})", valid)
        }

        override fun operator(op: String, a: WffExpression?, b: WffExpression?): WffExpression? {
            if (a == null || b == null) return null
            val x = a.value
            val y = b.value
            return when (op) {
                "&&", "||"                         -> WffExpression("(${test(a)} $op ${test(b)})", null)
                // A compare with no number is false in ours, also `!=`
                "<", "<=", ">", ">=", "==", "!=" -> WffExpression(zeroUnless(all(a.valid, b.valid), "($x $op $y)"), null)
                "/"                                -> WffExpression("($x / $y)", all(a.valid, b.valid, "($y != 0)"))
                "%"                                -> WffExpression(mod(x, y), all(a.valid, b.valid, "($y != 0)"))
                else                               -> WffExpression("($x $op $y)", all(a.valid, b.valid))
            }
        }

        override fun function(name: String, args: List<WffExpression?>): WffExpression? {
            if (args.any { it == null }) return null
            val a = args.map { it!!.value }
            val valid = all(*args.map { it!!.valid }.toTypedArray())
            val value = when (name) {
                in SAME_NAME  -> domain(name, a)?.let { (inside, safe) -> return WffExpression(safe, all(valid, inside)) }
                    ?: "$name(${a.joinToString(", ")})"

                "mod"         -> return WffExpression(mod(a[0], a[1]), all(valid, "(${a[1]} != 0)"))
                "min"         -> a.reduce { x, y -> "($x < $y ? $x : $y)" }
                "max"         -> a.reduce { x, y -> "($x > $y ? $x : $y)" }
                // Ours takes the two ends in any order
                "clamp"       -> "clamp(${a[0]}, (${a[1]} < ${a[2]} ? ${a[1]} : ${a[2]}), (${a[1]} > ${a[2]} ? ${a[1]} : ${a[2]}))"
                "sign"        -> "(${a[0]} > 0 ? 1 : (${a[0]} < 0 ? -1 : 0))"
                // A compare: 0 with no number, as in ours
                "step"        -> return WffExpression(zeroUnless(valid, "(${a[1]} >= ${a[0]} ? 1 : 0)"), null)
                "mix"         -> "(${a[0]} + (${a[1]} - ${a[0]}) * ${a[2]})"
                "smoothstep"  -> {
                    val u = "clamp((${a[2]} - ${a[0]}) / (${a[1]} - ${a[0]}), 0, 1)"
                    "(${a[0]} == ${a[1]} ? (${a[2]} >= ${a[0]} ? 1 : 0) : ($u * $u * (3 - 2 * $u)))"
                }

                "window"      -> "(${a[2]} <= 0 ? (${a[0]} >= ${a[1]} ? 1 : 0) : clamp((${a[0]} - ${a[1]}) / ${a[2]}, 0, 1))"
                "pingpong"    -> "(${a[1]} > 0 ? (${a[1]} - abs(${mod(a[0], "(2 * ${a[1]})")} - ${a[1]})) : 0)"
                "easeInOut"   -> "(0.5 - 0.5 * cos($PI * clamp(${a[0]}, 0, 1)))"
                "atan2"       -> atan2(a[0], a[1])
                else          -> return null // noise, hash, missing: the part is baked
            }
            return WffExpression(value, valid)
        }

        override fun source(name: String): WffExpression = WffExpression("[$name]", null)
        override fun other(kind: String): WffExpression? = null

        /** 1 when [e] is a number that is not 0, else 0: how ours tests a value in `if`, `&&`, `||` and `!`. */
        private fun test(e: WffExpression): String = if (e.valid == null) e.value else "(${e.valid} && ${e.value})"

        /** [compare] where [valid] is true (or always when it is null), else 0. */
        private fun zeroUnless(valid: String?, compare: String): String = if (valid == null) compare else "($valid ? $compare : 0)"

        /** Ours `a % b`: never negative for a positive b, unlike WFF's. */
        private fun mod(a: String, b: String) = "($a - $b * floor($a / $b))"

        private fun atan2(y: String, x: String): String {
            val base = "atan($y / $x)"
            return "($x > 0 ? $base : ($x < 0 ? ($y >= 0 ? $base + $PI : $base - $PI) : ($y > 0 ? $HALF_PI : ($y < 0 ? -$HALF_PI : 0))))"
        }

        /**
         * For a function of [SAME_NAME] that gives no number for some values: when ours gives a number,
         * and the call with a safe value outside that, so WFF never works out a NaN. Null for the others.
         */
        private fun domain(name: String, a: List<String>): Pair<String, String>? {
            val x = a[0]
            return when (name) {
                "sqrt"                  -> "($x >= 0)".let { it to "$name(($it ? $x : 0))" }
                "log", "log2", "log10"  -> "($x > 0)".let { it to "$name(($it ? $x : 1))" }
                "asin", "acos"          -> "($x >= (-1) && $x <= 1)".let { it to "$name(($it ? $x : 0))" }
                // A negative number to a broken power is NaN, 0 to a negative power endless
                "pow"                   -> {
                    val y = a[1]
                    "($x > 0 || ($x == 0 && $y >= 0) || ($x < 0 && floor($y) == $y))".let { it to "pow(($it ? $x : 1), ($it ? $y : 1))" }
                }

                else                    -> null
            }
        }
    }

    companion object {

        /** The functions WFF has with exactly our meaning (radians; `round` half up). */
        private val SAME_NAME = setOf(
            "sin", "cos", "tan", "asin", "acos", "atan", "abs", "sqrt", "exp", "log", "pow", "floor", "ceil", "round",
            "log2", "log10", "cbrt", "expm1", "deg", "rad", "fract"
        )

        private val PI = number(Math.PI)!!
        private val HALF_PI = number(Math.PI / 2)!!

        /** A fixed formula that is no number, like `1 / 0`. */
        private val NO_NUMBER = WffExpression("0", "0")

        /** All of [tests] (null ones left out) joined with `&&`, or null when there is none. */
        private fun all(vararg tests: String?): String? {
            val given = tests.filterNotNull().distinct()
            return when (given.size) {
                0    -> null
                1    -> given[0]
                else -> given.joinToString(" && ", "(", ")")
            }
        }

        /** [value] as WFF writes a number: no exponent, no ".0"; null for NaN and infinity. */
        fun number(value: Double): String? {
            if (value.isNaN() || value.isInfinite()) return null
            val plain = if (value == Math.rint(value) && abs(value) < 1e15) value.toLong().toString() else BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            return if (value < 0) "($plain)" else plain
        }

        /**
         * The WFF source for one of our variable names, when WFF has the same value live: the clock
         * and the watch's own values. Null for every other name.
         */
        fun liveName(name: String): String? = when (name) {
            "sec"                                                    -> "[SECOND_MILLISECOND]"
            "min"                                                    -> "[MINUTE]"
            "hour"                                                   -> "[HOUR_0_23]"
            LayerVariables.VALUE_PREFIX + "watch_battery"            -> "[BATTERY_PERCENT]"
            LayerVariables.VALUE_PREFIX + "steps"                    -> "[STEP_COUNT]"
            LayerVariables.VALUE_PREFIX + "heart_rate"               -> "[HEART_RATE]"
            else                                                     -> null
        }
    }
}
