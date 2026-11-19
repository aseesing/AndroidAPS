// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.Formula
import app.aaps.wear.watchfaces.animation.LayerVariables
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The printed WFF expression gives the same value as our formula: both are worked out at many
 * sample times and battery levels (design doc 10.2).
 */
internal class WffExpressionPrinterTest {

    private val variables = LayerVariables()

    private fun parse(text: String): Formula = Formula.parse(text) { name -> variables.index(name) }

    private val printer = WffExpressionPrinter { index -> nameOf(index)?.let { WffExpressionPrinter.liveName(it) } }

    private fun nameOf(index: Int): String? =
        LayerVariables.FIXED.getOrNull(index) ?: variables.valueKeys.getOrNull(variables.valueIndices.indexOf(index))?.let { LayerVariables.VALUE_PREFIX + it }

    /** Our formula and the printed one at [samples] random clock times and battery levels. */
    private fun assertSame(text: String, samples: Int = 400) {
        val formula = parse(text)
        val printed = printer.print(formula)
        assertWithMessage("'$text' should be printable").that(printed).isNotNull()
        val random = Random(text.hashCode())
        repeat(samples) {
            val hour = random.nextInt(24).toDouble()
            val min = random.nextInt(60).toDouble()
            val sec = random.nextInt(60) + random.nextInt(1000) / 1000.0
            val battery = random.nextInt(101).toDouble()
            val values = DoubleArray(variables.size)
            values[LayerVariables.HOUR] = hour
            values[LayerVariables.MIN] = min
            values[LayerVariables.SEC] = sec
            variables.valueKeys.forEachIndexed { i, key -> if (key == "watch_battery") values[variables.valueIndices[i]] = battery }
            val ours = formula.eval(values)
            val wff = WffEval(mapOf("HOUR_0_23" to hour, "MINUTE" to min, "SECOND_MILLISECOND" to sec, "BATTERY_PERCENT" to battery)).eval(printed!!)
            if (ours.isNaN()) return@repeat
            assertWithMessage("'$text' at $hour:$min:$sec battery $battery printed as $printed").that(abs(ours - wff)).isAtMost(1e-9 * maxOf(1.0, abs(ours)))
        }
    }

    @Test fun arithmeticAndCompare() = assertSame("hour * 30 + min / 2 - 1.5 > 100 && v_watch_battery <= 20 || !(sec < 30)")
    @Test fun modOfNegativeNumbers() = assertSame("(sec - 45) % 7 + mod(30 - min, 11)")
    @Test fun fractOfNegativeNumbers() = assertSame("fract(sec / 7 - 4)")
    @Test fun choices() = assertSame("hour >= 12 ? if(min > 30, 1, 2) : -3")
    @Test fun minMax() = assertSame("min(sec, min, 40) + max(hour, 6) * min(v_watch_battery, 50, 30, 70)")
    @Test fun clampWithEndsInAnyOrder() = assertSame("clamp(sec * 3, 100, 20) + clamp(min, 10, 40)")
    @Test fun sign() = assertSame("sign(sec - 30) * 5 + sign(0 * sec)")
    @Test fun stepMixSmoothstep() = assertSame("step(30, sec) + mix(1, 9, min / 60) + smoothstep(10, 50, sec) + smoothstep(20, 20, sec)")
    @Test fun windowPingpongEase() = assertSame("window(sec, 10, 20) + window(sec, 30, 0) + pingpong(sec, 7) + easeInOut(min / 60)")
    @Test fun atan2AllQuadrants() = assertSame("atan2(sec - 30, min - 30) + atan2(0 * sec, min - 30) + atan2(sec - 30, 0 * min)")
    @Test fun trigonometryAndPowers() = assertSame("sin(sec) + cos(min) * tan(0.3) + pow(hour + 1, 0.5) + sqrt(sec) + exp(-sec / 30) + log(min + 1) + abs(sec - 30)")
    @Test fun rounding() = assertSame("round(sec - 30) + floor(-sec / 7) + ceil(sec / 7) + round(min / 2 - 15)")
    @Test fun newWffNames() = assertSame("asin(sec / 60) + acos(min / 60) + atan(hour) + log2(sec + 1) + log10(min + 1) + cbrt(sec - 30) + expm1(sec / 60) + deg(sec) + rad(min)")
    @Test fun clockSources() = assertSame("[HOUR_1_12] * 100 + [MINUTE_SECOND] + [SECONDS_IN_DAY] / 3600 + [HOUR_0_11_MINUTE]")

    @Test
    fun numbersHaveNoExponentAndNegativesAreBracketed() {
        assertThat(WffExpressionPrinter.number(3.0)).isEqualTo("3")
        assertThat(WffExpressionPrinter.number(1e-7)).isEqualTo("0.0000001")
        assertThat(WffExpressionPrinter.number(-2.5)).isEqualTo("(-2.5)")
        assertThat(WffExpressionPrinter.number(Double.NaN)).isNull()
    }

    @Test
    fun theWakeAndRandomNumbersCannotBePrinted() {
        assertThat(printer.print(parse("sin(t) * 3"))).isNull()
        assertThat(printer.print(parse("noise(sec)"))).isNull()
        assertThat(printer.print(parse("missing(v_watch_battery)"))).isNull()
        assertThat(printer.print(parse("len('ab' & sec)"))).isNull()
    }

    @Test
    fun aFixedFormulaIsOneNumber() {
        assertThat(printer.print(parse("2 * 3 + noise(1)"))).doesNotContain("noise")
    }
}
