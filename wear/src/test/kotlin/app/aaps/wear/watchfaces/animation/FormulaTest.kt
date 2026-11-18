// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** The formula language of the animated layers: what it reads, what it works out and what it refuses. */
class FormulaTest {

    /** The variables of the tests: `t`, `x`, `y` and `nan` (always NaN). */
    private val names = listOf("t", "x", "y", "nan")

    private fun parse(text: String): Formula = Formula.parse(text) { name -> names.indexOf(name).takeIf { it >= 0 } }

    private fun eval(text: String, t: Double = 0.0, x: Double = 0.0, y: Double = 0.0): Double =
        parse(text).eval(doubleArrayOf(t, x, y, Double.NaN))

    private fun assertError(text: String, part: String) {
        val e = assertThrows<FormulaException> { parse(text) }
        assertWithMessage("error of '$text'").that(e.message).contains(part)
    }

    // ---- order of the operations ----

    @Test
    fun multiplyGoesBeforeAdd() {
        assertThat(eval("2+3*4")).isEqualTo(14.0)
        assertThat(eval("(2+3)*4")).isEqualTo(20.0)
        assertThat(eval("2*3+4")).isEqualTo(10.0)
        assertThat(eval("1 + 6 / 3 - 2")).isEqualTo(1.0)
    }

    @Test
    fun sameLevelGoesFromLeftToRight() {
        assertThat(eval("10-4-3")).isEqualTo(3.0)
        assertThat(eval("16/4/2")).isEqualTo(2.0)
        assertThat(eval("17 % 10 % 4")).isEqualTo(3.0)
    }

    @Test
    fun minusAndNotOfOneValue() {
        assertThat(eval("-2*3")).isEqualTo(-6.0)
        assertThat(eval("-x*3", x = 2.0)).isEqualTo(-6.0)
        assertThat(eval("2*-3")).isEqualTo(-6.0)
        assertThat(eval("--2")).isEqualTo(2.0)
        assertThat(eval("+2")).isEqualTo(2.0)
        assertThat(eval("!0")).isEqualTo(1.0)
        assertThat(eval("!1")).isEqualTo(0.0)
        assertThat(eval("!5")).isEqualTo(0.0)
        assertThat(eval("!!5")).isEqualTo(1.0)
        assertThat(eval("!nan")).isEqualTo(1.0)
        // ! takes only the next value
        assertThat(eval("!0 + 1")).isEqualTo(2.0)
    }

    @Test
    fun comparisonGoesAfterAddAndBeforeAndOr() {
        assertThat(eval("1 + 1 == 2")).isEqualTo(1.0)
        assertThat(eval("1 < 2 && 3 < 2")).isEqualTo(0.0)
        assertThat(eval("1 < 2 || 3 < 2")).isEqualTo(1.0)
        // && goes before ||
        assertThat(eval("1 || 0 && 0")).isEqualTo(1.0)
        assertThat(eval("(1 || 0) && 0")).isEqualTo(0.0)
    }

    @Test
    fun numbersCanHaveADotAndAnExponent() {
        assertThat(eval("1.5")).isEqualTo(1.5)
        assertThat(eval(".5")).isEqualTo(0.5)
        assertThat(eval("2.")).isEqualTo(2.0)
        assertThat(eval("1e3")).isEqualTo(1000.0)
        assertThat(eval("2.5E-1")).isEqualTo(0.25)
        assertThat(eval("1e+2")).isEqualTo(100.0)
        assertThat(eval("pi")).isEqualTo(PI)
    }

    // ---- mod ----

    @Test
    fun modIsNeverNegativeForAPositiveDivisor() {
        assertThat(eval("-1 % 4")).isEqualTo(3.0)
        assertThat(eval("-5 % 4")).isEqualTo(3.0)
        assertThat(eval("-4 % 4")).isEqualTo(0.0)
        assertThat(eval("5 % 4")).isEqualTo(1.0)
        assertThat(eval("5.5 % 2")).isEqualTo(1.5)
        assertThat(eval("mod(-1, 4)")).isEqualTo(3.0)
        assertThat(Formula.mod(-0.25, 1.0)).isEqualTo(0.75)
        for (k in -50..50) {
            val m = Formula.mod(k * 0.37, 2.0)
            assertWithMessage("mod(${k * 0.37}, 2)").that(m).isAtLeast(0.0)
            assertWithMessage("mod(${k * 0.37}, 2)").that(m).isLessThan(2.0)
        }
    }

    @Test
    fun modByZeroIsNaN() {
        assertThat(eval("5 % 0")).isNaN()
        assertThat(eval("mod(5, 0)")).isNaN()
    }

    // ---- comparisons and NaN ----

    @Test
    fun comparisonsGiveOneOrZero() {
        assertThat(eval("x < 20", x = 10.0)).isEqualTo(1.0)
        assertThat(eval("x < 20", x = 20.0)).isEqualTo(0.0)
        assertThat(eval("x <= 20", x = 20.0)).isEqualTo(1.0)
        assertThat(eval("x > 20", x = 20.0)).isEqualTo(0.0)
        assertThat(eval("x >= 20", x = 20.0)).isEqualTo(1.0)
        assertThat(eval("x == 20", x = 20.0)).isEqualTo(1.0)
        assertThat(eval("x != 20", x = 20.0)).isEqualTo(0.0)
        assertThat(eval("x != 20", x = 21.0)).isEqualTo(1.0)
    }

    @Test
    fun aComparisonWithNaNIsFalse() {
        for (op in listOf("<", "<=", ">", ">=", "==", "!=")) {
            assertWithMessage("nan $op 20").that(eval("nan $op 20")).isEqualTo(0.0)
            assertWithMessage("20 $op nan").that(eval("20 $op nan")).isEqualTo(0.0)
            assertWithMessage("nan $op nan").that(eval("nan $op nan")).isEqualTo(0.0)
        }
        // The case of a watch that does not know its battery
        assertThat(eval("nan <= 20")).isEqualTo(0.0)
        assertThat(eval("nan <= 20 && 1")).isEqualTo(0.0)
        assertThat(eval("nan || 0")).isEqualTo(0.0)
        assertThat(eval("nan && 1")).isEqualTo(0.0)
    }

    @Test
    fun nanGoesThroughTheMath() {
        assertThat(eval("nan + 1")).isNaN()
        assertThat(eval("sin(nan)")).isNaN()
        assertThat(eval("min(nan, 1)")).isNaN()
    }

    // ---- if, && and || only work out what they need ----

    @Test
    fun ifIsLazy() {
        val f = Formula.parse("if(t, x, y)") { name ->
            when (name) {
                "t" -> 0; "x" -> 1; "y" -> 2; else -> null
            }
        }
        assertThat(f.eval(doubleArrayOf(1.0, 10.0, 20.0))).isEqualTo(10.0)
        assertThat(f.eval(doubleArrayOf(0.0, 10.0, 20.0))).isEqualTo(20.0)
        assertThat(f.eval(doubleArrayOf(Double.NaN, 10.0, 20.0))).isEqualTo(20.0)
        // The branch not taken is not worked out: x is an index far out of the array and would throw
        val lazy = Formula.parse("if(t, 1, x)") { name ->
            when (name) {
                "t" -> 0; "x" -> 99; else -> null
            }
        }
        assertThat(lazy.eval(doubleArrayOf(1.0))).isEqualTo(1.0)
        assertThrows<ArrayIndexOutOfBoundsException> { lazy.eval(doubleArrayOf(0.0)) }
    }

    @Test
    fun andOrStopEarly() {
        // x is index 99: reading it would throw
        val and = Formula.parse("t && x") { name -> if (name == "t") 0 else if (name == "x") 99 else null }
        assertThat(and.eval(doubleArrayOf(0.0))).isEqualTo(0.0)
        val or = Formula.parse("t || x") { name -> if (name == "t") 0 else if (name == "x") 99 else null }
        assertThat(or.eval(doubleArrayOf(1.0))).isEqualTo(1.0)
    }

    // ---- the functions ----

    /** A sample call of every function, with its value. */
    private val samples: Map<String, List<Pair<String, Double>>> = mapOf(
        "sin" to listOf("sin(pi / 2)" to 1.0),
        "cos" to listOf("cos(0)" to 1.0, "cos(pi)" to -1.0),
        "tan" to listOf("tan(pi / 4)" to 1.0),
        "atan2" to listOf("atan2(1, 1)" to atan2(1.0, 1.0)),
        "abs" to listOf("abs(-3)" to 3.0, "abs(3)" to 3.0),
        "sign" to listOf("sign(-3)" to -1.0, "sign(0)" to 0.0, "sign(2)" to 1.0),
        "sqrt" to listOf("sqrt(9)" to 3.0, "sqrt(2)" to sqrt(2.0)),
        "exp" to listOf("exp(1)" to E),
        "log" to listOf("log(exp(2))" to 2.0),
        "pow" to listOf("pow(2, 10)" to 1024.0),
        "floor" to listOf("floor(1.7)" to 1.0, "floor(-1.2)" to -2.0),
        "ceil" to listOf("ceil(1.2)" to 2.0, "ceil(-1.7)" to -1.0),
        "round" to listOf("round(1.4)" to 1.0, "round(1.6)" to 2.0, "round(2.5)" to 3.0, "round(0.5)" to 1.0, "round(-1.6)" to -2.0),
        "fract" to listOf("fract(1.25)" to 0.25, "fract(-0.25)" to 0.75),
        "mod" to listOf("mod(7, 3)" to 1.0, "mod(-7, 3)" to 2.0),
        "min" to listOf("min(3, 1)" to 1.0, "min(5, 4, 3, 2, 9, 8, 7, 6)" to 2.0),
        "max" to listOf("max(3, 1)" to 3.0, "max(5, 4, 3, 2, 9, 8, 7, 6)" to 9.0),
        "clamp" to listOf("clamp(5, 0, 1)" to 1.0, "clamp(-5, 0, 1)" to 0.0, "clamp(0.5, 0, 1)" to 0.5, "clamp(5, 1, 0)" to 1.0),
        "mix" to listOf("mix(10, 20, 0.25)" to 12.5, "mix(10, 20, 0)" to 10.0, "mix(10, 20, 1)" to 20.0),
        "step" to listOf("step(0.5, 0.4)" to 0.0, "step(0.5, 0.5)" to 1.0, "step(0.5, 0.6)" to 1.0),
        "smoothstep" to listOf(
            "smoothstep(0, 10, 5)" to 0.5, "smoothstep(0, 10, -1)" to 0.0, "smoothstep(0, 10, 11)" to 1.0,
            "smoothstep(0, 10, 2.5)" to 0.15625,
            // Both edges the same: a hard step, not NaN
            "smoothstep(3, 3, 2)" to 0.0, "smoothstep(3, 3, 3)" to 1.0, "smoothstep(3, 3, 4)" to 1.0
        ),
        "if" to listOf("if(1, 2, 3)" to 2.0, "if(0, 2, 3)" to 3.0, "if(nan, 2, 3)" to 3.0),
        "noise" to listOf("noise(3)" to Formula.hash(3.0), "noise(2, 5)" to Formula.hash(2.0, 5.0)),
        "hash" to listOf("hash(1)" to Formula.hash(1.0), "hash(1, 2)" to Formula.hash(1.0, 2.0), "hash(1, 2, 3)" to Formula.hash(1.0, 2.0, 3.0)),
        "easeInOut" to listOf("easeInOut(0)" to 0.0, "easeInOut(0.5)" to 0.5, "easeInOut(1)" to 1.0),
        "settle" to listOf("settle(0)" to 0.0, "settle(1)" to 1.0),
        "pulse" to listOf(
            "pulse(120, 60, 0.35)" to PulseCurve.value(120, 60, 0.35f).toDouble(),
            "pulse(500, 90, 0)" to PulseCurve.value(500, 90, 0f).toDouble()
        ),
        "window" to listOf("window(3, 2, 4)" to 0.25, "window(1, 2, 0)" to 0.0, "window(2, 2, 0)" to 1.0),
        "pingpong" to listOf("pingpong(3, 2)" to 1.0, "pingpong(4, 2)" to 0.0, "pingpong(1, 0)" to 0.0),
        "f32" to listOf("f32(0.5)" to 0.5, "f32(0.1)" to 0.1)
    )

    @Test
    fun everyFunctionHasASample() {
        assertThat(samples.keys).containsExactlyElementsIn(Formula.FUNCTIONS.keys)
    }

    @Test
    fun everyFunctionGivesItsValue() {
        for ((name, calls) in samples)
            for ((text, expected) in calls)
                assertWithMessage("$name: $text").that(eval(text)).isWithin(1e-9).of(expected)
    }

    @Test
    fun functionsWorkWithVariablesToo() {
        // Not folded, so the call is worked out at eval time
        assertThat(eval("min(x, y, t)", t = 3.0, x = 5.0, y = 4.0)).isEqualTo(3.0)
        assertThat(eval("clamp(x, 0, 1)", x = 0.25)).isEqualTo(0.25)
        assertThat(eval("noise(x)", x = 2.0)).isEqualTo(Formula.hash(2.0))
        assertThat(eval("pulse(t, 60, 0.35)", t = 120.0)).isWithin(1e-6).of(PulseCurve.value(120, 60, 0.35f).toDouble())
        // The same call node used again with other values
        val f = parse("max(x, y) + max(y, x)")
        assertThat(f.eval(doubleArrayOf(0.0, 1.0, 2.0, 0.0))).isEqualTo(4.0)
        assertThat(f.eval(doubleArrayOf(0.0, 7.0, 2.0, 0.0))).isEqualTo(14.0)
    }

    @Test
    fun aFunctionWithTheWrongNumberOfValuesIsRefused() {
        assertError("sin()", "takes 1..1")
        assertError("sin(1, 2)", "takes 1..1")
        assertError("atan2(1)", "takes 2..2")
        assertError("min(1)", "takes 2..8")
        assertError("max(1, 2, 3, 4, 5, 6, 7, 8, 9)", "takes 2..8")
        assertError("if(1, 2)", "takes 3..3")
        assertError("noise(1, 2, 3)", "takes 1..2")
        assertError("hash()", "takes 1..3")
        assertError("hash(1, 2, 3, 4)", "takes 1..3")
        assertError("pulse(1, 2)", "takes 3..3")
    }

    // ---- easing, settle, noise and hash ----

    @Test
    fun easeInOutGoesFromZeroToOne() {
        assertThat(Formula.easeInOut(0.0)).isEqualTo(0.0)
        assertThat(Formula.easeInOut(1.0)).isWithin(1e-12).of(1.0)
        assertThat(Formula.easeInOut(-1.0)).isEqualTo(0.0)
        assertThat(Formula.easeInOut(2.0)).isWithin(1e-12).of(1.0)
        var last = -1.0
        for (k in 0..100) {
            val v = Formula.easeInOut(k / 100.0)
            assertThat(v).isAtLeast(last)
            last = v
        }
    }

    @Test
    fun settleGoesPastOneAndStopsOnOne() {
        assertThat(Formula.settle(0.0)).isWithin(1e-12).of(0.0)
        assertThat(Formula.settle(1.0)).isWithin(1e-12).of(1.0)
        assertThat(Formula.settle(2.0)).isWithin(1e-12).of(1.0)
        val highest = (0..1000).maxOf { Formula.settle(it / 1000.0) }
        assertThat(highest).isGreaterThan(1.1)
        assertThat(highest).isLessThan(1.3)
    }

    @Test
    fun hashIsTheSameForTheSameValues() {
        assertThat(Formula.hash(1.0, 2.0)).isEqualTo(Formula.hash(1.0, 2.0))
        assertThat(Formula.hash(1.0, 2.0)).isNotEqualTo(Formula.hash(2.0, 1.0))
        assertThat(Formula.hash(1.0)).isNotEqualTo(Formula.hash(2.0))
        assertThat(Formula.hash(0.0)).isEqualTo(Formula.hash(-0.0))
        assertThat(eval("hash(x, y)", x = 3.0, y = 4.0)).isEqualTo(Formula.hash(3.0, 4.0))
    }

    @Test
    fun hashIsFromZeroToOneAndSpreadOut() {
        var sum = 0.0
        var low = 0
        for (k in 0 until 10_000) {
            val h = Formula.hash(k.toDouble())
            assertThat(h).isAtLeast(0.0)
            assertThat(h).isLessThan(1.0)
            sum += h
            if (h < 0.5) low++
        }
        assertThat(sum / 10_000).isWithin(0.02).of(0.5)
        assertThat(low).isAtLeast(4500)
        assertThat(low).isAtMost(5500)
    }

    @Test
    fun noiseIsTheSameForTheSameValuesAndFromZeroToOne() {
        for (k in 0 until 2000) {
            val x = k * 0.137 - 50
            val y = k * 0.071 - 20
            assertThat(Formula.noise(x)).isEqualTo(Formula.noise(x))
            assertThat(Formula.noise(x, y)).isEqualTo(Formula.noise(x, y))
            assertThat(Formula.noise(x)).isAtLeast(0.0)
            assertThat(Formula.noise(x)).isAtMost(1.0)
            assertThat(Formula.noise(x, y)).isAtLeast(0.0)
            assertThat(Formula.noise(x, y)).isAtMost(1.0)
        }
    }

    @Test
    fun noiseHasNoJumps() {
        val step = 1e-4
        for (k in 0 until 2000) {
            val x = k * 0.0123 - 7
            val y = k * 0.0071 + 3
            assertWithMessage("noise($x)").that(abs(Formula.noise(x + step) - Formula.noise(x))).isLessThan(1e-3)
            assertWithMessage("noise($x, $y)").that(abs(Formula.noise(x + step, y) - Formula.noise(x, y))).isLessThan(1e-3)
            assertWithMessage("noise($x, $y)").that(abs(Formula.noise(x, y + step) - Formula.noise(x, y))).isLessThan(1e-3)
        }
        // Across a whole number too, where the random values change
        assertThat(abs(Formula.noise(5.0 - 1e-9) - Formula.noise(5.0))).isLessThan(1e-6)
    }

    @Test
    fun noiseIsNotFlat() {
        val values = (0 until 100).map { Formula.noise(it * 0.5) }
        assertThat(values.max() - values.min()).isGreaterThan(0.5)
    }

    // ---- variables ----

    @Test
    fun variablesAreReadByTheIndexTheNameGave() {
        val asked = ArrayList<String>()
        val f = Formula.parse("a * 10 + b") { name ->
            asked += name
            when (name) {
                "a" -> 2; "b" -> 0; else -> null
            }
        }
        assertThat(asked).containsExactly("a", "b").inOrder()
        assertThat(f.eval(doubleArrayOf(5.0, 0.0, 3.0))).isEqualTo(35.0)
        assertThat(f.evalF(doubleArrayOf(1.5, 0.0, 0.25))).isEqualTo(4.0f)
        assertThat(f.isConstant).isFalse()
    }

    @Test
    fun namesCanHaveDigitsAndUnderscores() {
        val f = Formula.parse("v_watch_battery + r2") { name ->
            when (name) {
                "v_watch_battery" -> 0; "r2" -> 1; else -> null
            }
        }
        assertThat(f.eval(doubleArrayOf(40.0, 0.5))).isEqualTo(40.5)
    }

    @Test
    fun functionNamesAreNotAskedAsVariables() {
        val asked = ArrayList<String>()
        Formula.parse("sin(t) + pi") { name -> asked += name; 0 }
        assertThat(asked).containsExactly("t")
    }

    // ---- errors ----

    @Test
    fun unknownNamesAreRefused() {
        assertError("foo + 1", "unknown name 'foo'")
        assertError("sinus(1)", "unknown function 'sinus'")
        assertError("pi(1)", "unknown function 'pi'")
        assertError("sin + 1", "unknown name 'sin'")
    }

    @Test
    fun badCharactersAreRefused() {
        for (text in listOf("1 # 2", "x ^ 2", "\$x", "1 & 2", "1 | 2", "x = 1", "a.b", "1 ; 2", "\"1\"", "[1]", "{1}"))
            assertThrows<FormulaException>(text) { parse(text) }
        assertError("2 @ 3", "unexpected '@'")
    }

    @Test
    fun badNumbersAreRefused() {
        assertError("1.2.3", "bad number")
        assertError("1e", "bad number")
        assertError("2e+", "bad number")
    }

    @Test
    fun unbalancedBracketsAreRefused() {
        assertThrows<FormulaException> { parse("(1 + 2") }
        assertThrows<FormulaException> { parse("1 + 2)") }
        assertThrows<FormulaException> { parse("sin(1") }
        assertThrows<FormulaException> { parse("((1)") }
        assertThrows<FormulaException> { parse("()") }
        assertThrows<FormulaException> { parse(")(") }
    }

    @Test
    fun brokenFormulasAreRefused() {
        assertError("", "ends too early")
        assertError("   ", "ends too early")
        assertError("1 +", "ends too early")
        assertError("1 2", "unexpected '2'")
        assertError("2pi", "unexpected 'pi'")
        assertError("1, 2", "unexpected ','")
        assertThrows<FormulaException> { parse("* 2") }
        assertThrows<FormulaException> { parse("1 * * 2") }
        assertThrows<FormulaException> { parse("1 !") }
        assertThrows<FormulaException> { parse("min(1,)") }
        assertThrows<FormulaException> { parse("min(,1)") }
    }

    @Test
    fun aTooLongFormulaIsRefused() {
        val longest = "1" + " ".repeat(Formula.MAX_LENGTH - 1)
        assertThat(parse(longest).eval(DoubleArray(0))).isEqualTo(1.0)
        assertError(longest + " ", "longer than ${Formula.MAX_LENGTH}")
    }

    @Test
    fun aFormulaWithTooManyPartsIsRefused() {
        // "t+t+...": n names and n - 1 additions
        val fits = List(150) { "t" }.joinToString("+")
        assertThat(parse(fits).eval(doubleArrayOf(1.0, 0.0, 0.0, 0.0))).isEqualTo(150.0)
        val tooMany = List(151) { "t" }.joinToString("+")
        assertThat(tooMany.length).isAtMost(Formula.MAX_LENGTH)
        assertError(tooMany, "more than ${Formula.MAX_NODES} parts")
        // A long chain of minus signs is counted too
        assertError("-".repeat(Formula.MAX_NODES + 10) + "t", "more than ${Formula.MAX_NODES} parts")
    }

    @Test
    fun aTooDeepFormulaIsRefused() {
        assertThat(eval("(".repeat(30) + "x" + ")".repeat(30), x = 2.0)).isEqualTo(2.0)
        assertError("(".repeat(50) + "x" + ")".repeat(50), "nested too deep")
        assertError("sin(".repeat(50) + "x" + ")".repeat(50), "nested too deep")
    }

    @Test
    fun aVeryLongChainOfSignsDoesNotBreakTheReader() {
        // Signs that make no parts must still be read without running out of stack
        assertThat(eval("+".repeat(Formula.MAX_LENGTH - 1) + "x", x = 4.0)).isEqualTo(4.0)
        assertThrows<FormulaException> { parse("!".repeat(Formula.MAX_LENGTH - 1) + "x") }
    }

    // ---- fixed numbers are worked out once ----

    @Test
    fun aFormulaOfFixedNumbersIsConstant() {
        assertThat(parse("2 + 3 * 4").isConstant).isTrue()
        assertThat(parse("-pi / 2").isConstant).isTrue()
        assertThat(parse("sin(pi / 2) + max(1, 2, 3)").isConstant).isTrue()
        assertThat(parse("if(1 < 2, 3, 4)").isConstant).isTrue()
        assertThat(parse("!0 && 1").isConstant).isTrue()
        assertThat(parse("hash(1, 2)").isConstant).isTrue()
        assertThat(parse("t").isConstant).isFalse()
        assertThat(parse("t * 0").isConstant).isFalse()
        assertThat(parse("sin(t) + 2 * 3").isConstant).isFalse()
        assertThat(parse("if(1, 2, t)").isConstant).isFalse()
        assertThat(Formula.constant(2.5).isConstant).isTrue()
        assertThat(Formula.constant(2.5).eval(DoubleArray(0))).isEqualTo(2.5)
    }

    @Test
    fun aConstantNeedsNoVariables() {
        // Worked out with an empty array: no variable is read
        assertThat(parse("(1 + 2) * sqrt(16) - 10 % 4").eval(DoubleArray(0))).isEqualTo(10.0)
    }

    @Test
    fun theTextIsKept() {
        val f = parse("sin(t) * 8")
        assertThat(f.text).isEqualTo("sin(t) * 8")
        assertThat(f.toString()).isEqualTo("sin(t) * 8")
        assertThat(f).isEqualTo(parse("sin(t) * 8"))
        assertThat(f.hashCode()).isEqualTo(parse("sin(t) * 8").hashCode())
        assertThat(f).isNotEqualTo(parse("sin(t) * 9"))
    }

    // ---- window and pingpong ----

    @Test
    fun windowRunsFromStartOverTheDuration() {
        assertThat(eval("window(t, 2, 4)", t = 0.0)).isEqualTo(0.0)
        assertThat(eval("window(t, 2, 4)", t = 2.0)).isEqualTo(0.0)
        assertThat(eval("window(t, 2, 4)", t = 3.0)).isEqualTo(0.25)
        assertThat(eval("window(t, 2, 4)", t = 6.0)).isEqualTo(1.0)
        assertThat(eval("window(t, 2, 4)", t = 60.0)).isEqualTo(1.0)
        assertThat(Formula.window(5.0, 2.0, 4.0)).isEqualTo(0.75)
    }

    @Test
    fun aWindowWithoutLengthIsAStep() {
        assertThat(eval("window(t, 2, 0)", t = 1.999)).isEqualTo(0.0)
        assertThat(eval("window(t, 2, 0)", t = 2.0)).isEqualTo(1.0)
        assertThat(eval("window(t, 2, -1)", t = 1.0)).isEqualTo(0.0)
        assertThat(eval("window(t, 2, -1)", t = 3.0)).isEqualTo(1.0)
        // A missing time is no number, as in every other function
        assertThat(eval("window(nan, 2, 4)").isNaN()).isTrue()
        assertThat(eval("window(nan, 2, 0)")).isEqualTo(0.0)
    }

    @Test
    fun pingpongGoesUpAndDownAgain() {
        val expected = mapOf(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0, 3.0 to 1.0, 4.0 to 0.0, 5.0 to 1.0, -1.0 to 1.0, 9.5 to 1.5)
        for ((x, value) in expected) assertWithMessage("pingpong($x, 2)").that(eval("pingpong(t, 2)", t = x)).isEqualTo(value)
        assertThat(eval("pingpong(t, 0)", t = 3.0)).isEqualTo(0.0)
        assertThat(eval("pingpong(t, -2)", t = 3.0)).isEqualTo(0.0)
        assertThat(parse("pingpong(1, 2) + window(1, 0, 2)").isConstant).isTrue()
    }

    // ---- the variables a formula reads ----

    @Test
    fun aFormulaSaysWhichVariablesItReads() {
        // t = 0, x = 1, y = 2, nan = 3
        assertThat(parse("2 * pi").reads).isEqualTo(0L)
        assertThat(parse("t").reads).isEqualTo(0b0001L)
        assertThat(parse("sin(x) + y * 2").reads).isEqualTo(0b0110L)
        assertThat(parse("if(t > 1, x, y)").reads).isEqualTo(0b0111L)
        assertThat(parse("max(t, 1, 2, 3, nan)").reads).isEqualTo(0b1001L)
        assertThat(parse("hash(x, y, t) * 0").reads).isEqualTo(0b0111L)
        // A part with fixed numbers only is worked out once and reads nothing
        assertThat(parse("t + sin(pi / 2)").reads).isEqualTo(0b0001L)
    }

    @Test
    fun anIndexPastSixtyThreeReadsAsBitSixtyThree() {
        val f = Formula.parse("far + near") { name -> if (name == "far") 100 else 2 }
        assertThat(f.reads).isEqualTo((1L shl 63) or 0b100L)
    }

    @Test
    fun functionsWithManyValuesWorkWithoutBoxing() {
        // The same values as before the change to `fun interface` functions
        assertThat(eval("min(5, 3, 4, 1, 2)")).isEqualTo(1.0)
        assertThat(eval("max(5, 3, 4, 1, 2, 9, 0, 8)")).isEqualTo(9.0)
        assertThat(eval("min(t, x, y)", t = 3.0, x = 2.0, y = 1.0)).isEqualTo(1.0)
        assertThat(eval("max(t, x, y)", t = 3.0, x = 2.0, y = 1.0)).isEqualTo(3.0)
        assertThat(eval("hash(t)", t = 1.0)).isEqualTo(Formula.hash(1.0))
        assertThat(eval("hash(t, x)", t = 1.0, x = 2.0)).isEqualTo(Formula.hash(1.0, 2.0))
        assertThat(eval("hash(t, x, y)", t = 1.0, x = 2.0, y = 3.0)).isEqualTo(Formula.hash(1.0, 2.0, 3.0))
        assertThat(eval("noise(t)", t = 1.5)).isEqualTo(Formula.noise(1.5))
        assertThat(eval("noise(t, x)", t = 1.5, x = 0.25)).isEqualTo(Formula.noise(1.5, 0.25))
    }
}
