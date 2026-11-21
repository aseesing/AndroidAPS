// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import app.aaps.wear.watchfaces.options.DesignOptions
import java.time.LocalDate

/**
 * The data sources of the Watch Face Format (WFF) that a formula can read, written as in WFF:
 * `[HOUR_0_23]`, `[BATTERY_PERCENT]`, `[CONFIGURATION.santa]`. Each one means exactly what it means
 * in WFF, so a formula reads the same on our engine and on a WFF watch face. Where we cannot give the
 * WFF meaning, the source is not here: an unknown source is a mistake of the formula.
 *
 * Most of them are made from our own variables, as a formula (see [formula]). The date, the 12 or 24
 * hours of the watch and the options need a part of their own (see [part]).
 *
 * The WFF reference: https://developer.android.com/reference/wear-os/wff/common/attributes/source-type
 */
object WffSources {

    /**
     * The sources that are a formula of our variables and values. Like in WFF they are whole numbers,
     * except the ones with a part of the minute or of the hour.
     */
    private val FORMULAS: Map<String, String> = mapOf(
        "MILLISECOND" to "round(fract(sec) * 1000)",
        "SECOND" to "floor(sec)",
        "SECOND_MILLISECOND" to "sec",
        "MINUTE" to "min",
        "MINUTE_SECOND" to "min + floor(sec) / 60",
        "SECONDS_IN_DAY" to "hour * 3600 + min * 60 + floor(sec)",
        "HOUR_0_23" to "hour",
        "HOUR_0_11" to "mod(hour, 12)",
        "HOUR_1_12" to "mod(hour + 11, 12) + 1",
        "HOUR_1_24" to "if(hour == 0, 24, hour)",
        "HOUR_0_23_MINUTE" to "hour + min / 60",
        "HOUR_0_11_MINUTE" to "mod(hour, 12) + min / 60",
        "HOUR_1_12_MINUTE" to "mod(hour + 11, 12) + 1 + min / 60",
        "HOUR_1_24_MINUTE" to "if(hour == 0, 24, hour) + min / 60",
        "AMPM_STATE" to "hour >= 12",
        "BATTERY_PERCENT" to "v_watch_battery",
        // The watch app has no step count: `v_steps` is a value nobody fills, so this has no number
        "STEP_COUNT" to "v_steps",
        // The heart rate the engine uses: the one the watch measured in a design that reads it, else the
        // fallback of the design. Reading it counts as reading `bpm`, so the watch measures for it too
        "HEART_RATE" to "bpm",
        "WEATHER.IS_AVAILABLE" to "!missing(v_weather_kind)",
        // Our kinds 0 clear, 1 partly cloudy, 2 cloudy, 3 rain as the WFF codes 1 CLEAR, 14 PARTLY_CLOUDY,
        // 2 CLOUDY, 6 RAIN; 0 (UNKNOWN) without weather
        "WEATHER.CONDITION" to "missing(v_weather_kind) ? 0 : v_weather_kind == 0 ? 1 : v_weather_kind == 1 ? 14 : v_weather_kind == 2 ? 2 : v_weather_kind == 3 ? 6 : 0",
        // The phone sends degrees Celsius, so the unit of the wearer is always Celsius (1) here
        "WEATHER.TEMPERATURE" to "round(v_weather_temperature)",
        "WEATHER.TEMPERATURE_UNIT" to "1"
    )

    /** The date sources: a part of the date of the day count of the clock. */
    private val DATE_PARTS: Map<String, Int> = mapOf(
        "DAY" to DatePart.DAY, "DAY_0_30" to DatePart.DAY_0_30, "MONTH" to DatePart.MONTH, "MONTH_0_11" to DatePart.MONTH_0_11,
        "YEAR" to DatePart.YEAR, "DAY_OF_YEAR" to DatePart.DAY_OF_YEAR, "DAYS_IN_MONTH" to DatePart.DAYS_IN_MONTH,
        "DAY_OF_WEEK" to DatePart.DAY_OF_WEEK
    )

    private const val CONFIGURATION = "CONFIGURATION."
    private val OPTION_NAME = Regex("[A-Za-z0-9_]+")

    /** The names of all sources, for the tests and the manual. `CONFIGURATION.<name>` is one per option. */
    val NAMES: Set<String> get() = FORMULAS.keys + DATE_PARTS.keys + "IS_24_HOUR_MODE" + "MOON_PHASE_POSITION"

    /** The formula of the source [name] in our variables, or null when it is not made that way. */
    internal fun formula(name: String): String? = FORMULAS[name]

    /**
     * The part of the source [name] that is not a formula, or null when there is no such source.
     *
     * @param index the index of one of our variables; throws when the formula cannot read it here
     */
    internal fun part(name: String, setting: FormulaSetting, index: (String) -> Int): Part? {
        DATE_PARTS[name]?.let { return DatePart(it, Var(index(LayerVariables.DAY_NAME)), name) }
        if (name == "MOON_PHASE_POSITION") {
            // The moon of the clock's moment, so only where there is a clock (see MoonQuarters)
            index(LayerVariables.DAY_NAME)
            return MoonPosition(setting)
        }
        if (name == "IS_24_HOUR_MODE") {
            // Only where there is a clock: there the setting is the one of the watch (in a look it is not set)
            index(LayerVariables.DAY_NAME)
            return Is24Hour(setting)
        }
        if (name.startsWith(CONFIGURATION)) {
            val option = name.removePrefix(CONFIGURATION)
            if (!OPTION_NAME.matches(option)) return null
            return OptionText(Var(index(LayerVariables.VALUE_PREFIX + DesignOptions.VALUE_PREFIX + option)), setting)
        }
        return null
    }
}

/**
 * A part of the date of [day], the day count of the clock (days since 1970 in the zone of the watch).
 * The date is worked out again only on another day. [sourceName] is the WFF name, like `DAY_OF_WEEK`.
 */
internal class DatePart(private val kind: Int, private val day: Node, val sourceName: String) : Node() {

    private var lastDay = Double.NaN
    private var last = Double.NaN

    override fun eval(v: DoubleArray): Double {
        val d = day.eval(v)
        if (d.toRawBits() == lastDay.toRawBits()) return last
        lastDay = d
        last = if (d.isNaN()) Double.NaN else of(LocalDate.ofEpochDay(d.toLong()))
        return last
    }

    private fun of(date: LocalDate): Double = when (kind) {
        DAY           -> date.dayOfMonth
        DAY_0_30      -> date.dayOfMonth - 1
        MONTH         -> date.monthValue
        MONTH_0_11    -> date.monthValue - 1
        // WFF keeps the year from 1902 to 2100
        YEAR          -> date.year.coerceIn(1902, 2100)
        DAY_OF_YEAR   -> date.dayOfYear
        DAYS_IN_MONTH -> date.lengthOfMonth()
        // WFF: 1 is Sunday, 7 is Saturday (java.time: 1 is Monday, 7 is Sunday)
        else          -> date.dayOfWeek.value % 7 + 1
    }.toDouble()

    override fun children(): List<Part> = listOf(day)

    companion object {

        const val DAY = 0
        const val DAY_0_30 = 1
        const val MONTH = 2
        const val MONTH_0_11 = 3
        const val YEAR = 4
        const val DAY_OF_YEAR = 5
        const val DAYS_IN_MONTH = 6
        const val DAY_OF_WEEK = 7
    }
}

/** `[MOON_PHASE_POSITION]`: 0 at new moon to 28, at the clock's moment ([FormulaSetting.nowMs]), see [MoonQuarters]. */
internal class MoonPosition(private val setting: FormulaSetting) : Node() {

    override val usesSetting: Boolean get() = true
    override fun eval(v: DoubleArray): Double = MoonQuarters.position(setting.nowMs)
}

/** `[IS_24_HOUR_MODE]`: 1 when the watch shows 24 hours, else 0. */
internal class Is24Hour(private val setting: FormulaSetting) : Node() {

    override val usesSetting: Boolean get() = true
    override fun eval(v: DoubleArray): Double = if (setting.is24Hour) 1.0 else 0.0
}

/**
 * `[CONFIGURATION.<name>]`: the chosen choice of an option as a text, "0" for the first, as the id of
 * a WFF list option. So it compares with a text: `[CONFIGURATION.santa] == "1"`. Written without the
 * signs of the language, and empty when there is no value.
 */
internal class OptionText(choice: Node, setting: FormulaSetting) : KeptTextNode(arrayOf<Part>(choice), setting) {

    override fun make(n: DoubleArray, t: Array<String?>): String = if (n[0].isNaN()) "" else n[0].toLong().toString()
}
