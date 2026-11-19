// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import app.aaps.core.data.configuration.Constants
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.ParsePosition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the text functions of a formula need to know about the watch: its language, 12 or 24 hours,
 * the glucose unit, and the clock time and zone of the frame. One per animation; the animator sets
 * it once per wake (and the clock time per frame).
 *
 * [generation] changes each time the language, the hours, the unit or the zone changes, so a kept
 * text is made again then.
 */
class FormulaSetting {

    var locale: Locale = Locale.getDefault()
        private set
    var is24Hour: Boolean = true
        private set

    /** True for mg/dL, false for mmol/L. */
    var unitsMgdl: Boolean = true
        private set
    var zone: ZoneId = ZoneId.systemDefault()
        private set

    /** The clock time of the frame, in epoch ms: the time `clock()` and `date()` write. */
    var nowMs: Long = 0L

    var generation: Int = 0
        private set

    /** The signs of numbers in [locale]: the decimal sign, the minus, the digits. */
    var symbols: DecimalFormatSymbols = DecimalFormatSymbols.getInstance(locale)
        private set

    /** Sets what the watch says now. Only a real change makes the kept texts stale. */
    fun set(locale: Locale, is24Hour: Boolean, unitsMgdl: Boolean, zone: ZoneId = this.zone) {
        if (locale == this.locale && is24Hour == this.is24Hour && unitsMgdl == this.unitsMgdl && zone == this.zone) return
        if (locale != this.locale) symbols = DecimalFormatSymbols.getInstance(locale)
        this.locale = locale
        this.is24Hour = is24Hour
        this.unitsMgdl = unitsMgdl
        this.zone = zone
        generation++
    }

    /** Sets the zone of the clock texts. */
    fun setZone(zone: ZoneId) = set(locale, is24Hour, unitsMgdl, zone)
}

/**
 * The functions of a formula that take or give a text, in the style of a spreadsheet. Positions count
 * from 1 and a character is one Unicode code point, so a sign made of two `Char`s is never cut in half.
 *
 * Every number is written with the signs of the watch language ([FormulaSetting.locale]); no text of
 * a language is ever split or changed here to get there.
 *
 * @param setting what the watch says, read when a text is made
 * @param names the index of a variable; the clock functions read `hour`, `min` and the day through it
 */
internal class TextFunctions(private val setting: FormulaSetting, private val names: (String) -> Int?) {

    /** The part for the call of [name] with [args], or null when [name] is not one of these functions. */
    fun call(name: String, args: List<Part>): Part? {
        val params = PARAMS[name] ?: return null
        val arity = FUNCTIONS.getValue(name)
        if (args.size !in arity) throw FormulaException("'$name' takes ${arity.first}..${arity.last} values, not ${args.size}")
        val parts = Array(args.size) { i ->
            if (params[i] == 't') asText(args[i])
            else args[i] as? Node ?: throw FormulaException("'$name' needs a number as value ${i + 1}, not a text")
        }
        return when (name) {
            "len"          -> TextNumber(parts) { _, t -> count(t[0]!!).toDouble() }
            "find"         -> TextNumber(parts) { n, t -> find(t[0]!!, t[1]!!, if (parts.size > 2) whole(n[2]) else 1).toDouble() }
            "value"        -> TextNumber(parts, usesSetting = true) { _, t -> value(t[0]!!) }
            "upper"        -> TextCall(parts, setting, usesSetting = true) { _, t, s -> t[0]!!.uppercase(s.locale) }
            "lower"        -> TextCall(parts, setting, usesSetting = true) { _, t, s -> t[0]!!.lowercase(s.locale) }
            "concat"       -> join(parts)
            "left"         -> TextCall(parts, setting) { n, t, _ -> left(t[0]!!, whole(n[1])) }
            "right"        -> TextCall(parts, setting) { n, t, _ -> right(t[0]!!, whole(n[1])) }
            "mid"          -> TextCall(parts, setting) { n, t, _ -> mid(t[0]!!, whole(n[1]), whole(n[2])) }
            "trim"         -> TextCall(parts, setting) { _, t, _ -> trim(t[0]!!) }
            "rept"         -> TextCall(parts, setting) { n, t, _ -> rept(t[0]!!, whole(n[1])) }
            "substitute"   -> TextCall(parts, setting) { _, t, _ -> if (t[1]!!.isEmpty()) t[0]!! else cut(t[0]!!.replace(t[1]!!, t[2]!!)) }
            "padLeft"      -> TextCall(parts, setting) { n, t, _ -> pad(t[0]!!, whole(n[1]), if (parts.size > 2) t[2]!! else " ", atStart = true) }
            "padRight"     -> TextCall(parts, setting) { n, t, _ -> pad(t[0]!!, whole(n[1]), if (parts.size > 2) t[2]!! else " ", atStart = false) }
            "fixed"        -> NumberText(NumberText.FIXED, parts, setting)
            "text"         -> NumberText(NumberText.PATTERN, parts, setting).also { checkPattern(parts[1]) { p -> DecimalFormat(p) } }
            "glucose"      -> NumberText(NumberText.GLUCOSE, parts, setting)
            "clock"        -> ClockText(ClockText.CLOCK, clockParts(name, null), setting)
            "clockHour"    -> ClockText(ClockText.HOUR, clockParts(name, null), setting)
            "clockMinute"  -> ClockText(ClockText.MINUTE, clockParts(name, null), setting)
            "ampm"         -> ClockText(ClockText.AM_PM, clockParts(name, null), setting)
            "date"         -> ClockText(ClockText.PATTERN, clockParts(name, parts[0]), setting).also { checkPattern(parts[0]) { p -> DateTimeFormatter.ofPattern(p) } }
            // The same names as in the Watch Face Format, with the same meaning
            "textLength"   -> TextNumber(parts) { _, t -> count(t[0]!!).toDouble() }
            "subText"      -> TextCall(parts, setting) { n, t, _ -> subText(t[0]!!, whole(n[1]), whole(n[2])) }
            "numberFormat" -> NumberText(NumberText.NUMBER_FORMAT, parts, setting).also { checkPattern(parts[0]) { p -> DecimalFormat(p) } }
            "icuText"      -> ClockText(ClockText.ICU, clockParts(name, parts[0]), setting).also {
                checkPattern(parts[0]) { p -> require(isIcuPattern(p)) { p }; DateTimeFormatter.ofPattern(p) }
            }
            else           -> throw IllegalStateException(name)
        }
    }

    /** [part] as a text: a number is written like a spreadsheet does by itself (see [NumberText.GENERAL]). */
    fun asText(part: Part): TextNode = part as? TextNode ?: NumberText(NumberText.GENERAL, arrayOf(part), setting)

    /** The texts of [parts] one after the other (`&` and `concat`). */
    fun join(parts: Array<Part>): TextNode = TextCall(parts, setting) { _, t, _ ->
        var length = 0
        for (s in t) length += s!!.length
        val all = StringBuilder(length)
        for (s in t) all.append(s)
        cut(all.toString())
    }

    /** A fixed format that the watch cannot read is a mistake of the design: said when the formula is read. */
    private fun checkPattern(part: Part, read: (String) -> Unit) {
        val pattern = (part as? TextConst)?.value ?: return
        try {
            read(pattern)
        } catch (_: IllegalArgumentException) {
            throw FormulaException("'$pattern' is not a format")
        }
    }

    /** The day, the hour and the minute as hidden values of a clock text, so it is made again when they change; after [first]. */
    private fun clockParts(name: String, first: Part?): Array<Part> {
        val clock = listOf(LayerVariables.DAY_NAME, "hour", "min").map { Var(names(it) ?: throw FormulaException("'$name' cannot be used here: there is no clock")) }
        return (listOfNotNull(first) + clock).toTypedArray()
    }

    private fun value(text: String): Double {
        val trimmed = text.trim()
        trimmed.toDoubleOrNull()?.let { return it }
        if (trimmed.isEmpty()) return Double.NaN
        val position = ParsePosition(0)
        val number = DecimalFormat("0.#", setting.symbols).parse(trimmed, position)
        return if (number != null && position.index == trimmed.length) number.toDouble() else Double.NaN
    }

    companion object {

        /** The most characters of a text; a longer one is cut. */
        const val MAX_TEXT = 200

        /** The kind of each value a function takes: `t` a text (a number is written as one), `n` a number. */
        private val PARAMS: Map<String, String> = mapOf(
            "concat" to "tttttttt", "left" to "tn", "right" to "tn", "mid" to "tnn", "len" to "t", "upper" to "t", "lower" to "t",
            "trim" to "t", "rept" to "tn", "substitute" to "ttt", "find" to "ttn", "padLeft" to "tnt", "padRight" to "tnt",
            "text" to "nt", "fixed" to "nnn", "value" to "t", "glucose" to "nt",
            "clock" to "", "clockHour" to "", "clockMinute" to "", "ampm" to "", "date" to "t",
            "textLength" to "t", "subText" to "tnn", "numberFormat" to "tn", "icuText" to "t"
        )

        /** The names of the text functions, with the number of values each takes. */
        val FUNCTIONS: Map<String, IntRange> = mapOf(
            "concat" to 2..8, "left" to 2..2, "right" to 2..2, "mid" to 3..3, "len" to 1..1, "upper" to 1..1, "lower" to 1..1,
            "trim" to 1..1, "rept" to 2..2, "substitute" to 3..3, "find" to 2..3, "padLeft" to 2..3, "padRight" to 2..3,
            "text" to 2..2, "fixed" to 2..3, "value" to 1..1, "glucose" to 1..2,
            "clock" to 0..0, "clockHour" to 0..0, "clockMinute" to 0..0, "ampm" to 0..0, "date" to 1..1,
            "textLength" to 1..1, "subText" to 3..3, "numberFormat" to 2..2, "icuText" to 1..1
        )

        /** [x] as a whole number for a count or a position: cut towards 0, a missing value is 0. */
        fun whole(x: Double): Int = if (x.isNaN()) 0 else x.coerceIn(-1_000_000.0, 1_000_000.0).toInt()

        fun count(text: String): Int = text.codePointCount(0, text.length)

        /** [text] cut to [MAX_TEXT] characters. */
        fun cut(text: String): String = if (text.length <= MAX_TEXT) text else left(text, MAX_TEXT)

        fun left(text: String, n: Int): String = when {
            n <= 0           -> ""
            n >= count(text) -> text
            else             -> text.substring(0, text.offsetByCodePoints(0, n))
        }

        fun right(text: String, n: Int): String {
            val size = count(text)
            return when {
                n <= 0    -> ""
                n >= size -> text
                else      -> text.substring(text.offsetByCodePoints(0, size - n))
            }
        }

        /** [n] characters of [text] from position [start] (1 is the first). */
        fun mid(text: String, start: Int, n: Int): String {
            val size = count(text)
            val first = (start - 1).coerceAtLeast(0)
            if (n <= 0 || first >= size) return ""
            val from = text.offsetByCodePoints(0, first)
            return if (first + n >= size) text.substring(from) else text.substring(from, text.offsetByCodePoints(from, n))
        }

        /**
         * The characters of [text] from position [start] up to, not with, position [end], counted from 0,
         * as `subText` of the Watch Face Format: `subText('abc def', 2, 5)` is "c d". Positions outside the
         * text count as its start or its end.
         */
        fun subText(text: String, start: Int, end: Int): String {
            val size = count(text)
            val from = start.coerceIn(0, size)
            val to = end.coerceIn(0, size)
            if (to <= from) return ""
            val first = text.offsetByCodePoints(0, from)
            return text.substring(first, text.offsetByCodePoints(first, to - from))
        }

        /**
         * The pattern letters of `icuText` that ICU (the Watch Face Format) and java.time read the same:
         * era, year, month, day, day of the week, am/pm, the hours, minutes, seconds, part of a second
         * and the name of the zone.
         */
        private const val ICU_LETTERS = "GyMdEahHkKmsSz"

        /** True when [pattern] uses only [ICU_LETTERS]; text between quotes does not count. */
        fun isIcuPattern(pattern: String): Boolean {
            var quoted = false
            for (c in pattern) {
                if (c == '\'') quoted = !quoted
                else if (!quoted && c.isLetter() && c !in ICU_LETTERS) return false
            }
            return true
        }

        /** No spaces at the ends, and one space where there were more. */
        fun trim(text: String): String {
            if (!text.startsWith(' ') && !text.endsWith(' ') && "  " !in text) return text
            val out = StringBuilder(text.length)
            var space = false
            for (c in text) {
                if (c == ' ') space = out.isNotEmpty()
                else {
                    if (space) out.append(' ')
                    space = false
                    out.append(c)
                }
            }
            return out.toString()
        }

        fun rept(text: String, n: Int): String {
            if (n <= 0 || text.isEmpty()) return ""
            val out = StringBuilder()
            var k = 0
            while (k < n && out.length <= MAX_TEXT) {
                out.append(text)
                k++
            }
            return cut(out.toString())
        }

        /** The position of [part] in [text] at or after position [start], or 0 when it is not there. */
        fun find(part: String, text: String, start: Int): Int {
            val first = (start - 1).coerceAtLeast(0)
            if (first > count(text)) return 0
            val at = text.indexOf(part, text.offsetByCodePoints(0, first))
            return if (at < 0) 0 else text.codePointCount(0, at) + 1
        }

        /** [text] made [n] characters long with [fill], before it ([atStart]) or after it. A longer text stays as it is. */
        fun pad(text: String, n: Int, fill: String, atStart: Boolean): String {
            val missing = n.coerceAtMost(MAX_TEXT) - count(text)
            if (missing <= 0 || fill.isEmpty()) return text
            val fillSize = count(fill)
            val filling = StringBuilder()
            for (k in 0 until missing) filling.appendCodePoint(fill.codePointAt(fill.offsetByCodePoints(0, k % fillSize)))
            return if (atStart) filling.append(text).toString() else text + filling
        }
    }
}

/** The work of a text function: [n] holds the numbers and [t] the texts, each at the place of its value. */
internal fun interface TextWork {

    fun make(n: DoubleArray, t: Array<String?>, setting: FormulaSetting): String
}

/** The work of a function that takes texts and gives a number. */
internal fun interface TextNumberWork {

    fun eval(n: DoubleArray, t: Array<String?>): Double
}

/** A text function that only needs its values (and, with [usesSetting], the language). */
internal class TextCall(args: Array<Part>, setting: FormulaSetting, override val usesSetting: Boolean = false, private val work: TextWork) : KeptTextNode(args, setting) {

    override fun make(n: DoubleArray, t: Array<String?>): String = work.make(n, t, setting)
}

/** A function that takes texts and gives a number, like `len`. */
internal class TextNumber(private val args: Array<Part>, override val usesSetting: Boolean = false, private val work: TextNumberWork) : Node() {

    private val numbers = DoubleArray(args.size)
    private val texts = arrayOfNulls<String>(args.size)

    override fun eval(v: DoubleArray): Double {
        for (i in args.indices) {
            val arg = args[i]
            if (arg is Node) numbers[i] = arg.eval(v) else texts[i] = (arg as TextNode).text(v)
        }
        return work.eval(numbers, texts)
    }

    override fun children(): List<Part> = args.asList()
}

/**
 * A number written as a text, with the signs of the watch language. A missing value (NaN) and an
 * endless one give an empty text. A value that rounds to zero is written without a minus.
 *
 * The format is kept and made again only when the language, the decimals or the pattern change.
 */
internal class NumberText(private val kind: Int, args: Array<Part>, setting: FormulaSetting) : KeptTextNode(args, setting) {

    override val usesSetting: Boolean get() = true

    private var format: DecimalFormat? = null
    private var formatGeneration = -1
    private var formatDecimals = -1
    private var formatGrouping = false
    private var formatPattern: String? = null

    override fun make(n: DoubleArray, t: Array<String?>): String {
        // numberFormat(pattern, x) has the number second
        val x = if (kind == NUMBER_FORMAT) n[1] else n[0]
        if (!x.isFinite()) return ""
        return when (kind) {
            GENERAL       -> write(x, format(MAX_GENERAL_DECIMALS, 0, false))
            FIXED         -> TextFunctions.whole(n[1]).coerceIn(0, MAX_DECIMALS).let { write(x, format(it, it, args.size > 2 && n[2] != 0.0 && !n[2].isNaN())) }
            PATTERN       -> pattern(t[1]!!, RoundingMode.HALF_UP)?.let { write(x, it) } ?: ""
            // As java.text.DecimalFormat does by itself, which the Watch Face Format refers to: a half goes to the even digit
            NUMBER_FORMAT -> pattern(t[0]!!, RoundingMode.HALF_EVEN)?.let { write(x, it) } ?: ""
            else    -> {
                val unit = if (args.size > 1) t[1]!!.lowercase(Locale.ROOT) else ""
                val mgdl = if (unit.startsWith("mmol")) false else if (unit.startsWith("mg")) true else setting.unitsMgdl
                if (mgdl) write(x, format(0, 0, false)) else write(x * Constants.MGDL_TO_MMOLL, format(1, 1, false))
            }
        }
    }

    private fun write(x: Double, format: DecimalFormat): String {
        val text = format.format(x)
        // -0.04 with one decimal is "0.0", not "-0.0"
        return if (x < 0 && format.format(-x) == format.format(0.0)) format.format(0.0) else TextFunctions.cut(text)
    }

    /** A format with at most [most] and at least [least] decimals. */
    private fun format(most: Int, least: Int, grouping: Boolean): DecimalFormat {
        format?.let { if (formatPattern == null && formatGeneration == setting.generation && formatDecimals == most && formatGrouping == grouping) return it }
        return DecimalFormat("0", setting.symbols).also {
            it.maximumFractionDigits = most
            it.minimumFractionDigits = least
            it.isGroupingUsed = grouping
            it.groupingSize = 3
            it.roundingMode = RoundingMode.HALF_UP
            keep(it, most, grouping, null)
        }
    }

    /** The format of the spreadsheet style [pattern] that rounds a half by [rounding], or null when it is not one. */
    private fun pattern(pattern: String, rounding: RoundingMode): DecimalFormat? {
        format?.let { if (formatPattern === pattern && formatGeneration == setting.generation) return it }
        return try {
            DecimalFormat(pattern, setting.symbols).also {
                it.roundingMode = rounding
                keep(it, -1, false, pattern)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun keep(made: DecimalFormat, decimals: Int, grouping: Boolean, pattern: String?) {
        format = made
        formatGeneration = setting.generation
        formatDecimals = decimals
        formatGrouping = grouping
        formatPattern = pattern
    }

    companion object {

        /** Like a spreadsheet writes a number by itself: whole without decimals, else at most [MAX_GENERAL_DECIMALS]. */
        const val GENERAL = 0

        /** `fixed(x, decimals [, grouping])`. */
        const val FIXED = 1

        /** `text(x, format)`. */
        const val PATTERN = 2

        /** `glucose(mgdl [, unit])`. */
        const val GLUCOSE = 3

        /** `numberFormat(format, x)` of the Watch Face Format: `text(x, format)` with the values the other way round. */
        const val NUMBER_FORMAT = 4

        const val MAX_GENERAL_DECIMALS = 6
        const val MAX_DECIMALS = 10
    }
}

/**
 * The clock time of the frame ([FormulaSetting.nowMs]) as a text, in the zone and the language of the
 * watch. Its hidden values are the day, the hour and the minute, so the text is made again once a
 * minute and not every frame. A pattern with seconds so only changes once a minute.
 */
internal class ClockText(private val kind: Int, args: Array<Part>, setting: FormulaSetting) : KeptTextNode(args, setting) {

    override val usesSetting: Boolean get() = true

    private var formatter: DateTimeFormatter? = null
    private var formatterGeneration = -1
    private var formatterPattern: String? = null

    override fun make(n: DoubleArray, t: Array<String?>): String {
        val pattern = when (kind) {
            CLOCK  -> if (setting.is24Hour) "HH:mm" else "hh:mm"
            HOUR   -> if (setting.is24Hour) "HH" else "hh"
            MINUTE -> "mm"
            AM_PM  -> if (setting.is24Hour) return "" else "a"
            ICU    -> t[0]!!.takeIf { TextFunctions.isIcuPattern(it) } ?: return ""
            else   -> t[0]!!
        }
        val format = formatter?.takeIf { formatterPattern === pattern && formatterGeneration == setting.generation } ?: try {
            DateTimeFormatter.ofPattern(pattern, setting.locale).withZone(setting.zone).also {
                formatter = it
                formatterPattern = pattern
                formatterGeneration = setting.generation
            }
        } catch (_: IllegalArgumentException) {
            return ""
        }
        return try {
            TextFunctions.cut(format.format(Instant.ofEpochMilli(setting.nowMs)))
        } catch (_: RuntimeException) {
            // A pattern that asks for something a moment does not have
            ""
        }
    }

    companion object {

        const val CLOCK = 0
        const val HOUR = 1
        const val MINUTE = 2
        const val AM_PM = 3
        const val PATTERN = 4

        /** `icuText(format)` of the Watch Face Format: `date(format)`, for the letters ICU and java.time read the same. */
        const val ICU = 5
    }
}
