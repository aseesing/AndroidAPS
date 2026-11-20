// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The names of the Watch Face Format (WFF) in our formulas: each one must mean exactly what it means in
 * WFF (https://developer.android.com/reference/wear-os/wff/common/attributes/arithmetic-expression and
 * .../source-type). The last tests pin where we differ on purpose, so nobody "aligns" those by mistake.
 */
class WffNamesTest {

    private val names = listOf(
        "sec", "min", "hour", LayerVariables.DAY_NAME, "v_watch_battery", "v_steps", "v_heart_rate", "v_moon_phase", "v_weather_kind",
        "v_weather_temperature", "v_opt_santa", "x"
    )
    private val utc: ZoneId = ZoneId.of("UTC")
    private val setting = FormulaSetting().apply { set(Locale.US, true, true, utc) }

    private fun index(name: String): Int? = names.indexOf(name).takeIf { it >= 0 }

    /** The values at [time] (UTC) as the animator sets them, through [WallClock], with the other values given. */
    private fun values(
        time: ZonedDateTime = ZonedDateTime.of(2026, 11, 19, 12, 30, 15, 250_000_000, utc), battery: Double = 80.0, steps: Double = 1234.0,
        heartRate: Double = 72.0, moonPhase: Double = 0.25, weatherKind: Double = 0.0, temperature: Double = 21.6, santa: Double = 1.0, x: Double = 0.0
    ): DoubleArray {
        val clock = WallClock().apply {
            zone = utc
            set(time.toInstant().toEpochMilli())
        }
        return doubleArrayOf(clock.second, clock.minute, clock.hour, clock.day, battery, steps, heartRate, moonPhase, weatherKind, temperature, santa, x)
    }

    private fun parse(text: String): Formula = Formula.parse(text, setting = setting, names = ::index)
    private fun eval(text: String, values: DoubleArray = values()): Double = parse(text).eval(values)
    private fun text(text: String, values: DoubleArray = values()): String = TextFormula.parse(text, setting = setting, names = ::index).eval(values)
    private fun at(hour: Int, minute: Int = 0, second: Int = 0, nanos: Int = 0) = values(ZonedDateTime.of(2026, 11, 19, hour, minute, second, nanos, utc))

    private fun assertError(text: String, part: String) {
        val e = assertThrows<FormulaException> { TextFormula.parse(text, setting = setting, names = ::index) }
        assertWithMessage("error of '$text'").that(e.message).contains(part)
    }

    // ---- c ? a : b ----

    @Test
    fun questionMarkChoosesLikeIf() {
        assertThat(eval("1 ? 2 : 3")).isEqualTo(2.0)
        assertThat(eval("0 ? 2 : 3")).isEqualTo(3.0)
        assertThat(eval("x > 1 ? 10 : 20", values(x = 5.0))).isEqualTo(10.0)
        // A missing value is false, as for if
        assertThat(eval("(0 / 0) ? 2 : 3")).isEqualTo(3.0)
        assertThat(text("x > 1 ? 'big' : 'small'", values(x = 0.0))).isEqualTo("small")
    }

    @Test
    fun questionMarkIsTheWeakestAndGoesFromTheRight() {
        // The other operators are worked out first, on both sides
        assertThat(eval("1 ? 2 : 3 + 1")).isEqualTo(2.0)
        assertThat(eval("0 ? 2 : 3 + 1")).isEqualTo(4.0)
        assertThat(eval("(1 ? 2 : 3) + 1")).isEqualTo(3.0)
        assertThat(eval("1 > 2 || 1 ? 5 : 6")).isEqualTo(5.0)
        // a ? b : c ? d : e is a ? b : (c ? d : e)
        assertThat(eval("0 ? 1 : 0 ? 2 : 3")).isEqualTo(3.0)
        assertThat(eval("0 ? 1 : 1 ? 2 : 3")).isEqualTo(2.0)
        assertThat(eval("1 ? 0 ? 7 : 8 : 9")).isEqualTo(8.0)
        // Also inside the values of a function
        assertThat(eval("max(1 ? 4 : 5, 2)")).isEqualTo(4.0)
    }

    @Test
    fun questionMarkWorksOutOnlyTheChosenSide() {
        // y is index 99: reading it would throw
        val f = Formula.parse("x ? 1 : y") { name -> if (name == "x") 0 else if (name == "y") 99 else null }
        assertThat(f.eval(doubleArrayOf(1.0))).isEqualTo(1.0)
    }

    @Test
    fun questionMarkMistakes() {
        assertError("1 ? 2", "needs a ':'")
        assertError("1 ? 2 : 'a'", "two numbers or two texts")
        assertError("1 : 2", "unexpected ':'")
        assertError("'a' ? 1 : 2", "needs a number")
    }

    // ---- the data sources [NAME] ----

    @Test
    fun everySourceCanBeRead() {
        // Each is read as a valid formula of our variables, and gives a number at a normal moment
        for (name in WffSources.NAMES) assertWithMessage(name).that(eval("[$name]").isNaN()).isFalse()
    }

    @Test
    fun theTimeOfDay() {
        val v = at(12, 30, 15, 250_000_000)
        assertThat(eval("[SECOND]", v)).isEqualTo(15.0)
        assertThat(eval("[SECOND_MILLISECOND]", v)).isEqualTo(15.25)
        assertThat(eval("[MILLISECOND]", v)).isEqualTo(250.0)
        assertThat(eval("[MINUTE]", v)).isEqualTo(30.0)
        // Whole seconds only, like WFF: 0 to 59 59/60
        assertThat(eval("[MINUTE_SECOND]", v)).isEqualTo(30.25)
        assertThat(eval("[SECONDS_IN_DAY]", v)).isEqualTo(12 * 3600.0 + 30 * 60 + 15)
        assertThat(eval("[HOUR_0_23_MINUTE]", v)).isEqualTo(12.5)
        // The last millisecond of the day
        val last = at(23, 59, 59, 999_000_000)
        assertThat(eval("[MILLISECOND]", last)).isEqualTo(999.0)
        assertThat(eval("[SECOND]", last)).isEqualTo(59.0)
        assertThat(eval("[SECONDS_IN_DAY]", last)).isEqualTo(86399.0)
    }

    @Test
    fun theHoursInEachWay() {
        // hour -> [HOUR_0_23], [HOUR_0_11], [HOUR_1_12], [HOUR_1_24], [AMPM_STATE]
        val table = mapOf(
            0 to listOf(0, 0, 12, 24, 0), 1 to listOf(1, 1, 1, 1, 0), 11 to listOf(11, 11, 11, 11, 0),
            12 to listOf(12, 0, 12, 12, 1), 13 to listOf(13, 1, 1, 13, 1), 23 to listOf(23, 11, 11, 23, 1)
        )
        for ((hour, expected) in table) {
            val v = at(hour, 30)
            val got = listOf("[HOUR_0_23]", "[HOUR_0_11]", "[HOUR_1_12]", "[HOUR_1_24]", "[AMPM_STATE]").map { eval(it, v).toInt() }
            assertWithMessage("hour $hour").that(got).isEqualTo(expected)
            assertWithMessage("hour $hour").that(eval("[HOUR_1_12_MINUTE]", v)).isEqualTo(expected[2] + 0.5)
            assertWithMessage("hour $hour").that(eval("[HOUR_0_11_MINUTE]", v)).isEqualTo(expected[1] + 0.5)
            assertWithMessage("hour $hour").that(eval("[HOUR_1_24_MINUTE]", v)).isEqualTo(expected[3] + 0.5)
        }
    }

    @Test
    fun theDate() {
        // Thursday 19 November 2026
        val v = at(12)
        assertThat(eval("[DAY]", v)).isEqualTo(19.0)
        assertThat(eval("[DAY_0_30]", v)).isEqualTo(18.0)
        assertThat(eval("[MONTH]", v)).isEqualTo(11.0)
        assertThat(eval("[MONTH_0_11]", v)).isEqualTo(10.0)
        assertThat(eval("[YEAR]", v)).isEqualTo(2026.0)
        assertThat(eval("[DAY_OF_YEAR]", v)).isEqualTo(LocalDate.of(2026, 11, 19).dayOfYear.toDouble())
        assertThat(eval("[DAYS_IN_MONTH]", v)).isEqualTo(30.0)
        assertThat(eval("[DAYS_IN_MONTH]", values(ZonedDateTime.of(2028, 2, 10, 12, 0, 0, 0, utc)))).isEqualTo(29.0)
    }

    @Test
    fun theDayOfTheWeekStartsOnSunday() {
        // 15 November 2026 is a Sunday: 1, up to Saturday 21 November: 7
        for (day in 15..21) {
            val v = values(ZonedDateTime.of(2026, 11, day, 9, 0, 0, 0, utc))
            assertWithMessage("$day November").that(eval("[DAY_OF_WEEK]", v)).isEqualTo(day - 14.0)
        }
    }

    @Test
    fun theDateFollowsTheZoneOfTheClock() {
        // 23:30 UTC on 31 December is already 1 January in Amsterdam
        val ms = ZonedDateTime.of(2026, 12, 31, 23, 30, 0, 0, utc).toInstant().toEpochMilli()
        val clock = WallClock().apply {
            zone = ZoneOffset.ofHours(1)
            set(ms)
        }
        val v = values().also { it[3] = clock.day }
        assertThat(eval("[DAY] * 10000 + [MONTH] * 100 + [YEAR] - 2000", v)).isEqualTo(10127.0)
    }

    @Test
    fun the24HoursOfTheWatch() {
        val f = parse("[IS_24_HOUR_MODE]")
        assertThat(f.eval(values())).isEqualTo(1.0)
        setting.set(Locale.US, false, true, utc)
        assertThat(f.eval(values())).isEqualTo(0.0)
    }

    @Test
    fun theValuesOfTheWatch() {
        assertThat(eval("[BATTERY_PERCENT]", values(battery = 55.0))).isEqualTo(55.0)
        assertThat(eval("[STEP_COUNT]", values(steps = 4321.0))).isEqualTo(4321.0)
        assertThat(eval("[HEART_RATE]", values(heartRate = 64.0))).isEqualTo(64.0)
        // No value stays no value (WFF does not say what it gives)
        assertThat(eval("[HEART_RATE]", values(heartRate = Double.NaN))).isNaN()
    }

    /** The values the WFF runtime showed on the Wear OS 6 emulator at these moments (UTC), to 4 decimals. */
    @Test
    fun theMoonPhasePositionIsTheOneOfWff() {
        val shown = listOf(
            "2026-11-19T23:00" to 9.4221, "2026-11-20T05:00" to 9.6676, "2026-11-20T11:00" to 9.9131, "2026-11-20T17:00" to 10.1586,
            "2026-11-21T23:00" to 11.3859, "2026-12-19T23:00" to 9.7908, "2027-06-01T12:00" to 24.7866, "2026-11-10T12:00" to 1.0305,
            "2026-11-10T00:00" to 0.6035, "2026-11-09T12:00" to 0.1766, "2025-01-01T12:00" to 1.5509, "2020-01-07T12:00" to 10.9578
        )
        val position = parse("[MOON_PHASE_POSITION]")
        for ((moment, wff) in shown) {
            setting.nowMs = LocalDateTime.parse(moment).toInstant(ZoneOffset.UTC).toEpochMilli()
            assertWithMessage(moment).that(position.eval(values())).isWithin(0.003).of(wff)
        }
    }

    @Test
    fun theWeatherInTheCodesOfWff() {
        // Our kinds 0 clear, 1 partly cloudy, 2 cloudy, 3 rain
        val codes = listOf(0.0 to 1.0, 1.0 to 14.0, 2.0 to 2.0, 3.0 to 6.0, Double.NaN to 0.0)
        for ((kind, code) in codes) {
            assertWithMessage("kind $kind").that(eval("[WEATHER.CONDITION]", values(weatherKind = kind))).isEqualTo(code)
            assertWithMessage("kind $kind").that(eval("[WEATHER.IS_AVAILABLE]", values(weatherKind = kind))).isEqualTo(if (kind.isNaN()) 0.0 else 1.0)
        }
        // A whole number, in the unit [WEATHER.TEMPERATURE_UNIT] says: 1, Celsius
        assertThat(eval("[WEATHER.TEMPERATURE]", values(temperature = 21.6))).isEqualTo(22.0)
        assertThat(eval("[WEATHER.TEMPERATURE]", values(temperature = -3.5))).isEqualTo(-3.0)
        assertThat(eval("[WEATHER.TEMPERATURE_UNIT]")).isEqualTo(1.0)
    }

    @Test
    fun anOptionIsATextLikeTheIdOfAWffListOption() {
        assertThat(text("[CONFIGURATION.santa]", values(santa = 2.0))).isEqualTo("2")
        assertThat(eval("[CONFIGURATION.santa] == \"1\"", values(santa = 1.0))).isEqualTo(1.0)
        assertThat(eval("[CONFIGURATION.santa] == '1'", values(santa = 0.0))).isEqualTo(0.0)
        // No digits of the language: the id is the same everywhere
        val arabic = FormulaSetting().apply { set(Locale.forLanguageTag("ar-EG"), true, true, utc) }
        assertThat(TextFormula.parse("[CONFIGURATION.santa]", setting = arabic, names = ::index).eval(values(santa = 3.0))).isEqualTo("3")
        // WFF compares an option as a text: a number is a mistake here
        assertError("[CONFIGURATION.santa] == 1", "cannot compare a text with a number")
    }

    @Test
    fun aSourceIsNotChangedByTheValuesOfAnOwnFunction() {
        val functions = DesignFunctions(listOf(DesignFunction.read("f(hour)", "[HOUR_1_12] + hour")), ::index)
        val f = Formula.parse("f(100)", setting = setting, functions = functions, names = ::index)
        assertThat(f.eval(at(13))).isEqualTo(101.0)
    }

    @Test
    fun sourceMistakes() {
        assertError("[NO_SUCH_SOURCE]", "unknown data source '[NO_SUCH_SOURCE]'")
        assertError("[HOUR_0_23", "has no closing ']'")
        assertError("[CONFIGURATION.a-b]", "unknown data source")
        // Without a clock (a formula of a look, say) the time cannot be read
        val e = assertThrows<FormulaException> { Formula.parse("[HOUR_0_23]") { null } }
        assertThat(e.message).contains("cannot be used here")
        val d = assertThrows<FormulaException> { Formula.parse("[DAY]") { null } }
        assertThat(d.message).contains("cannot be used here")
        // Nor the 12 or 24 hours: only an animation sets them
        val h = assertThrows<FormulaException> { Formula.parse("[IS_24_HOUR_MODE]") { null } }
        assertThat(h.message).contains("cannot be used here")
        // The values work everywhere, as v_watch_battery does in a look
        assertThat(Formula.parse("[BATTERY_PERCENT] < 20") { name -> if (name == "v_watch_battery") 0 else null }.eval(doubleArrayOf(10.0))).isEqualTo(1.0)
    }

    // ---- the functions ----

    @Test
    fun roundIsJavaMathRound() {
        // WFF: "the standard round()" of Java: a half goes up, also below 0
        for (x in listOf(-3.5, -2.5, -1.5, -0.5, -0.4, 0.0, 0.4, 0.5, 1.5, 2.5, 3.49, 3.5)) {
            assertWithMessage("round($x)").that(eval("round($x)")).isEqualTo(Math.round(x).toDouble())
        }
    }

    @Test
    fun theAnglesAreRadians() {
        assertThat(eval("sin(pi / 2)")).isEqualTo(1.0)
        assertThat(eval("deg(asin(1))")).isWithin(1e-12).of(90.0)
        assertThat(eval("deg(acos(-1))")).isWithin(1e-12).of(180.0)
        assertThat(eval("deg(atan(1))")).isWithin(1e-12).of(45.0)
        assertThat(eval("sin(rad(30))")).isWithin(1e-12).of(0.5)
    }

    @Test
    fun log2IsWorkedOutAsWffDoes() {
        // WFF: the base 10 log of x divided by the base 10 log of 2
        assertThat(eval("log2(10)")).isEqualTo(Math.log10(10.0) / Math.log10(2.0))
        assertThat(eval("log(exp(1))")).isEqualTo(1.0)
    }

    @Test
    fun clampTakesTheValueFirst() {
        assertThat(eval("clamp(5, 0, 3)")).isEqualTo(3.0)
        assertThat(eval("clamp(-5, 0, 3)")).isEqualTo(0.0)
    }

    @Test
    fun theTextFunctionsOfWff() {
        assertThat(text("subText('abc def', 2, 5)")).isEqualTo("c d")
        assertThat(eval("textLength('abcdef')")).isEqualTo(6.0)
        assertThat(text("numberFormat('#,##0.0', 1234.56)")).isEqualTo("1,234.6")
        // numberFormat rounds a half to the even digit, as java.text.DecimalFormat; our text() rounds it up
        assertThat(text("numberFormat('0', 2.5)")).isEqualTo("2")
        assertThat(text("text(2.5, '0')")).isEqualTo("3")
    }

    @Test
    fun icuTextNeedsALetter() {
        // The texts themselves need the ICU of Android: see IcuTextTest
        assertError("icuText('-')", "is not a format")
        assertThat(text("icuText(x > 0 ? ':' : 'd')", values(x = 1.0))).isEqualTo("")
    }

    @Test
    fun theNewFunctionNamesCannotBeAName() {
        for (name in listOf("asin", "acos", "atan", "log2", "log10", "cbrt", "expm1", "deg", "rad", "textLength", "subText", "numberFormat", "icuText")) {
            assertWithMessage(name).that(LayerVariables.isName(name)).isFalse()
        }
    }

    // ---- where we differ from WFF on purpose ----

    @Test
    fun divisionByZeroIsNoNumberNotZero() {
        // WFF gives 0 for x / 0; ours gives no number (or an endless one), so a mistake shows
        assertThat(eval("7 / 0")).isPositiveInfinity()
        assertThat(eval("0 / 0")).isNaN()
    }

    @Test
    fun percentIsNeverNegative() {
        // WFF: -1 % 4 is -1 (the rest of Java); ours is mod: 3
        assertThat(eval("-1 % 4")).isEqualTo(3.0)
        assertThat(eval("-1 - 4 * floor(-1 / 4)")).isEqualTo(3.0)
    }

    @Test
    fun andSignJoinsTexts() {
        // WFF: 6 & 3 is the bitwise and, 2; ours joins: "63"
        assertThat(text("6 & 3")).isEqualTo("63")
    }
}
