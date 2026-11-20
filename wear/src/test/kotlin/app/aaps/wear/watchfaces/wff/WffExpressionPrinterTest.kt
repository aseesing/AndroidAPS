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
 * sample times and battery levels (design doc 10.2). Where ours gives no number (NaN or Infinity),
 * the printed `valid` must be false.
 */
internal class WffExpressionPrinterTest {

    private val variables = LayerVariables()

    private fun parse(text: String): Formula = Formula.parse(text) { name -> variables.index(name) }

    private val printer = WffExpressionPrinter { index -> nameOf(index)?.let { WffExpressionPrinter.liveName(it) } }

    private fun nameOf(index: Int): String? =
        LayerVariables.FIXED.getOrNull(index) ?: variables.valueKeys.getOrNull(variables.valueIndices.indexOf(index))?.let { LayerVariables.VALUE_PREFIX + it }

    /**
     * Our formula and the printed one at [samples] random clock times and battery levels. Returns at
     * how many samples ours gave no number.
     */
    private fun compare(text: String, samples: Int = 400): Int {
        val formula = parse(text)
        val printed = printer.print(formula)
        assertWithMessage("'$text' should be printable").that(printed).isNotNull()
        val random = Random(text.hashCode())
        var noNumber = 0
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
            val wff = WffEval(mapOf("HOUR_0_23" to hour, "MINUTE" to min, "SECOND_MILLISECOND" to sec, "BATTERY_PERCENT" to battery))
            val at = "'$text' at $hour:$min:$sec battery $battery printed as ${printed!!.value} valid ${printed.valid}"
            val valid = printed.valid?.let { wff.eval(it) != 0.0 } ?: true
            if (!ours.isFinite()) {
                noNumber++
                assertWithMessage("$at: ours is $ours, so it should not be valid").that(valid).isFalse()
                // What the writer does with it: the fallback, and false
                assertWithMessage(at).that(wff.eval(printed.orElse("(-7)"))).isEqualTo(-7.0)
                assertWithMessage(at).that(wff.eval(printed.truth)).isEqualTo(0.0)
                return@repeat
            }
            assertWithMessage("$at: ours is $ours, so it should be valid").that(valid).isTrue()
            val value = wff.eval(printed.value)
            assertWithMessage(at).that(abs(ours - value)).isAtMost(1e-9 * maxOf(1.0, abs(ours)))
            assertWithMessage(at).that(wff.eval(printed.orElse("(-7)"))).isEqualTo(value)
            assertWithMessage(at).that(wff.eval(printed.truth)).isEqualTo(if (ours != 0.0) 1.0 else 0.0)
        }
        return noNumber
    }

    private fun assertSame(text: String) {
        compare(text)
    }

    /** As [assertSame], for a formula that must give no number at some of the samples. */
    private fun assertSameWithNoNumber(text: String) {
        assertWithMessage("'$text' should give no number at some samples").that(compare(text)).isGreaterThan(0)
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

    // ---- where ours gives no number: x / 0 is 0 in WFF, but must not be a number here ----

    @Test fun divisionByZero() = assertSameWithNoNumber("60 / (min - 30) + hour")
    @Test fun zeroByZero() = assertSameWithNoNumber("(min - 30) / (min - 30) * 5")
    @Test fun modByZero() = assertSameWithNoNumber("mod(sec, min - 30) + hour % (min - 30)")
    @Test fun noNumberGoesThroughFunctions() = assertSameWithNoNumber("min(60 / (min - 30), 5) + clamp(sec, 0, 10) * sin(-(hour / (min - 30)))")
    @Test fun outsideTheDomain() = assertSameWithNoNumber("sqrt(min - 30) + log(min - 29) + log2(min - 20) + log10(min - 40) + asin((min - 30) / 20) + acos((30 - min) / 20) + pow(min - 30, 0.5)")
    @Test fun powersOfZeroAndNegatives() = assertSameWithNoNumber("pow(min - 30, -1) + pow(min - 30, 2) + pow(min - 30, 0)")

    // Here ours turns no number (0 / 0, NaN) into a number, so the printed form must too
    @Test fun compareWithNoNumberIsFalse() = assertSame("((min - 30) / (min - 30) >= 1) + ((min - 30) / (min - 30) != 7) * 2 + ((min - 30) / (min - 30) == 1) * 4")
    @Test fun notOfNoNumberIsOne() = assertSame("!((min - 30) / (min - 30)) + !(min / 0 * 0) * 2")
    @Test fun ifTakesTheOtherWayForNoNumber() = assertSame("if((min - 30) / (min - 30), 5, 7) + ((min - 30) / (min - 30) && 1) * 10 + ((min - 30) / (min - 30) || 0) * 100")
    @Test fun stepOfNoNumberIsZero() = assertSame("step((min - 30) / (min - 30), 1) + step(1, (min - 30) / (min - 30)) * 2")
    @Test fun onlyTheWayTakenCounts() = assertSame("min == 30 ? 4 : 60 / (min - 30)")

    @Test
    fun aFixedNoNumberIsNeverValid() {
        val printed = printer.print(parse("1 / 0 + sec"))!!
        assertThat(WffEval(mapOf("SECOND_MILLISECOND" to 5.0)).eval(printed.valid!!)).isEqualTo(0.0)
    }

    @Test
    fun aFormulaWithoutDivisionNeedsNoTest() {
        assertThat(printer.print(parse("sin(sec) * 3 + min"))!!.valid).isNull()
    }

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
        assertThat(printer.print(parse("2 * 3 + noise(1)"))!!.value).doesNotContain("noise")
    }
}
