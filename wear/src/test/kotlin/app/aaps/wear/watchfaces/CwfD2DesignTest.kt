// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.provider.Settings
import androidx.core.graphics.createBitmap
import androidx.wear.watchface.complications.data.ComplicationType
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.JsonKeyValues
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import app.aaps.wear.health.HealthKind
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.animation.WakeAnimator
import app.aaps.wear.watchfaces.animation.WakeTimer
import app.aaps.wear.watchfaces.colour.ColourLooks
import app.aaps.wear.watchfaces.daynight.DayNightClock
import app.aaps.wear.watchfaces.daynight.DayOverlay
import app.aaps.wear.watchfaces.daynight.StoredWeather
import app.aaps.wear.watchfaces.utils.DesignTimeText
import app.aaps.wear.watchfaces.utils.LogTimeGraph
import app.aaps.wear.watchfaces.utils.LogTimeScale
import app.aaps.wear.watchfaces.utils.PartTimeLeft
import app.aaps.wear.watchfaces.utils.WatchBatteryLevel
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "D2" (`D2.zip`): a sporty digital face in dark panels, after a well known layout with two small
 * scales on the left edge. Everything AAPS needs is on it:
 *
 * - the **glucose scale** at the top left (where the original has its power scale): a red pointer turns
 *   with the glucose value over 2 to 18 mmol/L, along a band that is red (low), green (in range) and
 *   amber (high);
 * - the **time left** of the sensor and the pod (or cannula) at the bottom left, where the original has its
 *   daily goal: one scale along the edge from 72 hours at the top to 0 at the bottom, red for the last 12
 *   hours, and two hands that come in from the edge, cyan for the sensor and orange for the pump. The ends
 *   are those of the phone's status lights; more than 72 hours shows as 72. No hand when it is not known;
 * - the **trend dial**: a big half circle right of the time, against the rim, with a steel arrow that turns with
 *   the delta of the glucose, not in the seven steps of the arrows: straight right when it stays the same,
 *   45 degrees up at +7.5 mg/dL in 5 minutes, straight up at +15 or more, and the same way down. No arrow
 *   when the delta is not known. The delta itself stands under the pivot, or over it while the glucose falls;
 * - the glucose value very big at the top right, in green, amber or red, and right of it the heart rate over
 *   the weather: an icon (sun or moon, with a cloud, a cloud, rain) and the temperature, as the phone
 *   reads them from Open-Meteo. Nothing when the phone sent no weather for three hours;
 * - a line with the age of the value (red from 10 minutes), the IOB, the carbs and the basal rate;
 * - the batteries of the watch and the phone (red at 20 % or less);
 * - all texts under a fine dark mesh, so they look like LED displays;
 * - the big time with seconds and AM/PM, the day, the date and the moon phase, the months of the year
 *   with a mark on this month;
 * - a **graph** of the glucose of the last hours (as set on the watch, 3 by default) in a small sunk window at
 *   the bottom right, like the display of an instrument: dark tinted glass, a faint grid and dim dots in
 *   muted colours, so it does not compete with the glucose value;
 * - the next sunrise or sunset with its time (from the phone's rough place; `--:--` without one);
 * - two complication slots: heart rate (1) and steps (2).
 *
 * What the wearer can change on the watch:
 * - 12 or 24 hours: the face follows the setting of the watch; AM/PM only shows with 12 hours;
 * - the main colour (the time) and the second colour (the date), see [ColourLooks];
 * - what slots 1 and 2 show, in the watch face editor, and whether each one shows at all;
 * - each value on its own (seconds, delta, IOB, basal, batteries, ...) in the face settings.
 *
 * The texts and the numbers on the dial are in D2 Text (a cut-down Saira Condensed Medium), which the zip brings; the words
 * printed on the dial in Michroma (in the pictures only). Both are in `cwf-designs/fonts`, with their licences.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfD2DesignTest : CwfDesignBase() {

    override val displayName = "D2"
    override val fileName = "D2"

    /**
     * The font of every text, and of the numbers printed on the dial: Saira Condensed Medium, tall and narrow
     * like the digits of the original, cut down to the Latin letters and signs (41 KB instead of 96 KB, see
     * `.claude/procedures/font-subset.md`). Its licence keeps the name "Saira" for the font as made, so the
     * cut-down font is named D2 Text.
     */
    private val textFile = File("cwf-designs/fonts/$TEXT_FONT.ttf")
    private fun textFont(): Typeface = Typeface.createFromFile(textFile)
    override fun valueTypeface(): Typeface = textFont()
    override fun fonts(): Map<String, ByteArray> = mapOf(TEXT_FONT to textFile.readBytes())

    /** The font of the words printed on the dial (MTH, BG, SENSOR, PUMP, IOB, COB): Michroma, wide and square. In the pictures only. */
    private val printFile = File("cwf-designs/fonts/$PRINT_FONT.ttf")
    private fun printFont(): Typeface = Typeface.createFromFile(printFile)

    /** Heart rate and steps in the two slots, as the watch draws them. */
    override fun drawComplicationValues(canvas: Canvas, design: JSONObject, type: ComplicationType) {
        drawSlot(canvas, design.getJSONObject(HR_VIEW), health(HealthKind.HEART_RATE, HR_SAMPLE, type))
        drawSlot(canvas, design.getJSONObject(STEPS_VIEW), health(HealthKind.STEPS, STEPS_SAMPLE, type))
    }

    // ------------------------------------------------------------------------------------------
    // The design json
    // ------------------------------------------------------------------------------------------

    /** A text view in the text font, its digits in the middle of [r] (see [centredText]). */
    private fun JSONObject.text(key: String, r: RectF, size: Int, gravity: String, color: String): JSONObject {
        val (top, height) = centredText(r, size, textFont())
        return place(key, r.left.toInt(), top, r.width().toInt(), height)
            .put("textsize", size).put("gravity", gravity).put("fontColor", color).put("font", TEXT_FONT).put("fontStyle", "normal")
    }

    /** A view that only shows a picture, over [r]. */
    private fun JSONObject.picture(key: String, r: RectF, picture: String): JSONObject =
        place(key, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt()).put("textvalue", "").put("background", picture)

    /**
     * A complication slot that shows only its value, big, as in the original. The watch draws the icon of
     * the source (or the ring) in a square at the left of the box; that square is see-through here, and
     * the icon of the row is in the background under it (see [ROW_ICON_SIZE]).
     */
    private fun JSONObject.slot(key: String, r: RectF, size: Int) {
        place(key, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt())
            .put("color", CLEAR).put("borderRadius", 0).put("ringPrimaryColor", CLEAR).put("ringSecondaryColor", CLEAR)
            .put("fontColor", VALUE).put("iconColor", CLEAR).put("textsize", size).put("font", TEXT_FONT).put("fontStyle", "normal").put("gravity", "left")
    }

    override fun design(default: CwfData): JSONObject = JSONObject(default.json).apply {
        getJSONObject("metadata")
            .put(CwfMetadataKey.CWF_NAME.key, displayName)
            .put(CwfMetadataKey.CWF_FILENAME.key, fileName)
            .put(CwfMetadataKey.CWF_AUTHOR.key, "AAPS (local)")
            .put(CwfMetadataKey.CWF_CREATED_AT.key, "14/11/26")
            .put(CwfMetadataKey.CWF_COMMENT.key, "Digital sports face: glucose scale, trend dial, graph, time left of sensor and pump, big time, weather, sunrise and sunset, batteries, heart rate and steps. Colours in the face settings")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_SECONDS.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DELTA.key, "true")
            // The trend arrow is the view of the average delta
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_AVG_DELTA.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_UPLOADER_BATTERY.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_RIG_BATTERY.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_IOB.key, "true")
            // The pump hand is the view of the reservoir
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_RESERVOIR_LEVEL.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COB.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DETAILED_IOB.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_TEMP_BASAL.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_WEEK_NUMBER.key, "false")

        put("enableSecond", true)
        // The colours of the graph: muted, but bright enough to read on the watch. The glucose value has its own colours (see SGV_DYN).
        put("highColor", GRAPH_HIGH).put("midColor", GRAPH_MID).put("lowColor", GRAPH_LOW)
        put("gridColor", GRAPH_GRID).put("carbColor", GRAPH_CARBS).put("basalBackgroundColor", GRAPH_BASAL).put("basalCenterColor", GRAPH_BASAL_LINE)
        put("pointSize", GRAPH_POINT_SIZE)

        // Every view this design does not use is off
        val used = setOf(
            "background", "chart", "cover_chart", "sgv", "delta", "timestamp", "time", "iob2", "basalRate", "hour", "minute",
            "timePeriod", "day_name", "day", "cob2", "second_hand", HR_VIEW, STEPS_VIEW, SUN_TIME_VIEW, BG_NEEDLE_VIEW, SENSOR_NEEDLE_VIEW, PUMP_NEEDLE_VIEW, TREND_HAND_VIEW, MOON_VIEW, MONTH_VIEW,
            TEMPERATURE_VIEW, "cover_plate", WATCH_NUMBER_VIEW, PHONE_NUMBER_VIEW
        )
        keys().asSequence().toList().forEach { key ->
            val view = optJSONObject(key) ?: return@forEach
            if (view.has("visibility") && key !in used) view.put("visibility", "gone")
        }
        put("background", JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put("visibility", "visible"))

        // The glucose value, very big over two rows at the top right; its trend is the big arrow right of the time
        text("sgv", SGV_BOX, SGV_SIZE, "left", MID).put("dynData", SGV_DYN)
        // In a column right of it: the heart rate, and under it the weather: the icon (the weather looks below choose it)
        // and the temperature. The icon is in the view of the time text, which this design does not use otherwise.
        place("time", WEATHER_ICON_BOX.left.toInt(), WEATHER_ICON_BOX.top.toInt(), WEATHER_ICON_BOX.width().toInt(), WEATHER_ICON_BOX.height().toInt())
            .put("fontColor", CLEAR).put("textsize", 1).put("dynData", WEATHER_ICON_DYN)
        // Over all the texts: a fine dark mesh, so the digits look like a display of LEDs (see ledMesh)
        place("cover_plate", 0, 0, 400, 400)
        text(TEMPERATURE_VIEW, TEMPERATURE_BOX, TEMPERATURE_SIZE, "left", VALUE).put("textvalue", "%.0f°").put("dynValue", false).put("dynData", TEMPERATURE_DYN)

        // The batteries: the numbers, red at 20 % or less (the icons are in the background)
        text(WATCH_NUMBER_VIEW, WATCH_NUMBER_BOX, SMALL_SIZE, "left", WHITE).put("textvalue", "%.0f%%").put("dynValue", false).put("dynData", WATCH_NUMBER_DYN)
        text(PHONE_NUMBER_VIEW, PHONE_NUMBER_BOX, SMALL_SIZE, "left", WHITE).put("textvalue", "%.0f%%").put("dynValue", false).put("dynData", PHONE_NUMBER_DYN)

        slot(HR_VIEW, HR_BOX, HR_SIZE)

        // The delta, in the trend dial: under the pivot, or over it when the arrow points down, so the arrow never hides it
        text("delta", DELTA_BOX, DELTA_SIZE, "center", VALUE).put("dynData", DELTA_PLACE_DYN)
        // The line under the glucose: age, IOB, carbs, basal rate
        text("timestamp", AGE_BOX, LINE_SIZE, "center", VALUE).put("textvalue", "%.0f'").put("dynValue", false).put("dynData", AGE_DYN)
        text("iob2", IOB_BOX, LINE_SIZE, "left", VALUE)
        text("cob2", COB_BOX, LINE_SIZE, "left", VALUE)
        text("basalRate", BASAL_BOX, LINE_SIZE, "left", VALUE)

        // The date with the moon, and the time
        text("day_name", DAY_NAME_BOX, DAY_NAME_SIZE, "center", WHITE).put("allCaps", true)
        text("day", DAY_BOX, DAY_SIZE, "center", COLOUR_2)
        picture(MOON_VIEW, MOON_BOX, moonName(MOON_STEPS / 2 + 1)).put("dynData", MOON_DYN)
        // The colon between them is a picture (see colonPicture), in the same colour
        text("hour", HOUR_BOX, TIME_SIZE, "right", COLOUR_1)
        text("minute", MINUTE_BOX, TIME_SIZE, "left", COLOUR_1)
        // The seconds: a lit marker that runs round the rim, a tick each second: the second hand, a picture of the whole face, in the main colour
        place("second_hand", 0, 0, 400, 400).put("color", COLOUR_1)
        text("timePeriod", PERIOD_BOX, PERIOD_SIZE, "center", WHITE).put("background", PERIOD_FRAME)

        // The mark over this month: moved to the right by the month. In the view of the month, so "show date" hides it with the
        // date; its own text (the name of the month) is see-through.
        picture(MONTH_VIEW, monthMarkBox(1), MONTH_MARK).put("fontColor", CLEAR).put("dynData", MONTH_DYN)

        // The two pointers: one picture of the whole dial, turned by the watch round the middle
        picture(BG_NEEDLE_VIEW, NEEDLE_BOX, NEEDLE).put("rotationOffset", true).put("dynData", BG_NEEDLE_DYN)
        // The time-left hands of the sensor and the pump: like the glucose pointer, turned round the middle of the dial
        picture(SENSOR_NEEDLE_VIEW, NEEDLE_BOX, SENSOR_HAND).put("dynData", SENSOR_NEEDLE_DYN)
        picture(PUMP_NEEDLE_VIEW, NEEDLE_BOX, PUMP_HAND).put("dynData", PUMP_NEEDLE_DYN)
            .put("fontColor", CLEAR).put("dynValue", false)
        // The trend arrow, big, right of the time: turned round the middle of the straight side of its half dial by the delta of the glucose
        picture(TREND_HAND_VIEW, trendDialBox(), TREND_HAND).put("rotationOffset", true).put("fontColor", CLEAR).put("dynValue", false)
            .put("dynData", TREND_HAND_DYN)
        // The colon of the time, white, so the watch paints it in the main colour
        place("cover_chart", COLON_BOX.left.toInt(), COLON_BOX.top.toInt(), COLON_BOX.width().toInt(), COLON_BOX.height().toInt())
            .put("color", COLOUR_1)

        // The bottom panel: the next sunrise or sunset (its icon is in the background) and the steps
        text(SUN_TIME_VIEW, SUN_TIME_BOX, SUN_TIME_SIZE, "left", VALUE).put("textvalue", DesignTimeText.TOKEN).put("dynValue", false).put("dynData", SUN_TIME_DYN)
        slot(STEPS_VIEW, STEPS_BOX, STEPS_SIZE)
        // The graph, in its window
        // The view is taller than the glass: the chart keeps an empty margin round the plot, so the plot fills the glass
        place("chart", CHART_VIEW.left.toInt(), CHART_VIEW.top.toInt(), CHART_VIEW.width().toInt(), CHART_VIEW.height().toInt())

        (optJSONObject("dynData") ?: JSONObject().also { put("dynData", it) })
            .put(SGV_DYN, JSONObject().put("valueKey", JsonKeyValues.SGV_LEVEL.key).put("minData", -1).put("maxData", 1)
                .put("invalidFontColor", MUTED).put("fontColor1", LOW).put("fontColor2", MID).put("fontColor3", HIGH))
            .put(WATCH_NUMBER_DYN, batteryNumberDynData(WatchBatteryLevel.KEY))
            .put(PHONE_NUMBER_DYN, batteryNumberDynData(PHONE_VALUE_KEY))
            .put(DELTA_PLACE_DYN, JSONObject().put("valueKey", "delta").put("minData", -1).put("maxData", 1)
                .put("topOffset1", -DELTA_UP).put("topOffset2", 0).put("invalidTopOffset", 0))
            .put(AGE_DYN, ageColourDynData("timestamp", AGE_LIMIT, VALUE))
            .put(MOON_DYN, moonDynData())
            .put(MONTH_DYN, monthDynData())
            .put(BG_NEEDLE_DYN, JSONObject().put("valueKey", "sgv").put("minData", BG_MIN).put("maxData", BG_MAX)
                .put("rotationOffset", JSONObject().put("minValue", BG_FROM).put("maxValue", BG_TO).put("invalidValue", BG_FROM))
                .put("invalidImage", BLANK).put("image1", NEEDLE))
            .put(SENSOR_NEEDLE_DYN, hoursHandDynData(PartTimeLeft.SENSOR_KEY, SENSOR_HAND))
            .put(PUMP_NEEDLE_DYN, hoursHandDynData(PartTimeLeft.PUMP_KEY, PUMP_HAND))
            .put(TREND_HAND_DYN, trendHandDynData())
            .put(WEATHER_ICON_DYN, weatherIconDynData(BLANK, BLANK))
            .put(SUN_TIME_DYN, JSONObject().put("valueKey", DayNightClock.NEXT_SUN_EVENT_KEY).put("minData", 0).put("maxData", 1)
                .put("textvalue1", DesignTimeText.TOKEN).put("invalidTextvalue", "--:--"))
            .put(TEMPERATURE_DYN, JSONObject().put("valueKey", StoredWeather.JSON_VALUE_KEY).put("textvalue1", "%.0f°").put("invalidTextvalue", ""))

        // The weather: the icon of each kind, by day and by night. Without a weather (none, or too old) no icon.
        put(DayOverlay.WEATHER_KEY, JSONObject().apply {
            for ((weather, icons) in WEATHER_ICONS)
                put(weather, JSONObject().put("dynData", JSONObject().put(WEATHER_ICON_DYN, weatherIconDynData(icons.first, icons.second))))
        })

        // The colours the wearer can choose on the watch
        put(ColourLooks.JSON_KEY, JSONObject()
            .put("colour1", JSONObject().apply {
                for (key in listOf("hour", "minute")) put(key, JSONObject().put("fontColor", ColourLooks.PLACEHOLDER))
                for (key in listOf("cover_chart", "second_hand")) put(key, JSONObject().put("color", ColourLooks.PLACEHOLDER))
            })
            .put("colour2", JSONObject().put("day", JSONObject().put("fontColor", ColourLooks.PLACEHOLDER))))

        put(WakeAnimationSpec.JSON_KEY, wakeAnimation())
    }

    /**
     * What moves when the wrist is raised, like an instrument that is switched on: the glucose pointer and then
     * the two time-left hands swing to full scale and back to their value; the trend arrow shakes and settles;
     * the graph draws itself in from the left; at the end a light slides over the rim and the glass of the graph.
     */
    private fun wakeAnimation(): JSONObject {
        fun views(vararg keys: String) = JSONArray().apply { keys.forEach { put(it) } }
        fun box(r: RectF) = JSONObject().put("left", r.left).put("top", r.top).put("width", r.width()).put("height", r.height())
        fun sweep(view: String, empty: Double, full: Double, delay: Long) =
            JSONObject().put("views", views(view)).put("emptyAngle", empty).put("fullAngle", full).put("delayMs", delay).put("upMs", SWEEP_UP_MS).put("settleMs", SWEEP_SETTLE_MS)
        return JSONObject()
            .put("durationMs", ANIMATION_MS).put("fps", ANIMATION_FPS).put("fadeMs", 0)
            .put("sweep", JSONArray()
                .put(sweep(BG_NEEDLE_VIEW, BG_FROM, BG_TO, 0))
                .put(sweep(SENSOR_NEEDLE_VIEW, HOURS_EMPTY_ANGLE, HOURS_FULL_ANGLE, SENSOR_SWEEP_DELAY_MS))
                .put(sweep(PUMP_NEEDLE_VIEW, HOURS_EMPTY_ANGLE, HOURS_FULL_ANGLE, PUMP_SWEEP_DELAY_MS)))
            // The month mark runs to December and back to this month, like the needles
            .put("slide", JSONArray().put(JSONObject().put("views", views(MONTH_VIEW)).put("fullDx", 11 * MONTH_STEP)
                .put("delayMs", MONTH_SLIDE_DELAY_MS).put("upMs", SWEEP_UP_MS).put("settleMs", SWEEP_SETTLE_MS)))
            .put("wobble", JSONArray().put(JSONObject().put("views", views(TREND_HAND_VIEW))
                .put("delayMs", WOBBLE_DELAY_MS).put("amplitude", WOBBLE_DEGREES).put("hz", WOBBLE_HZ).put("decayMs", WOBBLE_DECAY_MS)))
            .put("trace", JSONObject().put("view", "chart").put("rect", box(GRAPH_BOX)).put("delayMs", TRACE_DELAY_MS).put("durationMs", TRACE_MS)
                .put("color", TRACE_COLOUR).put("alpha", 170).put("width", 7))
            .put("glint", JSONObject()
                .put("rings", JSONArray().put(JSONObject().put("cx", 200).put("cy", 200).put("inner", RIM_INNER).put("outer", 200)))
                .put("boxes", JSONArray().put(box(GRAPH_WINDOW))).put("cornerRadius", 4)
                .put("delayMs", GLINT_DELAY_MS).put("durationMs", GLINT_MS).put("angle", GLINT_ANGLE).put("width", 90).put("color", "#FFFFFF").put("alpha", 210))
    }

    /** A battery number: the value in white, red at 20 % or less (five steps over 1 to 101), `--` without a value. */
    private fun batteryNumberDynData(valueKey: String): JSONObject = JSONObject().put("valueKey", valueKey).put("minData", 1).put("maxData", 101)
        .put("textvalue1", "%.0f%%").put("invalidTextvalue", "--").put("invalidFontColor", MUTED).put("fontColor1", LOW)
        .apply { for (step in 2..5) put("fontColor$step", WHITE) }

    /** An age in minutes, in [color] below [limit] and red from [limit] (two steps over 0 to 2 x [limit]), `--` without a value. */
    private fun ageColourDynData(valueKey: String, limit: Double, color: String): JSONObject = JSONObject()
        .put("valueKey", valueKey).put("minData", 0).put("maxData", 2 * limit)
        .put("textvalue1", "%.0f'").put("invalidTextvalue", "--").put("invalidFontColor", MUTED).put("fontColor1", color).put("fontColor2", LOW)

    /**
     * A time-left hand for the part [valueKey] (see [PartTimeLeft]), with the picture [hand]: turned from 0 hours at
     * [HOURS_EMPTY_ANGLE] (the end, or past it) to [HOURS_MAX] at [HOURS_FULL_ANGLE] on the log scale of [hoursAngle];
     * more hours stay there. No hand when it is not known. No text and no colour of its own: the pump hand is the view
     * of the reservoir, whose text the watch fills in.
     *
     * The watch only turns a view in a straight line with the value, so the hand turns by steps: one per
     * [HOURS_PER_STEP], each with the angle of the middle of its step. The watch rounds an angle to whole degrees anyway.
     */
    private fun hoursHandDynData(valueKey: String, hand: String): JSONObject {
        val steps = (HOURS_MAX / HOURS_PER_STEP).toInt()
        return JSONObject().put("valueKey", valueKey).put("minData", 0).put("maxData", HOURS_MAX)
            .put("invalidRotationOffset", HOURS_EMPTY_ANGLE.roundToInt())
            .put("invalidImage", BLANK).put("image1", hand)
            .put("textvalue1", "").put("invalidTextvalue", "").put("fontColor1", CLEAR).put("invalidFontColor", CLEAR)
            .apply { for (i in 1..steps) put("rotationOffset$i", hoursAngle((i - 0.5) * HOURS_PER_STEP).roundToInt()) }
    }

    /**
     * The angle of a time-left hand at [hours] left: a mild log scale, so the last day takes half of the scale and
     * the last 12 hours a third.
     */
    private fun hoursAngle(hours: Double): Float {
        val part = ln(1 + hours.coerceIn(0.0, HOURS_MAX) / HOURS_DETAIL) / ln(1 + HOURS_MAX / HOURS_DETAIL)
        return (HOURS_EMPTY_ANGLE + part * (HOURS_FULL_ANGLE - HOURS_EMPTY_ANGLE)).toFloat()
    }

    /** The hours at [part] (0 to 1) of the time-left scale: the opposite of [hoursAngle]. */
    private fun hoursAtPart(part: Double): Double = HOURS_DETAIL * ((1 + HOURS_MAX / HOURS_DETAIL).pow(part) - 1)

    /**
     * The trend arrow: turned by the delta of the glucose (mg/dL in 5 minutes), [TREND_DEGREES_PER_MGDL] degrees for
     * each, from straight right; at [TREND_MAX_DELTA] or more it points straight up or down. No arrow when the delta
     * is not known. No text and no colour of its own: it is the view of the average delta.
     */
    private fun trendHandDynData(): JSONObject = JSONObject().put("valueKey", "delta").put("minData", -TREND_MAX_DELTA).put("maxData", TREND_MAX_DELTA)
        .put("rotationOffset", JSONObject().put("minValue", trendAngle(-TREND_MAX_DELTA)).put("maxValue", trendAngle(TREND_MAX_DELTA)).put("invalidValue", 90))
        .put("invalidImage", BLANK).put("image1", TREND_HAND)
        .put("textvalue1", "").put("invalidTextvalue", "").put("fontColor1", CLEAR).put("invalidFontColor", CLEAR)

    /** The angle of the trend arrow, from straight up, clockwise, for a delta of [mgdl] in 5 minutes. */
    private fun trendAngle(mgdl: Double): Double = 90.0 - mgdl.coerceIn(-TREND_MAX_DELTA, TREND_MAX_DELTA) * TREND_DEGREES_PER_MGDL

    /** The view of the trend arrow: a square round the middle of the straight side of its half dial. */
    private fun trendDialBox() = RectF(TREND_DIAL_X - TREND_DIAL_R, TREND_DIAL_Y - TREND_DIAL_R, TREND_DIAL_X + TREND_DIAL_R, TREND_DIAL_Y + TREND_DIAL_R)

    /**
     * The weather icon: [day] by day and [night] at night. The night progress is 0 all day and more at
     * night, so of two steps over 0 to [NIGHT_STEP] the first is the day.
     */
    private fun weatherIconDynData(day: String, night: String): JSONObject = JSONObject()
        .put("valueKey", JsonKeyValues.NIGHT_PROGRESS.key).put("minData", 0).put("maxData", 2 * NIGHT_STEP)
        .put("invalidImage", BLANK).put("image1", day).put("image2", night)

    private fun moonDynData(): JSONObject = JSONObject().put("valueKey", JsonKeyValues.MOON_PHASE.key)
        .put("minData", -0.5 / MOON_STEPS).put("maxData", 1 + 0.5 / MOON_STEPS).put("invalidImage", moonName(MOON_STEPS / 2 + 1))
        .apply { for (k in 1..MOON_STEPS + 1) put("image$k", moonName(if (k == MOON_STEPS + 1) 1 else k)) }

    private fun moonName(k: Int) = "Moon" + k.toString().padStart(2, '0')

    /** The angle of the glucose pointer at [mgdl], in degrees from straight up, clockwise. */
    private fun bgAngle(mgdl: Double): Double = BG_FROM + (mgdl.coerceIn(BG_MIN, BG_MAX) - BG_MIN) * (BG_TO - BG_FROM) / (BG_MAX - BG_MIN)


    /**
     * Moves the month mark: one step per month, `leftOffset1` (January) to `leftOffset12`. Not a range with `minValue` and
     * `maxValue`: the watch scales such an offset twice, so on a screen that is not 400 pixels the mark lands on the wrong
     * month. A step offset is scaled once.
     */
    private fun monthDynData(): JSONObject = JSONObject().put("valueKey", "month").put("minData", 1).put("maxData", 12).put("invalidLeftOffset", 0).apply {
        for (month in 1..12) put("leftOffset$month", (month - 1) * MONTH_STEP)
    }

    /** Where the month mark is over [month] (1 to 12). */
    private fun monthMarkBox(month: Int): RectF {
        val x = monthX(month)
        return RectF(x - 8f, MONTH_MARK_TOP, x + 8f, MONTH_MARK_TOP + 5f)
    }

    private fun monthX(month: Int): Float = MONTH_FIRST_X + (month - 1) * MONTH_STEP

    /** Where the texts of the face are: the boxes the [ledMesh] lies over. */
    private fun textBoxes(): List<RectF> = listOf(
        HOUR_BOX, COLON_BOX, MINUTE_BOX, SGV_BOX, HR_BOX, TEMPERATURE_BOX,
        DAY_NAME_BOX, DAY_BOX, AGE_BOX, IOB_BOX, COB_BOX, BASAL_BOX, WATCH_NUMBER_BOX, PHONE_NUMBER_BOX, SUN_TIME_BOX, STEPS_BOX,
        // The numbers of the months, not the mark over them, which moves
        RectF(MONTH_PANEL.left + 4f, MONTH_TEXT_Y - 8f, MONTH_PANEL.right - 4f, MONTH_PANEL.bottom - 1f)
    )

    /**
     * The LED look: a picture of the whole face, see-through but for a fine mesh in the colour of the panels over the
     * [textBoxes], every [LED_PITCH] across and down. Over the panels it does not show; over a lit digit it splits the
     * strokes into small dots, like the lights of an LED display. It lies over all texts (the view over them all), so it needs no
     * view of its own per text.
     */
    private fun ledMesh(): ByteArray {
        val s = SCALE
        val image = createBitmap(800, 800).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val gap = Paint().apply { color = color(PANEL); strokeWidth = LED_GAP * s }
        for (box in textBoxes()) {
            canvas.save()
            canvas.clipRect(scaled(box))
            var x = (box.left / LED_PITCH).toInt() * LED_PITCH
            while (x <= box.right) {
                canvas.drawLine(x * s, box.top * s, x * s, box.bottom * s, gap)
                x += LED_PITCH
            }
            var y = (box.top / LED_PITCH).toInt() * LED_PITCH
            while (y <= box.bottom) {
                canvas.drawLine(box.left * s, y * s, box.right * s, y * s, gap)
                y += LED_PITCH
            }
            canvas.restore()
        }
        return pngBytes(image)
    }

    // ------------------------------------------------------------------------------------------
    // The pictures
    // ------------------------------------------------------------------------------------------

    override fun images(): Map<String, ByteArray> = mapOf(
        ResFileMap.BACKGROUND.fileName to background(),
        ResFileMap.COVER_CHART.fileName to colonPicture(),
        ResFileMap.COVER_PLATE.fileName to ledMesh(),
        NEEDLE to needle(NEEDLE_RED, "#B3121A"),
        SENSOR_HAND to needle(SENSOR_COLOUR, "#1A7FA8"),
        PUMP_HAND to needle(PUMP_COLOUR, "#B8650A"),
        TREND_HAND to trendHand(),
        MONTH_MARK to monthMark(),
        PERIOD_FRAME to periodFrame(),
        ResFileMap.SECOND_HAND.fileName to runnerPicture(),
        BLANK to blankPng()
    ) + (1..MOON_STEPS).associate { moonName(it) to moonPicture((it - 1).toDouble() / MOON_STEPS, MOON_BOX.width().toInt()) } + weatherIcons()

    private fun color(hex: String) = Color.parseColor(hex)
    private fun paint(hex: String, style: Paint.Style = Paint.Style.FILL, width: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(hex); this.style = style; strokeWidth = width * SCALE }

    private fun Canvas.line(from: Pair<Float, Float>, to: Pair<Float, Float>, paint: Paint) =
        drawLine(from.first * SCALE, from.second * SCALE, to.first * SCALE, to.second * SCALE, paint)

    /** A text printed on the background, its middle at [x], [y], turned by [angle] degrees. */
    private fun Canvas.label(
        text: String, x: Float, y: Float, size: Float, hex: String, angle: Float = 0f, align: Paint.Align = Paint.Align.CENTER, shadow: Boolean = false,
        face: Typeface = printFont()
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(hex); textSize = size * SCALE; textAlign = align; typeface = face
            if (shadow) setShadowLayer(2.5f * SCALE, 0.6f * SCALE, 0.8f * SCALE, Color.BLACK)
        }
        save()
        rotate(angle, x * SCALE, y * SCALE)
        drawText(text, x * SCALE, y * SCALE - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f, p)
        restore()
    }

    private fun scaled(r: RectF) = RectF(r.left * SCALE, r.top * SCALE, r.right * SCALE, r.bottom * SCALE)

    /** A dark panel with a metal edge, lit from above: light at the top, dark at the bottom. */
    private fun Canvas.panel(path: Path) {
        drawPath(path, paint(PANEL))
        val bounds = RectF().also { path.computeBounds(it, true) }
        drawPath(path, paint(EDGE, Paint.Style.STROKE, 1.8f).apply {
            shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom, color(EDGE_LIGHT), color(EDGE_DARK), Shader.TileMode.CLAMP)
        })
    }

    /**
     * The pocket of the glucose scale: the dial left of the top right panel, as low as that panel, so the steel strip
     * under them runs on to the rim; a strip of [POCKET_STRIP] steel between it and the panel.
     */
    private fun topPocket(): Path = pocket(
        listOf(0f to 0f, edgeX(0f, TOP_PANEL_TOP, TOP_PANEL_BOTTOM, TOP_PANEL_TOP_LEFT, TOP_PANEL_BOTTOM_LEFT) - POCKET_STRIP to 0f,
               edgeX(TOP_PANEL_BOTTOM, TOP_PANEL_TOP, TOP_PANEL_BOTTOM, TOP_PANEL_TOP_LEFT, TOP_PANEL_BOTTOM_LEFT) - POCKET_STRIP to TOP_PANEL_BOTTOM,
               0f to TOP_PANEL_BOTTOM)
    )

    /** The pocket of the time-left scale: the dial left of the bottom right panel, from as high as that panel, the same way. */
    private fun bottomPocket(): Path = pocket(
        listOf(0f to LOW_PANEL_TOP,
               LOW_PANEL_TOP_LEFT - POCKET_STRIP to LOW_PANEL_TOP,
               edgeX(400f, LOW_PANEL_TOP, LOW_PANEL_BOTTOM, LOW_PANEL_TOP_LEFT, LOW_PANEL_BOTTOM_LEFT) - POCKET_STRIP to 400f, 0f to 400f)
    )

    /** The part of the dial inside the metal rim that lies within the polygon [corners]. */
    private fun pocket(corners: List<Pair<Float, Float>>): Path {
        val s = SCALE
        val shape = Path().apply {
            corners.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
            close()
        }
        return Path().apply { op(shape, Path().apply { addCircle(200f * s, 200f * s, RIM_INNER * s, Path.Direction.CW) }, Path.Op.INTERSECT) }
    }

    /**
     * A pocket of brushed metal, sunk into the dial: grey metal with fine rings round the middle of the dial and a
     * light that turns with the angle, darker so the white printing stands out, and a soft shadow inside along
     * its edges, as if the panels and the rim lie over it.
     */
    private fun Canvas.metalPocket(path: Path) {
        val s = SCALE
        val c = 200f * s
        save()
        clipPath(path)
        drawPaint(Paint().apply { shader = SweepGradient(c, c, METAL_SWEEP.map { color(it) }.toIntArray(), null) })
        // Brushing: fine rings, each a little lighter or darker
        val random = Random(307)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
        var r = 60f * s
        while (r < 200f * s) {
            val light = random.nextBoolean()
            ring.color = if (light) Color.argb(random.nextInt(22), 255, 255, 255) else Color.argb(random.nextInt(30), 0, 0, 0)
            drawCircle(c, c, r, ring)
            r += 1f
        }
        // Ribs: raised ridges that run out from the middle, each with a lit side and a dark side
        ridges(this, from = 60f, to = 200f, every = POCKET_RIDGE_STEP, light = 46, dark = 120)
        drawPaint(Paint().apply { color = Color.argb(70, 0, 0, 0) })
        // The shadow inside the edges, deepest at the top left where the light comes from, and the light the
        // lower edge catches: together they make the pocket lie lower than the panels
        save()
        translate(2f * s, 3f * s)
        drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 16f * s; color = Color.argb(235, 0, 0, 0); maskFilter = BlurMaskFilter(9f * s, BlurMaskFilter.Blur.NORMAL)
        })
        restore()
        save()
        translate(-1.2f * s, -1.2f * s)
        drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * s; color = Color.argb(90, 255, 255, 255); maskFilter = BlurMaskFilter(0.8f * s, BlurMaskFilter.Blur.NORMAL)
        })
        restore()
        restore()
        drawPath(path, paint("#050506", Paint.Style.STROKE, 1.2f))
    }

    /** The metal rim round the dial, with a dark line inside and a light line outside, and the minute ticks on it. */
    private fun metalRim(canvas: Canvas) {
        val s = SCALE
        val c = 200f * s
        val rim = Path().apply {
            addCircle(c, c, 200f * s, Path.Direction.CW)
            addCircle(c, c, RIM_INNER * s, Path.Direction.CCW)
        }
        canvas.drawPath(rim, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = SweepGradient(c, c, RIM_SWEEP.map { color(it) }.toIntArray(), null) })
        // Fine ridges all round, like the edge of a coin
        canvas.save()
        canvas.clipPath(rim)
        ridges(canvas, from = RIM_INNER, to = 200f, every = RIM_RIDGE_STEP, light = 70, dark = 150)
        canvas.restore()
        canvas.drawCircle(c, c, RIM_INNER * s, paint("#060607", Paint.Style.STROKE, 1.4f))
        canvas.drawCircle(c, c, (RIM_INNER + 1.3f) * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f * s; color = Color.argb(70, 255, 255, 255) })
        for (i in 0 until 60) {
            val major = i % 5 == 0
            canvas.line(polar(200f, 200f, 198.5f, i * 6f), polar(200f, 200f, if (major) 190.5f else 194f, i * 6f), paint(if (major) "#E2E5E9" else "#1C1E21", width = if (major) 1.8f else 1.1f))
        }
        // Screws in the rim, between the hour ticks
        for (angle in RIM_SCREW_ANGLES) {
            val (x, y) = polar(200f, 200f, (RIM_INNER + 200f) / 2, angle)
            screw(canvas, x, y, 2.8f, angle)
        }
    }

    /**
     * Ridges that run out from the middle of the dial, from radius [from] to [to], one every [every] degrees: a
     * lit line and, just beside it, a dark line, with the alpha [light] and [dark]. Draws where the canvas is clipped.
     */
    private fun ridges(canvas: Canvas, from: Float, to: Float, every: Float, light: Int, dark: Int) {
        val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(light, 255, 255, 255); strokeWidth = 0.7f * SCALE }
        val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(dark, 0, 0, 0); strokeWidth = 0.9f * SCALE }
        var angle = 0f
        while (angle < 360f) {
            canvas.line(polar(200f, 200f, from, angle), polar(200f, 200f, to, angle), lit)
            canvas.line(polar(200f, 200f, from, angle + every * 0.35f), polar(200f, 200f, to, angle + every * 0.35f), shade)
            angle += every
        }
    }

    /**
     * A small screw head at [x], [y] with radius [r]: a domed metal dot lit from the top left, a dark ring round it,
     * and a slot turned by [slot] degrees.
     */
    private fun screw(canvas: Canvas, x: Float, y: Float, r: Float, slot: Float) {
        val s = SCALE
        canvas.drawCircle((x + 0.4f) * s, (y + 0.6f) * s, (r + 0.6f) * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) })
        canvas.drawCircle(x * s, y * s, r * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient((x - r * 0.4f) * s, (y - r * 0.4f) * s, r * 1.6f * s, color("#F0F2F4"), color("#55595F"), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(x * s, y * s, r * s, paint("#26282C", Paint.Style.STROKE, 0.5f))
        val (x1, y1) = polar(x, y, r * 0.7f, slot)
        val (x2, y2) = polar(x, y, r * 0.7f, slot + 180f)
        canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, paint("#2E3135", width = 0.8f))
    }

    /** The holes of the metal frame: the pockets and the panels, which lie lower than the frame. */
    private fun frameHoles(): List<Path> = listOf(
        topPocket(), bottomPocket(), trendDialShape(),
        sidePanel(TOP_PANEL_TOP, TOP_PANEL_BOTTOM, TOP_PANEL_TOP_LEFT, TOP_PANEL_BOTTOM_LEFT),
        sidePanel(LOW_PANEL_TOP, LOW_PANEL_BOTTOM, LOW_PANEL_TOP_LEFT, LOW_PANEL_BOTTOM_LEFT),
        roundRect(LINE_PANEL, 12f), roundRect(TIME_PANEL, 16f), roundRect(MONTH_PANEL, 10f)
    )

    /**
     * The frame between the panels and the pockets, inside the rim: raised steel, brushed side to side, lighter at
     * the top. Returns its shape, for [frameBevel].
     */
    private fun metalFrame(canvas: Canvas): Path {
        val s = SCALE
        val frame = Path().apply { addCircle(200f * s, 200f * s, RIM_INNER * s, Path.Direction.CW) }
        for (hole in frameHoles()) frame.op(hole, Path.Op.DIFFERENCE)
        canvas.save()
        canvas.clipPath(frame)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 400f * s, color(FRAME_TOP), color(FRAME_BOTTOM), Shader.TileMode.CLAMP) })
        // Brushing: fine lines side to side, each a little lighter or darker
        val random = Random(2307)
        val line = Paint().apply { strokeWidth = 1f }
        var y = 0f
        while (y < 400f * s) {
            line.color = if (random.nextBoolean()) Color.argb(random.nextInt(26), 255, 255, 255) else Color.argb(random.nextInt(34), 0, 0, 0)
            canvas.drawLine(0f, y, 400f * s, y, line)
            y += 1f
        }
        canvas.restore()
        return frame
    }

    /**
     * The edges of the raised [frame] round its holes, lit from the top left: a light edge where the frame faces up
     * (below a hole) and a shadow where it faces down (above a hole).
     */
    private fun frameBevel(canvas: Canvas, frame: Path) {
        val s = SCALE
        canvas.save()
        canvas.clipPath(frame)
        for (hole in frameHoles()) {
            canvas.save()
            canvas.translate(0.7f * s, 1.1f * s)
            canvas.drawPath(hole, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * s; color = Color.argb(130, 255, 255, 255) })
            canvas.restore()
            canvas.save()
            canvas.translate(-0.6f * s, -1f * s)
            canvas.drawPath(hole, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * s; color = Color.argb(190, 0, 0, 0) })
            canvas.restore()
        }
        canvas.restore()
    }

    /** A small round pin head at [x], [y] with radius [r]: domed steel lit from the top left, with a dark ring and a shadow. */
    private fun pin(canvas: Canvas, x: Float, y: Float, r: Float) {
        val s = SCALE
        canvas.drawCircle((x + 0.3f) * s, (y + 0.5f) * s, (r + 0.4f) * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) })
        canvas.drawCircle(x * s, y * s, r * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient((x - r * 0.4f) * s, (y - r * 0.4f) * s, r * 1.5f * s, color("#FFFFFF"), color("#5A5F66"), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(x * s, y * s, r * s, paint("#2A2D31", Paint.Style.STROKE, 0.4f))
    }

    private fun roundRect(r: RectF, radius: Float) = Path().apply { addRoundRect(scaled(r), radius * SCALE, radius * SCALE, Path.Direction.CW) }

    /** A panel that a slanted line cuts on the left, from [topLeft] to [bottomLeft], between [top] and [bottom], round on the right. */
    private fun sidePanel(top: Float, bottom: Float, topLeft: Float, bottomLeft: Float): Path {
        val s = SCALE
        val shape = Path().apply {
            moveTo(topLeft * s, top * s)
            lineTo(400f * s, top * s)
            lineTo(400f * s, bottom * s)
            lineTo(bottomLeft * s, bottom * s)
            close()
        }
        return Path().apply { op(shape, Path().apply { addCircle(200f * s, 200f * s, PANEL_R * s, Path.Direction.CW) }, Path.Op.INTERSECT) }
    }

    /** The x of the slanted left edge of a side panel at [y]. */
    private fun edgeX(y: Float, top: Float, bottom: Float, topLeft: Float, bottomLeft: Float) = topLeft + (y - top) * (bottomLeft - topLeft) / (bottom - top)

    /** A line across a side panel at [y], from its slanted edge to the round edge. */
    private fun Canvas.rowLine(y: Float, left: Float) {
        val right = 200f + sqrt(PANEL_R * PANEL_R - (y - 200f) * (y - 200f)) - 4f
        drawLine((left + 6f) * SCALE, y * SCALE, right * SCALE, y * SCALE, paint(ROW_LINE, width = 1f))
    }

    /**
     * The background, 800 x 800, as jpg: the dial, the panels with their frames, the scales with their
     * numbers, the months, the battery icons and the small labels. All that does not move.
     */
    private fun background(): ByteArray {
        val s = SCALE
        val image = createBitmap(800, 800).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(image)
        val c = 200f * s
        // The dial: dark grey, lighter in the middle, with a fine carbon weave
        canvas.drawCircle(c, c, 200f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(c, c * 0.8f, 200f * s, color("#2B2E33"), color("#121315"), Shader.TileMode.CLAMP)
        })
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(c, c, 200f * s, Path.Direction.CW) })
        val weave = Paint().apply { color = Color.argb(14, 255, 255, 255); strokeWidth = 1f }
        var d = -800f
        while (d < 800f) {
            canvas.drawLine(d, 0f, d + 800f, 800f, weave)
            d += 6f
        }
        canvas.restore()
        // The two scales lie in pockets of brushed metal, below the panels
        canvas.metalPocket(topPocket())
        canvas.metalPocket(bottomPocket())
        // A ring of metal round the edge, with its ticks
        metalRim(canvas)
        // Screws at the inner corners of the pockets
        for ((x, y) in POCKET_SCREWS) screw(canvas, x, y, 2.6f, 35f)
        // Between the panels and the pockets: a raised frame of brushed steel
        val frame = metalFrame(canvas)

        // The panels
        canvas.panel(sidePanel(TOP_PANEL_TOP, TOP_PANEL_BOTTOM, TOP_PANEL_TOP_LEFT, TOP_PANEL_BOTTOM_LEFT))
        canvas.panel(sidePanel(LOW_PANEL_TOP, LOW_PANEL_BOTTOM, LOW_PANEL_TOP_LEFT, LOW_PANEL_BOTTOM_LEFT))
        canvas.rowLine(ROW_A_LINE, edgeX(ROW_A_LINE, TOP_PANEL_TOP, TOP_PANEL_BOTTOM, TOP_PANEL_TOP_LEFT, TOP_PANEL_BOTTOM_LEFT))
        // The column right of the glucose, and the line between heart rate and weather in it
        canvas.drawLine(COLUMN_X * s, (ROW_A_LINE + 6f) * s, COLUMN_X * s, (TOP_PANEL_BOTTOM - 6f) * s, paint(ROW_LINE, width = 1f))
        canvas.rowLine(COLUMN_LINE, COLUMN_X - 2f)
        canvas.rowLine(ROW_D_LINE, edgeX(ROW_D_LINE, LOW_PANEL_TOP, LOW_PANEL_BOTTOM, LOW_PANEL_TOP_LEFT, LOW_PANEL_BOTTOM_LEFT))
        graphWindow(canvas)
        canvas.panel(roundRect(LINE_PANEL, 12f))
        canvas.panel(roundRect(TIME_PANEL, 16f))
        canvas.panel(roundRect(MONTH_PANEL, 10f))
        // The edges of the frame round the panels and pockets, and its pins
        frameBevel(canvas, frame)
        for ((x, y) in FRAME_PINS) pin(canvas, x, y, FRAME_PIN_R)
        for ((x, y) in FRAME_SCREWS) screw(canvas, x, y, FRAME_SCREW_R, 30f)
        // A thin light along the top of the time panel, and the line between date and time
        canvas.drawLine((TIME_PANEL.left + 16f) * s, (TIME_PANEL.top + 2.5f) * s, (TIME_PANEL.right - 16f) * s, (TIME_PANEL.top + 2.5f) * s, paint("#3A3E44", width = 1f))
        canvas.drawLine(DATE_LINE_X * s, (TIME_PANEL.top + 10f) * s, DATE_LINE_X * s, (TIME_PANEL.bottom - 10f) * s, paint(ROW_LINE, width = 1.5f))

        // The battery icons: a flash for the watch, a phone
        flash(canvas, WATCH_ICON_X, ROW_A_Y)
        phone(canvas, PHONE_ICON_X, ROW_A_Y)
        // The labels of the line under the glucose
        canvas.label("IOB", IOB_BOX.left - 14f, LINE_PANEL.centerY(), 7.5f, MUTED)
        canvas.label("COB", COB_BOX.left - 14f, LINE_PANEL.centerY(), 7.5f, MUTED)
        // The icons of the bottom rows, a little smaller than drawn, to fit the rows under the graph
        for ((icon, y) in listOf<Pair<(Canvas, Float, Float) -> Unit, Float>>(::sunset to SUN_ROW_Y, ::walker to STEPS_ROW_Y)) {
            canvas.save()
            canvas.scale(ROW_ICON_SCALE, ROW_ICON_SCALE, ROW_ICON_X * s, y * s)
            icon(canvas, ROW_ICON_X, y)
            canvas.restore()
        }
        // Small markers at the right end of the rows, pointing up in the upper row and down in the lower one
        for ((x, y, up) in ROW_MARKERS) marker(canvas, x, y, up)
        heart(canvas, HR_ICON_X, HR_ROW_Y)

        glucoseScale(canvas)
        hoursScale(canvas)
        trendDial(canvas)
        months(canvas)
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    }

    /**
     * The icon of the next sunrise or sunset, as in the original: a red to orange sun half over the horizon,
     * and three lines of water under it, its middle at [x], [y].
     */
    private fun sunset(canvas: Canvas, x: Float, y: Float) {
        val s = SCALE
        val horizon = y + 1f
        val r = 8f
        canvas.save()
        canvas.clipRect(0f, 0f, 800f, (horizon - 0.8f) * s)
        canvas.drawCircle(x * s, horizon * s, r * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, (horizon - r) * s, 0f, horizon * s, color(SUNSET_TOP), color(SUNSET_LOW), Shader.TileMode.CLAMP)
        })
        canvas.restore()
        val water = paint(WHITE, width = 1.6f).apply { strokeCap = Paint.Cap.ROUND }
        for ((dy, half) in listOf(2.5f to 10.5f, 5.5f to 8f, 8.5f to 5f))
            canvas.drawLine((x - half) * s, (horizon + dy) * s, (x + half) * s, (horizon + dy) * s, water)
    }

    /**
     * The window of the graph, as on an instrument: a metal bezel, dark glass with a faint green tint, a shadow inside
     * along the top, a soft light across the glass, and the name printed faintly in its corner.
     */
    private fun graphWindow(canvas: Canvas) {
        val s = SCALE
        val outer = GRAPH_WINDOW
        val glass = RectF(outer.left + 2f, outer.top + 2f, outer.right - 2f, outer.bottom - 2f)
        canvas.drawRoundRect(scaled(outer), 5f * s, 5f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, outer.top * s, 0f, outer.bottom * s, color("#1A1C1F"), color("#7B8087"), Shader.TileMode.CLAMP)
        })
        canvas.drawRoundRect(scaled(glass), 3.5f * s, 3.5f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, glass.top * s, 0f, glass.bottom * s, color(GLASS_TOP), color(GLASS_BOTTOM), Shader.TileMode.CLAMP)
        })
        canvas.save()
        canvas.clipRect(scaled(glass))
        // The shadow of the bezel on the glass, along the top and the left
        canvas.drawRoundRect(scaled(glass), 3.5f * s, 3.5f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5f * s; color = Color.argb(200, 0, 0, 0); maskFilter = BlurMaskFilter(3f * s, BlurMaskFilter.Blur.NORMAL)
        })
        // A soft light across the glass, from the top left
        canvas.drawPath(Path().apply {
            moveTo(glass.left * s, glass.top * s)
            lineTo((glass.left + glass.width() * 0.45f) * s, glass.top * s)
            lineTo((glass.left + glass.width() * 0.25f) * s, glass.bottom * s)
            lineTo(glass.left * s, glass.bottom * s)
            close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(10, 255, 255, 255) })
        canvas.restore()
        // Bottom left: the curve runs along the top, and the hour labels along the bottom start further right
        canvas.label("AAPS", glass.left + 12f, glass.bottom - 5f, 5f, "#3A4440")
    }

    /** A small green triangle with its middle at [x], [y], pointing up or down: a marker at the end of a row. */
    private fun marker(canvas: Canvas, x: Float, y: Float, up: Boolean) {
        val s = SCALE
        val d = if (up) -1f else 1f
        canvas.drawPath(Path().apply {
            moveTo(x * s, (y + 3.2f * d) * s)
            lineTo((x - 3.6f) * s, (y - 2.4f * d) * s)
            lineTo((x + 3.6f) * s, (y - 2.4f * d) * s)
            close()
        }, paint(MARKER))
    }

    /** A walking person, grey, its middle at [x], [y]: the icon of the steps. */
    private fun walker(canvas: Canvas, x: Float, y: Float) {
        val s = SCALE
        val p = paint(ICON, width = 2.6f).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }
        fun line(vararg points: Pair<Float, Float>) = canvas.drawPath(Path().apply {
            points.forEachIndexed { i, (px, py) -> if (i == 0) moveTo((x + px) * s, (y + py) * s) else lineTo((x + px) * s, (y + py) * s) }
        }, p)
        canvas.drawCircle((x + 1.5f) * s, (y - 9f) * s, 2.4f * s, paint(ICON))
        line(0.5f to -5f, -1f to 2f)                    // body
        line(-1f to 2f, -4.5f to 6.5f, -6f to 10f)      // back leg
        line(-1f to 2f, 2.5f to 5.5f, 3.5f to 10f)      // front leg
        line(-4.5f to -1f, -1.5f to -4f, 2.5f to -1.5f) // back arm and front arm
        line(2.5f to -1.5f, 4.5f to 1.5f)
    }

    /** A grey heart, its middle at [x], [y]: the icon of the heart rate. */
    private fun heart(canvas: Canvas, x: Float, y: Float) {
        val s = SCALE
        canvas.drawPath(Path().apply {
            moveTo(x * s, (y + 6.5f) * s)
            cubicTo((x - 9f) * s, (y - 0.5f) * s, (x - 5.5f) * s, (y - 8f) * s, x * s, (y - 3.5f) * s)
            cubicTo((x + 5.5f) * s, (y - 8f) * s, (x + 9f) * s, (y - 0.5f) * s, x * s, (y + 6.5f) * s)
            close()
        }, paint(ICON))
    }

    private fun flash(canvas: Canvas, x: Float, y: Float) {
        val s = SCALE
        canvas.drawPath(Path().apply {
            moveTo((x + 1.5f) * s, (y - 8f) * s)
            lineTo((x - 4f) * s, (y + 1f) * s)
            lineTo((x - 0.5f) * s, (y + 1f) * s)
            lineTo((x - 1.5f) * s, (y + 8f) * s)
            lineTo((x + 4f) * s, (y - 1f) * s)
            lineTo((x + 0.5f) * s, (y - 1f) * s)
            close()
        }, paint(ICON))
    }

    private fun phone(canvas: Canvas, x: Float, y: Float) {
        val s = SCALE
        canvas.drawRoundRect(RectF((x - 4f) * s, (y - 7.5f) * s, (x + 4f) * s, (y + 7.5f) * s), 1.5f * s, 1.5f * s, paint(ICON, Paint.Style.STROKE, 1.4f))
        canvas.drawLine((x - 1.5f) * s, (y + 5f) * s, (x + 1.5f) * s, (y + 5f) * s, paint(ICON, width = 1.2f))
    }

    /**
     * The glucose scale along the edge at the top left: ticks every 1 mmol/L, the numbers 2, 6, 10, 14 and 18,
     * turned along the edge, a band of short lines in red, green and amber, and the label.
     */
    private fun glucoseScale(canvas: Canvas) {
        var mmol = 2
        while (mmol <= 18) {
            val angle = bgAngle(mmol * MGDL_PER_MMOL).toFloat()
            val major = (mmol - 2) % 4 == 0
            canvas.line(polar(200f, 200f, SCALE_OUTER, angle), polar(200f, 200f, if (major) SCALE_OUTER - 12f else SCALE_OUTER - 6f, angle),
                        paint(if (major) WHITE else "#8A9099", width = if (major) 2f else 1.2f))
            if (major) {
                val (x, y) = polar(200f, 200f, SCALE_LABEL_R, angle)
                canvas.label(mmol.toString(), x, y, SCALE_LABEL_SIZE, WHITE, angle, shadow = true, face = textFont())
            }
            mmol++
        }
        var v = 2.0
        while (v <= 18.0) {
            val angle = bgAngle(v * MGDL_PER_MMOL).toFloat()
            val hex = when {
                v < 3.9  -> LOW
                v <= 10.0 -> MID
                else      -> HIGH
            }
            canvas.line(polar(200f, 200f, BAND_OUTER, angle), polar(200f, 200f, BAND_INNER, angle), paint(hex, width = 1.4f))
            v += 0.5
        }
        val mid = ((BG_FROM + BG_TO) / 2).toFloat()
        val (x, y) = polar(200f, 200f, SCALE_NAME_R, mid)
        canvas.label("BG", x, y, 11f, "#C8CDD3", mid, shadow = true)
    }

    /**
     * The time-left scale along the edge at the bottom left, on the log scale of [hoursAngle]: ticks every 3 hours up to
     * 24 and every 12 hours after that, up to 96 (top); the numbers 0, 12, 24, 48 and 96; a band that is red for the
     * last 12 hours and amber up to 24; and a legend of the two hands.
     */
    private fun hoursScale(canvas: Canvas) {
        val ticks = (0..24 step 3) + (36..HOURS_MAX.toInt() step 12)
        for (hours in ticks) {
            val angle = hoursAngle(hours.toDouble())
            val major = hours in HOURS_NUMBERS
            canvas.line(polar(200f, 200f, SCALE_OUTER, angle), polar(200f, 200f, if (major) SCALE_OUTER - 12f else SCALE_OUTER - 6f, angle),
                        paint(if (major) WHITE else "#8A9099", width = if (major) 2f else 1.2f))
            if (major) {
                val (x, y) = polar(200f, 200f, SCALE_LABEL_R, angle)
                canvas.label(hours.toString(), x, y, SCALE_LABEL_SIZE, WHITE, angle + 180f, shadow = true, face = textFont())
            }
        }
        // The band: lines at even angles, each in the colour of its hours
        for (i in 0..BAND_LINES) {
            val part = i.toDouble() / BAND_LINES
            val h = hoursAtPart(part)
            val hex = when {
                h < 12.0 -> LOW
                h < 24.0 -> HIGH
                else     -> "#9AA0A8"
            }
            val angle = hoursAngle(h)
            canvas.line(polar(200f, 200f, BAND_OUTER, angle), polar(200f, 200f, BAND_INNER, angle), paint(hex, width = 1.4f))
        }
        // The legend in the middle of the scale, one word over the other: side by side they do not fit in the pocket
        val middle = ((HOURS_EMPTY_ANGLE + HOURS_FULL_ANGLE) / 2).toFloat()
        legend(canvas, "SENSOR", SENSOR_COLOUR, middle, SCALE_NAME_R - 1f)
        legend(canvas, "PUMP", PUMP_COLOUR, middle, SCALE_NAME_R - 11f)
    }

    /** A dot in [hex] and the word [text], along the time-left scale at [angle] and [radius], inside its band. */
    private fun legend(canvas: Canvas, text: String, hex: String, angle: Float, radius: Float) {
        val s = SCALE
        val (x, y) = polar(200f, 200f, radius, angle)
        val width = Paint().apply { textSize = LEGEND_SIZE * s; typeface = printFont() }.measureText(text) / s
        canvas.save()
        canvas.rotate(angle + 180f, x * s, y * s)
        canvas.drawCircle((x - width / 2 - 4f) * s, y * s, 2.2f * s, paint(hex))
        canvas.label(text, x + 1.5f, y, LEGEND_SIZE, "#C8CDD3", shadow = true)
        canvas.restore()
    }

    /**
     * The half dial of the trend arrow, sunk into the panel: a dark half circle with a metal rim, open to the right,
     * the straight side on the left, [TREND_DIAL_INSET] left of the pivot. Ticks every 15 degrees from straight up to straight down, and a band that is
     * green while the glucose stays about the same, amber when it moves and red when it moves fast.
     */
    private fun trendDial(canvas: Canvas) {
        val s = SCALE
        val (x, y) = TREND_DIAL_X to TREND_DIAL_Y
        val r = TREND_DIAL_R
        val half = trendDialShape()
        canvas.drawPath(half, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x * s, (y - r * 0.3f) * s, r * 1.2f * s, color("#25282C"), color("#0B0C0E"), Shader.TileMode.CLAMP)
        })
        canvas.drawPath(half, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.8f * s; strokeJoin = Paint.Join.ROUND
            shader = LinearGradient(0f, (y - r) * s, 0f, (y + r) * s, color("#D9DDE2"), color("#4A4E54"), Shader.TileMode.CLAMP)
        })
        // The band: green near flat, amber when it moves, red when it moves fast
        var delta = -TREND_MAX_DELTA
        while (delta <= TREND_MAX_DELTA + 0.01) {
            val hex = when {
                abs(delta) < 5.0  -> MID
                abs(delta) < 10.0 -> HIGH
                else              -> LOW
            }
            val angle = trendAngle(delta).toFloat()
            canvas.line(polar(x, y, r - 9f, angle), polar(x, y, r - 15f, angle), paint(hex, width = 1.8f))
            delta += 0.75
        }
        for (k in 0..12) {
            val angle = k * 15f
            val major = k % 3 == 0
            canvas.line(polar(x, y, r - 2f, angle), polar(x, y, r - if (major) 9f else 6f, angle), paint(if (major) WHITE else "#8A9099", width = if (major) 2.2f else 1.3f))
        }
    }

    /** The half circle of the trend dial: open to the right, its straight side [TREND_DIAL_INSET] left of the pivot. */
    private fun trendDialShape(): Path {
        val s = SCALE
        val (x, y) = TREND_DIAL_X to TREND_DIAL_Y
        val r = TREND_DIAL_R
        return Path().apply {
            moveTo((x - TREND_DIAL_INSET) * s, (y - r) * s)
            lineTo(x * s, (y - r) * s)
            arcTo(RectF((x - r) * s, (y - r) * s, (x + r) * s, (y + r) * s), -90f, 180f, false)
            lineTo((x - TREND_DIAL_INSET) * s, (y + r) * s)
            close()
        }
    }

    /** The row of the months: the label, the numbers and a small tick over each. The mark is [monthMark]. */
    private fun months(canvas: Canvas) {
        canvas.label("MTH", MONTH_LABEL_X, MONTH_TEXT_Y, 9f, MUTED)
        for (month in 1..12) {
            val x = monthX(month)
            canvas.drawLine((x - 5f) * SCALE, (MONTH_MARK_TOP + 2.5f) * SCALE, (x + 5f) * SCALE, (MONTH_MARK_TOP + 2.5f) * SCALE, paint("#3E4248", width = 2.5f))
            canvas.label(month.toString(), x, MONTH_TEXT_Y, 14f, "#C8CDD3", face = textFont())
        }
    }

    /**
     * The colon of the time, white on transparent, for the view over [COLON_BOX]: the watch paints it in the main
     * colour. It stands where a text view over [COLON_BOX] would show it, measured with the font the way
     * [centredText] places the time.
     */
    private fun colonPicture(): ByteArray {
        val s = SCALE
        val box = COLON_BOX
        val image = createBitmap((box.width() * s).toInt(), (box.height() * s).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        canvas.translate(-box.left * s, -box.top * s)
        val (top, height) = centredText(COLON_BOX, TIME_SIZE, textFont())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = TIME_SIZE * s; textAlign = Paint.Align.CENTER; typeface = textFont() }
        val line = Paint().apply { textSize = TIME_SIZE.toFloat(); typeface = textFont() }.fontMetricsInt
        val baseline = top + (height - (line.bottom - line.top)) / 2f - line.top
        canvas.drawText(":", COLON_BOX.centerX() * s, baseline * s, text)
        return pngBytes(image)
    }

    /**
     * The trend arrow, pointing up, on a picture of the square round its pivot: a steel arrow with a ridge down its middle
     * (one side lit, one in shade), a pointed head, a short tail, a shadow under it and a round cap with a screw slot.
     */
    private fun trendHand(): ByteArray {
        val s = 4f
        val size = 2 * TREND_DIAL_R
        val image = createBitmap((size * s).toInt(), (size * s).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val c = TREND_DIAL_R
        val tip = c - TREND_HAND_LENGTH
        val headBase = tip + 20f
        val tail = c + 12f
        fun path(vararg points: Pair<Float, Float>) = Path().apply {
            points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
            close()
        }
        // Each half: the head, then the shaft down to the tail
        val left = path(c to tip, (c - 12f) to headBase, (c - 4.5f) to (headBase - 2f), (c - 4.5f) to tail, c to tail)
        val right = path(c to tip, (c + 12f) to headBase, (c + 4.5f) to (headBase - 2f), (c + 4.5f) to tail, c to tail)
        canvas.save()
        canvas.translate(0.8f * s, 1.2f * s)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0); maskFilter = BlurMaskFilter(1f * s, BlurMaskFilter.Blur.NORMAL) }
        canvas.drawPath(left, shadow)
        canvas.drawPath(right, shadow)
        canvas.restore()
        canvas.drawPath(left, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, tip * s, 0f, tail * s, color("#FFFFFF"), color("#A4AAB0"), Shader.TileMode.CLAMP) })
        canvas.drawPath(right, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, tip * s, 0f, tail * s, color("#8E949B"), color("#4C5056"), Shader.TileMode.CLAMP) })
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.35f * s; color = Color.argb(150, 30, 30, 30) }
        canvas.drawPath(left, edge)
        canvas.drawPath(right, edge)
        canvas.drawCircle(c * s, c * s, 8f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient((c - 2.5f) * s, (c - 2.5f) * s, 11f * s, color("#F4F6F8"), color("#5A5E64"), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(c * s, c * s, 8f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f * s; color = color("#2A2C30") })
        canvas.drawLine((c - 4.8f) * s, (c + 2.2f) * s, (c + 4.8f) * s, (c - 2.2f) * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f * s; color = color("#34373B") })
        return pngBytes(image)
    }

    /**
     * The pointer, on a picture of [NEEDLE_BOX]: a slim spike that comes in from the rim, as in the original: wide at
     * [NEEDLE_OUTER], its tip at [NEEDLE_INNER], towards the middle of the dial; [dark] at the rim, [light] at the tip.
     */
    private fun needle(light: String, dark: String): ByteArray {
        val s = SCALE
        val size = NEEDLE_BOX.width()
        val image = createBitmap((size * s).toInt(), (size * s).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val c = size / 2f
        canvas.drawPath(Path().apply {
            moveTo((c - NEEDLE_HALF_WIDTH) * s, (c - NEEDLE_OUTER) * s)
            lineTo(c * s, (c - NEEDLE_INNER) * s)
            lineTo((c + NEEDLE_HALF_WIDTH) * s, (c - NEEDLE_OUTER) * s)
            close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, (c - NEEDLE_OUTER) * s, 0f, (c - NEEDLE_INNER) * s, color(dark), color(light), Shader.TileMode.CLAMP)
        })
        // A lit edge on one side, so the spike looks like a folded piece of metal
        canvas.drawPath(Path().apply {
            moveTo((c - NEEDLE_HALF_WIDTH) * s, (c - NEEDLE_OUTER) * s)
            lineTo(c * s, (c - NEEDLE_INNER) * s)
            lineTo(c * s, (c - NEEDLE_OUTER) * s)
            close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) })
        return pngBytes(image)
    }

    /** The weather icons, for a view of [WEATHER_ICON_BOX] at 2 x. */
    private fun weatherIcons(): Map<String, ByteArray> = mapOf(
        WX_SUN to weatherIcon(sun = true),
        WX_MOON to weatherIcon(moon = true),
        WX_SUN_CLOUD to weatherIcon(sun = true, cloud = true),
        WX_MOON_CLOUD to weatherIcon(moon = true, cloud = true),
        WX_CLOUD to weatherIcon(cloud = true, bigCloud = true),
        WX_RAIN to weatherIcon(cloud = true, bigCloud = true, rain = true)
    )

    /** One weather icon: a sun with rays or a moon, top left; a cloud in front, low right, or big in the middle; rain under it. */
    private fun weatherIcon(sun: Boolean = false, moon: Boolean = false, cloud: Boolean = false, bigCloud: Boolean = false, rain: Boolean = false): ByteArray {
        val size = (WEATHER_ICON_BOX.width() * SCALE).toInt()
        val image = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val u = size / 26f
        val alone = !cloud
        val cx = if (alone) 13f else 10f
        val cy = if (alone) 13f else 10f
        if (sun) {
            val r = if (alone) 6f else 5f
            val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(SUN); strokeWidth = 1.6f * u; strokeCap = Paint.Cap.ROUND }
            for (i in 0 until 8) {
                val (x1, y1) = polar(cx, cy, r + 2f, i * 45f)
                val (x2, y2) = polar(cx, cy, r + 4.5f, i * 45f)
                canvas.drawLine(x1 * u, y1 * u, x2 * u, y2 * u, ray)
            }
            canvas.drawCircle(cx * u, cy * u, r * u, paint(SUN))
        }
        if (moon) {
            val r = if (alone) 8f else 6.5f
            // A crescent: the disc, with a disc a little to the upper right cut out of it
            canvas.saveLayer(null, null)
            canvas.drawCircle(cx * u, cy * u, r * u, paint(MOON_LIGHT))
            canvas.drawCircle((cx + r * 0.55f) * u, (cy - r * 0.35f) * u, r * 0.85f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            })
            canvas.restore()
        }
        if (cloud) {
            val (left, top, k) = if (bigCloud) Triple(2f, if (rain) 3f else 6f, 1.05f) else Triple(7f, 10f, 0.8f)
            for ((hex, dy) in listOf(CLOUD_SHADE to 1.2f, CLOUD to 0f)) {
                val p = paint(hex)
                for ((x, y, r) in listOf(Triple(6f, 10f, 5f), Triple(12.5f, 6.5f, 6.5f), Triple(18.5f, 9.5f, 4.5f)))
                    canvas.drawCircle((left + x * k) * u, (top + (y + dy) * k) * u, r * k * u, p)
                canvas.drawRoundRect(RectF((left + 1f * k) * u, (top + (10f + dy) * k) * u, (left + 23f * k) * u, (top + (15f + dy) * k) * u), 2.5f * u, 2.5f * u, p)
            }
            if (rain) {
                val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(RAIN); strokeWidth = 1.8f * u; strokeCap = Paint.Cap.ROUND }
                for (i in 0 until 4) {
                    val x = 7f + i * 4.5f
                    val y = 21f + (i % 2) * 1.5f
                    canvas.drawLine(x * u, y * u, (x - 1f) * u, (y + 3f) * u, drop)
                }
            }
        }
        return pngBytes(image)
    }

    /**
     * The runner on the rim: a picture of the whole face, see-through but for one lit marker over the minute track
     * at the top, pointing in, with a soft glow. White, so the watch paints it in the main colour. The watch turns
     * it a tick (6 degrees) each second, so it runs round the rim once a minute.
     */
    private fun runnerPicture(): ByteArray {
        val s = SCALE
        val image = createBitmap((400 * s).toInt(), (400 * s).toInt())
        val canvas = Canvas(image)
        val c = 200f * s
        val marker = Path().apply {
            moveTo(c, (200f - RUNNER_INNER) * s)
            lineTo(c + RUNNER_HALF_WIDTH * s, (200f - RUNNER_OUTER) * s)
            lineTo(c - RUNNER_HALF_WIDTH * s, (200f - RUNNER_OUTER) * s)
            close()
        }
        canvas.drawPath(marker, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); maskFilter = BlurMaskFilter(3f * s, BlurMaskFilter.Blur.NORMAL) })
        canvas.drawPath(marker, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return pngBytes(image)
    }

    private fun monthMark(): ByteArray {
        val r = monthMarkBox(1)
        val image = createBitmap((r.width() * SCALE).toInt(), (r.height() * SCALE).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        Canvas(image).drawRoundRect(RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()), 2f * SCALE, 2f * SCALE, paint(NEEDLE_RED))
        return pngBytes(image)
    }

    /** The frame round AM / PM: the background of its view, so it goes away with 24 hours. */
    private fun periodFrame(): ByteArray {
        val (viewTop, h) = centredText(PERIOD_BOX, PERIOD_SIZE, textFont())
        val w = PERIOD_BOX.width().toInt()
        val image = createBitmap((w * SCALE).toInt(), (h * SCALE).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        val top = (PERIOD_BOX.top - viewTop) * SCALE
        val frame = RectF(1.5f * SCALE, top + 1f * SCALE, (w - 1.5f) * SCALE, top + (PERIOD_BOX.height() - 1f) * SCALE)
        Canvas(image).drawRoundRect(frame, 3f * SCALE, 3f * SCALE, paint(MUTED, Paint.Style.STROKE, 1.4f))
        return pngBytes(image)
    }

    // ------------------------------------------------------------------------------------------
    // Rendering helpers of the tests
    // ------------------------------------------------------------------------------------------

    private fun render(
        sample: Sample = Sample(deltaMgdl = DELTA_SAMPLE), bgAgeMin: Long = 2, loopAgeMin: Long = 3, watch: Int? = WATCH_SAMPLE, phone: String = PHONE_SAMPLE,
        colours: Pair<String, String> = Pair(ColourLooks.AS_DESIGNED, ColourLooks.AS_DESIGNED), hours24: Boolean = true, instant: Long = PREVIEW_MS,
        weather: String? = EventData.Weather.PARTLY, temperature: Double? = TEMPERATURE_SAMPLE, place: Boolean = true,
        sensorHours: Double? = SENSOR_HOURS_SAMPLE, pumpHours: Double? = PUMP_HOURS_SAMPLE, size: Int = 400,
        graph: ((now: Long) -> List<EventData.SingleBg>)? = null, logGraph: Boolean = true
    ): Bitmap {
        val (face, design) = preparedFace(sample, bgAgeMin, loopAgeMin, watch, phone, colours, hours24, instant, weather, temperature, place, sensorHours, pumpHours, graph, logGraph)
        val picture = face.renderToBitmap(size, size)
        // The values of the complication slots, drawn the way the phone shows them; they are in 400 x 400 places
        if (size == 400) drawComplicationValues(Canvas(picture), design)
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "24")
        return picture
    }

    /** The face of [render], with its design, ready to draw; the clock stays on 12 hours when [hours24] is false. */
    private fun preparedFace(
        sample: Sample = Sample(deltaMgdl = DELTA_SAMPLE), bgAgeMin: Long = 2, loopAgeMin: Long = 3, watch: Int? = WATCH_SAMPLE, phone: String = PHONE_SAMPLE,
        colours: Pair<String, String> = Pair(ColourLooks.AS_DESIGNED, ColourLooks.AS_DESIGNED), hours24: Boolean = true, instant: Long = PREVIEW_MS,
        weather: String? = EventData.Weather.PARTLY, temperature: Double? = TEMPERATURE_SAMPLE, place: Boolean = true,
        sensorHours: Double? = SENSOR_HOURS_SAMPLE, pumpHours: Double? = PUMP_HOURS_SAMPLE,
        graph: ((now: Long) -> List<EventData.SingleBg>)? = null, logGraph: Boolean = true
    ): Pair<CustomWatchface, JSONObject> {
        val default = defaultDesign()
        val design = design(default)
        val face = storedFace(design, default, images(), instant, weather, watch)
        face.sp.putString(R.string.key_weather_temperature, temperature?.toString() ?: "")
        // Amsterdam, for the sunrise and sunset; NaN is no place
        face.sp.putDouble(R.string.key_location_latitude, if (place) 52.37 else Double.NaN)
        face.sp.putDouble(R.string.key_location_longitude, if (place) 4.90 else Double.NaN)
        face.sp.putString(R.string.key_cwf_colour_1, colours.first)
        face.sp.putString(R.string.key_cwf_colour_2, colours.second)
        face.sp.putBoolean(R.string.key_graph_log_time, logGraph)
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, if (hours24) "24" else "12")
        // The sensor and the pod stop [sensorHours] and [pumpHours] after [instant]
        storeSampleData(face, sample, bgAgeMin, loopAgeMin, phone, adjustStatus = {
            it.copy(sensorEndsAt = sensorHours?.let { h -> instant + (h * HOUR_MS).toLong() }, pumpEndsAt = pumpHours?.let { h -> instant + (h * HOUR_MS).toLong() })
        }, graph = graph)
        Thread.sleep(600)   // past the render data share window
        face.refreshRenderData()
        return face to design
    }

    /**
     * The frames of the wake-up animation at the times [atMs] (in order), drawn by the real views of the sample face and a
     * [WakeAnimator] with a test clock; null for a time where the face is drawn the normal way again.
     */
    private fun wakeFrames(atMs: List<Long>): Map<Long, Bitmap?> {
        val (face, design) = preparedFace()
        // The views exist only after a first draw
        face.renderToBitmap(400, 400).recycle()
        var clock = 0L
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { clock })
        animator.setDesign(readAnimation(design))
        return atMs.associateWith { ms ->
            clock = ms
            val frame = createBitmap(400, 400).apply { eraseColor(Color.BLACK) }
            if (animator.drawFrame(Canvas(frame), 400, 400, PREVIEW_MS, 0, emptyList(), face.wakeParts, allowed = true, ready = true)) {
                drawComplicationValues(Canvas(frame), design)
                frame
            } else null
        }
    }


    private fun near(pixel: Int, hex: String, tolerance: Int = 50): Boolean {
        val c = color(hex)
        return abs(Color.red(pixel) - Color.red(c)) <= tolerance && abs(Color.green(pixel) - Color.green(c)) <= tolerance && abs(Color.blue(pixel) - Color.blue(c)) <= tolerance
    }

    private fun countNear(bitmap: Bitmap, r: RectF, hex: String, tolerance: Int = 50): Int {
        var n = 0
        for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt())
            if (x in 0 until bitmap.width && y in 0 until bitmap.height && near(bitmap.getPixel(x, y), hex, tolerance)) n++
        return n
    }

    /**
     * The red pixels of a pointer near radius [radius] at [angle]: between the band (it has red for low)
     * and the ticks, where the spike is still about 3 wide and nothing else is red.
     */
    /** The pixels near [hex] of a time-left hand at [hours], on its way in from the edge. */
    private fun handAt(bitmap: Bitmap, hours: Double, hex: String): Int {
        val (x, y) = polar(200f, 200f, 166f, hoursAngle(hours))
        return countNear(bitmap, RectF(x - 3f, y - 3f, x + 3f, y + 3f), hex, 60)
    }

    /** The light steel pixels of the trend arrow on its shaft, if it points at [angle]. */
    private fun trendHandAt(bitmap: Bitmap, angle: Float): Int {
        val (x, y) = polar(TREND_DIAL_X, TREND_DIAL_Y, TREND_HAND_LENGTH - 12f, angle)   // on the shaft, inside the band of the dial
        var n = 0
        for (py in (y - 2.5f).toInt()..(y + 2.5f).toInt()) for (px in (x - 2.5f).toInt()..(x + 2.5f).toInt())
            bitmap.getPixel(px, py).let { if (Color.red(it) > 110 && abs(Color.red(it) - Color.blue(it)) < 30 && abs(Color.red(it) - Color.green(it)) < 30) n++ }
        return n
    }

    private fun needleRedAt(bitmap: Bitmap, angle: Double, radius: Float = 166f): Int {
        val (x, y) = polar(200f, 200f, radius, angle.toFloat())
        return countNear(bitmap, RectF(x - 3f, y - 3f, x + 3f, y + 3f), NEEDLE_RED, 70)
    }

    private fun textWidth(text: String, size: Int): Float = Paint().apply { textSize = size.toFloat(); typeface = textFont() }.measureText(text)

    // ------------------------------------------------------------------------------------------
    // The tests
    // ------------------------------------------------------------------------------------------

    @Test
    fun buildsTheZipAndItsPreview() {
        val default = defaultDesign()
        val design = design(default)
        val json = design.toString(4)
        val images = images()
        val previewImage = render()
        val jpg = ByteArrayOutputStream().also { previewImage.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        val zip = zip(json, jpg, images)
        File(out, "$fileName.zip").writeBytes(zip)
        File(out, "$fileName.png").writeBytes(pngBytes(previewImage))
        println("$fileName.zip: ${zip.size} bytes")
        assertThat(zip.size).isAtMost(MAX_ZIP_BYTES)

        val loaded = ZipWatchfaceFormat.loadCustomWatchface(zip, "$fileName.zip", authorization = true)
        assertThat(loaded).isNotNull()
        val metadata = loaded!!.cwfData.metadata
        assertThat(metadata[CwfMetadataKey.CWF_NAME]).isEqualTo(displayName)
        assertThat(metadata[CwfMetadataKey.CWF_FILENAME]).isEqualTo(fileName)
        for (key in listOf(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1, CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2))
            assertWithMessage("$key").that(metadata[key]).isEqualTo("true")
        // The font of the texts is in the zip, and every text uses it
        assertThat(loaded.cwfData.resData[TEXT_FONT]?.format).isEqualTo(ResFormat.TTF)
        assertThat(loaded.cwfData.resData.keys).containsAtLeastElementsIn(images.keys)
        // Both colours can be chosen on the watch
        assertThat(ColourLooks.rows(design)).hasSize(2)

        // The copy kept in the repository is this design
        val kept = File("cwf-designs/$fileName.zip")
        assertWithMessage("$kept").that(kept.exists()).isTrue()
        assertThat(JSONObject(jsonInZip(kept.readBytes())).toString(4)).isEqualTo(json)
    }

    @Test
    fun everythingStaysOnTheDial() {
        val design = design(defaultDesign())
        for (key in listOf("sgv", "delta", "timestamp", "time", "iob2", "basalRate", "day_name", "day", "hour", "minute",
                           "timePeriod", "cob2", HR_VIEW, STEPS_VIEW, SUN_TIME_VIEW, MOON_VIEW, TEMPERATURE_VIEW, WATCH_NUMBER_VIEW, PHONE_NUMBER_VIEW, "cover_chart"))
            insideRound(key, rectOf(design.getJSONObject(key)))
        // The month mark stays on the dial over December too
        insideRound("month mark", monthMarkBox(12))
        // The pointers turn round the middle of the dial
        val r = rectOf(design.getJSONObject(BG_NEEDLE_VIEW))
        assertThat(r.centerX()).isEqualTo(200f)
        assertThat(r.centerY()).isEqualTo(200f)
        // The time-left hands turn round the middle of the dial; the trend dial lies inside the glucose pointer's reach
        for (key in listOf(SENSOR_NEEDLE_VIEW, PUMP_NEEDLE_VIEW)) {
            val box = rectOf(design.getJSONObject(key))
            assertWithMessage(key).that(box.centerX()).isEqualTo(200f)
            assertWithMessage(key).that(box.centerY()).isEqualTo(200f)
        }
        // The trend arrow turns round the middle of the straight side of its dial
        val hand = rectOf(design.getJSONObject(TREND_HAND_VIEW))
        assertThat(hand.centerX()).isEqualTo(TREND_DIAL_X)
        assertThat(hand.centerY()).isEqualTo(TREND_DIAL_Y)
        // The glucose and the heart rate start right of the slanted edge of the top right panel
        for (box in listOf(HR_BOX, SGV_BOX)) assertThat(box.left).isAtLeast(edgeX(box.top, TOP_PANEL_TOP, TOP_PANEL_BOTTOM, TOP_PANEL_TOP_LEFT, TOP_PANEL_BOTTOM_LEFT) + 2f)
        assertThat(HR_BOX.top).isGreaterThan(ROW_A_LINE)
        assertThat(SGV_BOX.top).isAtLeast(ROW_A_LINE)
        assertThat(SGV_BOX.right).isAtMost(COLUMN_X)
        for (box in listOf(HR_BOX, WEATHER_ICON_BOX, TEMPERATURE_BOX)) assertThat(box.left).isAtLeast(COLUMN_X)
        // The delta stays in the trend dial, over and under the pivot
        val dialR = TREND_DIAL_R - 16f
        for (box in listOf(DELTA_BOX, RectF(DELTA_BOX).apply { offset(0f, -DELTA_UP.toFloat()) }))
            for ((x, y) in listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)) {
                assertWithMessage("delta $x, $y").that(hypot(x - TREND_DIAL_X, y - TREND_DIAL_Y)).isAtMost(dialR)
                assertWithMessage("delta $x, $y").that(x).isAtLeast(TREND_DIAL_X)
            }
        assertThat(SGV_BOX.bottom).isAtMost(TOP_PANEL_BOTTOM)
    }

    @Test
    fun theTextsFitTheirViews() {
        val tooWide = listOf(
            Triple("23", HOUR_BOX, TIME_SIZE), Triple("59", MINUTE_BOX, TIME_SIZE),
            Triple("WED", DAY_NAME_BOX, DAY_NAME_SIZE), Triple("28", DAY_BOX, DAY_SIZE), Triple("18,5", SGV_BOX, SGV_SIZE),
            Triple("100%", WATCH_NUMBER_BOX, SMALL_SIZE), Triple("100%", PHONE_NUMBER_BOX, SMALL_SIZE), Triple("+10,5", DELTA_BOX, DELTA_SIZE), Triple("120", RectF(HR_BOX.left + HR_BOX.height(), HR_BOX.top, HR_BOX.right, HR_BOX.bottom), HR_SIZE),
            Triple("12.25U", IOB_BOX, LINE_SIZE), Triple("120g", COB_BOX, LINE_SIZE), Triple("10.85U/h", BASAL_BOX, LINE_SIZE),
            Triple("PM", PERIOD_BOX, PERIOD_SIZE), Triple("10:48 PM", SUN_TIME_BOX, SUN_TIME_SIZE),
            Triple("12345", RectF(STEPS_BOX.left + STEPS_BOX.height() + 2f, 0f, STEPS_BOX.right, 0f), STEPS_SIZE),
            Triple("-12°", TEMPERATURE_BOX, TEMPERATURE_SIZE), Triple("188", SGV_BOX, SGV_SIZE)
        ).filter { (text, box, size) -> textWidth(text, size) > box.width() }.map { (text, box, size) -> "$text: ${textWidth(text, size)} > ${box.width()}" }
        assertWithMessage("texts wider than their view").that(tooWide).isEmpty()
    }

    @Test
    fun theGlucosePointerFollowsTheValue() {
        for (mgdl in listOf(54.0, 120.0, 250.0)) {
            val picture = render(Sample(text = "x", mgdl = mgdl))
            assertWithMessage("$mgdl").that(needleRedAt(picture, bgAngle(mgdl))).isGreaterThan(3)
            val other = bgAngle(if (mgdl < 150) 300.0 else 60.0)
            assertWithMessage("$mgdl other").that(needleRedAt(picture, other)).isEqualTo(0)
        }
    }

    @Test
    fun theTimeLeftHandsShowTheHours() {
        val picture = render(sensorHours = 60.0, pumpHours = 10.0)
        assertThat(handAt(picture, 60.0, SENSOR_COLOUR)).isGreaterThan(3)
        assertThat(handAt(picture, 10.0, SENSOR_COLOUR)).isEqualTo(0)
        assertThat(handAt(picture, 10.0, PUMP_COLOUR)).isGreaterThan(3)
        assertThat(handAt(picture, 60.0, PUMP_COLOUR)).isEqualTo(0)
        // A mild log scale: a day is the middle of the scale, 12 hours a third
        assertThat(hoursAngle(24.0)).isWithin(0.01f).of(((HOURS_EMPTY_ANGLE + HOURS_FULL_ANGLE) / 2).toFloat())
        assertThat((hoursAngle(12.0) - HOURS_EMPTY_ANGLE) / (HOURS_FULL_ANGLE - HOURS_EMPTY_ANGLE)).isWithin(0.02).of(0.32)
        val day = render(sensorHours = 24.0, pumpHours = 90.0)
        assertThat(handAt(day, 24.0, SENSOR_COLOUR)).isGreaterThan(3)
        assertThat(handAt(day, 90.0, PUMP_COLOUR)).isGreaterThan(3)
        // More than 96 hours shows at 96; past the end at 0
        val far = render(sensorHours = 200.0, pumpHours = -5.0)
        assertThat(handAt(far, 96.0, SENSOR_COLOUR)).isGreaterThan(3)
        assertThat(handAt(far, 0.0, PUMP_COLOUR)).isGreaterThan(3)
        // Not known: no hands; the reservoir text does not show
        val unknown = render(sensorHours = null, pumpHours = null)
        for (h in listOf(0.0, 36.0, 72.0)) {
            assertWithMessage("sensor $h").that(handAt(unknown, h, SENSOR_COLOUR)).isEqualTo(0)
            assertWithMessage("pump $h").that(handAt(unknown, h, PUMP_COLOUR)).isEqualTo(0)
        }
    }

    // ------------------------------------------------------------------------------------------
    // The graph on the watch: 466 pixels at the density of a watch, where the chart's labels and margins are twice as big
    // ------------------------------------------------------------------------------------------

    /** The graph of the watch: a log time scale, and a plot that fills most of the glass. */
    @Test
    @Config(qualifiers = "xhdpi")
    fun theGraphFillsItsGlassOnTheWatch() {
        val picture = render(size = WATCH_SIZE)
        File(out, "${fileName}_watch.png").writeBytes(pngBytes(picture))
        val glass = onWatch(GRAPH_WINDOW)
        val dots = dotsBox(picture, glass)
        assertWithMessage("glucose dots in the glass").that(dots).isNotNull()
        // The sample goes from 90 to 160 mg/dL between the lines at 70 and 180: 64 % of the plot. On the old graph the
        // margins left the plot a strip of a few pixels.
        assertWithMessage("height of the dots").that(dots!!.height() / glass.height()).isAtLeast(0.45f)
        // Three hours of glucose start halfway on the log scale, and end at the right
        assertWithMessage("oldest dot").that((dots.left - glass.left) / glass.width()).isIn(Range.closed(0.35f, 0.62f))
        assertWithMessage("newest dot").that((dots.right - glass.left) / glass.width()).isAtLeast(0.85f)
        // Small dots: where the line of dots is flattest, it is at most 6 pixels thick (radius 1 dp; with 2 dp it was 9)
        fun isDot(c: Int) = near(c, GRAPH_MID, 30) && Color.green(c) - Color.red(c) > 15
        val thickness = (dots.left.toInt()..dots.right.toInt()).map { x -> (glass.top.toInt() until glass.bottom.toInt()).count { y -> isDot(picture.getPixel(x, y)) } }.filter { it > 0 }
        assertWithMessage("thinnest part of the line of dots").that(thickness.min()).isAtMost(6)
    }

    /** A day of glucose spans the log graph; the plain graph shows its hours over the whole width. */
    @Test
    @Config(qualifiers = "xhdpi")
    fun theLogGraphShowsADayAndThePlainGraphItsHours() {
        val glass = onWatch(GRAPH_WINDOW)
        val day = dotsBox(render(size = WATCH_SIZE, graph = ::dayOfGlucose), glass)!!
        assertWithMessage("a day, oldest").that((day.left - glass.left) / glass.width()).isLessThan(0.12f)
        assertWithMessage("a day, newest").that((day.right - glass.left) / glass.width()).isAtLeast(0.85f)
        val plain = dotsBox(render(size = WATCH_SIZE, logGraph = false), glass)!!
        assertWithMessage("plain, oldest").that((plain.left - glass.left) / glass.width()).isLessThan(0.15f)
    }

    /** The log graph has a line at 1, 3, 6 and 12 hours ago, where the scale puts them. */
    @Test
    @Config(qualifiers = "xhdpi")
    fun theTimeAxisMarksTheHoursOnTheWatch() {
        val picture = render(size = WATCH_SIZE)
        val glass = onWatch(GRAPH_WINDOW)
        // The high line runs over the whole time of the graph: a day back to 5 minutes ahead
        val highY = (glass.top.toInt() until glass.bottom.toInt()).maxBy { y -> (glass.left.toInt() until glass.right.toInt()).count { x -> near(picture.getPixel(x, y), GRAPH_HIGH, 40) } }
        val highXs = (glass.left.toInt() until glass.right.toInt()).filter { x -> near(picture.getPixel(x, highY), GRAPH_HIGH, 40) }
        val left = highXs.first().toFloat()
        val right = highXs.last().toFloat()
        val from = LogTimeScale.xOfOffset(-LogTimeScale.PAST_MS)
        val to = LogTimeScale.xOfOffset(LogTimeScale.MIN_FUTURE_MS)
        // The line itself is in the grid colour; the glass beside it is darker
        fun lineAt(px: Int) = (glass.top.toInt() until glass.bottom.toInt()).count { y -> (px - 1..px + 1).any { x -> brightness(picture.getPixel(x, y)) > brightness(picture.getPixel(x + 5, y)) + 6 } }
        // The labels, in a lighter colour than the lines so they can be read, in the lower half round each line
        val labelHex = String.format("#%06X", 0xFFFFFF and LogTimeGraph.labelColour(Color.parseColor(GRAPH_GRID)))
        for (age in LogTimeScale.LABEL_AGES_MS) {
            val px = (left + (LogTimeScale.xOfOffset(-age) - from) / (to - from) * (right - left)).roundToInt()
            assertWithMessage("line at ${age / HOUR_MS} h, x $px").that(lineAt(px)).isGreaterThan((glass.height() * 0.3f).toInt())
            val around = RectF(px - 14f, glass.centerY(), px + 14f, glass.bottom)
            assertWithMessage("label at ${age / HOUR_MS} h").that(countNear(picture, around, labelHex, 30)).isGreaterThan(8)
        }
    }

    /** The bounding box of the in-range glucose dots inside [glass], or null when there are none. Green, not the grey of the frame. */
    private fun dotsBox(picture: Bitmap, glass: RectF): RectF? {
        var box: RectF? = null
        fun isDot(c: Int) = near(c, GRAPH_MID, 30) && Color.green(c) - Color.red(c) > 15 && Color.green(c) - Color.blue(c) > 12
        for (y in glass.top.toInt() until glass.bottom.toInt()) for (x in glass.left.toInt() until glass.right.toInt())
            if (isDot(picture.getPixel(x, y))) box = (box ?: RectF(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat())).apply { union(x.toFloat(), y.toFloat()) }
        return box
    }

    /** [r], from the 400 x 400 design to the watch's [WATCH_SIZE]. */
    private fun onWatch(r: RectF): RectF {
        val k = WATCH_SIZE / 400f
        return RectF(r.left * k, r.top * k, r.right * k, r.bottom * k)
    }

    private fun brightness(color: Int): Int = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3

    /** Glucose of a whole day, every 5 minutes, slow waves between 90 and 160 mg/dL. */
    private fun dayOfGlucose(now: Long): List<EventData.SingleBg> = (0 until 288).map { i ->
        EventData.SingleBg(dataset = 0, timeStamp = now - (287 - i) * 5 * 60_000L, sgv = 125.0 + 35.0 * sin(i * PI / 20.0), high = 180.0, low = 70.0)
    }

    // ------------------------------------------------------------------------------------------
    // The trend arrow: big, right of the time, AM / PM under it with 12 hours
    // ------------------------------------------------------------------------------------------

    @Test
    fun theTrendDialIsRightOfTheTimeWithAmPmBesideIt() {
        val dial = RectF(TREND_DIAL_X - TREND_DIAL_INSET, TREND_DIAL_Y - TREND_DIAL_R, TREND_DIAL_X + TREND_DIAL_R, TREND_DIAL_Y + TREND_DIAL_R)
        // Against the rim on the right, over the ends of the line and month panels, which end before it
        assertWithMessage("against the rim").that(dial.right).isAtLeast(200f + RIM_INNER - 6f)
        assertWithMessage("inside the rim").that(dial.right).isAtMost(200f + RIM_INNER - 1f)
        for (panel in listOf(LINE_PANEL, MONTH_PANEL)) assertThat(panel.right).isLessThan(dial.left - 4f)
        assertThat(dial.top).isLessThan(LINE_PANEL.bottom)
        assertThat(dial.bottom).isGreaterThan(MONTH_PANEL.top)
        // The time panel runs on under the dial, and its round end stays out of sight there
        assertThat(TIME_PANEL.right).isGreaterThan(dial.left + 12f)
        assertThat(dial.top).isLessThan(TIME_PANEL.top)
        assertThat(dial.bottom).isGreaterThan(TIME_PANEL.bottom)
        assertWithMessage("time panel corner under the dial").that(hypot(TIME_PANEL.right - TREND_DIAL_X, TIME_PANEL.top - TREND_DIAL_Y)).isLessThan(TREND_DIAL_R)
        // Right of the widest minutes and AM / PM between them
        assertWithMessage("minutes and AM / PM").that(PERIOD_BOX.left).isAtLeast(MINUTE_BOX.left + (0..59).maxOf { textWidth("%02d".format(it), TIME_SIZE) } + 1f)
        assertWithMessage("AM / PM and dial").that(PERIOD_BOX.right).isAtMost(dial.left)
        assertWithMessage("hours and the date line").that(HOUR_BOX.right - (0..23).maxOf { textWidth("%02d".format(it), TIME_SIZE) }).isAtLeast(DATE_LINE_X + 2f)
        assertThat(TIME_PANEL.contains(PERIOD_BOX)).isTrue()
        // The minutes never reach into the dial
        assertWithMessage("main colour in the dial").that(countNear(render(hours24 = true), dial, COLOUR_1, 60)).isEqualTo(0)
        val twelve = render(hours24 = false)
        File(out, "${fileName}_12h.png").writeBytes(pngBytes(twelve))
    }

    /** The delta stands under the pivot of the trend arrow, and over it while the glucose falls, so the arrow never covers it. */
    @Test
    fun theDeltaMovesOverThePivotWhenTheGlucoseFalls() {
        val up = RectF(DELTA_BOX).apply { offset(0f, -DELTA_UP.toFloat()) }
        val rising = render(Sample(deltaMgdl = 4.0))
        val falling = render(Sample(deltaMgdl = -4.0))
        // Counted in the cream of the text, which the grey steel of the arrow passing a box does not have
        fun cream(picture: Bitmap, box: RectF): Int {
            var n = 0
            for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt())
                picture.getPixel(x, y).let { if (near(it, VALUE, 60) && Color.red(it) - Color.blue(it) > 35) n++ }
            return n
        }
        assertThat(cream(rising, DELTA_BOX)).isGreaterThan(15)
        assertThat(cream(falling, up)).isGreaterThan(15)
        assertThat(cream(rising, up)).isEqualTo(0)
        assertThat(cream(falling, DELTA_BOX)).isEqualTo(0)
    }

    /** The texts lie under a fine dark mesh, so a big digit shows as small lit dots with dark lines between them. */
    @Test
    fun theDigitsLookLikeLeds() {
        // At the size of the watch, as the wearer sees it
        val picture = render(size = WATCH_SIZE)
        fun lit(x: Int, y: Int) = near(picture.getPixel(x, y), COLOUR_1, 60)
        val box = onWatch(MINUTE_BOX)
        var dark = 0
        // A pixel clearly darker than the lit ones two to the left and two to the right: a line of the mesh through a stroke
        for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() + 2 until box.right.toInt() - 2)
            if (lit(x - 2, y) && lit(x + 2, y) &&
                brightness(picture.getPixel(x, y)) < (brightness(picture.getPixel(x - 2, y)) + brightness(picture.getPixel(x + 2, y))) / 2 - 20) dark++
        assertWithMessage("lit dots").that(countNear(picture, box, COLOUR_1, 60)).isGreaterThan(300)
        assertWithMessage("dark lines between them").that(dark).isGreaterThan(100)
    }

    /** The strips between the panels are raised brushed steel, not the dark dial, with small domed pins in them. */
    @Test
    fun theStripsBetweenThePanelsAreRaisedMetalWithPins() {
        val picture = render()
        // Steel half way between two pins, in each strip: grey and of middle brightness (the dark dial was about 45)
        for ((x, y) in listOf(70f to 155f, 150f to 155f, 250f to 155f, 70f to 247f, 296f to 247f, 210f to 124f, 270f to 278f, 90f to 124f, 90f to 278f, 157f to 60f, 150f to 330f)) {
            val c = picture.getPixel(x.toInt(), y.toInt())
            assertWithMessage("steel at $x, $y").that(brightness(c)).isIn(Range.closed(80, 200))
            assertWithMessage("grey at $x, $y").that(maxOf(abs(Color.red(c) - Color.green(c)), abs(Color.green(c) - Color.blue(c)))).isAtMost(20)
        }
        // Each pin: a bright highlight on its top left, and it lies in a strip, between the panels
        val gaps = listOf(TOP_PANEL_BOTTOM to LINE_PANEL.top, LINE_PANEL.bottom to TIME_PANEL.top, TIME_PANEL.bottom to MONTH_PANEL.top, MONTH_PANEL.bottom to LOW_PANEL_TOP)
        for ((x, y) in FRAME_PINS) {
            val brightest = (-2..1).flatMap { dy -> (-2..1).map { dx -> brightness(picture.getPixel(x.toInt() + dx, y.toInt() + dy)) } }.max()
            assertWithMessage("pin highlight at $x, $y").that(brightest).isAtLeast(180)
            assertWithMessage("pin $x, $y in a strip").that(gaps.any { (top, bottom) -> y - FRAME_PIN_R >= top && y + FRAME_PIN_R <= bottom }).isTrue()
            assertWithMessage("pin $x, $y inside the rim").that(hypot(x - 200f, y - 200f) + FRAME_PIN_R).isLessThan(RIM_INNER)
        }
        // The screws on the wider steel: a bright head, clear of the rim and of the panels beside them
        for ((x, y) in FRAME_SCREWS) {
            val brightest = (-3..1).flatMap { dy -> (-3..1).map { dx -> brightness(picture.getPixel(x.toInt() + dx, y.toInt() + dy)) } }.max()
            assertWithMessage("screw head at $x, $y").that(brightest).isAtLeast(180)
            assertWithMessage("screw $x, $y inside the rim").that(hypot(x - 200f, y - 200f) + FRAME_SCREW_R).isLessThan(RIM_INNER - 1f)
            val head = RectF(x - FRAME_SCREW_R, y - FRAME_SCREW_R, x + FRAME_SCREW_R, y + FRAME_SCREW_R)
            for (panel in listOf(LINE_PANEL, TIME_PANEL, MONTH_PANEL)) assertWithMessage("screw $x, $y and a panel").that(RectF.intersects(head, panel)).isFalse()
        }
    }

    @Test
    fun theTrendArrowTurnsWithTheDelta() {
        for ((delta, angle) in listOf(0.0 to 90f, 7.5 to 45f, 15.0 to 0f, 30.0 to 0f, -3.0 to 108f, -7.5 to 135f, -20.0 to 180f)) {
            val picture = render(Sample(deltaMgdl = delta))
            assertWithMessage("$delta").that(trendHandAt(picture, angle)).isGreaterThan(2)
            assertWithMessage("$delta opposite").that(trendHandAt(picture, angle + 180f)).isEqualTo(0)
        }
        // No delta: no arrow
        val none = render(Sample(deltaMgdl = null))
        for (angle in listOf(0f, 90f, 180f)) assertWithMessage("none $angle").that(trendHandAt(none, angle)).isEqualTo(0)
    }

    @Test
    fun theMonthMarkIsOverThisMonth() {
        val picture = render()
        val month = LocalDateTime.now().monthValue
        assertThat(countNear(picture, monthMarkBox(month), NEEDLE_RED, 70)).isGreaterThan(20)
        assertThat(countNear(picture, monthMarkBox(if (month == 1) 2 else 1), NEEDLE_RED, 70)).isEqualTo(0)
    }

    /**
     * Also on a screen that is not 400 pixels, like the 466 of a OnePlus Watch 2R. The watch scales an offset from a range
     * (`minValue` to `maxValue`) twice, so the mark moves by steps, which it scales once.
     */
    @Test
    fun theMonthMarkIsOverThisMonthOnABiggerScreen() {
        val size = 466
        val picture = render(size = size)
        val scale = size / 400f
        fun scaled(r: RectF) = RectF(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale)
        val month = LocalDateTime.now().monthValue
        for (m in 1..12) {
            val red = countNear(picture, scaled(monthMarkBox(m)), NEEDLE_RED, 70)
            if (m == month) assertWithMessage("month $m").that(red).isGreaterThan(20) else assertWithMessage("month $m").that(red).isEqualTo(0)
        }
    }

    @Test
    fun theColoursChosenOnTheWatchShow() {
        val time = RectF(HOUR_BOX.left, TIME_PANEL.top + 8f, MINUTE_BOX.right, TIME_PANEL.bottom - 8f)
        val designed = render()
        assertThat(countNear(designed, time, COLOUR_1)).isGreaterThan(300)
        assertThat(countNear(designed, DAY_BOX, COLOUR_2)).isGreaterThan(50)
        val chosen = render(colours = Pair("#FF3B3B", "#3D8BFF"))
        assertThat(countNear(chosen, time, COLOUR_1)).isEqualTo(0)
        assertThat(countNear(chosen, time, "#FF3B3B")).isGreaterThan(300)
        assertThat(countNear(chosen, DAY_BOX, "#3D8BFF")).isGreaterThan(50)
    }

    @Test
    fun amPmOnlyShowsWith12Hours() {
        val frame = PERIOD_BOX
        val with24 = render(hours24 = true)
        val with12 = render(hours24 = false)
        assertThat(countNear(with24, frame, WHITE, 60)).isEqualTo(0)
        assertThat(countNear(with12, frame, WHITE, 70)).isGreaterThan(4)
    }

    @Test
    fun oldValuesAndLowBatteriesTurnRed() {
        val fresh = render()
        val old = render(bgAgeMin = 14, loopAgeMin = 20, watch = 12, phone = "9")
        for ((name, box) in listOf("age" to AGE_BOX, "watch" to WATCH_NUMBER_BOX, "phone" to PHONE_NUMBER_BOX)) {
            assertWithMessage("$name fresh").that(countNear(fresh, box, LOW, 40)).isEqualTo(0)
            assertWithMessage("$name old").that(countNear(old, box, LOW, 40)).isGreaterThan(5)
        }
    }

    @Test
    fun theWeatherShowsItsIconAndTheTemperature() {
        val icon = WEATHER_ICON_BOX
        // By day a sun, at night a moon (pale, not yellow); a cloud is light grey; rain has blue drops
        assertThat(countNear(render(weather = EventData.Weather.CLEAR), icon, SUN, 40)).isGreaterThan(40)
        val night = render(weather = EventData.Weather.CLEAR, instant = NIGHT_MS)
        assertThat(countNear(night, icon, SUN, 40)).isEqualTo(0)
        assertThat(countNear(night, icon, MOON_LIGHT, 30)).isGreaterThan(30)
        assertThat(countNear(render(weather = EventData.Weather.CLOUDY), icon, CLOUD, 20)).isGreaterThan(80)
        assertThat(countNear(render(weather = EventData.Weather.RAIN), icon, RAIN, 50)).isGreaterThan(5)
        // The temperature in white
        assertThat(countNear(render(temperature = 25.0), TEMPERATURE_BOX, VALUE, 40)).isGreaterThan(15)
        // No weather: no icon and no temperature
        val none = render(weather = null, temperature = null)
        for (hex in listOf(SUN, CLOUD, MOON_LIGHT)) assertWithMessage(hex).that(countNear(none, icon, hex, 30)).isEqualTo(0)
        assertThat(countNear(none, TEMPERATURE_BOX, VALUE, 40)).isEqualTo(0)
    }

    @Test
    fun theNextSunriseOrSunsetShowsWithAPlace() {
        // The time is a text; without a place the watch shows "--:--", narrower than "16:45"
        val withPlace = countNear(render(), SUN_TIME_BOX, VALUE, 40)
        val withoutPlace = countNear(render(place = false), SUN_TIME_BOX, VALUE, 40)
        assertThat(withPlace).isGreaterThan(40)
        assertThat(withoutPlace).isGreaterThan(5)
        assertThat(withoutPlace).isLessThan(withPlace)
        // The icon is in the background, always
        assertThat(countNear(render(place = false), RectF(ROW_ICON_X - 6f, SUN_ROW_Y - 6f, ROW_ICON_X + 6f, SUN_ROW_Y), SUNSET_LOW, 60)).isGreaterThan(10)
    }

    @Test
    fun theGraphIsThereButQuiet() {
        val design = design(defaultDesign())
        val chart = rectOf(design.getJSONObject("chart"))
        insideRound("chart", chart)
        // The view is the glass and a little more above and below, for the empty margin of the chart
        assertThat(chart.left).isAtLeast(GRAPH_WINDOW.left)
        assertThat(chart.right).isAtMost(GRAPH_WINDOW.right)
        assertThat(chart.contains(GRAPH_BOX)).isTrue()
        assertThat(chart.height() - GRAPH_BOX.height()).isAtMost(14f)
        // The dots are there, in the glass, in the muted colour, and nothing in the window is bright
        val picture = render()
        assertThat(countNear(picture, GRAPH_BOX, GRAPH_MID, 40)).isGreaterThan(20)
        // Readable on the watch, but quieter than the bright glucose value above it
        assertThat(brightestAround(picture, GRAPH_BOX.centerX() to GRAPH_BOX.centerY(), (GRAPH_BOX.height() / 2 - 1).toInt())).isLessThan(0.6)
        // The glucose value keeps its own bright colour
        assertThat(countNear(picture, SGV_BOX, MID, 40)).isGreaterThan(100)
    }

    @Test
    fun theColonHasTheMainColour() {
        assertThat(countNear(render(), COLON_BOX, COLOUR_1)).isGreaterThan(30)
        assertThat(countNear(render(colours = Pair("#FF3B3B", ColourLooks.AS_DESIGNED)), COLON_BOX, "#FF3B3B")).isGreaterThan(30)
    }

    @Test
    fun theWakeAnimationIsReadAsDesigned() {
        val spec = readAnimation(design(defaultDesign()))
        // Every value is inside the safe limits, so nothing is changed on the watch
        assertThat(spec.normalized()).isEqualTo(spec)
        assertThat(spec.durationMs).isEqualTo(ANIMATION_MS)
        assertThat(spec.sweep.map { it.views.single() }).containsExactly(BG_NEEDLE_VIEW, SENSOR_NEEDLE_VIEW, PUMP_NEEDLE_VIEW).inOrder()
        assertThat(spec.wobble.single().views).containsExactly(TREND_HAND_VIEW)
        assertThat(spec.trace?.view).isEqualTo("chart")
        assertThat(spec.glint?.rings).hasSize(1)
        // Everything has settled before the end, and the light comes after the hands
        val last = spec.sweep.maxOf { it.delayMs + it.upMs + it.settleMs }
        assertThat(last).isAtMost(GLINT_DELAY_MS + 100)
        assertThat(GLINT_DELAY_MS + GLINT_MS).isAtMost(ANIMATION_MS)
        assertThat(TRACE_DELAY_MS + TRACE_MS).isAtMost(ANIMATION_MS)
    }

    @Test
    fun theWakeSwingsThePointersAndDrawsTheGraphIn() {
        val upBg = SWEEP_UP_MS
        val upSensor = SENSOR_SWEEP_DELAY_MS + SWEEP_UP_MS
        val halfTrace = TRACE_DELAY_MS + TRACE_MS / 2
        val glint = GLINT_DELAY_MS + GLINT_MS / 2
        val settled = GLINT_DELAY_MS + GLINT_MS + 50
        val upMonth = MONTH_SLIDE_DELAY_MS + SWEEP_UP_MS
        val frames = wakeFrames(listOf(0L, upBg, upMonth, upSensor, halfTrace, glint, settled, ANIMATION_MS + 50))
        val still = render()
        // At the start the pointer waits at the bottom of its scale, then it is at the top: 18 mmol/L
        assertThat(needleRedAt(frames.getValue(0L)!!, BG_FROM)).isGreaterThan(3)
        assertThat(needleRedAt(frames.getValue(upBg)!!, BG_TO)).isGreaterThan(3)
        assertThat(needleRedAt(frames.getValue(upBg)!!, bgAngle(Sample().mgdl))).isEqualTo(0)
        // The month mark: at January at the start, at December at the top of its run, then back on this month
        val month = LocalDateTime.now().monthValue
        assertWithMessage("month mark at January").that(countNear(frames.getValue(0L)!!, monthMarkBox(1), NEEDLE_RED, 70)).isGreaterThan(20)
        assertWithMessage("month mark at December").that(countNear(frames.getValue(upMonth)!!, monthMarkBox(12), NEEDLE_RED, 70)).isGreaterThan(20)
        assertWithMessage("month mark on this month").that(countNear(frames.getValue(settled)!!, monthMarkBox(month), NEEDLE_RED, 70)).isGreaterThan(20)
        if (month != 12) assertWithMessage("not at December when settled").that(countNear(frames.getValue(settled)!!, monthMarkBox(12), NEEDLE_RED, 70)).isEqualTo(0)
        // The sensor hand at 96 hours, where it does not show (54)
        assertThat(handAt(frames.getValue(upSensor)!!, HOURS_MAX, SENSOR_COLOUR)).isGreaterThan(3)
        assertThat(handAt(still, HOURS_MAX, SENSOR_COLOUR)).isEqualTo(0)
        // Halfway, the graph shows on the left and not yet on the right
        val left = RectF(GRAPH_BOX.left + 2, GRAPH_BOX.top, GRAPH_BOX.left + GRAPH_BOX.width() * 0.4f, GRAPH_BOX.bottom)
        val right = RectF(GRAPH_BOX.left + GRAPH_BOX.width() * 0.62f, GRAPH_BOX.top, GRAPH_BOX.right, GRAPH_BOX.bottom)
        // (compared with a settled frame of the same wake: the graph of a render at another time can be moved a little)
        val half = frames.getValue(halfTrace)!!
        val whole = frames.getValue(settled)!!
        assertThat(countNear(whole, right, GRAPH_MID, 40)).isGreaterThan(5)
        assertThat(countNear(half, right, GRAPH_MID, 40)).isEqualTo(0)
        assertThat(countNear(half, left, GRAPH_MID, 40)).isEqualTo(countNear(whole, left, GRAPH_MID, 40))
        // The light makes part of the rim lighter, and nothing in the middle of the face. Compared with a frame after
        // it, in the same wake: everything else has settled by then, and the clock text is of the same minute.
        val lit = frames.getValue(glint)!!
        val after = frames.getValue(settled)!!
        fun lighter(r: RectF): Int {
            var n = 0
            for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt())
                if (luminance(lit.getPixel(x, y)) - luminance(after.getPixel(x, y)) > 0.1) n++
            return n
        }
        assertThat(lighter(RectF(0f, 0f, 400f, 400f))).isGreaterThan(200)
        assertThat(lighter(RectF(HOUR_BOX.left, HOUR_BOX.top, MINUTE_BOX.right, MINUTE_BOX.bottom))).isEqualTo(0)
        // After the end the face is drawn the normal way
        assertThat(frames.getValue(ANIMATION_MS + 50)).isNull()
    }

    @Test
    fun theRunnerFollowsTheSeconds() {
        // PREVIEW_MS is at 32 seconds
        val at32 = render()
        val at45 = render(instant = PREVIEW_MS + 13_000)
        // The runner over the rim, in the main colour, where the second hand points
        assertThat(countNear(at32, around(32), COLOUR_1, 70)).isGreaterThan(4)
        assertThat(countNear(at32, around(45), COLOUR_1, 70)).isEqualTo(0)
        assertThat(countNear(at45, around(45), COLOUR_1, 70)).isGreaterThan(4)
        // The runner follows the main colour chosen on the watch
        assertThat(countNear(render(colours = Pair("#FF3B3B", ColourLooks.AS_DESIGNED)), around(32), "#FF3B3B", 70)).isGreaterThan(4)
    }

    /** A small square over the rim where the runner is at [second]. */
    private fun around(second: Int): RectF {
        val (x, y) = polar(200f, 200f, (RUNNER_INNER + RUNNER_OUTER) / 2, second * 6f)
        return RectF(x - 3f, y - 3f, x + 3f, y + 3f)
    }

    /** Frames of the wake-up animation, every 100 ms, on one sheet, and every 50 ms as pictures in `build/cwf-design/anim`. */
    @Test
    fun rendersTheWakeAnimation() {
        val times = (0L..ANIMATION_MS step 50)
        val frames = wakeFrames(times.toList())
        val dir = File(out, "anim").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("d2_") }?.forEach { it.delete() }
        for ((ms, frame) in frames) frame?.let { File(dir, "d2_%04d.png".format(ms)).writeBytes(pngBytes(it)) }
        sheet(times.filter { it % 300 == 0L }.mapNotNull { ms -> frames.getValue(ms)?.let { "$ms ms" to it } }, "${fileName}_wake.png")
        // The same as a moving picture, with the still face for a moment before and after
        val still = render()
        writeGif(listOf(still) + frames.values.filterNotNull() + listOf(still), File(out, "${fileName}_wake.gif"), frameMs = 50, holdFirstAndLastMs = 1000)
    }

    /** Writes [frames] as a GIF that loops, [frameMs] each, the first and the last one shown [holdFirstAndLastMs]. */
    private fun writeGif(frames: List<Bitmap>, file: File, frameMs: Int, holdFirstAndLastMs: Int) {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(file.apply { delete() }).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            frames.forEachIndexed { index, bitmap ->
                val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_RGB)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                image.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
                val type = ImageTypeSpecifier.createFromRenderedImage(image)
                val metadata = writer.getDefaultImageMetadata(type, null)
                val root = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode
                val delay = if (index == 0 || index == frames.lastIndex) holdFirstAndLastMs else frameMs
                IIOMetadataNode("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", (delay / 10).toString())
                    setAttribute("transparentColorIndex", "0")
                    root.appendChild(this)
                }
                if (index == 0) IIOMetadataNode("ApplicationExtensions").apply {
                    appendChild(IIOMetadataNode("ApplicationExtension").apply {
                        setAttribute("applicationID", "NETSCAPE")
                        setAttribute("authenticationCode", "2.0")
                        userObject = byteArrayOf(1, 0, 0)
                    })
                    root.appendChild(this)
                }
                metadata.setFromTree(metadata.nativeMetadataFormatName, root)
                writer.writeToSequence(IIOImage(image, null, metadata), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }

    @Test
    fun rendersEveryLook() {
        sheet(
            listOf(
                "as designed, partly cloudy" to render(),
                "night, clear" to render(weather = EventData.Weather.CLEAR, temperature = 4.0, instant = NIGHT_MS),
                "rain, no temperature" to render(weather = EventData.Weather.RAIN, temperature = null),
                "red / blue" to render(colours = Pair("#FF3B3B", "#3D8BFF")),
                "green / white" to render(colours = Pair("#2EE66B", "#F2F2F2")),
                "12 hours, high ⇈" to render(Sample("13,9", "⇈", 250.0, 1, deltaMgdl = 18.0), hours24 = false),
                "low ↘, old, batteries" to render(Sample("3,4", "↘", 61.0, -1, deltaMgdl = -8.0), bgAgeMin = 14, loopAgeMin = 20, watch = 12, phone = "9"),
                "no delta" to render(Sample("6,1", "??", 110.0, 0, deltaMgdl = null))
            ), "${fileName}_looks.png"
        )
    }

    private companion object {

        const val TEXT_FONT = "D2Text"
        const val PRINT_FONT = "Michroma"
        const val JPEG_QUALITY = 90

        /** The colours. [COLOUR_1] and [COLOUR_2] are the colours as designed; the wearer can change them. */
        const val COLOUR_1 = "#00E5FF"
        const val COLOUR_2 = "#B6FF1A"
        const val WHITE = "#F2F2F2"

        /** The values in the panels, a pale yellow as in the original. */
        const val VALUE = "#F3E9B5"
        const val MUTED = "#8A9099"
        const val ICON = "#C8CDD3"
        const val PANEL = "#0C0D0F"
        const val EDGE = "#4C5158"
        const val EDGE_LIGHT = "#9AA0A8"
        const val EDGE_DARK = "#2A2D31"
        const val SUNSET_TOP = "#FF3B2F"
        const val MARKER = "#2BAA55"
        const val SUNSET_LOW = "#FFA126"

        /** The metal: the rim and the pockets of the scales, lit from the top left. */
        val RIM_SWEEP = listOf("#5C6066", "#C9CDD2", "#6A6E74", "#3A3D42", "#8E9399", "#E1E4E8", "#70747A", "#3E4146", "#5C6066")
        val METAL_SWEEP = listOf("#4A4E54", "#9AA0A6", "#55595F", "#3C3F44", "#7D8288", "#B4B9BE", "#5A5E64", "#43464B", "#4A4E54")
        const val RIM_INNER = 187f

        /** Ridges: one every so many degrees, fine in the rim, wider in the pockets. Screws in the rim, between hour ticks. */
        const val RIM_RIDGE_STEP = 1.2f
        const val POCKET_RIDGE_STEP = 2.4f
        val RIM_SCREW_ANGLES = listOf(45f, 135f, 225f, 315f)

        /** Screws at the inner corners of the pockets, clear of the scales. */
        val POCKET_SCREWS = listOf(134f to 112f, 52f to 112f, 129f to 290f, 52f to 290f)

        /** The room between a pocket and the panels beside it. */
        /** The steel strip between a pocket and the panel right of it. */
        const val POCKET_STRIP = 7f

        /** The raised steel frame between the panels: its colour at the top and the bottom of the face, and its pins. */
        const val FRAME_TOP = "#9CA2A9"
        const val FRAME_BOTTOM = "#5E636A"
        const val FRAME_PIN_R = 1.6f

        /** Screws in the wider steel left and right of the ends of the line panel and of the month panel. */
        const val FRAME_SCREW_R = 3f
        val FRAME_SCREWS = listOf(32f to 141f, 368f to 141f, 29f to 262f, 371f to 262f)
        val FRAME_PINS = listOf(
            // Under the top right panel, above the line under the glucose
            60f to 124f, 120f to 124f, 180f to 124f, 240f to 124f, 300f to 124f, 355f to 124f,
            // Between the line panel and the time panel, and between the time panel and the months
            110f to 155f, 200f to 155f, 290f to 155f,
            110f to 247f, 200f to 247f, 290f to 247f,
            // Under the months, above the bottom right panel
            60f to 278f, 120f to 278f, 180f to 278f, 240f to 278f, 300f to 278f, 355f to 278f
        )
        const val ROW_LINE = "#2A2D32"
        const val NEEDLE_RED = "#FF2A2A"
        const val LOW = "#FF3B3B"
        const val MID = "#2EE66B"
        const val HIGH = "#FFB21A"

        /** The panels: their round edge, the panel at the top right and at the bottom right (cut by a slanted line on the left), the line, the time and the months. */
        const val PANEL_R = 188f
        const val TOP_PANEL_TOP = 8f
        const val TOP_PANEL_BOTTOM = 120f
        const val TOP_PANEL_TOP_LEFT = 174f
        const val TOP_PANEL_BOTTOM_LEFT = 146f
        const val LOW_PANEL_TOP = 282f
        const val LOW_PANEL_BOTTOM = 400f
        const val LOW_PANEL_TOP_LEFT = 142f
        const val LOW_PANEL_BOTTOM_LEFT = 170f
        /** The line panel and the months end before the trend dial; the time panel runs on under it, out of sight. */
        val LINE_PANEL = RectF(44f, 128f, 306f, 152f)
        val TIME_PANEL = RectF(18f, 158f, 330f, 244f)
        val MONTH_PANEL = RectF(36f, 250f, 306f, 274f)

        /** The top right panel: the batteries (row A), heart rate and loop (row B), the glucose (row C). */
        const val ROW_A_Y = 36f
        const val ROW_A_LINE = 49f
        const val WATCH_ICON_X = 176f
        const val PHONE_ICON_X = 232f
        val WATCH_NUMBER_BOX = RectF(184f, 26f, 224f, 46f)
        val PHONE_NUMBER_BOX = RectF(240f, 26f, 284f, 46f)
        const val SMALL_SIZE = 18
        /** The glucose fills rows B and C; right of it a column ([COLUMN_X]) with the heart rate over the weather ([COLUMN_LINE]). */
        val SGV_BOX = RectF(165f, 52f, 266f, 120f)
        const val SGV_SIZE = 70
        const val COLUMN_X = 269f
        const val COLUMN_LINE = 81f
        /** The slot keeps a square at its left for its icon: the heart of the background lies in it. */
        val HR_BOX = RectF(270f, 54f, 318f, 76f)
        const val HR_ICON_X = 281f
        const val HR_ROW_Y = 65f
        const val HR_SIZE = 21
        val WEATHER_ICON_BOX = RectF(274f, 86f, 300f, 112f)
        val TEMPERATURE_BOX = RectF(301f, 86f, 344f, 112f)
        const val TEMPERATURE_SIZE = 24

        /** The line under the glucose. */
        val AGE_BOX = RectF(54f, 129f, 80f, 151f)
        val IOB_BOX = RectF(108f, 129f, 160f, 151f)
        val COB_BOX = RectF(190f, 129f, 228f, 151f)
        val BASAL_BOX = RectF(236f, 129f, 304f, 151f)

        /** The delta, in the trend dial under its pivot; [DELTA_UP] higher, over the pivot, while the glucose falls. */
        val DELTA_BOX = RectF(321f, 216f, 351f, 232f)
        const val DELTA_SIZE = 15
        const val DELTA_UP = 42
        const val LINE_SIZE = 17

        /** The time panel: the day name and the day with the moon, the time, the seconds and AM / PM. */
        const val DATE_LINE_X = 100f
        val DAY_NAME_BOX = RectF(28f, 164f, 96f, 194f)
        const val DAY_NAME_SIZE = 26
        val DAY_BOX = RectF(52f, 198f, 98f, 240f)
        const val DAY_SIZE = 38
        val MOON_BOX = RectF(26f, 207f, 50f, 231f)
        val HOUR_BOX = RectF(89f, 162f, 188f, 240f)
        val COLON_BOX = RectF(188f, 162f, 206f, 240f)
        val MINUTE_BOX = RectF(206f, 162f, 305f, 240f)
        const val TIME_SIZE = 88

        /** The runner on the rim: from the inner edge of the rim to near the outside, this wide at the outside. */
        const val RUNNER_INNER = 188f
        const val RUNNER_OUTER = 198.5f
        const val RUNNER_HALF_WIDTH = 2.8f
        /** AM / PM, with 12 hours only: between the minutes and the trend dial, under the pivot. */
        val PERIOD_BOX = RectF(293f, 214f, 312f, 229f)
        const val PERIOD_SIZE = 11

        /** The months: the label, the first number and the room between two, the mark over the numbers. */
        const val MONTH_LABEL_X = 56f
        const val MONTH_FIRST_X = 80f
        const val MONTH_STEP = 19
        const val MONTH_TEXT_Y = 265f
        const val MONTH_MARK_TOP = 252f

        /** The bottom right panel: the free slot (row D) and the steps (row E), and the name. */
        /** The bottom right panel: the graph window at the top, then the sunrise or sunset and the steps, a line between them. */
        val GRAPH_WINDOW = RectF(163f, 283f, 340f, 330f)
        val GRAPH_BOX = RectF(166f, 286f, 337f, 327f)
        val CHART_VIEW = RectF(GRAPH_BOX.left, GRAPH_BOX.top - 7f, GRAPH_BOX.right, GRAPH_BOX.bottom + 7f)
        const val ROW_D_LINE = 356f

        /** The icons of the bottom rows, in a column at [ROW_ICON_X]; the values start right of them. */
        const val ROW_ICON_X = 178f
        const val ROW_ICON_SIZE = 28
        const val ROW_ICON_SCALE = 0.9f
        const val SUN_ROW_Y = 343f
        const val STEPS_ROW_Y = 369f
        val SUN_TIME_BOX = RectF(191f, 331f, 304f, 355f)
        const val SUN_TIME_SIZE = 26
        val STEPS_BOX = RectF(166f, 357f, 266f, 381f)
        const val STEPS_SIZE = 26

        /** The small markers at the right end of the bottom rows, as in the original: where each one stands. */
        val ROW_MARKERS = listOf(Triple(312f, SUN_ROW_Y, true), Triple(268f, STEPS_ROW_Y, false))

        /** The graph: muted, low contrast, on dark tinted glass. */
        const val GLASS_TOP = "#0E1311"
        const val GLASS_BOTTOM = "#141A17"
        /** The radius of a glucose dot, in dp: the chart takes whole numbers only. */
        const val GRAPH_POINT_SIZE = 1
        const val GRAPH_MID = "#A3C4A8"
        const val GRAPH_HIGH = "#CDB676"
        const val GRAPH_LOW = "#C97C74"
        const val GRAPH_GRID = "#3C4A44"
        const val GRAPH_CARBS = "#6E6A4C"
        const val GRAPH_BASAL = "#18221F"
        const val GRAPH_BASAL_LINE = "#3B4A44"

        /** The scales along the edge: the ticks, the numbers or arrows, the band and the name, all round the middle of the dial. */
        const val SCALE_OUTER = 186f
        const val SCALE_LABEL_R = 164f
        const val SCALE_LABEL_SIZE = 14f
        const val BAND_OUTER = 154f
        const val BAND_INNER = 146f
        const val SCALE_NAME_R = 132f

        /** The glucose scale: 2 to 18 mmol/L, from -55 to -22 degrees, inside its pocket with the steel strips round it. */
        const val BG_MIN = 36.0
        const val BG_MAX = 324.0
        const val BG_FROM = -55.0
        const val BG_TO = -22.0
        const val MGDL_PER_MMOL = 18.0

        /**
         * The time-left scale: 0 hours at -158 degrees (bottom) to 96 at -127 (top), log with [HOURS_DETAIL] (see
         * hoursAngle), the hand in steps of [HOURS_PER_STEP]. The size of its legend.
         */
        const val HOURS_MAX = 96.0
        const val HOURS_DETAIL = 12.0
        const val HOURS_PER_STEP = 0.5
        const val WATCH_SIZE = 466
        val HOURS_NUMBERS = listOf(0, 12, 24, 48, 96)
        const val BAND_LINES = 48
        const val HOURS_EMPTY_ANGLE = -158.0
        const val HOURS_FULL_ANGLE = -127.0
        const val LEGEND_SIZE = 6f

        /**
         * The trend dial: a half circle right of the minutes, against the rim, over the ends of the line and month panels, its straight side on the
         * left, the arrow turning round the middle of that side. The arrow turns [TREND_DEGREES_PER_MGDL] degrees for each
         * mg/dL in 5 minutes.
         */
        const val TREND_DIAL_X = 318f
        const val TREND_DIAL_Y = 201f
        const val TREND_DIAL_R = 66f
        const val TREND_HAND_LENGTH = 58f
        const val TREND_DIAL_INSET = 4f
        const val TREND_DEGREES_PER_MGDL = 6.0
        const val TREND_MAX_DELTA = 15.0

        /** The pointers: a picture of the whole dial but the edge, the spike from [NEEDLE_INNER] to [NEEDLE_OUTER]. */
        val NEEDLE_BOX = RectF(10f, 10f, 390f, 390f)
        const val NEEDLE_INNER = 136f
        const val NEEDLE_OUTER = 188f
        const val NEEDLE_HALF_WIDTH = 4.2f

        /** The views and their dynData. */
        const val HR_VIEW = "complication1"
        const val STEPS_VIEW = "complication2"
        const val SUN_TIME_VIEW = "freetext3"
        const val BG_NEEDLE_VIEW = "patient_name"
        const val SENSOR_NEEDLE_VIEW = "freetext1"
        const val PUMP_NEEDLE_VIEW = "reservoir"
        const val TREND_HAND_VIEW = "avg_delta"
        const val MOON_VIEW = "freetext2"
        const val MONTH_VIEW = "month"
        const val TEMPERATURE_VIEW = "freetext4"
        const val WATCH_NUMBER_VIEW = "rig_battery"
        const val PHONE_NUMBER_VIEW = "uploader_battery"
        const val WATCH_NUMBER_DYN = "watchBatteryNumber"
        const val PHONE_NUMBER_DYN = "phoneBatteryNumber"
        const val DELTA_PLACE_DYN = "deltaPlace"
        const val AGE_DYN = "bgAge"
        const val MOON_DYN = "moonPhase"
        const val MONTH_DYN = "monthMark"
        const val BG_NEEDLE_DYN = "bgNeedle"
        const val SENSOR_NEEDLE_DYN = "sensorNeedle"
        const val PUMP_NEEDLE_DYN = "pumpNeedle"
        const val TREND_HAND_DYN = "trendHand"
        const val WEATHER_ICON_DYN = "weatherIcon"
        const val TEMPERATURE_DYN = "temperature"
        const val SUN_TIME_DYN = "sunTime"
        const val SGV_DYN = "sgvColour"

        /** Less than any night progress after sunset: the night progress is exactly 0 all day. */
        const val NIGHT_STEP = 0.001
        /** The LED mesh: the room between two lines, and their width. */
        const val LED_PITCH = 2.4f
        const val LED_GAP = 0.6f
        const val AGE_LIMIT = 10.0

        /** The pictures. */
        const val NEEDLE = "Needle"
        const val SENSOR_HAND = "SensorHand"
        const val PUMP_HAND = "PumpHand"
        const val TREND_HAND = "TrendHand"
        const val SENSOR_COLOUR = "#2FC6FF"
        const val PUMP_COLOUR = "#FF9F1C"
        const val MONTH_MARK = "MonthMark"
        const val PERIOD_FRAME = "PeriodFrame"
        const val MOON_STEPS = 16
        const val WX_SUN = "WxSun"
        const val WX_MOON = "WxMoon"
        const val WX_SUN_CLOUD = "WxSunCloud"
        const val WX_MOON_CLOUD = "WxMoonCloud"
        const val WX_CLOUD = "WxCloud"
        const val WX_RAIN = "WxRain"

        /** The icon of each kind of weather, by day and by night. */
        val WEATHER_ICONS = mapOf(
            EventData.Weather.CLEAR to (WX_SUN to WX_MOON),
            EventData.Weather.PARTLY to (WX_SUN_CLOUD to WX_MOON_CLOUD),
            EventData.Weather.CLOUDY to (WX_CLOUD to WX_CLOUD),
            EventData.Weather.RAIN to (WX_RAIN to WX_RAIN)
        )
        const val SUN = "#FFC93A"
        const val MOON_LIGHT = "#F1E6C0"
        const val CLOUD = "#E9EDF2"
        const val CLOUD_SHADE = "#9AA3AD"
        const val RAIN = "#3D8BFF"
        const val TEMPERATURE_SAMPLE = 14.0
        /** The delta of the samples in mg/dL: the +0,2 mmol/L they show. */
        const val DELTA_SAMPLE = 3.6
        const val SENSOR_HOURS_SAMPLE = 54.0

        /**
         * The wake-up animation, in ms from the wrist raise: the pointers swing one after the other, the trend arrow
         * shakes, the graph draws itself in, and the light comes last, when everything has settled.
         */
        const val ANIMATION_MS = 2700L
        const val ANIMATION_FPS = 30
        const val SWEEP_UP_MS = 600L
        const val SWEEP_SETTLE_MS = 900L
        const val SENSOR_SWEEP_DELAY_MS = 150L
        const val PUMP_SWEEP_DELAY_MS = 300L
        const val MONTH_SLIDE_DELAY_MS = 200L
        const val WOBBLE_DELAY_MS = 250L
        const val WOBBLE_DEGREES = 22.0
        const val WOBBLE_HZ = 2.8
        const val WOBBLE_DECAY_MS = 330L
        const val TRACE_DELAY_MS = 350L
        const val TRACE_MS = 1000L
        const val TRACE_COLOUR = "#B9E8C0"
        const val GLINT_DELAY_MS = 1700L
        const val GLINT_MS = 900L
        const val GLINT_ANGLE = 35.0
        const val PUMP_HOURS_SAMPLE = 30.0
        const val HOUR_MS = 60 * 60 * 1000L

        const val HR_SAMPLE = 72L
        const val STEPS_SAMPLE = 6375L

        /** The moment of the preview and of the moon: 13 November 2026 at 10:08:32, a waxing crescent. */
        val PREVIEW_MS: Long = LocalDateTime.of(2026, 11, 13, 10, 8, 32).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
