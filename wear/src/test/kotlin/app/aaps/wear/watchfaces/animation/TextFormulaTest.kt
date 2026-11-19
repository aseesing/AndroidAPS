// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The texts of the formula language: the text functions, the joins, the kinds, and what is kept. */
class TextFormulaTest {

    /** The variables of the tests; `hour`, `min` and the hidden day are there for the clock texts. */
    private val names = listOf("t", "x", "nan", "hour", "min", LayerVariables.DAY_NAME)
    private val utc = ZoneId.of("UTC")

    private fun setting(locale: Locale = Locale.US, is24Hour: Boolean = true, mgdl: Boolean = true) =
        FormulaSetting().apply { set(locale, is24Hour, mgdl, utc) }

    private fun index(name: String): Int? = names.indexOf(name).takeIf { it >= 0 }

    private fun parse(text: String, setting: FormulaSetting = setting()): TextFormula = TextFormula.parse(text, setting = setting, names = ::index)

    private fun values(t: Double = 0.0, x: Double = 0.0, hour: Double = 0.0, min: Double = 0.0, day: Double = 0.0) = doubleArrayOf(t, x, Double.NaN, hour, min, day)

    private fun text(formula: String, t: Double = 0.0, x: Double = 0.0, setting: FormulaSetting = setting()): String = parse(formula, setting).eval(values(t, x))

    private fun number(formula: String, x: Double = 0.0, setting: FormulaSetting = setting()): Double =
        Formula.parse(formula, setting = setting, names = ::index).eval(values(x = x))

    private fun assertError(formula: String, part: String) {
        val e = assertThrows<FormulaException> { TextFormula.parse(formula, names = ::index) }
        assertWithMessage("error of '$formula'").that(e.message).contains(part)
    }

    // ---- writing a text ----

    @Test
    fun aTextStandsBetweenQuotes() {
        assertThat(text("'hello'")).isEqualTo("hello")
        assertThat(text("\"hello\"")).isEqualTo("hello")
        assertThat(text("''")).isEqualTo("")
        assertThat(text("'a + b, (c)'")).isEqualTo("a + b, (c)")
    }

    @Test
    fun aQuoteTwiceIsOneQuote() {
        assertThat(text("'it''s'")).isEqualTo("it's")
        assertThat(text("\"say \"\"hi\"\"\"")).isEqualTo("say \"hi\"")
        assertThat(text("'say \"hi\"'")).isEqualTo("say \"hi\"")
    }

    @Test
    fun aTextWithoutItsEndIsAMistake() {
        assertError("'hello", "no closing")
        assertError("hello", "a fixed text needs quotes")
    }

    // ---- joining ----

    @Test
    fun andSignJoins() {
        assertThat(text("'a' & 'b'")).isEqualTo("ab")
        assertThat(text("'a' & 'b' & 'c'")).isEqualTo("abc")
        assertThat(text("'x = ' & x", x = 3.0)).isEqualTo("x = 3")
    }

    @Test
    fun plusAndMinusGoBeforeTheJoin() {
        assertThat(text("1 + 2 & 3 + 4")).isEqualTo("37")
        assertThat(text("'n' & 2 * 3")).isEqualTo("n6")
    }

    @Test
    fun theJoinGoesBeforeACompare() {
        assertThat(number("'a' & 'b' == 'ab'")).isEqualTo(1.0)
        assertThat(number("'a' & 'b' != 'ab'")).isEqualTo(0.0)
    }

    @Test
    fun aNumberByItselfIsWrittenLikeASpreadsheet() {
        assertThat(text("5")).isEqualTo("5")
        assertThat(text("x", x = 12.0)).isEqualTo("12")
        assertThat(text("x", x = 2.5)).isEqualTo("2.5")
        assertThat(text("x", x = 1.0 / 3)).isEqualTo("0.333333")
        assertThat(text("x", x = -4.25)).isEqualTo("-4.25")
        assertThat(text("x", x = 1234567.0)).isEqualTo("1234567")
        assertThat(text("nan")).isEqualTo("")
        assertThat(text("'<' & nan & '>'")).isEqualTo("<>")
    }

    @Test
    fun aNumberUsesTheDecimalSignOfTheLanguage() {
        assertThat(text("x", x = 2.5, setting = setting(Locale.GERMANY))).isEqualTo("2,5")
        assertThat(text("'a' & x", x = 2.5, setting = setting(Locale.GERMANY))).isEqualTo("a2,5")
    }

    // ---- kinds ----

    @Test
    fun aTextIsNotANumber() {
        assertError("'a' + 1", "needs a number")
        assertError("'a' + 'b'", "join texts with &")
        assertError("-'a'", "needs a number")
        assertError("sin('a')", "needs a number")
        assertError("'a' < 'b'", "needs a number")
        assertError("'a' == 1", "cannot compare a text with a number")
        assertError("left('abc', 'b')", "needs a number as value 2")
        assertError("if(1, 'a', 2)", "two numbers or two texts")
    }

    @Test
    fun aNumberFormulaCannotGiveAText() {
        val e = assertThrows<FormulaException> { Formula.parse("'a' & 'b'", names = ::index) }
        assertThat(e.message).contains("gives a text")
    }

    @Test
    fun textsCompareExactly() {
        assertThat(number("'abc' == 'abc'")).isEqualTo(1.0)
        assertThat(number("'abc' == 'ABC'")).isEqualTo(0.0)
        assertThat(number("'abc' != 'abd'")).isEqualTo(1.0)
        assertThat(number("upper('abc') == 'ABC'")).isEqualTo(1.0)
    }

    @Test
    fun ifChoosesBetweenTwoTexts() {
        assertThat(text("if(x > 1, 'high', 'low')", x = 2.0)).isEqualTo("high")
        assertThat(text("if(x > 1, 'high', 'low')", x = 0.0)).isEqualTo("low")
        assertThat(text("if(nan, 'yes', 'no')")).isEqualTo("no")
        assertThat(text("if(missing(nan), '--', fixed(nan, 1))")).isEqualTo("--")
    }

    // ---- the text functions ----

    /** For every text function: calls with the text or the number they must give (in US English, 24 hours, mg/dL). */
    private val samples: Map<String, List<Pair<String, Any>>> = mapOf(
        "concat" to listOf("concat('a', 'b')" to "ab", "concat('a', 1, 'b', 2.5)" to "a1b2.5", "concat('a','b','c','d','e','f','g','h')" to "abcdefgh"),
        "left" to listOf("left('hello', 2)" to "he", "left('hello', 0)" to "", "left('hello', 9)" to "hello", "left('hello', -1)" to "", "left(12345, 2)" to "12"),
        "right" to listOf("right('hello', 2)" to "lo", "right('hello', 0)" to "", "right('hello', 9)" to "hello"),
        "mid" to listOf("mid('hello', 2, 3)" to "ell", "mid('hello', 1, 1)" to "h", "mid('hello', 4, 9)" to "lo", "mid('hello', 6, 2)" to "", "mid('hello', 0, 2)" to "he", "mid('hello', 2, 0)" to ""),
        "len" to listOf("len('hello')" to 5.0, "len('')" to 0.0, "len(1234)" to 4.0),
        "upper" to listOf("upper('Hello')" to "HELLO"),
        "lower" to listOf("lower('Hello')" to "hello"),
        "trim" to listOf("trim('  a   b  ')" to "a b", "trim('ab')" to "ab", "trim('   ')" to "", "trim(' a')" to "a"),
        "rept" to listOf("rept('ab', 3)" to "ababab", "rept('ab', 0)" to "", "rept('', 5)" to "", "rept('-', 2.9)" to "--"),
        "substitute" to listOf("substitute('a-b-c', '-', '+')" to "a+b+c", "substitute('abc', 'x', 'y')" to "abc", "substitute('abc', '', 'y')" to "abc", "substitute('aaa', 'a', '')" to ""),
        "find" to listOf("find('l', 'hello')" to 3.0, "find('l', 'hello', 4)" to 4.0, "find('x', 'hello')" to 0.0, "find('lo', 'hello')" to 4.0, "find('h', 'hello', 9)" to 0.0),
        "padLeft" to listOf("padLeft('7', 3)" to "  7", "padLeft('7', 3, '0')" to "007", "padLeft('1234', 3, '0')" to "1234", "padLeft('7', 4, 'ab')" to "aba7", "padLeft(7, 2, 0)" to "07"),
        "padRight" to listOf("padRight('7', 3)" to "7  ", "padRight('7', 3, '.')" to "7..", "padRight('7', 3, '')" to "7"),
        "text" to listOf(
            "text(5, '00')" to "05", "text(12, '00')" to "12", "text(3.14159, '0.00')" to "3.14", "text(1234567, '#,##0')" to "1,234,567",
            "text(2.5, '+0.0;-0.0')" to "+2.5", "text(-2.5, '+0.0;-0.0')" to "-2.5", "text(0.5, '0%')" to "50%", "text(nan, '0')" to ""
        ),
        "fixed" to listOf(
            "fixed(3.14159, 2)" to "3.14", "fixed(3, 2)" to "3.00", "fixed(2.5, 0)" to "3", "fixed(1234.5, 1)" to "1234.5", "fixed(1234.5, 1, 1)" to "1,234.5",
            "fixed(-0.04, 1)" to "0.0", "fixed(-0.06, 1)" to "-0.1", "fixed(nan, 1)" to "", "fixed(1 / 0, 1)" to ""
        ),
        "value" to listOf("value('12.5')" to 12.5, "value(' 7 ')" to 7.0, "value('abc')" to Double.NaN, "value('')" to Double.NaN, "value('12abc')" to Double.NaN),
        "glucose" to listOf("glucose(120)" to "120", "glucose(120.6)" to "121", "glucose(180, 'mmol')" to "10.0", "glucose(100, 'mmol/L')" to "5.6", "glucose(100, 'mg/dL')" to "100", "glucose(nan)" to ""),
        "clock" to listOf("clock()" to "13:05"),
        "clockHour" to listOf("clockHour()" to "13"),
        "clockMinute" to listOf("clockMinute()" to "05"),
        "ampm" to listOf("ampm()" to ""),
        "date" to listOf("date('EEE d MMM yyyy')" to "Thu 19 Nov 2026", "date('HH:mm')" to "13:05")
    )

    /** 13:05 on Thursday 19 November 2026, in UTC. */
    private val nowMs = ZonedDateTime.of(2026, 11, 19, 13, 5, 30, 0, utc).toInstant().toEpochMilli()

    @Test
    fun everyTextFunctionHasASample() {
        assertThat(samples.keys).containsExactlyElementsIn(TextFunctions.FUNCTIONS.keys)
    }

    @Test
    fun everyTextFunctionGivesItsValue() {
        val setting = setting().apply { nowMs = this@TextFormulaTest.nowMs }
        val values = values(hour = 13.0, min = 5.0, day = 20776.0)
        for ((name, calls) in samples)
            for ((formula, expected) in calls) {
                if (expected is String) assertWithMessage("$name: $formula").that(parse(formula, setting).eval(values)).isEqualTo(expected)
                else {
                    val value = Formula.parse(formula, setting = setting, names = ::index).eval(values)
                    if ((expected as Double).isNaN()) assertWithMessage("$name: $formula").that(value).isNaN()
                    else assertWithMessage("$name: $formula").that(value).isEqualTo(expected)
                }
            }
    }

    @Test
    fun aFunctionWithTheWrongNumberOfValuesIsAMistake() {
        assertError("left('a')", "'left' takes 2..2 values, not 1")
        assertError("clock(1)", "'clock' takes 0..0 values, not 1")
        assertError("concat('a')", "'concat' takes 2..8 values, not 1")
    }

    @Test
    fun aFixedFormatThatIsNotOneIsAMistake() {
        assertError("text(1, '0.0.0')", "is not a format")
        assertError("date('QQQQQQQ{')", "is not a format")
    }

    @Test
    fun aFormatThatIsWorkedOutAndWrongGivesAnEmptyText() {
        assertThat(text("text(1, if(x, '0.0.0', '0'))", x = 1.0)).isEqualTo("")
        assertThat(text("text(1, if(x, '0.0.0', '0'))", x = 0.0)).isEqualTo("1")
    }

    @Test
    fun missingSaysWhenAValueIsNotThere() {
        assertThat(number("missing(nan)")).isEqualTo(1.0)
        assertThat(number("missing(x)", x = 0.0)).isEqualTo(0.0)
        assertThat(number("missing(0 / 0)")).isEqualTo(1.0)
    }

    // ---- characters ----

    @Test
    fun aSignOfTwoCharsCountsAsOne() {
        val snow = "❄️"       // two chars, two code points
        val face = "😀"       // one code point, two chars
        assertThat(number("len('$face$face')")).isEqualTo(2.0)
        assertThat(text("left('$face$face', 1)")).isEqualTo(face)
        assertThat(text("right('a$face', 1)")).isEqualTo(face)
        assertThat(text("mid('a${face}b', 2, 1)")).isEqualTo(face)
        assertThat(number("find('b', 'a${face}b')")).isEqualTo(3.0)
        assertThat(text("padLeft('a', 3, '$face')")).isEqualTo("$face${face}a")
        assertThat(number("len('$snow')")).isEqualTo(2.0)
    }

    @Test
    fun aTextIsCutAtTheLimit() {
        assertThat(text("rept('ab', 1000)").length).isEqualTo(TextFunctions.MAX_TEXT)
        assertThat(text("rept('abcdefghij', 15) & rept('abcdefghij', 15)").length).isEqualTo(TextFunctions.MAX_TEXT)
        assertThat(text("padLeft('a', 5000, '0')").length).isEqualTo(TextFunctions.MAX_TEXT)
    }

    // ---- the language and the setting of the watch ----

    @Test
    fun numbersFollowTheLanguage() {
        val german = setting(Locale.GERMANY)
        assertThat(text("fixed(3.14159, 2)", setting = german)).isEqualTo("3,14")
        assertThat(text("fixed(1234.5, 1, 1)", setting = german)).isEqualTo("1.234,5")
        assertThat(text("text(1234.5, '#,##0.0')", setting = german)).isEqualTo("1.234,5")
        assertThat(text("glucose(100, 'mmol')", setting = german)).isEqualTo("5,6")
        assertThat(number("value('12,5')", setting = german)).isEqualTo(12.5)
        assertThat(number("value('12.5')", setting = german)).isEqualTo(12.5)
    }

    @Test
    fun glucoseFollowsTheUnitOfTheWatch() {
        assertThat(text("glucose(x)", x = 100.0, setting = setting(mgdl = true))).isEqualTo("100")
        assertThat(text("glucose(x)", x = 100.0, setting = setting(mgdl = false))).isEqualTo("5.6")
        assertThat(text("glucose(x, 'mgdl')", x = 100.0, setting = setting(mgdl = false))).isEqualTo("100")
    }

    @Test
    fun theClockFollowsTwelveOrTwentyFourHours() {
        val values = values(hour = 13.0, min = 5.0, day = 20776.0)
        val twelve = setting(is24Hour = false).apply { nowMs = this@TextFormulaTest.nowMs }
        assertThat(parse("clock()", twelve).eval(values)).isEqualTo("01:05")
        assertThat(parse("clockHour()", twelve).eval(values)).isEqualTo("01")
        assertThat(parse("clockMinute()", twelve).eval(values)).isEqualTo("05")
        assertThat(parse("ampm()", twelve).eval(values)).isEqualTo("PM")
        assertThat(parse("clockHour() & ':' & clockMinute() & ' ' & ampm()", twelve).eval(values)).isEqualTo("01:05 PM")
    }

    @Test
    fun theDateFollowsTheLanguageAndTheZone() {
        val values = values(hour = 14.0, min = 5.0, day = 20776.0)
        val german = FormulaSetting().apply {
            set(Locale.GERMANY, true, true, ZoneId.of("Europe/Amsterdam"))
            nowMs = this@TextFormulaTest.nowMs
        }
        assertThat(parse("date('EEEE')", german).eval(values)).isEqualTo("Donnerstag")
        assertThat(parse("date('HH:mm')", german).eval(values)).isEqualTo("14:05")
    }

    @Test
    fun aClockTextNeedsAClock() {
        val e = assertThrows<FormulaException> { TextFormula.parse("clock()") { null } }
        assertThat(e.message).contains("there is no clock")
    }

    // ---- what is kept ----

    @Test
    fun aFixedTextIsWorkedOutWhenTheFormulaIsRead() {
        assertThat(parse("'a' & 'b'").isConstant).isTrue()
        assertThat(parse("left('hello', 2) & rept('-', 2)").isConstant).isTrue()
        assertThat(parse("if(1, 'a', 'b')").isConstant).isTrue()
        assertThat(parse("'a' & x").isConstant).isFalse()
    }

    @Test
    fun aTextThatReadsTheSettingIsNeverFixed() {
        // The decimal sign can change between two wakes
        assertThat(parse("fixed(2.5, 1)").isConstant).isFalse()
        assertThat(parse("fixed(2.5, 1)").readsSetting).isTrue()
        assertThat(parse("'a' & 5").readsSetting).isTrue()
        assertThat(parse("upper('a')").readsSetting).isTrue()
        assertThat(parse("'a' & 'b'").readsSetting).isFalse()
        assertThat(Formula.parse("len(fixed(2.5, 1))", names = ::index).readsSetting).isTrue()
        assertThat(Formula.parse("len('abc') + x", names = ::index).readsSetting).isFalse()
    }

    @Test
    fun theSameValuesGiveTheSameObject() {
        val formula = parse("'t = ' & text(floor(t), '0')")
        val first = formula.eval(values(t = 1.2))
        assertThat(first).isEqualTo("t = 1")
        // The time moved on, the whole second did not: no new text
        assertThat(formula.eval(values(t = 1.7))).isSameInstanceAs(first)
        val second = formula.eval(values(t = 2.1))
        assertThat(second).isEqualTo("t = 2")
        assertThat(formula.eval(values(t = 2.9))).isSameInstanceAs(second)
    }

    @Test
    fun aNewSettingMakesANewText() {
        val setting = setting(Locale.US)
        val formula = parse("fixed(x, 1)", setting)
        val first = formula.eval(values(x = 2.5))
        assertThat(first).isEqualTo("2.5")
        assertThat(formula.eval(values(x = 2.5))).isSameInstanceAs(first)
        setting.set(Locale.GERMANY, true, true, utc)
        assertThat(formula.eval(values(x = 2.5))).isEqualTo("2,5")
    }

    @Test
    fun theSameSettingAgainKeepsTheTexts() {
        val setting = setting(Locale.US)
        val generation = setting.generation
        setting.set(Locale.US, true, true, utc)
        assertThat(setting.generation).isEqualTo(generation)
        setting.set(Locale.US, false, true, utc)
        assertThat(setting.generation).isEqualTo(generation + 1)
    }

    @Test
    fun aClockTextIsMadeOnceAMinute() {
        val setting = setting().apply { nowMs = this@TextFormulaTest.nowMs }
        val formula = parse("clock()", setting)
        val first = formula.eval(values(hour = 13.0, min = 5.0, day = 20776.0))
        setting.nowMs += 20_000
        assertThat(formula.eval(values(hour = 13.0, min = 5.0, day = 20776.0))).isSameInstanceAs(first)
        setting.nowMs = nowMs + 60_000
        assertThat(formula.eval(values(hour = 13.0, min = 6.0, day = 20776.0))).isEqualTo("13:06")
    }

    @Test
    fun keptTextWorksOutOnlyWhenAnInputChanged() {
        val setting = setting()
        val kept = KeptText(parse("'x' & x", setting), slots = 2, setting = setting)
        val first = kept.text(values(x = 1.0), 0)
        assertThat(first).isEqualTo("x1")
        assertThat(kept.evals).isEqualTo(1)
        // Another variable changed, the one the formula reads did not
        assertThat(kept.text(values(x = 1.0, t = 5.0), 0)).isSameInstanceAs(first)
        assertThat(kept.evals).isEqualTo(1)
        // The other slot has its own values
        assertThat(kept.text(values(x = 2.0), 1)).isEqualTo("x2")
        assertThat(kept.text(values(x = 1.0), 0)).isSameInstanceAs(first)
        assertThat(kept.text(values(x = 2.0), 1)).isEqualTo("x2")
        assertThat(kept.evals).isEqualTo(2)
        assertThat(kept.text(values(x = 3.0), 0)).isEqualTo("x3")
        assertThat(kept.evals).isEqualTo(3)
        // A new setting makes every kept text stale
        setting.set(Locale.GERMANY, true, true, utc)
        kept.text(values(x = 3.0), 0)
        assertThat(kept.evals).isEqualTo(4)
    }

    @Test
    fun aMissingValueIsTheSameValueAgain() {
        val setting = setting()
        val kept = KeptText(parse("'<' & x & '>'", setting), slots = 1, setting = setting)
        kept.text(values(x = Double.NaN), 0)
        kept.text(values(x = Double.NaN), 0)
        assertThat(kept.evals).isEqualTo(1)
    }

    @Test
    fun aTextFunctionNameCannotBeALetName() {
        for (name in TextFunctions.FUNCTIONS.keys) assertWithMessage(name).that(LayerVariables.isName(name)).isFalse()
        assertThat(LayerVariables.isName("missing")).isFalse()
        assertThat(LayerVariables.isName("label")).isTrue()
    }
}
