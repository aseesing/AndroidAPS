package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.util.Locale

/** The own functions of a design: how they are read, put into a formula, and kept from running away. */
class DesignFunctionsTest {

    private val names = listOf("t", "x", "y")
    private fun index(name: String): Int? = names.indexOf(name).takeIf { it >= 0 }
    private val setting = FormulaSetting().apply { set(Locale.US, true, true, ZoneId.of("UTC")) }

    private fun functions(vararg entries: Pair<String, String>, constants: Map<String, Double> = emptyMap()) =
        DesignFunctions(entries.map { DesignFunction.read(it.first, it.second) }, ::index) { constants[it] }

    private fun number(formula: String, functions: DesignFunctions, t: Double = 0.0, x: Double = 0.0, y: Double = 0.0): Double =
        Formula.parse(formula, setting = setting, functions = functions, names = ::index).eval(doubleArrayOf(t, x, y))

    private fun text(formula: String, functions: DesignFunctions, x: Double = 0.0): String =
        TextFormula.parse(formula, setting = setting, functions = functions, names = ::index).eval(doubleArrayOf(0.0, x, 0.0))

    private fun assertError(formula: String, functions: DesignFunctions, part: String) {
        val e = assertThrows<FormulaException> { Formula.parse(formula, setting = setting, functions = functions, names = ::index) }
        assertWithMessage("error of '$formula'").that(e.message).contains(part)
    }

    // ---- reading the head ----

    @Test
    fun aHeadIsANameWithItsValues() {
        assertThat(DesignFunction.read("two(x)", "x * 2")).isEqualTo(DesignFunction("two", listOf("x"), "x * 2"))
        assertThat(DesignFunction.read(" wave ( a , speed ) ", "a")).isEqualTo(DesignFunction("wave", listOf("a", "speed"), "a"))
        assertThat(DesignFunction.read("bg()", "1").params).isEmpty()
        assertThat(DesignFunction.read("bg( )", "1").params).isEmpty()
    }

    @Test
    fun aWrongHeadIsAMistake() {
        for (head in listOf("two", "two x", "two(x", "(x)", "2x(a)", "two(a,)", "two(a b)", "two(a, a)", "two(2)", "two(a)(b)"))
            assertWithMessage(head).that(runCatching { DesignFunction.read(head, "1") }.exceptionOrNull()).isInstanceOf(FormulaException::class.java)
    }

    @Test
    fun aTakenNameCannotBeAFunction() {
        for (head in listOf("sin(a)", "left(a)", "clock()", "t(a)", "pi(a)", "v_sgv(a)", "missing(a)"))
            assertWithMessage(head).that(runCatching { DesignFunction.read(head, "1") }.exceptionOrNull()).isInstanceOf(FormulaException::class.java)
    }

    @Test
    fun tooManyValuesOrATooLongBodyIsAMistake() {
        assertThat(DesignFunction.read("f(a, b, c, d, e, f2, g, h)", "a").params).hasSize(DesignFunction.MAX_PARAMS)
        assertThrows<FormulaException> { DesignFunction.read("f(a, b, c, d, e, f2, g, h, k)", "a") }
        assertThrows<FormulaException> { DesignFunction.read("f(a)", "a" + " + 1".repeat(Formula.MAX_LENGTH / 4)) }
    }

    @Test
    fun onlyTheFirstFunctionsAreKept() {
        val many = (0 until DesignFunction.MAX_FUNCTIONS + 3).map { DesignFunction("f$it", emptyList(), "$it") }
        val all = DesignFunctions(many, ::index)
        assertThat(all["f${DesignFunction.MAX_FUNCTIONS - 1}"]).isNotNull()
        assertThat(all["f${DesignFunction.MAX_FUNCTIONS}"]).isNull()
    }

    @Test
    fun aNameThatComesTwiceKeepsTheFirst() {
        val all = DesignFunctions(listOf(DesignFunction("f", emptyList(), "1"), DesignFunction("f", emptyList(), "2")), ::index)
        assertThat(number("f()", all)).isEqualTo(1.0)
    }

    // ---- calling ----

    @Test
    fun aNumberFunctionPutsItsValuesIn() {
        val all = functions("double(a)" to "a * 2", "hyp(a, b)" to "sqrt(a * a + b * b)", "five()" to "5")
        assertThat(number("double(4)", all)).isEqualTo(8.0)
        assertThat(number("double(x) + 1", all, x = 3.0)).isEqualTo(7.0)
        assertThat(number("hyp(3, 4)", all)).isEqualTo(5.0)
        assertThat(number("five() + five()", all)).isEqualTo(10.0)
    }

    @Test
    fun aValueIsPutInAsAWhole() {
        // Not as text: 1 + 2 stays together
        val all = functions("double(a)" to "a * 2")
        assertThat(number("double(1 + 2)", all)).isEqualTo(6.0)
    }

    @Test
    fun aTextFunctionGivesAText() {
        val all = functions("two(n)" to "text(n, '00')", "label(v, unit)" to "fixed(v, 1) & ' ' & unit")
        assertThat(text("two(7)", all)).isEqualTo("07")
        assertThat(text("two(x) & ':' & two(5)", all, x = 12.0)).isEqualTo("12:05")
        assertThat(text("label(x, 'U')", all, x = 2.35)).isEqualTo("2.4 U")
    }

    @Test
    fun oneFunctionWorksForNumbersAndTexts() {
        val all = functions("pick(c, a, b)" to "if(c, a, b)")
        assertThat(number("pick(1, 2, 3)", all)).isEqualTo(2.0)
        assertThat(text("pick(0, 'yes', 'no')", all)).isEqualTo("no")
    }

    @Test
    fun aBodySeesTheNamesEveryLayerKnows() {
        val all = functions("swing(a)" to "a * t + base", constants = mapOf("base" to 100.0))
        assertThat(number("swing(2)", all, t = 3.0)).isEqualTo(106.0)
    }

    @Test
    fun aValueHidesAVariableOfTheSameUse() {
        // The value `a` of the function, not something outside it
        val all = functions("f(a)" to "a + x")
        assertThat(number("f(y)", all, x = 1.0, y = 10.0)).isEqualTo(11.0)
    }

    @Test
    fun aValueMayHaveTheNameOfAVariable() {
        // Inside the body `t` is the value, not the time
        val all = functions("f(t)" to "t * 2", "g(n, pi)" to "n + pi")
        assertThat(number("f(5)", all, t = 100.0)).isEqualTo(10.0)
        assertThat(number("f(x) + t", all, t = 100.0, x = 1.0)).isEqualTo(102.0)
        assertThat(number("g(1, 2)", all)).isEqualTo(3.0)
    }

    @Test
    fun aBodyDoesNotSeeTheNamesOfTheCaller() {
        // `own` is a name only the caller knows (like the let of one layer)
        val all = functions("f()" to "own + 1")
        val e = assertThrows<FormulaException> {
            Formula.parse("f()", setting = setting, functions = all) { name -> if (name == "own") 3 else index(name) }
        }
        assertThat(e.message).contains("in function 'f'")
        assertThat(e.message).contains("unknown name 'own'")
    }

    @Test
    fun aFunctionMayCallAnother() {
        val all = functions("two(n)" to "text(n, '00')", "hm(h, m)" to "two(h) & ':' & two(m)")
        assertThat(text("hm(7, 5)", all)).isEqualTo("07:05")
    }

    @Test
    fun aFunctionWithOnlyFixedValuesIsWorkedOutWhenRead() {
        val all = functions("double(a)" to "a * 2")
        assertThat(Formula.parse("double(4)", functions = all, names = ::index).isConstant).isTrue()
        assertThat(Formula.parse("double(x)", functions = all, names = ::index).isConstant).isFalse()
    }

    @Test
    fun aFunctionReadsOnlyWhatItsValuesRead() {
        val all = functions("double(a)" to "a * 2")
        assertThat(Formula.parse("double(x)", functions = all, names = ::index).variables.toList()).containsExactly(1)
    }

    // ---- mistakes ----

    @Test
    fun theWrongNumberOfValuesIsAMistake() {
        val all = functions("double(a)" to "a * 2")
        assertError("double()", all, "'double' takes 1 values, not 0")
        assertError("double(1, 2)", all, "'double' takes 1 values, not 2")
    }

    @Test
    fun aFunctionMayNotUseItself() {
        assertError("f(1)", functions("f(a)" to "f(a - 1)"), "function 'f' uses itself")
        assertError("f(1)", functions("f(a)" to "g(a)", "g(a)" to "f(a)"), "function 'f' uses itself")
    }

    @Test
    fun functionsGoFourDeep() {
        val four = functions("a(v)" to "b(v) + 1", "b(v)" to "c(v) + 1", "c(v)" to "d(v) + 1", "d(v)" to "v")
        assertThat(number("a(1)", four)).isEqualTo(4.0)
        val five = functions("a(v)" to "b(v) + 1", "b(v)" to "c(v) + 1", "c(v)" to "d(v) + 1", "d(v)" to "e(v) + 1", "e(v)" to "v")
        assertError("a(1)", five, "nested more than 4 deep")
    }

    @Test
    fun aMistakeInABodyNamesTheFunction() {
        assertError("f(1)", functions("f(a)" to "a +"), "in function 'f'")
        assertError("f(1)", functions("f(a)" to "a + nothing"), "unknown name 'nothing'")
        assertError("f('a')", functions("f(a)" to "a * 2"), "needs a number")
    }

    @Test
    fun thePartsOfTheFunctionsCountForTheFormula() {
        // Each call adds the parts of the body: many calls of a big body go over the limit
        val body = (1..40).joinToString(" + ") { "a * $it" }
        val all = functions("big(a)" to body)
        assertThat(number("big(x)", all, x = 1.0)).isEqualTo((1..40).sum().toDouble())
        assertError("big(x) + big(x) + big(x) + big(x)", all, "more than ${Formula.MAX_NODES} parts")
    }

    @Test
    fun anUnknownFunctionIsStillAMistake() {
        assertError("nothing(1)", functions("f(a)" to "a"), "unknown function 'nothing'")
    }
}
