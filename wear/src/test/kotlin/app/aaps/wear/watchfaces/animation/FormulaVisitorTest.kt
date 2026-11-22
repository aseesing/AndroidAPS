// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** [Formula.visit] shows the parts of a formula as they were read. */
internal class FormulaVisitorTest {

    private val names = listOf("t", "x", "y", "#day")

    private fun parse(text: String): Formula = Formula.parse(text) { name -> names.indexOf(name).takeIf { it >= 0 } }

    /** Writes the parts back in a plain form with every part in brackets. */
    private object Show : FormulaVisitor<String> {

        override fun number(value: Double) = value.toString()
        override fun variable(index: Int) = "v$index"
        override fun negate(a: String) = "(-$a)"
        override fun not(a: String) = "(!$a)"
        override fun choose(c: String, a: String, b: String) = "($c ? $a : $b)"
        override fun operator(op: String, a: String, b: String) = "($a $op $b)"
        override fun function(name: String, args: List<String>) = "$name(${args.joinToString(", ")})"
        override fun source(name: String) = "[$name]"
        override fun other(kind: String) = "<$kind>"
    }

    @Test
    fun showsOperatorsFunctionsAndVariables() {
        assertThat(parse("sin(t * 0.3) * 8 + x % 4").visit(Show)).isEqualTo("((sin((v0 * 0.3)) * 8.0) + (v1 % 4.0))")
    }

    @Test
    fun showsChoicesNegationAndNot() {
        assertThat(parse("t > 1 ? -x : !y").visit(Show)).isEqualTo("((v0 > 1.0) ? (-v1) : (!v2))")
        assertThat(parse("if(t, x, y)").visit(Show)).isEqualTo("(v0 ? v1 : v2)")
    }

    @Test
    fun showsFunctionsWithManyValues() {
        assertThat(parse("min(t, x, y, 1)").visit(Show)).isEqualTo("min(v0, v1, v2, 1.0)")
        assertThat(parse("clamp(t, 0, 1)").visit(Show)).isEqualTo("clamp(v0, 0.0, 1.0)")
    }

    @Test
    fun fixedPartsAreAlreadyWorkedOut() {
        assertThat(parse("2 * pi + t").visit(Show)).isEqualTo("(${2 * Math.PI} + v0)")
    }

    @Test
    fun dateSourcesKeepTheirName() {
        assertThat(parse("[DAY_OF_WEEK] == 1").visit(Show)).isEqualTo("([DAY_OF_WEEK] == 1.0)")
    }

    @Test
    fun textPartsComeAsOther() {
        assertThat(parse("len('ab' & t)").visit(Show)).startsWith("<")
    }

    @Test
    fun visitingDoesNotChangeTheValue() {
        val f = parse("sin(t) + x * 2")
        val before = f.eval(doubleArrayOf(0.5, 3.0, 0.0, 0.0))
        f.visit(Show)
        assertThat(f.eval(doubleArrayOf(0.5, 3.0, 0.0, 0.0))).isEqualTo(before)
    }
}
