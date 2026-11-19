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
 * Writes a [Formula] of a design as a Watch Face Format (WFF) expression with the same value, for
 * a live `Transform` or `Condition` of a converted face. Null when the formula cannot be written:
 * then the converter bakes that part.
 *
 * The meanings follow the WFF runtime as measured on the Wear OS 6 emulator (design doc 11.1):
 * WFF `%` keeps the sign of the left side and WFF `fract` cuts towards 0, so ours are written out
 * with `floor`. Functions WFF lacks (`min`, `max`, `mix`, `step`, `atan2`, ...) are written out.
 * `noise`, `hash`, `missing` and all text parts cannot be written.
 *
 * Every part is put in brackets, so no WFF operator strength can change the meaning.
 *
 * One difference stays: `a / 0` is 0 on WFF and no number (NaN) in ours. A compare with no number
 * is false in ours, and with 0 mostly too, so a design rarely sees it.
 *
 * @param variable the WFF text of the variable at an index of the formula's name lookup, or null
 *   when that variable cannot be live (for example `t`). [liveName] gives it for the names WFF has.
 */
class WffExpressionPrinter(private val variable: (Int) -> String?) {

    /** [formula] as a WFF expression, or null when it cannot be written. */
    fun print(formula: Formula): String? = formula.visit(Writer())

    private inner class Writer : FormulaVisitor<String?> {

        override fun number(value: Double): String? = Companion.number(value)
        override fun variable(index: Int): String? = this@WffExpressionPrinter.variable(index)
        override fun negate(a: String?): String? = a?.let { "(-$it)" }
        override fun not(a: String?): String? = a?.let { "(!$it)" }
        override fun choose(c: String?, a: String?, b: String?): String? = if (c == null || a == null || b == null) null else "($c ? $a : $b)"

        override fun operator(op: String, a: String?, b: String?): String? {
            if (a == null || b == null) return null
            return when (op) {
                "%"  -> mod(a, b)
                else -> "($a $op $b)"
            }
        }

        override fun function(name: String, args: List<String?>): String? {
            if (args.any { it == null }) return null
            val a = args.map { it!! }
            return when (name) {
                in SAME_NAME  -> "$name(${a.joinToString(", ")})"
                "fract"       -> "(${a[0]} - floor(${a[0]}))"
                "mod"         -> mod(a[0], a[1])
                "min"         -> a.reduce { x, y -> "($x < $y ? $x : $y)" }
                "max"         -> a.reduce { x, y -> "($x > $y ? $x : $y)" }
                // Ours takes the two ends in any order
                "clamp"       -> "clamp(${a[0]}, (${a[1]} < ${a[2]} ? ${a[1]} : ${a[2]}), (${a[1]} > ${a[2]} ? ${a[1]} : ${a[2]}))"
                "sign"        -> "(${a[0]} > 0 ? 1 : (${a[0]} < 0 ? -1 : 0))"
                "step"        -> "(${a[1]} >= ${a[0]} ? 1 : 0)"
                "mix"         -> "(${a[0]} + (${a[1]} - ${a[0]}) * ${a[2]})"
                "smoothstep"  -> {
                    val u = "clamp((${a[2]} - ${a[0]}) / (${a[1]} - ${a[0]}), 0, 1)"
                    "(${a[0]} == ${a[1]} ? (${a[2]} >= ${a[0]} ? 1 : 0) : ($u * $u * (3 - 2 * $u)))"
                }

                "window"      -> "(${a[2]} <= 0 ? (${a[0]} >= ${a[1]} ? 1 : 0) : clamp((${a[0]} - ${a[1]}) / ${a[2]}, 0, 1))"
                "pingpong"    -> "(${a[1]} > 0 ? (${a[1]} - abs(${mod(a[0], "(2 * ${a[1]})")} - ${a[1]})) : 0)"
                "easeInOut"   -> "(0.5 - 0.5 * cos($PI * clamp(${a[0]}, 0, 1)))"
                "atan2"       -> atan2(a[0], a[1])
                else          -> null // noise, hash, missing: the part is baked
            }
        }

        override fun source(name: String): String = "[$name]"
        override fun other(kind: String): String? = null

        /** Ours `a % b`: never negative for a positive b, unlike WFF's. */
        private fun mod(a: String, b: String) = "($a - $b * floor($a / $b))"

        private fun atan2(y: String, x: String): String {
            val base = "atan($y / $x)"
            return "($x > 0 ? $base : ($x < 0 ? ($y >= 0 ? $base + $PI : $base - $PI) : ($y > 0 ? $HALF_PI : ($y < 0 ? -$HALF_PI : 0))))"
        }
    }

    companion object {

        /** The functions WFF has with exactly our meaning (radians; `round` half up). */
        private val SAME_NAME = setOf(
            "sin", "cos", "tan", "asin", "acos", "atan", "abs", "sqrt", "exp", "log", "pow", "floor", "ceil", "round",
            "log2", "log10", "cbrt", "expm1", "deg", "rad"
        )

        private val PI = number(Math.PI)!!
        private val HALF_PI = number(Math.PI / 2)!!

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
