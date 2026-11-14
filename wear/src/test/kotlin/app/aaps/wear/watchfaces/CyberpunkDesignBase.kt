// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.JsonKeyValues
import app.aaps.shared.impl.weardata.JsonKeys
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import app.aaps.wear.health.HealthKind
import app.aaps.wear.watchfaces.animation.BlinkSpec
import app.aaps.wear.watchfaces.daynight.DayOverlay
import app.aaps.wear.watchfaces.daynight.Orbit
import app.aaps.wear.watchfaces.utils.WatchBatteryLevel
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the four Cyberpunk watch face designs share, and the tests every one of them must pass.
 *
 * The designs are separate faces; the wearer picks one for the day and one for the night in AAPS, and
 * the watch switches between the two whole designs at sunrise and sunset. So no design has a day
 * look (`dayLook`) any more. A design may still have looks for the weather (`weatherLooks`), keyed
 * by the weather alone (`partly`, `cloudy`, `rain`), see [DayOverlay.apply].
 *
 * - [CwfTechDesignTest]: "Cyberpunk Tech", the dark neon HUD with the moon on the edge.
 * - [CwfYellowDesignTest]: "Cyberpunk Yellow", signal yellow with black panels and the sun.
 * - [CwfWeatherDesignTest]: "Cyberpunk Weather", a light look in a colour per weather.
 * - [CwfWildsDesignTest]: "Wilds Weather", a painted landscape per weather with parchment panels.
 *
 * They all have the same layout ([layout]): no analog clock, no hands, no seconds. The time and the
 * glucose value with its arrow are the big things. Next to the glucose sits a small data table
 * (insulin, carbs, basal, reservoir, heart rate, steps). The batteries of the watch and the phone sit
 * side by side at the top right (see [batteryViews]), the graph in a framed panel at the bottom.
 *
 * The general helpers (sample data, rendering, the zip) are in [CwfDesignBase].
 *
 * The font is `cwf-designs/fonts/Cyber.ttf` when that file exists (it is then added to the zip),
 * otherwise the built-in condensed bold font. It is Rajdhani Bold (OFL, see `Cyber-OFL.txt`) cut to
 * the Latin letters, so the zip stays small enough to send to the watch (see [MAX_ZIP_BYTES]):
 * `pyftsubset Rajdhani-Bold.ttf --unicodes="U+0020-024F,U+02B0-02FF,U+1E00-1EFF,U+2000-206F,U+20A0-20CF,U+2100-215F,U+2190-21FF,U+2212,U+2215,U+221E,U+2248,U+2260-2265" --layout-features='*' --no-hinting --drop-tables+=DSIG,FFTM`
 *
 * Sample values only, no real data.
 */
abstract class CyberpunkDesignBase : CwfDesignBase() {

    /** The pictures of this design that the zip must have, besides those every design has. */
    protected abstract val ownPictures: List<String>

    /** The moment the preview of the zip shows. */
    protected abstract val previewInstant: Long

    /** The red of the glucose age and the loop warning. */
    protected abstract val ageColor: String

    /** The looks drawn on the render sheet `<file>_looks.png`. */
    protected abstract val looks: List<Look>

    /** True when the wake-up animation of the look in [weather] has rain. */
    protected abstract fun hasRain(weather: String?): Boolean

    /** True when the wake-up animation of the look in [weather] has glitch bands. */
    protected abstract fun hasGlitch(weather: String?): Boolean

    /** Checks on the rendered [looks], in the same order; for what only this design has. */
    protected open fun checkLooks(design: JSONObject, pictures: List<Bitmap>) {}

    /** One look of the render sheet: its name, the moment and the weather (null: not known). */
    protected data class Look(val name: String, val instant: Long, val weather: String?)

    protected val fontFile = File("cwf-designs/fonts/$CUSTOM_FONT.ttf")

    /** The custom font when the file is there, else a built-in one. */
    protected val fontKey = if (fontFile.exists()) CUSTOM_FONT else "roboto_condensed_bold"
    override fun fonts(): Map<String, ByteArray> = if (fontFile.exists()) mapOf(CUSTOM_FONT to fontFile.readBytes()) else emptyMap()


    /** A text view: where it is, its size, how it is aligned and its colour. */
    protected fun JSONObject.text(
        key: String, left: Int, top: Int, width: Int, height: Int, size: Int, gravity: String, color: String
    ): JSONObject = place(key, left, top, width, height)
        .put("textsize", size).put("gravity", gravity).put("fontColor", color).put("font", fontKey).put("fontStyle", "normal")

    /**
     * One row of the table: a small muted label in the label column and the value, white, aligned left in the
     * one value column that all six rows share (see [VAL_LEFT] and [VAL_RIGHT]).
     */
    private fun JSONObject.row(label: String, value: String, index: Int, labelText: String? = null) {
        val top = TABLE_TOP + index * ROW_HEIGHT
        text(label, TABLE_LEFT, top, ICON_WIDTH, ROW_HEIGHT, LABEL_SIZE, "left", LABEL_COLOR)
        if (labelText != null) getJSONObject(label).put("textvalue", labelText)
        text(value, VAL_LEFT, top, VAL_RIGHT - VAL_LEFT, ROW_HEIGHT, VALUE_SIZE, "left", VALUE_COLOR)
    }

    /**
     * A row of the table for heart rate or steps, as a complication box. The watch draws the ring or the
     * icon in a square at the left of the box and the value right of it, aligned left. Ring and icon are
     * transparent here, so only the value shows, and the box starts so far left ([ROW_BOX_LEFT]) that the
     * value starts where the other values start. The label is a picture in the cover plate.
     */
    private fun JSONObject.rowBox(key: String, index: Int) {
        place(key, ROW_BOX_LEFT, TABLE_TOP + index * ROW_HEIGHT, VAL_RIGHT - ROW_BOX_LEFT, ROW_HEIGHT)
            .put("color", CLEAR).put("borderRadius", 0)
            .put("fontColor", VALUE_COLOR).put("iconColor", CLEAR).put("ringPrimaryColor", CLEAR).put("ringSecondaryColor", CLEAR)
            .put("textsize", VALUE_SIZE).put("font", fontKey).put("fontStyle", "normal").put("gravity", "left")
    }

    /**
     * The layout all four designs share, in the colours of the night: the date, the time, the glucose
     * value with its arrow, the table, the battery and the graph. The age of the glucose value and the
     * loop warning are red ([ageColor]) and only show when they are old (see [ageDynData]). No sun or
     * moon and no animation: each design adds its own.
     */
    protected fun layout(default: CwfData, comment: String): JSONObject = JSONObject(default.json).apply {
        getJSONObject("metadata")
            .put(CwfMetadataKey.CWF_NAME.key, displayName)
            .put(CwfMetadataKey.CWF_FILENAME.key, fileName)
            .put(CwfMetadataKey.CWF_AUTHOR.key, "AAPS (local)")
            .put(CwfMetadataKey.CWF_CREATED_AT.key, "13/11/26")
            .put(CwfMetadataKey.CWF_COMMENT.key, comment)
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4.key, "false")
            // The watch battery is not a complication any more: the design reads it itself (see batteryViews)
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE.key, "true")
            // The four views that show the batteries only show when these are on
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DELTA.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_AVG_DELTA.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_UPLOADER_BATTERY.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_RIG_BATTERY.key, "true")
            // The table needs the plain IOB: with detailed IOB the label view shows the total and the value view the detail
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DETAILED_IOB.key, "false")

        // No seconds: the watch draws once a minute
        put("enableSecond", false)
        // The graph and glucose colours: in range is green, high is yellow, low is red
        put("highColor", HIGH_COLOR).put("midColor", MID_COLOR).put("lowColor", LOW_COLOR)
        put("gridColor", GRID_COLOR).put("carbColor", CARBS_COLOR)
        put("basalBackgroundColor", BASAL_BACK_COLOR).put("basalCenterColor", CYAN)
        put("pointSize", 3)

        // Views this design does not use. The three hands are off; the digital time replaces them.
        for (hidden in listOf(
            "time", "second", "timePeriod", "week_number", "patient_name", "status", "tempTarget",
            "bgi", "complication3", "complication4", "complication5", "hour_hand", "minute_hand", "second_hand"
        )) getJSONObject(hidden).put("visibility", "gone")

        // The background picture
        put("background", JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put("visibility", "visible"))

        // The date, small, at the top
        text("day_name", 154, DATE_TOP, 38, DATE_HEIGHT, DATE_SIZE, "right", DATE_COLOR).put("allCaps", true)
        text("day", 192, DATE_TOP, 20, DATE_HEIGHT, DATE_SIZE, "center", "#FFFFFF")
        text("month", 212, DATE_TOP, 34, DATE_HEIGHT, DATE_SIZE, "left", DATE_COLOR).put("allCaps", true)
        // The big digital time: hour, a fixed ":" and minute (the "time" view would show seconds).
        // The views are taller than the text line (1.15 x size), so no digit is cut.
        text("hour", MINUTE_LEFT - 96, TIME_TOP, 76, TIME_HEIGHT, TIME_SIZE, "right", TIME_COLOR)
        text("freetext1", MINUTE_LEFT - 20, TIME_TOP, 20, TIME_HEIGHT, TIME_SIZE, "center", TIME_COLOR).put("textvalue", ":")
        text("minute", MINUTE_LEFT, TIME_TOP, MINUTE_WIDTH, TIME_HEIGHT, TIME_SIZE, "left", TIME_COLOR)
        // The loop status: no circle, only a small red warning under the glucose age when the loop has
        // not run for 15 minutes or more (see ageDynData). The empty pictures stop the watch drawing
        // its own circle.
        text("loop", LOOP_LBL_LEFT, LOOP_LBL_TOP, LOOP_LBL_WIDTH, LOOP_LBL_HEIGHT, 13, "center", ageColor)
            .put("textvalue", "LOOP %.0f'").put("dynValue", false).put("dynData", LOOP_DYN)

        // The glucose value: the biggest text, with the arrow and its age to the right. The app colours it
        // (and the arrow and the graph points) with green, yellow or red.
        text("sgv", SGV_LEFT, SGV_TOP, SGV_WIDTH, SGV_HEIGHT, SGV_SIZE, "right", "#CCCCCC")
        place("direction", ARROW_LEFT, ARROW_TOP, ARROW_SIZE)
        // The age of the glucose value: only shown, in red, when it is 5 minutes or older (see ageDynData)
        text("timestamp", ARROW_LEFT, ARROW_TOP + ARROW_SIZE + 2, ARROW_SIZE, 22, 18, "center", ageColor).put("dynData", AGE_DYN)

        // The data table on the right. Each row ends where the round screen allows (see rowRight).
        row("iob1", "iob2", 0)
        row("cob1", "cob2", 1)
        row("freetext2", "basalRate", 2, "BAS")
        row("freetext3", "reservoir", 3, "RSV")

        // Heart rate and steps: two more rows of the same table, as row-shaped complication boxes that show
        // only the value. Their labels, a heart and two footprints, are in the cover plate.
        rowBox("complication1", 4)
        rowBox("complication2", 5)
        // The logo is a picture in the cover plate (see drawEmblem)
        // The batteries of the watch and the phone, side by side right of the time
        batteryViews()

        // The graph in its framed panel
        place("chart", CHART_LEFT, CHART_TOP, CHART_WIDTH, CHART_HEIGHT)

        (optJSONObject("dynData") ?: JSONObject().also { put("dynData", it) })
            .put(AGE_DYN, ageDynData("timestamp", 5.0, ageColor))
            .put(LOOP_DYN, ageDynData("loop", 15.0, ageColor).put("invalidImage", BLANK).put("image1", BLANK).put("image2", BLANK).put("textvalue1", "LOOP %.0f'").put("invalidTextvalue", "LOOP --"))
            .put(WATCH_ICON_DYN, batteryIconDynData(WatchBatteryLevel.KEY, BatteryShape.WATCH))
            .put(PHONE_ICON_DYN, batteryIconDynData(PHONE_VALUE_KEY, BatteryShape.PHONE))
            .put(WATCH_NUMBER_DYN, batteryNumberDynData(WatchBatteryLevel.KEY, VALUE_COLOR))
            .put(PHONE_NUMBER_DYN, batteryNumberDynData(PHONE_VALUE_KEY, VALUE_COLOR))
    }

    /** The two kinds of battery icon: the shape of the watch and the shape of the phone. */
    protected enum class BatteryShape(val picturePrefix: String) {

        WATCH("BattWatch"),
        PHONE("BattPhone");

        /** The picture for [step] (1 to [BATT_STEPS], filled to step / steps), or 0 for no value. */
        fun picture(step: Int): String = picturePrefix + step.toString().padStart(2, '0')
    }

    /**
     * The batteries of the watch and the phone, side by side right of the time: in each column the icon
     * (the shape of the watch, the shape of the phone) and the number under it.
     *
     * A Custom watch face has no free picture views, so four views that these designs do not use carry
     * them: `delta` and `avg_delta` the icons, `rig_battery` and `uploader_battery` the numbers. The
     * watch fills each in from its `dynData`: the icon shows the picture of its level, filled from the
     * bottom, coloured by the level; the number shows the value itself. See [batteryIconDynData].
     */
    private fun JSONObject.batteryViews() {
        for ((left, icon, number) in listOf(
            Triple(BATT_WATCH_LEFT, WATCH_ICON_VIEW to WATCH_ICON_DYN, WATCH_NUMBER_VIEW to WATCH_NUMBER_DYN),
            Triple(BATT_PHONE_LEFT, PHONE_ICON_VIEW to PHONE_ICON_DYN, PHONE_NUMBER_VIEW to PHONE_NUMBER_DYN)
        )) {
            place(icon.first, left, BATT_ICON_TOP, BATT_COL_WIDTH, BATT_ICON_HEIGHT).put("textvalue", "").put("dynData", icon.second)
            text(number.first, left, BATT_NUMBER_TOP, BATT_COL_WIDTH, BATT_NUMBER_HEIGHT, BATT_TEXT_SIZE, "center", VALUE_COLOR)
                .put("textvalue", "%.0f").put("dynValue", false).put("dynData", number.second)
        }
    }

    /**
     * The icon of a battery: [BATT_STEPS] pictures over 1 to 101, so each step is 5 % and the colour
     * steps fall exactly on the limits: 1 to 20 red, 21 to 40 yellow, 41 and more green. A value below 1
     * counts as 1. The pictures are white, so the watch colours them; their inside is see-through, so
     * the empty part shows the background. No value: the empty outline in grey. In the night colours;
     * a light look takes [batteryDayColours].
     */
    protected fun batteryIconDynData(valueKey: String, shape: BatteryShape): JSONObject = JSONObject()
        .put("valueKey", valueKey).put("minData", 1).put("maxData", 101)
        .put("invalidImage", shape.picture(0)).apply { for (step in 1..BATT_STEPS) put("image$step", shape.picture(step)) }
        .mergeIn(batteryColours(BATT_RED, BATT_YELLOW, BATT_GREEN, BATT_GREY))

    /** The colour steps of a battery icon: [red] up to 20 %, [yellow] up to 40 %, [green] above, [grey] without a value. */
    private fun batteryColours(red: String, yellow: String, green: String, grey: String): JSONObject = JSONObject()
        .put("invalidColor", grey).put("color1", red).put("color2", yellow).put("color3", green).put("color4", green).put("color5", green)

    /**
     * The battery icons of a light look, as `dynData` to lay over the design: the dark glucose colours of
     * the light looks (amber for yellow), which can be read on a light background.
     */
    protected fun batteryDayColours(): JSONObject = JSONObject()
        .put(WATCH_ICON_DYN, batteryColours(DAY_LOW, DAY_HIGH, DAY_MID, BATT_DAY_GREY))
        .put(PHONE_ICON_DYN, batteryColours(DAY_LOW, DAY_HIGH, DAY_MID, BATT_DAY_GREY))

    /**
     * The number of a battery: the value itself in [color], `--` without a value. The watch needs the
     * format as a step (`textvalue1`) too: with only `invalidTextvalue` it shows `--` for every value. The one colour step also
     * stops the watch from turning the phone battery number red on its own when the phone says it is low.
     */
    protected fun batteryNumberDynData(valueKey: String, color: String): JSONObject = JSONObject()
        .put("valueKey", valueKey).put("textvalue1", "%.0f").put("invalidTextvalue", "--").put("fontColor1", color)

    /** The pictures of the battery icons: per shape the empty one and one per step. */
    protected fun batteryImages(): Map<String, ByteArray> = BatteryShape.entries.flatMap { shape ->
        (0..BATT_STEPS).map { step -> shape.picture(step) to batteryPicture(shape, if (step == 0) null else step / BATT_STEPS.toFloat()) }
    }.toMap()

    /**
     * One battery icon, in white with a see-through inside, filled from the bottom to [fill] (0 to 1), or
     * empty for null. The picture covers the whole icon view, at twice the design size.
     */
    protected fun batteryPicture(shape: BatteryShape, fill: Float?): ByteArray {
        val s = SCALE
        val image = createBitmap((BATT_COL_WIDTH * s).toInt(), (BATT_ICON_HEIGHT * s).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val cx = BATT_COL_WIDTH / 2f
        val inside: Path
        val fillArea: Path
        val bottom: Float
        val top: Float
        when (shape) {
            BatteryShape.WATCH -> {
                // A round watch: the strap above and below, the crown on the right
                val cy = BATT_ICON_HEIGHT / 2f
                canvas.drawRoundRect(RectF((cx - 3.5f) * s, 0f, (cx + 3.5f) * s, BATT_ICON_HEIGHT * s), 1.5f * s, 1.5f * s, white)
                canvas.drawRoundRect(RectF((cx + 7f) * s, (cy - 1.6f) * s, (cx + 9.4f) * s, (cy + 1.6f) * s), 0.6f * s, 0.6f * s, white)
                canvas.drawCircle(cx * s, cy * s, WATCH_CASE_RADIUS * s, white)
                inside = Path().apply { addCircle(cx * s, cy * s, 6.5f * s, Path.Direction.CW) }
                fillArea = Path().apply { addCircle(cx * s, cy * s, 5.2f * s, Path.Direction.CW) }
                top = cy - 5.2f
                bottom = cy + 5.2f
            }

            BatteryShape.PHONE -> {
                // A phone: a tall rounded body with a wider edge at the top and the bottom
                val half = 6f
                canvas.drawRoundRect(RectF((cx - half) * s, 0.5f * s, (cx + half) * s, (BATT_ICON_HEIGHT - 0.5f) * s), 2.6f * s, 2.6f * s, white)
                inside = Path().apply { addRoundRect(RectF((cx - half + 1.5f) * s, 3f * s, (cx + half - 1.5f) * s, (BATT_ICON_HEIGHT - 3f) * s), 1f * s, 1f * s, Path.Direction.CW) }
                top = 4.2f
                bottom = BATT_ICON_HEIGHT - 4.2f
                fillArea = Path().apply { addRect(RectF((cx - half + 2.7f) * s, top * s, (cx + half - 2.7f) * s, bottom * s), Path.Direction.CW) }
            }
        }
        canvas.drawPath(inside, clear)
        if (fill != null && fill > 0f) {
            canvas.save()
            canvas.clipPath(fillArea)
            canvas.drawRect(0f, (bottom - (bottom - top) * fill.coerceIn(0f, 1f)) * s, image.width.toFloat(), bottom * s, white)
            canvas.restore()
        }
        return pngBytes(image)
    }


    /** The sun on the edge arc, from sunrise (left) to sunset (right), showing [picture]. The place in the json is the middle of its way. */
    protected fun JSONObject.putSun(picture: String = SUN) {
        val top = orbit(JsonKeyValues.SUN_PROGRESS.key).point(0.5)
        place("freetext4", (top.first - SUN_SIZE / 2f).toInt(), (top.second - SUN_SIZE / 2f).toInt(), SUN_SIZE)
            .put("textvalue", "").put("background", picture).put("orbit", orbitJson(JsonKeyValues.SUN_PROGRESS.key))
    }

    /** The arc along the edge that the sun (by day) and the moon (by night) follow. */
    protected fun orbit(valueKey: String) = Orbit(valueKey, radius = ORBIT_RADIUS, startAngle = ORBIT_START, endAngle = ORBIT_END)

    protected fun orbitJson(valueKey: String): JSONObject =
        JSONObject().put("valueKey", valueKey).put("radius", ORBIT_RADIUS).put("startAngle", ORBIT_START).put("endAngle", ORBIT_END)


    /**
     * The colours of a light look. The background is [bg]; the glucose value, the table and the graph
     * sit on light panels ([panel]) with dark text, so the glucose colours are the dark ones
     * ([DAY_MID] and friends): same meaning, readable on light.
     *
     * @param name the end of the names of its pictures (`BackgroundPartly`, ...)
     * @param ink the time, the date and the values
     * @param muted the day and month names
     * @param label the labels in the table
     * @param colon the ":" of the time
     * @param accent the frames and the second shadow of the logo
     * @param clouds how many clouds the background shows
     */
    protected data class DayPalette(
        val name: String, val bg: String, val panel: String, val ink: String, val muted: String, val label: String,
        val colon: String, val accent: String, val clouds: Int
    ) {

        val logo get() = Ink(line = ink, lineShadow = LOW_COLOR, text = ink, textShadow1 = LOW_COLOR, textShadow2 = accent)

        /** The colours of the cover plate. */
        val plate get() = Plate(frame = accent, thin = "#3C000000", label = label, logo = logo)
    }

    /**
     * The texts of [p]: dark on the background and on the light panels, and the dark glucose colours.
     * This is the json part of a light look; the pictures and the animation are not in it.
     */
    protected fun paletteColors(p: DayPalette): JSONObject = JSONObject().apply {
        put("day_name", JSONObject().put("fontColor", p.muted))
        put("month", JSONObject().put("fontColor", p.muted))
        for (key in listOf("day", "hour", "minute", WATCH_NUMBER_VIEW, PHONE_NUMBER_VIEW)) put(key, JSONObject().put("fontColor", p.ink))
        put("freetext1", JSONObject().put("fontColor", p.colon))
        for (label in listOf("iob1", "cob1", "freetext2", "freetext3")) put(label, JSONObject().put("fontColor", p.label))
        for (value in listOf("iob2", "cob2", "basalRate", "reservoir", "complication1", "complication2")) put(value, JSONObject().put("fontColor", p.ink))
        put("timestamp", JSONObject().put("fontColor", DAY_LOW))
        put("loop", JSONObject().put("fontColor", DAY_LOW))
        put("dynData", JSONObject().put(AGE_DYN, JSONObject().put("invalidFontColor", DAY_LOW).put("fontColor2", DAY_LOW))
            .put(LOOP_DYN, JSONObject().put("invalidFontColor", DAY_LOW).put("fontColor2", DAY_LOW))
            .put(WATCH_NUMBER_DYN, JSONObject().put("fontColor1", p.ink)).put(PHONE_NUMBER_DYN, JSONObject().put("fontColor1", p.ink))
            .mergeIn(batteryDayColours()))
        put("highColor", DAY_HIGH).put("midColor", DAY_MID).put("lowColor", DAY_LOW)
        // The app colours the reservoir itself: dark like the other values, amber and red when low
        put("reservoirColor", p.ink).put("reservoirWarningColor", DAY_HIGH).put("reservoirUrgentColor", DAY_LOW)
        put("carbColor", DAY_CARBS).put("basalBackgroundColor", DAY_BASAL_BACK).put("basalCenterColor", DAY_BASAL_CENTER)
    }

    /** Now and then a short burst of red bands and bands in [accent], under the data like the rain. */
    protected fun glitch(accent: String): JSONObject = JSONObject()
        .put("periodMs", 900).put("chance", 0.55).put("burstMs", 140).put("bands", 3).put("seed", 23)
        .put("minHeight", 2).put("maxHeight", 7).put("minWidth", 100).put("maxWidth", 260).put("jump", 8)
        .put("color1", LOW_COLOR).put("color2", accent).put("alpha", 200)

    /** Rain slanted 1 to the left for 4 down (about -14 degrees), in [color] at [alpha]. */
    protected fun rain(color: String, alpha: Int): JSONObject = JSONObject()
        .put("count", 140).put("seed", 11).put("speed", 470).put("angle", -14).put("length", 28)
        .put("lengthVariation", 0.4).put("thickness", 1.6).put("color", color).put("alpha", alpha)

    /**
     * The wake-up animation: the drop of the logo beating at the heart rate, with [rain] and [glitch]
     * bands when they are given.
     */
    protected fun animation(rain: JSONObject? = null, glitch: JSONObject? = null): JSONObject {
        val box = pulseBox()
        return JSONObject().put("durationMs", ANIMATION_MS).put("fps", 24).put("fadeMs", 600).apply {
            rain?.let { put("rain", it) }
            glitch?.let { put("glitch", it) }
        }.put("blink", batteryBlink()).put(
            "pulse", JSONObject().put("image", LOGO_PULSE).put("left", box.left).put("top", box.top).put("width", box.width).put("height", box.height)
                .put("amplitude", PULSE_AMPLITUDE).put("secondBeat", 0.45).put("glowColor", HEART_COLOR).put("glowAlpha", 255).put("glowRadius", 11)
        )
    }

    /** A battery at [BATT_LOW] % or less blinks, icon and number, while the wake-up animation plays. */
    protected fun batteryBlink(): JSONArray = JSONArray()
        .put(JSONObject().put("views", JSONArray().put(WATCH_ICON_VIEW).put(WATCH_NUMBER_VIEW)).put("valueKey", WatchBatteryLevel.KEY).put("atOrBelow", BATT_LOW))
        .put(JSONObject().put("views", JSONArray().put(PHONE_ICON_VIEW).put(PHONE_NUMBER_VIEW)).put("valueKey", PHONE_VALUE_KEY).put("atOrBelow", BATT_LOW))

    /**
     * A render-only face has no complication renderer, so this draws heart rate and steps with the
     * library's own [ComplicationDrawable], styled the way the face styles its slots, and with the data
     * the heart rate and steps sources send. In the design space.
     */
    override fun drawComplicationValues(canvas: Canvas, design: JSONObject, type: ComplicationType) {
        drawSlot(canvas, design.getJSONObject("complication1"), health(HealthKind.HEART_RATE, 72, type))
        drawSlot(canvas, design.getJSONObject("complication2"), health(HealthKind.STEPS, 6482, type))
    }
    /** The font of the table values. */
    override fun valueTypeface(): Typeface =
        if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.create("sans-serif-condensed", Typeface.BOLD)


    /** A closed shape through the given points (x, y in the 400x400 design space), scaled for the 800x800 pictures. */
    protected fun shape(points: List<Pair<Float, Float>>): Path = Path().apply {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * SCALE, y * SCALE) else lineTo(x * SCALE, y * SCALE) }
        close()
    }

    /** The table panel. Its right edge follows the round screen, row by row, with a slanted step between rows. */
    protected fun tablePanel(): Path {
        val points = mutableListOf(TABLE_LEFT - 8f to TABLE_TOP.toFloat())
        for (k in 0 until TABLE_ROWS) {
            val top = (TABLE_TOP + k * ROW_HEIGHT).toFloat()
            val bottom = top + ROW_HEIGHT
            val right = rowRight(k).toFloat()
            points += right to (if (k == 0) top else top + 6f)
            points += right to (if (k == TABLE_ROWS - 1) bottom else bottom - 6f)
        }
        points += TABLE_LEFT - 8f to TABLE_BOTTOM.toFloat()
        return shape(points)
    }

    protected fun chartPanel() = shape(
        listOf(
            CHART_LEFT + 16f to CHART_TOP - 2f, CHART_LEFT + CHART_WIDTH - 16f to CHART_TOP - 2f,
            CHART_LEFT + CHART_WIDTH.toFloat() to CHART_TOP + 14f, CHART_LEFT + CHART_WIDTH.toFloat() to 400f,
            CHART_LEFT.toFloat() to 400f, CHART_LEFT.toFloat() to CHART_TOP + 14f
        )
    )

    /** The panel behind the glucose value, its arrow and its age, with cut corners. */
    protected fun glucosePanel() = shape(
        listOf(
            GL_LEFT + 14f to GL_TOP.toFloat(), GL_RIGHT.toFloat() to GL_TOP.toFloat(), GL_RIGHT.toFloat() to GL_BOTTOM - 14f,
            GL_RIGHT - 14f to GL_BOTTOM.toFloat(), GL_LEFT.toFloat() to GL_BOTTOM.toFloat(), GL_LEFT.toFloat() to GL_TOP + 14f
        )
    )

    /** Hazard stripes in [color] along the bottom of the glucose panel, left of the arrow. */
    protected fun hazardStripes(canvas: Canvas, color: String) {
        canvas.save()
        canvas.clipPath(glucosePanel())
        canvas.clipRect(GL_LEFT * SCALE, (GL_BOTTOM - 6f) * SCALE, 150f * SCALE, GL_BOTTOM * SCALE)
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor(color); strokeWidth = 5f * SCALE }
        var x = GL_LEFT - 10f
        while (x < 160f) {
            canvas.drawLine(x * SCALE, GL_BOTTOM * SCALE, (x + 6f) * SCALE, (GL_BOTTOM - 6f) * SCALE, stripe)
            x += 10f
        }
        canvas.restore()
    }

    /** The dashed arc along the edge that the sun and the moon follow, on the 800 px pictures. */
    protected fun drawTrack(canvas: Canvas, color: Int) {
        val r = ORBIT_RADIUS * SCALE
        val c = 200f * SCALE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.2f * SCALE
            pathEffect = DashPathEffect(floatArrayOf(2f * SCALE, 5f * SCALE), 0f)
        }
        canvas.drawArc(RectF(c - r, c - r, c + r, c + r), ORBIT_START, ORBIT_END - ORBIT_START, false, paint)
    }

    /** Lines across the panel [panel] every 4 design units, in [color]: the scan lines of the HUD. */
    protected fun scanLines(canvas: Canvas, panel: Path, color: Int) {
        val lines = Paint().apply { this.color = color; strokeWidth = 1f * SCALE }
        canvas.save()
        canvas.clipPath(panel)
        var y = 0f
        while (y < 800f) {
            canvas.drawLine(0f, y, 800f, y, lines)
            y += 4f * SCALE
        }
        canvas.restore()
    }

    protected fun logoTypeface(): Typeface =
        if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.create("sans-serif-condensed", Typeface.BOLD)

    /**
     * The colours of the logo: the line ([line]) with a [lineShadow] copy behind it, and the word
     * ([text]) with two coloured copies behind it.
     */
    protected data class Ink(val line: String, val lineShadow: String, val text: String, val textShadow1: String, val textShadow2: String)

    /**
     * The colours of the cover plate: the [frame] of the table and the graph, the [thin] lines between
     * the rows, the [label] icons of heart rate and steps, and the [logo].
     */
    protected data class Plate(val frame: String, val thin: String, val label: String, val logo: Ink)

    /** Draws [block] twice, in design units: a [Ink.lineShadow] copy offset by 2,1 and the [Ink.line] one, as a glitch. */
    protected fun glitched(canvas: Canvas, width: Float, fillShape: Boolean = false, ink: Ink = NIGHT_INK, block: (Canvas, Paint) -> Unit) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = width; style = if (fillShape) Paint.Style.FILL else Paint.Style.STROKE
            strokeJoin = Paint.Join.MITER; strokeCap = Paint.Cap.SQUARE
        }
        for ((dx, dy, color) in listOf(Triple(2f, 1f, ink.lineShadow), Triple(0f, 0f, ink.line))) {
            paint.color = Color.parseColor(color)
            canvas.save()
            canvas.translate(dx, dy)
            block(canvas, paint)
            canvas.restore()
        }
    }

    /** The word "AAPS" with two coloured copies behind it; [stencil] cuts a thin gap through the letters. */
    protected fun word(canvas: Canvas, cx: Float, cy: Float, size: Float, stencil: Boolean = false, ink: Ink = NIGHT_INK) {
        canvas.saveLayer(null, null)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; textAlign = Paint.Align.CENTER; typeface = logoTypeface() }
        val baseline = cy - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
        text.color = Color.parseColor(ink.textShadow1)
        canvas.drawText("AAPS", cx + 1.5f, baseline + 1f, text)
        text.color = Color.parseColor(ink.textShadow2)
        canvas.drawText("AAPS", cx - 1.5f, baseline - 0.5f, text)
        text.color = Color.parseColor(ink.text)
        canvas.drawText("AAPS", cx, baseline, text)
        if (stencil) {
            val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.drawRect(cx - size * 1.4f, cy - 0.9f, cx + size * 1.4f, cy + 0.9f, clear)
        }
        canvas.restore()
    }

    /**
     * The drop of the logo, in the 48 high space of the logo (the drop spans x 3..27, y 2..43). The still
     * logo and the beating [logoPulse] picture both draw this one shape.
     */
    protected fun logoDrop(): Path = Path().apply {
        val cx = 15f
        moveTo(cx, 2f)
        cubicTo(cx + 3f, 13f, cx + 12f, 21f, cx + 12f, 30f)
        cubicTo(cx + 12f, 38f, cx + 6f, 43f, cx, 43f)
        cubicTo(cx - 6f, 43f, cx - 12f, 38f, cx - 12f, 30f)
        cubicTo(cx - 12f, 21f, cx - 3f, 13f, cx, 2f)
        close()
    }

    /**
     * The emblem: the drop with a pulse line through it and the stencil word "AAPS", every line with a
     * red copy just beside it (the glitch). Drawn in a 48 high space that is scaled to the logo slot.
     */
    protected fun drawEmblem(canvas: Canvas, ink: Ink) {
        canvas.save()
        canvas.scale(SCALE, SCALE)
        canvas.translate(LOGO_LEFT.toFloat(), LOGO_TOP.toFloat())
        val k = LOGO_HEIGHT / 48f
        canvas.scale(k, k)
        val w = LOGO_WIDTH / k
        glitched(canvas, 2f, ink = ink) { c, paint ->
            c.drawPath(logoDrop(), paint)
            c.drawPath(Path().apply {
                moveTo(1f, 36f); lineTo(8f, 36f); lineTo(12f, 27f); lineTo(17f, 45f)
                lineTo(20f, 36f); lineTo(w - 2f, 36f)
            }, paint)
        }
        word(canvas, 71f, 17f, 30f, stencil = true, ink = ink)
        canvas.restore()
    }

    /**
     * The picture of the beating drop: only the drop of the logo, with the same outline and red copy, at
     * [PULSE_PX_PER_UNIT] pixels per design unit. It fills the drop box ([DROP_BOX_LEFT] .. in the 48
     * high space), which is the pulse rectangle of the animation, so at rest it lies exactly on the drop
     * of the still logo.
     */
    protected fun logoPulse(ink: Ink = NIGHT_INK): ByteArray {
        val box = pulseBox()
        val width = ceil(box.width * PULSE_PX_PER_UNIT).toInt()
        val height = ceil(box.height * PULSE_PX_PER_UNIT).toInt()
        val image = createBitmap(width, height).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        // Each axis scaled so the box fills the picture exactly, also after rounding to whole pixels
        canvas.scale(width / (DROP_BOX_RIGHT - DROP_BOX_LEFT), height / (DROP_BOX_BOTTOM - DROP_BOX_TOP))
        canvas.translate(-DROP_BOX_LEFT, -DROP_BOX_TOP)
        glitched(canvas, 2f, ink = ink) { c, paint -> c.drawPath(logoDrop(), paint) }
        return pngBytes(image)
    }

    /** A rectangle in the design space, as the json gets it. */
    protected data class Box(val left: Double, val top: Double, val width: Double, val height: Double)

    /** The drop box of [logoPulse] in the 400 x 400 design space: the logo's own transform, rounded to 3 decimals. */
    protected fun pulseBox(): Box {
        val k = LOGO_HEIGHT / 48.0
        fun round(v: Double) = Math.round(v * 1000.0) / 1000.0
        return Box(
            round(LOGO_LEFT + DROP_BOX_LEFT * k), round(LOGO_TOP + DROP_BOX_TOP * k),
            round((DROP_BOX_RIGHT - DROP_BOX_LEFT) * k), round((DROP_BOX_BOTTOM - DROP_BOX_TOP) * k)
        )
    }

    /** One trend arrow picture: [count] chevrons pointing right, turned by [angle]. White, so the app's tint gives the colour. */
    private fun arrowPng(count: Int, angle: Float, trail: Boolean = count == 1, diamond: Boolean = false): ByteArray {
        val size = 240
        val k = size / 100f
        val image = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        canvas.rotate(angle, size / 2f, size / 2f)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 7f * k; strokeJoin = Paint.Join.MITER }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        fun chevron(offset: Float) = Path().apply {
            val pts = listOf(28f to 22f, 50f to 22f, 76f to 50f, 50f to 78f, 28f to 78f, 54f to 50f)
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo((x + offset) * k, y * k) else lineTo((x + offset) * k, y * k) }
            close()
        }
        if (diamond) {
            val d = Path().apply { moveTo(50f * k, 36f * k); lineTo(64f * k, 50f * k); lineTo(50f * k, 64f * k); lineTo(36f * k, 50f * k); close() }
            fill.alpha = 170
            canvas.drawPath(d, fill)
            return pngBytes(image)
        }
        for (i in 0 until count) {
            val offset = if (count == 1) -2f else if (i == 0) -15f else 9f
            canvas.drawPath(chevron(offset), glow)
            canvas.drawPath(chevron(offset), fill)
        }
        if (trail) {
            fill.alpha = 255
            canvas.drawRect(2f * k, 47f * k, 24f * k, 53f * k, fill)
            fill.alpha = 130
            canvas.drawRect(10f * k, 37f * k, 24f * k, 40f * k, fill)
            canvas.drawRect(10f * k, 60f * k, 24f * k, 63f * k, fill)
        }
        return pngBytes(image)
    }

    protected fun arrowImages(): Map<String, ByteArray> = mapOf(
        ResFileMap.ARROW_NONE.fileName to arrowPng(1, 0f, diamond = true),
        ResFileMap.ARROW_DOUBLE_UP.fileName to arrowPng(2, -90f),
        ResFileMap.ARROW_SINGLE_UP.fileName to arrowPng(1, -90f),
        ResFileMap.ARROW_FORTY_FIVE_UP.fileName to arrowPng(1, -45f),
        ResFileMap.ARROW_FLAT.fileName to arrowPng(1, 0f),
        ResFileMap.ARROW_FORTY_FIVE_DOWN.fileName to arrowPng(1, 45f),
        ResFileMap.ARROW_SINGLE_DOWN.fileName to arrowPng(1, 90f),
        ResFileMap.ARROW_DOUBLE_DOWN.fileName to arrowPng(2, 90f)
    )

    /**
     * The HUD frames, transparent everywhere else: a bar left of the table with thin row lines, a frame
     * line on top of the graph with cut corners, the labels of heart rate and steps, and the logo. In
     * the colours of [plate].
     */
    protected fun coverPlate(plate: Plate = NIGHT_PLATE): ByteArray {
        val image = createBitmap(800, 800).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val s = SCALE
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(plate.frame); strokeWidth = 3f * s; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(plate.thin); strokeWidth = 1f * s }
        // Table: a bar on the left with a short cap on top, and a line under each row
        canvas.drawLine((TABLE_LEFT - 8f) * s, TABLE_TOP * s, (TABLE_LEFT - 8f) * s, TABLE_BOTTOM * s, frame)
        canvas.drawLine((TABLE_LEFT - 8f) * s, TABLE_TOP * s, (TABLE_LEFT + 14f) * s, TABLE_TOP * s, frame)
        for (k in 1 until TABLE_ROWS) {
            val y = (TABLE_TOP + k * ROW_HEIGHT) * s
            canvas.drawLine((TABLE_LEFT + 4f) * s, y, (min(rowRight(k - 1), rowRight(k)) - 8f) * s, y, thin)
        }
        frame.strokeWidth = 2f * s
        // Graph: the frame line on top, with cut corners and short side ticks
        val top = Path().apply {
            moveTo((CHART_LEFT + 2f) * s, (CHART_TOP + 22f) * s)
            lineTo((CHART_LEFT + 2f) * s, (CHART_TOP + 14f) * s)
            lineTo((CHART_LEFT + 16f) * s, (CHART_TOP - 2f) * s)
            lineTo((CHART_LEFT + CHART_WIDTH - 16f) * s, (CHART_TOP - 2f) * s)
            lineTo((CHART_LEFT + CHART_WIDTH - 2f) * s, (CHART_TOP + 14f) * s)
            lineTo((CHART_LEFT + CHART_WIDTH - 2f) * s, (CHART_TOP + 22f) * s)
        }
        canvas.drawPath(top, frame)
        // The labels of the heart rate and steps rows, in the label colour like the other labels
        drawRowIcon(canvas, R.drawable.ic_health_heart, 4, plate.label)
        drawRowIcon(canvas, R.drawable.ic_health_steps, 5, plate.label)
        drawEmblem(canvas, plate.logo)
        return pngBytes(image)
    }

    /** Draws [icon] as the label of table row [index], in the label column, on the 800 px cover plate. */
    private fun drawRowIcon(canvas: Canvas, @DrawableRes icon: Int, index: Int, color: String) {
        val s = SCALE
        val half = ROW_ICON_SIZE / 2f
        val centerY = TABLE_TOP + index * ROW_HEIGHT + ROW_HEIGHT / 2f
        ContextCompat.getDrawable(RuntimeEnvironment.getApplication(), icon)!!.mutate().apply {
            setTint(Color.parseColor(color))
            setBounds((TABLE_LEFT * s).toInt(), ((centerY - half) * s).toInt(), ((TABLE_LEFT + ROW_ICON_SIZE) * s).toInt(), ((centerY + half) * s).toInt())
            draw(canvas)
        }
    }

    /** A 1 x 1 transparent picture: shown where the watch would otherwise draw its own picture. */
    protected fun blankPng(): ByteArray = pngBytes(createBitmap(1, 1).apply { eraseColor(Color.TRANSPARENT) })

    /**
     * The sun, in the style of the logo: a warm yellow disc with a dark ring, and a red and a cyan
     * copy of the ring just beside it, like a glitch. [SUN_PX] pixels for the [SUN_SIZE] view.
     */
    protected fun sunPng(): ByteArray {
        val image = createBitmap(SUN_PX, SUN_PX).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        canvas.scale(SUN_PX / SUN_SIZE.toFloat(), SUN_PX / SUN_SIZE.toFloat())
        val c = SUN_SIZE / 2f
        val r = SUN_SIZE / 2f - 3.5f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f }
        // Short rays every 45 degrees
        val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A0A0A"); strokeWidth = 1.4f; strokeCap = Paint.Cap.SQUARE }
        for (i in 0 until 8) {
            val a = i * PI / 4
            canvas.drawLine(c + (r + 1.2f) * cos(a).toFloat(), c + (r + 1.2f) * sin(a).toFloat(), c + (r + 3f) * cos(a).toFloat(), c + (r + 3f) * sin(a).toFloat(), ray)
        }
        ring.color = Color.parseColor(LOW_COLOR)
        canvas.drawCircle(c + 1.2f, c + 0.6f, r, ring)
        ring.color = Color.parseColor(CYAN)
        canvas.drawCircle(c - 1.2f, c - 0.4f, r, ring)
        canvas.drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(SUN_YELLOW) })
        ring.color = Color.parseColor("#0A0A0A")
        canvas.drawCircle(c, c, r, ring)
        return pngBytes(image)
    }

    /**
     * A cloud of blocks, in the pixel style of the HUD: a flat shape with stepped edges in [fill], a
     * thin outline in [line], and a copy of the outline offset in [glitch], like the logo. In design
     * units, with its top left at [left], [top] and [unit] the size of one block.
     */
    protected fun drawCloud(canvas: Canvas, left: Float, top: Float, unit: Float, fill: Int, line: Int, glitch: Int) {
        // Rows of blocks, each as (first block, number of blocks), from the top down
        val rows = listOf(2 to 3, 1 to 6, 0 to 9, 0 to 9)
        val shape = Path()
        rows.forEachIndexed { y, (x, n) -> shape.addRect(left + x * unit, top + y * unit, left + (x + n) * unit, top + (y + 1) * unit, Path.Direction.CW) }
        shape.op(Path(), Path.Op.UNION)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; strokeJoin = Paint.Join.MITER }
        canvas.save()
        canvas.translate(1.5f, 1f)
        outline.color = glitch
        canvas.drawPath(shape, outline)
        canvas.restore()
        canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        outline.color = line
        canvas.drawPath(shape, outline)
    }


    protected fun textWidth(text: String, size: Int): Float = Paint().apply {
        textSize = size.toFloat()
        typeface = valueTypeface()
    }.measureText(text)


    /** The number of pixels in [box] (design units) of [bitmap] that are clearly red. */
    private fun redPixels(bitmap: Bitmap, box: RectF): Int {
        var red = 0
        for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
            val c = bitmap.getPixel(x, y)
            if (Color.red(c) > 150 && Color.green(c) < 80 && Color.blue(c) < 110) red++
        }
        return red
    }


    private fun disjoint(name: String, a: RectF, b: RectF) =
        assertWithMessage(name).that(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top).isTrue()


    protected val rowBoxKeys = listOf("complication1", "complication2")
    protected val batteryKeys = listOf(WATCH_ICON_VIEW, WATCH_NUMBER_VIEW, PHONE_ICON_VIEW, PHONE_NUMBER_VIEW)
    protected val tableKeys = listOf("iob1", "iob2", "cob1", "cob2", "freetext2", "basalRate", "freetext3", "reservoir")

    // ------------------------------------------------------------------------------------------
    // The tests every design must pass
    // ------------------------------------------------------------------------------------------

    @Test
    fun buildsTheZipAndItsPreview() {
        val default = defaultDesign()
        val design = design(default)
        val json = design.toString(4)
        val images = images()
        val previewImage = preview(design, default, images, previewInstant)
        val jpg = ByteArrayOutputStream().also { previewImage.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        val zip = zip(json, jpg, images)
        File(out, "$fileName.zip").writeBytes(zip)
        File(out, "$fileName.png").writeBytes(pngBytes(previewImage))
        // The phone sends the zip to the watch in one message; a larger one is dropped without a word
        println("$fileName.zip: ${zip.size} bytes")
        assertThat(zip.size).isAtMost(MAX_ZIP_BYTES)

        // A whole design for day or night: no day look, and weather looks only by the weather alone
        assertThat(design.has("dayLook")).isFalse()
        design.optJSONObject(DayOverlay.WEATHER_KEY)?.keys()?.forEach {
            assertWithMessage("weather look $it").that(it).isIn(listOf(EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN))
        }
        // No picture ends with "Day": the watch would show it in the place of another one
        for (name in images.keys) assertWithMessage(name).that(name.endsWith(DayOverlay.IMAGE_SUFFIX)).isFalse()

        // The phone accepts it: it needs a name and a preview image
        val loaded = ZipWatchfaceFormat.loadCustomWatchface(zip, "$fileName.zip", authorization = true)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.cwfData.metadata[CwfMetadataKey.CWF_NAME]).isEqualTo(displayName)
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_FILENAME]).isEqualTo(fileName)
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3]).isEqualTo("false")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4]).isEqualTo("false")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5]).isEqualTo("false")
        for (key in listOf(
            CwfMetadataKey.CWF_PREF_WATCH_SHOW_DELTA, CwfMetadataKey.CWF_PREF_WATCH_SHOW_AVG_DELTA,
            CwfMetadataKey.CWF_PREF_WATCH_SHOW_UPLOADER_BATTERY, CwfMetadataKey.CWF_PREF_WATCH_SHOW_RIG_BATTERY
        )) assertWithMessage("$key").that(loaded.cwfData.metadata[key]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_DETAILED_IOB]).isEqualTo("false")
        // Brings its own background, plate, arrows and beating drop; no hand pictures
        assertThat(loaded.cwfData.resData.keys).containsAtLeast(ResFileMap.BACKGROUND.fileName, ResFileMap.COVER_PLATE.fileName, LOGO_PULSE, BLANK)
        assertThat(loaded.cwfData.resData.keys).doesNotContain(ResFileMap.SECOND_HAND.fileName)
        assertThat(loaded.cwfData.resData.keys).containsAtLeast("ArrowFlat", "ArrowDoubleDown", "ArrowSingleUp")
        assertThat(loaded.cwfData.resData.keys).containsAtLeastElementsIn(ownPictures)
        assertThat(loaded.cwfData.resData.keys).containsAtLeastElementsIn(batteryImages().keys)
        if (fontFile.exists()) assertThat(loaded.cwfData.resData[CUSTOM_FONT]?.format).isEqualTo(ResFormat.TTF)
        // Every picture a look swaps in is in the zip
        design.optJSONObject(DayOverlay.WEATHER_KEY)?.let { weatherLooks ->
            weatherLooks.keys().forEach { weather ->
                weatherLooks.getJSONObject(weather).optJSONObject(DayOverlay.IMAGES_KEY)?.let { swaps ->
                    swaps.keys().forEach { assertWithMessage("$weather $it").that(loaded.cwfData.resData.keys).contains(swaps.getString(it)) }
                }
            }
        }

        // The copy kept in the repository is this design
        val kept = File("cwf-designs/$fileName.zip")
        assertWithMessage("$kept").that(kept.exists()).isTrue()
        assertThat(JSONObject(jsonInZip(kept.readBytes())).toString(4)).isEqualTo(json)
        val keptEntries = ZipInputStream(kept.inputStream()).use { input -> generateSequence { input.nextEntry }.map { it.name }.toList() }
        assertThat(keptEntries).containsAtLeastElementsIn(images.map { (name, bytes) -> "$name.${if (isJpeg(bytes)) "jpg" else "png"}" })
        if (fontFile.exists()) assertThat(keptEntries).contains("$CUSTOM_FONT.ttf")
    }

    @Test
    fun everythingStaysOnTheRoundScreenAndApart() {
        val design = design(defaultDesign())
        for (look in listOf(null, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN).map { lookOf(design, it) }) {
            // The views that move along the edge are checked all along their arc, below
            val visible = look.keys().asSequence().filter { look.optJSONObject(it)?.optString("visibility") == "visible" }
                .filter { it !in listOf("cover_plate", "cover_chart", "background", "chart") && Orbit.read(look.optJSONObject(it)) == null }.toList()
            // Nothing is clipped by the round screen
            for (key in visible) insideRound(key, rectOf(look.getJSONObject(key)))
        }

        val date = block(design, "day_name", "day", "month")
        val time = block(design, "hour", "freetext1", "minute")
        val bg = block(design, "sgv", "direction", "timestamp")
        val table = block(design, *(tableKeys + rowBoxKeys).toTypedArray())
        val loop = rectOf(design.getJSONObject("loop"))
        val battery = block(design, *batteryKeys.toTypedArray())
        val logo = RectF(LOGO_LEFT.toFloat(), LOGO_TOP.toFloat(), (LOGO_LEFT + LOGO_WIDTH).toFloat(), (LOGO_TOP + LOGO_HEIGHT).toFloat())
        insideRound("logo", logo, margin = 6f)
        val chart = rectOf(design.getJSONObject("chart"))
        // The graph keeps a good size: full width (340) and at least 130 high, below the table
        assertThat(chart.width()).isAtLeast(300f)
        assertThat(chart.height()).isAtLeast(130f)

        val blocks = mapOf("date" to date, "time" to time, "bg" to bg, "table" to table, "battery" to battery, "logo" to logo, "chart" to chart)
        // The loop warning sits under the glucose age, apart from the value, the arrow and the age
        for (key in listOf("sgv", "direction", "timestamp")) disjoint("loop / $key", loop, rectOf(design.getJSONObject(key)))
        // The sun or the moon stays on the round screen all along its arc
        val body = design.getJSONObject("freetext4")
        val orbit = Orbit.read(body)!!
        var value = 0.0
        while (value <= 1.0) {
            // It is round: the middle plus the half size stays inside the screen
            val (x, y) = orbit.point(value)
            assertWithMessage("${orbit.valueKey} at $value").that(hypot(x - 200f, y - 200f) + body.getInt("width") / 2f).isAtMost(200f)
            value += 0.05
        }
        val names = blocks.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) disjoint("${names[i]} / ${names[j]}", blocks.getValue(names[i]), blocks.getValue(names[j]))
    }

    @Test
    fun theAgeAndTheLoopOnlyShowWhenOld() {
        val design = design(defaultDesign())
        for (look in listOf(null, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN).map { lookOf(design, it) }) {
            val dyn = look.getJSONObject("dynData")
            for ((key, limit) in listOf(AGE_DYN to 5.0, LOOP_DYN to 15.0)) {
                val d = dyn.getJSONObject(key)
                // Two steps over 0 .. 2 x limit: below the limit transparent, from the limit on red
                assertThat(d.getDouble("minData")).isEqualTo(0.0)
                assertThat(d.getDouble("maxData")).isEqualTo(2 * limit)
                assertThat(d.getString("fontColor1")).isEqualTo(CLEAR)
                assertThat(d.getString("fontColor2")).isEqualTo(ageColor)
                assertThat(d.has("fontColor3")).isFalse()
            }
            assertThat(look.getJSONObject("timestamp").getString("dynData")).isEqualTo(AGE_DYN)
            assertThat(look.getJSONObject("loop").getString("dynData")).isEqualTo(LOOP_DYN)
            // The loop has pictures in its steps, so the watch does not draw its own green circle
            assertThat(dyn.getJSONObject(LOOP_DYN).getString("image1")).isEqualTo(BLANK)
        }
        // The loop warning sits under the age, inside the glucose panel
        val loop = rectOf(design.getJSONObject("loop"))
        val age = rectOf(design.getJSONObject("timestamp"))
        assertThat(loop.top).isAtLeast(age.bottom - 2)
        assertThat(loop.bottom).isAtMost(GL_BOTTOM.toFloat())
    }

    @Test
    fun theAgeAndTheLoopTurnRedWhenOld() {
        val default = defaultDesign()
        val design = design(default)
        val images = images()
        val age = rectOf(design.getJSONObject("timestamp"))
        val loop = rectOf(design.getJSONObject("loop"))
        // Fresh: nothing red under the arrow
        storeSampleData(newFace(), bgAgeMin = 2, loopAgeMin = 3)
        val fresh = preview(design, default, images, previewInstant)
        assertWithMessage("fresh age").that(redPixels(fresh, age)).isEqualTo(0)
        assertWithMessage("fresh loop").that(redPixels(fresh, loop)).isEqualTo(0)
        // Old glucose value and a loop that stopped: both in red
        storeSampleData(newFace(), bgAgeMin = 8, loopAgeMin = 20)
        val old = preview(design, default, images, previewInstant)
        assertWithMessage("old age").that(redPixels(old, age)).isGreaterThan(10)
        assertWithMessage("old loop").that(redPixels(old, loop)).isGreaterThan(10)
        sheet(listOf("fresh" to fresh, "old" to old), "${fileName}_ages.png")
        // Leave the normal sample for the other tests
        storeSampleData(newFace())
    }

    @Test
    fun rendersEveryLook() {
        val default = defaultDesign()
        val design = design(default)
        val images = images()
        val pictures = looks.map { preview(design, default, images, it.instant, it.weather) }
        sheet(looks.map { it.name }.zip(pictures), "${fileName}_looks.png")
        checkLooks(design, pictures)
    }

    @Test
    fun theWakeAnimationFitsEveryLook() {
        val design = design(defaultDesign())
        val images = images()
        for (weather in listOf(null, EventData.Weather.CLEAR, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN)) {
            val look = lookOf(design, weather)
            val spec = readAnimation(look, lookImages(look, images))
            // Every value is inside the safe limits, so nothing is changed on the watch
            assertWithMessage("$weather").that(spec.normalized().withoutImage()).isEqualTo(spec.withoutImage())
            assertWithMessage("$weather duration").that(spec.durationMs).isEqualTo(ANIMATION_MS)
            assertWithMessage("$weather rain").that(spec.rain != null).isEqualTo(hasRain(weather))
            assertWithMessage("$weather glitch").that(spec.glitch != null).isEqualTo(hasGlitch(weather))
            // The beating drop, with its picture
            assertWithMessage("$weather pulse").that(spec.pulse?.drawable).isNotNull()
            // Each battery, icon and number, blinks at 20 % or less
            assertWithMessage("$weather blink").that(spec.blink).containsExactly(
                BlinkSpec(listOf(WATCH_ICON_VIEW, WATCH_NUMBER_VIEW), WatchBatteryLevel.KEY, BATT_LOW),
                BlinkSpec(listOf(PHONE_ICON_VIEW, PHONE_NUMBER_VIEW), PHONE_VALUE_KEY, BATT_LOW)
            )
        }
    }

    @Test
    fun theBatteriesSitSideBySideRightOfTheTime() {
        val design = design(defaultDesign())
        val watchIcon = rectOf(design.getJSONObject(WATCH_ICON_VIEW))
        val phoneIcon = rectOf(design.getJSONObject(PHONE_ICON_VIEW))
        val watchNumber = rectOf(design.getJSONObject(WATCH_NUMBER_VIEW))
        val phoneNumber = rectOf(design.getJSONObject(PHONE_NUMBER_VIEW))
        // Side by side, the watch first, at the same height; each number under its icon
        assertThat(watchIcon.right).isAtMost(phoneIcon.left)
        assertThat(watchIcon.top).isEqualTo(phoneIcon.top)
        for ((icon, number) in listOf(watchIcon to watchNumber, phoneIcon to phoneNumber)) {
            assertThat(number.top).isAtLeast(icon.bottom)
            assertThat(number.left).isEqualTo(icon.left)
            assertThat(number.width()).isEqualTo(icon.width())
        }
        // Right of the time, above the table, on the round screen; the watch lines up with the values of the table
        assertThat(watchIcon.left).isAtLeast(rectOf(design.getJSONObject("minute")).right)
        assertThat(watchIcon.centerX() - WATCH_CASE_RADIUS).isWithin(1f).of(VAL_LEFT.toFloat())
        assertThat(watchNumber.bottom).isAtMost(TABLE_TOP.toFloat())
        for (key in batteryKeys) insideRound(key, rectOf(design.getJSONObject(key)), margin = 2f)
        // "100" fits its column, and no number is cut
        assertThat(textWidth("100", BATT_TEXT_SIZE)).isAtMost(BATT_COL_WIDTH.toFloat())
        assertThat(watchNumber.height()).isAtLeast(BATT_TEXT_SIZE * 1.15f)
        // The views show and are not used for anything else
        for (key in batteryKeys) assertWithMessage(key).that(design.getJSONObject(key).getString("visibility")).isEqualTo("visible")
        assertThat(design.getJSONObject("complication5").getString("visibility")).isEqualTo("gone")
        // Without a value the numbers and the loop warning show "--", under the key the watch reads, and
        // with a value the number (with only the "--" the watch would show it for every value)
        for (dyn in listOf(WATCH_NUMBER_DYN, PHONE_NUMBER_DYN, LOOP_DYN)) {
            val d = design.getJSONObject("dynData").getJSONObject(dyn)
            assertWithMessage(dyn).that(d.getString(JsonKeys.INVALIDTEXTVALUE.key)).endsWith("--")
            assertWithMessage(dyn).that(d.getString(JsonKeys.TEXTVALUE.key + "1")).contains("%.0f")
        }
        // The number has the colour of its view in every look: the colour step of its dynData is the one that shows
        for (look in listOf(null, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN).map { lookOf(design, it) })
            for ((view, dyn) in listOf(WATCH_NUMBER_VIEW to WATCH_NUMBER_DYN, PHONE_NUMBER_VIEW to PHONE_NUMBER_DYN))
                assertWithMessage(view).that(look.getJSONObject("dynData").getJSONObject(dyn).getString("fontColor1")).isEqualTo(look.getJSONObject(view).getString("fontColor"))
    }

    /** Renders [design] with the watch battery at [watch] and the phone battery at [phone] %. At twice the size. */
    private fun renderBatteries(design: JSONObject, default: CwfData, images: Map<String, ByteArray>, watch: Int?, phone: String): Bitmap {
        val face = storedFace(design, default, images, previewInstant, watchBattery = watch)
        storeSampleData(face, phoneBattery = phone)
        Thread.sleep(600)   // past the render data share window
        face.refreshRenderData()
        return face.renderToBitmap(800, 800)
    }

    /** True when [pixel] is [hex], give or take a little for the smoothed edges. */
    private fun near(pixel: Int, hex: String): Boolean {
        val c = Color.parseColor(hex)
        return abs(Color.red(pixel) - Color.red(c)) <= 40 && abs(Color.green(pixel) - Color.green(c)) <= 40 && abs(Color.blue(pixel) - Color.blue(c)) <= 40
    }

    /** How many pixels of the column at [x] have the colour [hex], from [bottom] up, until the first other one. In the design space. */
    private fun filledFromBottom(picture: Bitmap, x: Float, bottom: Float, top: Float, hex: String): Float {
        var y = (bottom * SCALE).toInt()
        var filled = 0
        while (y >= (top * SCALE).toInt() && near(picture.getPixel((x * SCALE).toInt(), y), hex)) {
            filled++
            y--
        }
        return filled / SCALE
    }

    private fun hex(pixel: Int) = String.format("#%06X", pixel and 0xFFFFFF)

    @Test
    fun theBatteriesFillFromTheBottomInTheColourOfTheirLevel() {
        val default = defaultDesign()
        val design = design(default)
        val images = images()
        // Both icons have the same colours: red, yellow and green of the night, or the dark ones of a light look
        val colours = design.getJSONObject("dynData").getJSONObject(WATCH_ICON_DYN)
        for (key in listOf("invalidColor", "color1", "color2", "color3", "color4", "color5"))
            assertWithMessage(key).that(design.getJSONObject("dynData").getJSONObject(PHONE_ICON_DYN).getString(key)).isEqualTo(colours.getString(key))
        assertThat(listOf("color1", "color2", "color3").map { colours.getString(it) }).isAnyOf(listOf(BATT_RED, BATT_YELLOW, BATT_GREEN), listOf(DAY_LOW, DAY_HIGH, DAY_MID))
        assertThat(colours.getString("color4")).isEqualTo(colours.getString("color3"))
        assertThat(colours.getString("color5")).isEqualTo(colours.getString("color3"))
        // The middle of each icon, and the top and bottom of the part that fills
        val watchX = BATT_WATCH_LEFT + BATT_COL_WIDTH / 2f
        val phoneX = BATT_PHONE_LEFT + BATT_COL_WIDTH / 2f
        val watchBottom = BATT_ICON_TOP + BATT_ICON_HEIGHT / 2f + 4.6f
        val watchTop = BATT_ICON_TOP + BATT_ICON_HEIGHT / 2f - 5.2f
        val phoneBottom = BATT_ICON_TOP + BATT_ICON_HEIGHT - 4.8f
        val phoneTop = BATT_ICON_TOP + 4.2f
        // The outline has the level colour too: the strap of the watch, the top edge of the phone
        val outlineY = BATT_ICON_TOP + 1.5f
        // Left of the round watch case the icon is see-through: the background
        val backgroundX = BATT_WATCH_LEFT + 1f
        val backgroundY = BATT_ICON_TOP + BATT_ICON_HEIGHT / 2f
        val pictures = mutableListOf<Pair<String, Bitmap>>()
        val expected = listOf(5 to "color1", 20 to "color1", 21 to "color2", 40 to "color2", 41 to "color3", 66 to "color3", 100 to "color3")
        var lastWatch = 0f
        var lastPhone = 0f
        for ((level, step) in expected) {
            val colour = colours.getString(step)
            val picture = renderBatteries(design, default, images, level, level.toString())
            pictures += "$level %" to picture
            fun at(x: Float, y: Float) = picture.getPixel((x * SCALE).toInt(), (y * SCALE).toInt())
            assertWithMessage("watch at $level: ${hex(at(watchX, outlineY))}").that(near(at(watchX, outlineY), colour)).isTrue()
            assertWithMessage("phone at $level: ${hex(at(phoneX, outlineY))}").that(near(at(phoneX, outlineY), colour)).isTrue()
            if (level >= 20) {
                assertWithMessage("watch fill at $level").that(near(at(watchX, watchBottom), colour)).isTrue()
                assertWithMessage("phone fill at $level").that(near(at(phoneX, phoneBottom), colour)).isTrue()
            }
            // Easy to see on the background behind it
            val background = hex(at(backgroundX, backgroundY))
            assertWithMessage("$colour on $background").that(contrast(colour, background)).isAtLeast(3.0)
            // The fill grows from the bottom up with the level
            val watch = filledFromBottom(picture, watchX, watchBottom, watchTop, colour)
            val phone = filledFromBottom(picture, phoneX, phoneBottom, phoneTop, colour)
            println("$fileName battery $level %: watch filled $watch, phone filled $phone (design px)")
            assertWithMessage("watch at $level").that(watch).isAtLeast(lastWatch)
            assertWithMessage("phone at $level").that(phone).isAtLeast(lastPhone)
            lastWatch = watch
            lastPhone = phone
        }
        // Full is filled to the top, low is filled only at the bottom
        assertThat(lastWatch).isAtLeast((watchBottom - watchTop) * 0.85f)
        assertThat(lastPhone).isAtLeast((phoneBottom - phoneTop) * 0.85f)
        // No value: the empty outline in grey, nothing filled
        val none = renderBatteries(design, default, images, null, "")
        pictures += "no value" to none
        val grey = colours.getString("invalidColor")
        fun noneAt(x: Float, y: Float) = none.getPixel((x * SCALE).toInt(), (y * SCALE).toInt())
        assertThat(near(noneAt(watchX, watchBottom), grey)).isFalse()
        assertThat(near(noneAt(phoneX, phoneBottom), grey)).isFalse()
        assertThat(near(noneAt(watchX, outlineY), grey)).isTrue()
        assertThat(near(noneAt(phoneX, outlineY), grey)).isTrue()
        assertThat(contrast(grey, hex(noneAt(backgroundX, backgroundY)))).isAtLeast(3.0)
        sheet(pictures.map { (name, picture) -> name to Bitmap.createBitmap(picture, 540, 100, 180, 120).scale(360, 240) }, "${fileName}_batteries.png")
        // Leave the normal sample for the other tests
        storeSampleData(newFace())
    }

    protected companion object {

        const val CUSTOM_FONT = "Cyber"

        const val CYAN = "#00F0FF"
        const val VALUE_COLOR = "#FFFFFF"
        const val LINE_COLOR = "#2A2A3C"
        const val LABEL_COLOR = "#8C93B8"
        const val DATE_COLOR = "#8C93B8"
        const val TIME_COLOR = "#00F0FF"
        const val HIGH_COLOR = "#FCEE0A"
        const val MID_COLOR = "#39FF14"
        const val LOW_COLOR = "#FF003C"

        /**
         * The graph's grid lines and its hour and "now" labels. Transparent: the labels sit at the bottom edge,
         * where the round screen cuts them off, so they could never be read; the time is at the top.
         */
        const val GRID_COLOR = CLEAR
        const val CARBS_COLOR = "#FF4FD8"
        const val BASAL_BACK_COLOR = "#7A1FA2"
        const val HEART_COLOR = "#FF4F7B"

        const val DATE_TOP = 6
        const val DATE_HEIGHT = 20
        const val DATE_SIZE = 16

        const val TIME_TOP = 26
        const val TIME_HEIGHT = 74
        const val TIME_SIZE = 64

        /** The minute view: as wide as "48" needs, and the batteries start right after it. */
        const val TIME_SHIFT = 7
        const val MINUTE_LEFT = 210 - TIME_SHIFT
        const val MINUTE_WIDTH = 74

        const val SGV_LEFT = 28
        const val SGV_TOP = 102
        const val SGV_WIDTH = 142
        const val SGV_HEIGHT = 102
        const val SGV_SIZE = 78
        const val ARROW_LEFT = 174
        const val ARROW_TOP = 108
        const val ARROW_SIZE = 54

        const val TABLE_LEFT = 244
        const val TABLE_TOP = 100
        const val TABLE_ROWS = 6
        const val ROW_HEIGHT = 26
        const val TABLE_BOTTOM = TABLE_TOP + TABLE_ROWS * ROW_HEIGHT
        const val ICON_WIDTH = 30
        const val ROW_ICON_SIZE = 18

        /** Space between the label column and the values. */
        const val LABEL_GAP = 8
        const val ROW_PAD = 4
        const val EDGE_MARGIN = 10
        const val LABEL_SIZE = 15
        const val VALUE_SIZE = 20

        /** Where row [k] of the table ends: the round screen's edge at the row's farthest height, less a margin. */
        fun rowRight(k: Int): Int {
            val top = TABLE_TOP + k * ROW_HEIGHT
            val farthest = max(abs(top - 200), abs(top + ROW_HEIGHT - 200)).toDouble()
            return (200 + sqrt(200.0 * 200.0 - farthest * farthest) - EDGE_MARGIN).toInt()
        }

        /** Where the one value column starts, right of the label column. All six values start here, aligned left. */
        const val VAL_LEFT = TABLE_LEFT + ICON_WIDTH + LABEL_GAP

        /**
         * Left of the heart rate and steps boxes: the square the watch keeps for the ring or icon (as high as
         * the box, and a little more) lies before the value column, so the value starts at [VAL_LEFT]. The 2 is
         * measured with the library's drawing (see `allSixValuesStartInOneColumnWithOneColour`).
         */
        const val ROW_BOX_LEFT = VAL_LEFT - ROW_HEIGHT - 2
        val VAL_RIGHT = rowRight(0) - ROW_PAD

        const val LOGO_LEFT = 54
        const val LOGO_TOP = 207
        const val LOGO_WIDTH = 124
        const val LOGO_HEIGHT = 52

        /** The picture of the beating drop, and its box in the 48 high space of the logo: the drop with its outline, red copy and pointed tip. */
        const val LOGO_PULSE = "LogoPulse"
        const val DROP_BOX_LEFT = -3f
        const val DROP_BOX_TOP = -7f
        const val DROP_BOX_RIGHT = 35f
        const val DROP_BOX_BOTTOM = 49f
        const val PULSE_PX_PER_UNIT = 5

        /** How much larger the drop is at the top of a beat: the most that keeps it above the graph frame. */
        const val PULSE_AMPLITUDE = 0.20

        /** How long one wake plays, unless the watch setting chooses another length. */
        const val ANIMATION_MS = 15_000L

        /**
         * The batteries: two columns right of the time, the icon on top and the number under it. The
         * round case of the watch starts where the values of the table below start ([VAL_LEFT]).
         */
        const val BATT_COL_WIDTH = 26
        const val WATCH_CASE_RADIUS = 8.2f
        const val BATT_WATCH_LEFT = MINUTE_LEFT + MINUTE_WIDTH
        const val BATT_PHONE_LEFT = BATT_WATCH_LEFT + BATT_COL_WIDTH + 1
        const val BATT_ICON_TOP = 60
        const val BATT_ICON_HEIGHT = 23
        const val BATT_NUMBER_TOP = BATT_ICON_TOP + BATT_ICON_HEIGHT
        const val BATT_NUMBER_HEIGHT = 17
        const val BATT_TEXT_SIZE = 14

        /** Fill steps of an icon: 5 % each. */
        const val BATT_STEPS = 20

        /** At this level and below a battery is red and blinks. */
        const val BATT_LOW = 20.0
        const val BATT_GREEN = MID_COLOR
        const val BATT_YELLOW = HIGH_COLOR
        const val BATT_RED = LOW_COLOR
        const val BATT_GREY = "#8A8A99"
        const val BATT_DAY_GREY = "#5E5E6B"

        /** The views that carry the batteries, and their dynData. */
        const val WATCH_ICON_VIEW = "delta"
        const val WATCH_NUMBER_VIEW = "rig_battery"
        const val PHONE_ICON_VIEW = "avg_delta"
        const val PHONE_NUMBER_VIEW = "uploader_battery"
        const val WATCH_ICON_DYN = "watchBatteryIcon"
        const val WATCH_NUMBER_DYN = "watchBatteryNumber"
        const val PHONE_ICON_DYN = "phoneBatteryIcon"
        const val PHONE_NUMBER_DYN = "phoneBatteryNumber"



        const val CHART_LEFT = 30
        const val CHART_TOP = 264
        const val CHART_WIDTH = 340
        const val CHART_HEIGHT = 136

        /** The arc along the edge that the sun and the moon follow: from the left over the top to the right. */
        const val ORBIT_RADIUS = 187f
        const val ORBIT_START = 195f
        const val ORBIT_END = 345f

        /** The sun. */
        const val SUN = "Sun"
        const val SUN_SIZE = 24
        const val SUN_PX = 48
        const val SUN_YELLOW = "#FFC400"

        /** The glucose age and the loop warning under the arrow, red, only when they are old. */
        const val AGE_DYN = "bgAge"
        const val LOOP_DYN = "loopAge"
        const val LOOP_LBL_LEFT = 172
        const val LOOP_LBL_TOP = 187
        const val LOOP_LBL_WIDTH = 58
        const val LOOP_LBL_HEIGHT = 15

        /** The panel behind the glucose value. */
        const val GL_LEFT = 24
        const val GL_TOP = 104
        const val GL_RIGHT = 230
        const val GL_BOTTOM = 202

        /** The glucose and graph colours of the light looks: the same meaning as at night, dark enough for light panels. */
        const val DAY_MID = "#075F00"
        const val DAY_HIGH = "#8A4A00"
        const val DAY_LOW = "#A0001F"
        const val DAY_CARBS = "#C2008C"
        const val DAY_BASAL_BACK = "#CDB4EA"
        const val DAY_BASAL_CENTER = "#5B00B8"

        val NIGHT_INK = Ink(line = CYAN, lineShadow = LOW_COLOR, text = "#FFFFFF", textShadow1 = LOW_COLOR, textShadow2 = CYAN)
        val NIGHT_PLATE = Plate(frame = CYAN, thin = LINE_COLOR, label = LABEL_COLOR, logo = NIGHT_INK)

    }
}
