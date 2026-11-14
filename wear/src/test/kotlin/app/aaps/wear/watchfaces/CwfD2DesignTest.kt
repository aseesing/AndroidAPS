// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import androidx.core.graphics.createBitmap
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.JsonKeyValues
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import app.aaps.wear.health.HealthKind
import app.aaps.wear.watchfaces.colour.ColourLooks
import app.aaps.wear.watchfaces.utils.WatchBatteryLevel
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * "D2" (`D2.zip`): a sporty digital face in dark panels, after a well known layout with two small
 * scales on the left edge. Everything AAPS needs is on it:
 *
 * - the **glucose scale** at the top left (where the original has its power scale): a red pointer turns
 *   with the glucose value over 2 to 18 mmol/L, along a band that is red (low), green (in range) and
 *   amber (high);
 * - the **trend scale** at the bottom left (where the original has its daily goal): a red pointer shows
 *   the direction of the glucose, from ⇊ at the bottom to ⇈ at the top. No pointer without a direction;
 * - the glucose value big at the top right, with its arrow, in green, amber or red;
 * - a line with the delta, the age of the value (red from 10 minutes), the IOB and the basal rate;
 * - the batteries of the watch and the phone (red at 20 % or less), and the loop age in a small box
 *   (green, red from 15 minutes);
 * - the big time with seconds and AM/PM, the day, the date and the moon phase, the months of the year
 *   with a mark on this month;
 * - three complication slots: heart rate (1), steps (2) and one more (3, the watch shows carbs there
 *   until the wearer picks something else, like the weather or the sunset).
 *
 * What the wearer can change on the watch:
 * - 12 or 24 hours: the face follows the setting of the watch; AM/PM only shows with 12 hours;
 * - the main colour (the time and the trend band) and the second colour (the date), see [ColourLooks];
 * - what slots 1 to 3 show, in the watch face editor, and whether each one shows at all;
 * - each value on its own (seconds, delta, IOB, basal, batteries, loop, ...) in the face settings.
 *
 * The font is the one of the Cyberpunk faces (`cwf-designs/fonts/Cyber.ttf`, Rajdhani Bold), in the zip.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfD2DesignTest : CwfDesignBase() {

    override val displayName = "D2"
    override val fileName = "D2"

    private val fontFile = File("cwf-designs/fonts/$FONT.ttf")
    override fun fonts(): Map<String, ByteArray> = mapOf(FONT to fontFile.readBytes())
    override fun valueTypeface(): Typeface = font()
    private fun font(): Typeface = Typeface.createFromFile(fontFile)

    /** Heart rate, steps and carbs in the three slots, as the watch draws them. */
    override fun drawComplicationValues(canvas: Canvas, design: JSONObject, type: ComplicationType) {
        drawSlot(canvas, design.getJSONObject(HR_VIEW), health(HealthKind.HEART_RATE, HR_SAMPLE, type))
        drawSlot(canvas, design.getJSONObject(STEPS_VIEW), health(HealthKind.STEPS, STEPS_SAMPLE, type))
        drawSlot(canvas, design.getJSONObject(EXTRA_VIEW), ShortTextComplicationData.Builder(PlainComplicationText.Builder("12 g").build(), ComplicationText.EMPTY).build())
    }

    // ------------------------------------------------------------------------------------------
    // The design json
    // ------------------------------------------------------------------------------------------

    /** A text view in the font of the zip, its digits in the middle of [r] (see [centredText]). */
    private fun JSONObject.text(key: String, r: RectF, size: Int, gravity: String, color: String): JSONObject {
        val (top, height) = centredText(r, size, font())
        return place(key, r.left.toInt(), top, r.width().toInt(), height)
            .put("textsize", size).put("gravity", gravity).put("fontColor", color).put("font", FONT).put("fontStyle", "normal")
    }

    /** A view that only shows a picture, over [r]. */
    private fun JSONObject.picture(key: String, r: RectF, picture: String): JSONObject =
        place(key, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt()).put("textvalue", "").put("background", picture)

    /** A complication slot that shows the icon of its source and its value, no ring and no background. */
    private fun JSONObject.slot(key: String, r: RectF, size: Int) {
        place(key, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt())
            .put("color", CLEAR).put("borderRadius", 0).put("ringPrimaryColor", CLEAR).put("ringSecondaryColor", CLEAR)
            .put("fontColor", WHITE).put("iconColor", ICON).put("textsize", size).put("font", FONT).put("fontStyle", "normal").put("gravity", "left")
    }

    override fun design(default: CwfData): JSONObject = JSONObject(default.json).apply {
        getJSONObject("metadata")
            .put(CwfMetadataKey.CWF_NAME.key, displayName)
            .put(CwfMetadataKey.CWF_FILENAME.key, fileName)
            .put(CwfMetadataKey.CWF_AUTHOR.key, "AAPS (local)")
            .put(CwfMetadataKey.CWF_CREATED_AT.key, "14/11/26")
            .put(CwfMetadataKey.CWF_COMMENT.key, "Digital sports face: glucose scale, trend scale, big time, batteries, loop, three complications. Colours in the face settings")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_SECONDS.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DELTA.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_AVG_DELTA.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_UPLOADER_BATTERY.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_RIG_BATTERY.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_IOB.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DETAILED_IOB.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_TEMP_BASAL.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_WEEK_NUMBER.key, "false")

        put("enableSecond", true)
        put("highColor", HIGH).put("midColor", MID).put("lowColor", LOW)

        // Every view this design does not use is off
        val used = setOf(
            "background", "cover_chart", "sgv", "direction", "delta", "timestamp", "loop", "iob2", "basalRate", "hour", "minute", "second",
            "timePeriod", "day_name", "day", HR_VIEW, STEPS_VIEW, EXTRA_VIEW, BG_NEEDLE_VIEW, TREND_NEEDLE_VIEW, MOON_VIEW, MONTH_VIEW, COLON_VIEW,
            WATCH_NUMBER_VIEW, PHONE_NUMBER_VIEW
        )
        keys().asSequence().toList().forEach { key ->
            val view = optJSONObject(key) ?: return@forEach
            if (view.has("visibility") && key !in used) view.put("visibility", "gone")
        }
        put("background", JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put("visibility", "visible"))

        // The glucose value and its arrow, big at the top right
        place("direction", ARROW_BOX.left.toInt(), ARROW_BOX.top.toInt(), ARROW_BOX.width().toInt())
        text("sgv", SGV_BOX, SGV_SIZE, "left", MID)

        // The batteries: the numbers, red at 20 % or less (the icons are in the background)
        text(WATCH_NUMBER_VIEW, WATCH_NUMBER_BOX, SMALL_SIZE, "left", WHITE).put("textvalue", "%.0f%%").put("dynValue", false).put("dynData", WATCH_NUMBER_DYN)
        text(PHONE_NUMBER_VIEW, PHONE_NUMBER_BOX, SMALL_SIZE, "left", WHITE).put("textvalue", "%.0f%%").put("dynValue", false).put("dynData", PHONE_NUMBER_DYN)

        // Heart rate, and the loop age in its box: green, red from 15 minutes. The blank pictures stop the watch drawing its own circle.
        slot(HR_VIEW, HR_BOX, SLOT_SIZE)
        text("loop", LOOP_BOX, SMALL_SIZE, "center", MID).put("textvalue", "%.0f'").put("dynValue", false).put("dynData", LOOP_DYN)

        // The line under the glucose: delta, age, IOB, basal rate
        text("delta", DELTA_BOX, LINE_SIZE, "left", WHITE)
        text("timestamp", AGE_BOX, LINE_SIZE, "center", WHITE).put("textvalue", "%.0f'").put("dynValue", false).put("dynData", AGE_DYN)
        text("iob2", IOB_BOX, LINE_SIZE, "left", WHITE)
        text("basalRate", BASAL_BOX, LINE_SIZE, "left", WHITE)

        // The date with the moon, and the time
        text("day_name", DAY_NAME_BOX, DAY_NAME_SIZE, "center", WHITE).put("allCaps", true)
        text("day", DAY_BOX, DAY_SIZE, "center", COLOUR_2)
        picture(MOON_VIEW, MOON_BOX, moonName(MOON_STEPS / 2 + 1)).put("dynData", MOON_DYN)
        text("hour", HOUR_BOX, TIME_SIZE, "right", COLOUR_1)
        text(COLON_VIEW, COLON_BOX, TIME_SIZE, "center", COLOUR_1).put("textvalue", ":")
        text("minute", MINUTE_BOX, TIME_SIZE, "left", COLOUR_1)
        text("second", SECOND_BOX, SECOND_SIZE, "center", WHITE)
        text("timePeriod", PERIOD_BOX, PERIOD_SIZE, "center", WHITE).put("background", PERIOD_FRAME)

        // The mark over this month: moved to the right by the month
        picture(MONTH_VIEW, monthMarkBox(1), MONTH_MARK).put("leftOffset", true).put("dynData", MONTH_DYN)

        // The two pointers: one picture of the whole dial, turned by the watch round the middle
        picture(BG_NEEDLE_VIEW, NEEDLE_BOX, NEEDLE).put("rotationOffset", true).put("dynData", BG_NEEDLE_DYN)
        picture(TREND_NEEDLE_VIEW, NEEDLE_BOX, NEEDLE).put("rotationOffset", true).put("dynData", TREND_NEEDLE_DYN)
        // The band of the trend scale, white, so the watch paints it in the main colour
        place("cover_chart", TREND_BAND_BOX.left.toInt(), TREND_BAND_BOX.top.toInt(), TREND_BAND_BOX.width().toInt(), TREND_BAND_BOX.height().toInt())
            .put("color", COLOUR_1)

        // Complications: steps and the free slot in the bottom panel
        slot(EXTRA_VIEW, EXTRA_BOX, SLOT_SIZE)
        slot(STEPS_VIEW, STEPS_BOX, SLOT_SIZE)

        (optJSONObject("dynData") ?: JSONObject().also { put("dynData", it) })
            .put(WATCH_NUMBER_DYN, batteryNumberDynData(WatchBatteryLevel.KEY))
            .put(PHONE_NUMBER_DYN, batteryNumberDynData(PHONE_VALUE_KEY))
            .put(LOOP_DYN, ageColourDynData("loop", LOOP_LIMIT, MID).put("invalidImage", BLANK).put("image1", BLANK).put("image2", BLANK))
            .put(AGE_DYN, ageColourDynData("timestamp", AGE_LIMIT, WHITE))
            .put(MOON_DYN, moonDynData())
            .put(MONTH_DYN, JSONObject().put("valueKey", "month").put("minData", 1).put("maxData", 12)
                .put("leftOffset", JSONObject().put("minValue", 0).put("maxValue", 11 * MONTH_STEP).put("invalidValue", 0)))
            .put(BG_NEEDLE_DYN, JSONObject().put("valueKey", "sgv").put("minData", BG_MIN).put("maxData", BG_MAX)
                .put("rotationOffset", JSONObject().put("minValue", BG_FROM).put("maxValue", BG_TO).put("invalidValue", BG_FROM))
                .put("invalidImage", BLANK).put("image1", NEEDLE))
            .put(TREND_NEEDLE_DYN, trendNeedleDynData())

        // The colours the wearer can choose on the watch
        put(ColourLooks.JSON_KEY, JSONObject()
            .put("colour1", JSONObject().apply {
                for (key in listOf("hour", COLON_VIEW, "minute")) put(key, JSONObject().put("fontColor", ColourLooks.PLACEHOLDER))
                put("cover_chart", JSONObject().put("color", ColourLooks.PLACEHOLDER))
            })
            .put("colour2", JSONObject().put("day", JSONObject().put("fontColor", ColourLooks.PLACEHOLDER))))
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
     * The trend pointer. The watch gives the direction as 1 (⇊) to 7 (⇈), and 0 when there is none. Over 0 to 7
     * in eight picture steps, step 1 is the 0 alone: no pointer there. The angle at 0 is one step below ⇊.
     */
    private fun trendNeedleDynData(): JSONObject = JSONObject().put("valueKey", "direction").put("minData", 0).put("maxData", 7)
        .put("rotationOffset", JSONObject().put("minValue", trendAngle(0)).put("maxValue", trendAngle(7)).put("invalidValue", trendAngle(0).toInt()))
        .put("invalidImage", BLANK).put("image1", BLANK).apply { for (step in 2..8) put("image$step", NEEDLE) }

    private fun moonDynData(): JSONObject = JSONObject().put("valueKey", JsonKeyValues.MOON_PHASE.key)
        .put("minData", -0.5 / MOON_STEPS).put("maxData", 1 + 0.5 / MOON_STEPS).put("invalidImage", moonName(MOON_STEPS / 2 + 1))
        .apply { for (k in 1..MOON_STEPS + 1) put("image$k", moonName(if (k == MOON_STEPS + 1) 1 else k)) }

    private fun moonName(k: Int) = "Moon" + k.toString().padStart(2, '0')

    /** The angle of the glucose pointer at [mgdl], in degrees from straight up, clockwise. */
    private fun bgAngle(mgdl: Double): Double = BG_FROM + (mgdl.coerceIn(BG_MIN, BG_MAX) - BG_MIN) * (BG_TO - BG_FROM) / (BG_MAX - BG_MIN)

    /** The angle of the trend pointer at [direction] (0 none, 1 ⇊ to 7 ⇈). */
    private fun trendAngle(direction: Int): Double = TREND_DOWN + (direction - 1) * TREND_STEP

    /** Where the month mark is over [month] (1 to 12). */
    private fun monthMarkBox(month: Int): RectF {
        val x = monthX(month)
        return RectF(x - 8f, MONTH_MARK_TOP, x + 8f, MONTH_MARK_TOP + 5f)
    }

    private fun monthX(month: Int): Float = MONTH_FIRST_X + (month - 1) * MONTH_STEP

    // ------------------------------------------------------------------------------------------
    // The pictures
    // ------------------------------------------------------------------------------------------

    override fun images(): Map<String, ByteArray> = mapOf(
        ResFileMap.BACKGROUND.fileName to background(),
        ResFileMap.COVER_CHART.fileName to trendBand(),
        NEEDLE to needle(),
        MONTH_MARK to monthMark(),
        PERIOD_FRAME to periodFrame(),
        BLANK to blankPng()
    ) + (1..MOON_STEPS).associate { moonName(it) to moonPicture((it - 1).toDouble() / MOON_STEPS, MOON_BOX.width().toInt()) }

    private fun color(hex: String) = Color.parseColor(hex)
    private fun paint(hex: String, style: Paint.Style = Paint.Style.FILL, width: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(hex); this.style = style; strokeWidth = width * SCALE }

    private fun Canvas.line(from: Pair<Float, Float>, to: Pair<Float, Float>, paint: Paint) =
        drawLine(from.first * SCALE, from.second * SCALE, to.first * SCALE, to.second * SCALE, paint)

    /** A text printed on the background, its middle at [x], [y], turned by [angle] degrees. */
    private fun Canvas.label(text: String, x: Float, y: Float, size: Float, hex: String, angle: Float = 0f, align: Paint.Align = Paint.Align.CENTER) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(hex); textSize = size * SCALE; textAlign = align; typeface = font() }
        save()
        rotate(angle, x * SCALE, y * SCALE)
        drawText(text, x * SCALE, y * SCALE - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f, p)
        restore()
    }

    private fun scaled(r: RectF) = RectF(r.left * SCALE, r.top * SCALE, r.right * SCALE, r.bottom * SCALE)

    /** A dark panel with a metal edge. */
    private fun Canvas.panel(path: Path) {
        drawPath(path, paint(PANEL))
        drawPath(path, paint(EDGE, Paint.Style.STROKE, 1.6f))
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
        // The ticks round the edge
        for (i in 0 until 60) {
            val major = i % 5 == 0
            canvas.line(polar(200f, 200f, 198f, i * 6f), polar(200f, 200f, if (major) 190f else 193f, i * 6f), paint(if (major) "#7A8089" else "#41454B", width = if (major) 1.6f else 1f))
        }

        // The panels
        canvas.panel(sidePanel(TOP_PANEL_TOP, TOP_PANEL_BOTTOM, TOP_PANEL_TOP_LEFT, TOP_PANEL_BOTTOM_LEFT))
        canvas.panel(sidePanel(LOW_PANEL_TOP, LOW_PANEL_BOTTOM, LOW_PANEL_TOP_LEFT, LOW_PANEL_BOTTOM_LEFT))
        for (y in listOf(ROW_A_LINE, ROW_B_LINE)) canvas.rowLine(y, edgeX(y, TOP_PANEL_TOP, TOP_PANEL_BOTTOM, TOP_PANEL_TOP_LEFT, TOP_PANEL_BOTTOM_LEFT))
        canvas.rowLine(ROW_D_LINE, edgeX(ROW_D_LINE, LOW_PANEL_TOP, LOW_PANEL_BOTTOM, LOW_PANEL_TOP_LEFT, LOW_PANEL_BOTTOM_LEFT))
        canvas.panel(roundRect(LINE_PANEL, 12f))
        canvas.panel(roundRect(TIME_PANEL, 16f))
        canvas.panel(roundRect(MONTH_PANEL, 10f))
        // A thin light along the top of the time panel, and the line between date and time
        canvas.drawLine((TIME_PANEL.left + 16f) * s, (TIME_PANEL.top + 2.5f) * s, (TIME_PANEL.right - 16f) * s, (TIME_PANEL.top + 2.5f) * s, paint("#3A3E44", width = 1f))
        canvas.drawLine(DATE_LINE_X * s, (TIME_PANEL.top + 10f) * s, DATE_LINE_X * s, (TIME_PANEL.bottom - 10f) * s, paint(ROW_LINE, width = 1.5f))

        // The box of the loop age
        canvas.drawRoundRect(scaled(LOOP_FRAME), 3f * s, 3f * s, paint(MUTED, Paint.Style.STROKE, 1.4f))
        // The battery icons: a flash for the watch, a phone
        flash(canvas, WATCH_ICON_X, ROW_A_Y)
        phone(canvas, PHONE_ICON_X, ROW_A_Y)
        // The labels of the line under the glucose
        canvas.label("Δ", DELTA_BOX.left - 7f, LINE_PANEL.centerY(), 13f, MUTED)
        canvas.label("IOB", IOB_BOX.left - 15f, LINE_PANEL.centerY(), 12f, MUTED)
        canvas.label("BAS", BASAL_BOX.left - 15f, LINE_PANEL.centerY(), 12f, MUTED)

        glucoseScale(canvas)
        trendScale(canvas)
        months(canvas)
        // The name of the face
        canvas.label("D2", LOGO_X, LOGO_Y, 16f, "#9AA0A8")
        canvas.drawLine((LOGO_X - 22f) * s, LOGO_Y * s, (LOGO_X - 12f) * s, LOGO_Y * s, paint("#9AA0A8", width = 1.2f))
        canvas.drawLine((LOGO_X + 12f) * s, LOGO_Y * s, (LOGO_X + 22f) * s, LOGO_Y * s, paint("#9AA0A8", width = 1.2f))
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
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
                canvas.label(mmol.toString(), x, y, SCALE_LABEL_SIZE, WHITE, angle)
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
        canvas.label("BG", x, y, 14f, MUTED, mid)
    }

    /** The trend scale along the edge at the bottom left: a tick and an arrow for each of the seven directions, and the label. The band is [trendBand]. */
    private fun trendScale(canvas: Canvas) {
        for (direction in 1..7) {
            val angle = trendAngle(direction).toFloat()
            canvas.line(polar(200f, 200f, SCALE_OUTER, angle), polar(200f, 200f, SCALE_OUTER - 10f, angle), paint(WHITE, width = 1.8f))
            if (direction < 7) {
                val between = (angle + TREND_STEP / 2).toFloat()
                canvas.line(polar(200f, 200f, SCALE_OUTER, between), polar(200f, 200f, SCALE_OUTER - 5f, between), paint("#8A9099", width = 1.1f))
            }
            val (x, y) = polar(200f, 200f, SCALE_LABEL_R, angle)
            arrow(canvas, x, y, ARROW_ANGLES[direction - 1], if (direction == 1 || direction == 7) 2 else 1)
        }
        val mid = ((TREND_DOWN + trendAngle(7)) / 2).toFloat()
        val (x, y) = polar(200f, 200f, SCALE_NAME_R, mid)
        canvas.label("TREND", x, y, 13f, MUTED, mid + 180f)
    }

    /** A small arrow with its middle at [x], [y], pointing at [angle] (0 right, 90 down), with one or two heads. */
    private fun arrow(canvas: Canvas, x: Float, y: Float, angle: Float, heads: Int) {
        val s = SCALE
        val p = paint(WHITE, width = 1.6f).apply { strokeCap = Paint.Cap.ROUND }
        canvas.save()
        canvas.rotate(angle, x * s, y * s)
        canvas.drawLine((x - 6f) * s, y * s, (x + 5f) * s, y * s, p)
        for (h in 0 until heads) {
            val tip = x + 6f - h * 4f
            canvas.drawPath(Path().apply {
                moveTo(tip * s, y * s)
                lineTo((tip - 5f) * s, (y - 3.5f) * s)
                lineTo((tip - 5f) * s, (y + 3.5f) * s)
                close()
            }, paint(WHITE))
        }
        canvas.restore()
    }

    /** The row of the months: the label, the numbers and a small tick over each. The mark is [monthMark]. */
    private fun months(canvas: Canvas) {
        canvas.label("MTH", MONTH_LABEL_X, MONTH_TEXT_Y, 12f, MUTED)
        for (month in 1..12) {
            val x = monthX(month)
            canvas.drawLine((x - 5f) * SCALE, (MONTH_MARK_TOP + 2.5f) * SCALE, (x + 5f) * SCALE, (MONTH_MARK_TOP + 2.5f) * SCALE, paint("#3E4248", width = 2.5f))
            canvas.label(month.toString(), x, MONTH_TEXT_Y, 13f, "#C8CDD3")
        }
    }

    /** The band of the trend scale, white on transparent, for the view over [TREND_BAND_BOX]: the watch paints it in the main colour. */
    private fun trendBand(): ByteArray {
        val s = SCALE
        val box = TREND_BAND_BOX
        val image = createBitmap((box.width() * s).toInt(), (box.height() * s).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        canvas.translate(-box.left * s, -box.top * s)
        val p = paint(WHITE, width = 1.4f)
        var angle = trendAngle(1)
        while (angle <= trendAngle(7) + 0.01) {
            val a = angle.toFloat()
            canvas.line(polar(200f, 200f, BAND_OUTER, a), polar(200f, 200f, BAND_INNER, a), p)
            angle += TREND_STEP / 4
        }
        return pngBytes(image)
    }

    /** The pointer, on a picture of [NEEDLE_BOX]: a slim red spike from [NEEDLE_INNER] to [NEEDLE_OUTER], pointing up. */
    private fun needle(): ByteArray {
        val s = SCALE
        val size = NEEDLE_BOX.width()
        val image = createBitmap((size * s).toInt(), (size * s).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val c = size / 2f
        canvas.drawPath(Path().apply {
            moveTo((c - 3.2f) * s, (c - NEEDLE_INNER) * s)
            lineTo(c * s, (c - NEEDLE_OUTER) * s)
            lineTo((c + 3.2f) * s, (c - NEEDLE_INNER) * s)
            close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, (c - NEEDLE_OUTER) * s, 0f, (c - NEEDLE_INNER) * s, color(NEEDLE_RED), color("#B3121A"), Shader.TileMode.CLAMP)
        })
        canvas.drawLine(c * s, (c - NEEDLE_OUTER + 4f) * s, c * s, (c - NEEDLE_INNER - 2f) * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255); strokeWidth = 0.6f * s })
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
        val (viewTop, h) = centredText(PERIOD_BOX, PERIOD_SIZE, font())
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
        sample: Sample = Sample(), bgAgeMin: Long = 2, loopAgeMin: Long = 3, watch: Int? = WATCH_SAMPLE, phone: String = PHONE_SAMPLE,
        colours: Pair<String, String> = Pair(ColourLooks.AS_DESIGNED, ColourLooks.AS_DESIGNED), hours24: Boolean = true, instant: Long = PREVIEW_MS
    ): Bitmap {
        val default = defaultDesign()
        val design = design(default)
        val face = storedFace(design, default, images(), instant, null, watch)
        face.sp.putString(R.string.key_cwf_colour_1, colours.first)
        face.sp.putString(R.string.key_cwf_colour_2, colours.second)
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, if (hours24) "24" else "12")
        storeSampleData(face, sample, bgAgeMin, loopAgeMin, phone)
        Thread.sleep(600)   // past the render data share window
        face.refreshRenderData()
        val picture = face.renderToBitmap(400, 400)
        drawComplicationValues(Canvas(picture), design)
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "24")
        return picture
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
    private fun needleRedAt(bitmap: Bitmap, angle: Double, radius: Float = 166f): Int {
        val (x, y) = polar(200f, 200f, radius, angle.toFloat())
        return countNear(bitmap, RectF(x - 3f, y - 3f, x + 3f, y + 3f), NEEDLE_RED, 70)
    }

    private fun textWidth(text: String, size: Int): Float = Paint().apply { textSize = size.toFloat(); typeface = font() }.measureText(text)

    // ------------------------------------------------------------------------------------------
    // The tests
    // ------------------------------------------------------------------------------------------

    @Test
    fun buildsTheZipAndItsPreview() {
        val default = defaultDesign()
        val design = design(default)
        val json = design.toString(4)
        val images = images()
        val previewImage = preview(design, default, images, PREVIEW_MS)
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
        for (key in listOf(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1, CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2, CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3))
            assertWithMessage("$key").that(metadata[key]).isEqualTo("true")
        assertThat(loaded.cwfData.resData[FONT]?.format).isEqualTo(ResFormat.TTF)
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
        for (key in listOf("sgv", "direction", "delta", "timestamp", "loop", "iob2", "basalRate", "day_name", "day", "hour", "minute", "second",
                           "timePeriod", HR_VIEW, STEPS_VIEW, EXTRA_VIEW, MOON_VIEW, COLON_VIEW, WATCH_NUMBER_VIEW, PHONE_NUMBER_VIEW, "cover_chart"))
            insideRound(key, rectOf(design.getJSONObject(key)))
        // The month mark stays on the dial over December too
        insideRound("month mark", monthMarkBox(12))
        // The pointers turn round the middle of the dial
        for (key in listOf(BG_NEEDLE_VIEW, TREND_NEEDLE_VIEW)) {
            val r = rectOf(design.getJSONObject(key))
            assertWithMessage(key).that(r.centerX()).isEqualTo(200f)
            assertWithMessage(key).that(r.centerY()).isEqualTo(200f)
        }
    }

    @Test
    fun theTextsFitTheirViews() {
        for ((text, box, size) in listOf(
            Triple("23", HOUR_BOX, TIME_SIZE), Triple("59", MINUTE_BOX, TIME_SIZE), Triple("59", SECOND_BOX, SECOND_SIZE),
            Triple("WED", DAY_NAME_BOX, DAY_NAME_SIZE), Triple("28", DAY_BOX, DAY_SIZE), Triple("18,5", SGV_BOX, SGV_SIZE),
            Triple("100%", WATCH_NUMBER_BOX, SMALL_SIZE), Triple("100%", PHONE_NUMBER_BOX, SMALL_SIZE), Triple("+10,5", DELTA_BOX, LINE_SIZE),
            Triple("12.25U", IOB_BOX, LINE_SIZE), Triple("10.85U/h", BASAL_BOX, LINE_SIZE), Triple("PM", PERIOD_BOX, PERIOD_SIZE)
        )) assertWithMessage(text).that(textWidth(text, size)).isAtMost(box.width())
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
    fun theTrendPointerShowsTheDirection() {
        for ((arrow, direction) in listOf("⇊" to 1, "↘" to 3, "→" to 4, "↑" to 6, "⇈" to 7)) {
            val picture = render(Sample(arrow = arrow))
            assertWithMessage(arrow).that(needleRedAt(picture, trendAngle(direction))).isGreaterThan(3)
            assertWithMessage("$arrow other").that(needleRedAt(picture, trendAngle(if (direction < 4) 7 else 1))).isEqualTo(0)
        }
        // No direction: no pointer anywhere on the scale
        val none = render(Sample(arrow = "??"))
        for (direction in 0..7) assertWithMessage("none $direction").that(needleRedAt(none, trendAngle(direction))).isEqualTo(0)
    }

    @Test
    fun theMonthMarkIsOverThisMonth() {
        val picture = render()
        val month = LocalDateTime.now().monthValue
        assertThat(countNear(picture, monthMarkBox(month), NEEDLE_RED, 70)).isGreaterThan(20)
        assertThat(countNear(picture, monthMarkBox(if (month == 1) 2 else 1), NEEDLE_RED, 70)).isEqualTo(0)
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
        // The trend band takes the main colour too
        assertThat(countNear(chosen, TREND_BAND_BOX, "#FF3B3B")).isGreaterThan(20)
    }

    @Test
    fun amPmOnlyShowsWith12Hours() {
        val frame = RectF(PERIOD_BOX.left, PERIOD_BOX.top, PERIOD_BOX.right, PERIOD_BOX.bottom)
        val with24 = render(hours24 = true)
        val with12 = render(hours24 = false)
        assertThat(countNear(with24, frame, WHITE, 60)).isEqualTo(0)
        assertThat(countNear(with12, frame, WHITE, 60)).isGreaterThan(10)
    }

    @Test
    fun oldValuesAndLowBatteriesTurnRed() {
        val fresh = render()
        val old = render(bgAgeMin = 14, loopAgeMin = 20, watch = 12, phone = "9")
        for ((name, box) in listOf("age" to AGE_BOX, "loop" to LOOP_FRAME, "watch" to WATCH_NUMBER_BOX, "phone" to PHONE_NUMBER_BOX)) {
            assertWithMessage("$name fresh").that(countNear(fresh, box, LOW, 40)).isEqualTo(0)
            assertWithMessage("$name old").that(countNear(old, box, LOW, 40)).isGreaterThan(10)
        }
    }

    @Test
    fun rendersEveryLook() {
        sheet(
            listOf(
                "as designed" to render(),
                "red / blue" to render(colours = Pair("#FF3B3B", "#3D8BFF")),
                "green / white" to render(colours = Pair("#2EE66B", "#F2F2F2")),
                "12 hours, high ⇈" to render(Sample("13,9", "⇈", 250.0, 1), hours24 = false),
                "low ↘, old, batteries" to render(Sample("3,4", "↘", 61.0, -1), bgAgeMin = 14, loopAgeMin = 20, watch = 12, phone = "9"),
                "no direction" to render(Sample("6,1", "??", 110.0, 0))
            ), "${fileName}_looks.png"
        )
    }

    private companion object {

        const val FONT = "Cyber"
        const val JPEG_QUALITY = 90

        /** The colours. [COLOUR_1] and [COLOUR_2] are the colours as designed; the wearer can change them. */
        const val COLOUR_1 = "#00E5FF"
        const val COLOUR_2 = "#B6FF1A"
        const val WHITE = "#F2F2F2"
        const val MUTED = "#8A9099"
        const val ICON = "#C8CDD3"
        const val PANEL = "#0C0D0F"
        const val EDGE = "#4C5158"
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
        val LINE_PANEL = RectF(44f, 128f, 356f, 152f)
        val TIME_PANEL = RectF(18f, 158f, 382f, 244f)
        val MONTH_PANEL = RectF(36f, 250f, 364f, 274f)

        /** The top right panel: the batteries (row A), heart rate and loop (row B), the glucose (row C). */
        const val ROW_A_Y = 36f
        const val ROW_A_LINE = 49f
        const val ROW_B_LINE = 82f
        const val WATCH_ICON_X = 176f
        const val PHONE_ICON_X = 232f
        val WATCH_NUMBER_BOX = RectF(184f, 26f, 224f, 46f)
        val PHONE_NUMBER_BOX = RectF(240f, 26f, 284f, 46f)
        const val SMALL_SIZE = 18
        val HR_BOX = RectF(162f, 53f, 238f, 79f)
        val LOOP_FRAME = RectF(246f, 55f, 292f, 77f)
        val LOOP_BOX = RectF(248f, 56f, 290f, 76f)
        val ARROW_BOX = RectF(154f, 86f, 182f, 114f)
        val SGV_BOX = RectF(186f, 84f, 300f, 118f)
        const val SGV_SIZE = 40

        /** The line under the glucose. */
        val DELTA_BOX = RectF(66f, 129f, 116f, 151f)
        val AGE_BOX = RectF(116f, 129f, 146f, 151f)
        val IOB_BOX = RectF(178f, 129f, 248f, 151f)
        val BASAL_BOX = RectF(280f, 129f, 352f, 151f)
        const val LINE_SIZE = 18

        /** The time panel: the day name and the day with the moon, the time, the seconds and AM / PM. */
        const val DATE_LINE_X = 110f
        val DAY_NAME_BOX = RectF(28f, 164f, 106f, 194f)
        const val DAY_NAME_SIZE = 26
        val DAY_BOX = RectF(52f, 198f, 108f, 240f)
        const val DAY_SIZE = 38
        val MOON_BOX = RectF(26f, 207f, 50f, 231f)
        val HOUR_BOX = RectF(112f, 162f, 207f, 240f)
        val COLON_BOX = RectF(207f, 162f, 223f, 240f)
        val MINUTE_BOX = RectF(223f, 162f, 318f, 240f)
        const val TIME_SIZE = 80
        val SECOND_BOX = RectF(322f, 166f, 368f, 198f)
        const val SECOND_SIZE = 32
        val PERIOD_BOX = RectF(326f, 206f, 366f, 230f)
        const val PERIOD_SIZE = 17

        /** The months: the label, the first number and the room between two, the mark over the numbers. */
        const val MONTH_LABEL_X = 62f
        const val MONTH_FIRST_X = 96f
        const val MONTH_STEP = 22
        const val MONTH_TEXT_Y = 265f
        const val MONTH_MARK_TOP = 252f

        /** The bottom right panel: the free slot (row D) and the steps (row E), and the name. */
        const val ROW_D_LINE = 320f
        val EXTRA_BOX = RectF(156f, 288f, 330f, 316f)
        val STEPS_BOX = RectF(164f, 324f, 300f, 352f)
        const val SLOT_SIZE = 24
        const val LOGO_X = 232f
        const val LOGO_Y = 368f

        /** The scales along the edge: the ticks, the numbers or arrows, the band and the name, all round the middle of the dial. */
        const val SCALE_OUTER = 186f
        const val SCALE_LABEL_R = 164f
        const val SCALE_LABEL_SIZE = 14f
        const val BAND_OUTER = 154f
        const val BAND_INNER = 146f
        const val SCALE_NAME_R = 132f

        /** The glucose scale: 2 to 18 mmol/L, from -58 to -20 degrees. */
        const val BG_MIN = 36.0
        const val BG_MAX = 324.0
        const val BG_FROM = -58.0
        const val BG_TO = -20.0
        const val MGDL_PER_MMOL = 18.0

        /** The trend scale: ⇊ at -160 degrees to ⇈ at -122 degrees. The arrows on it, as screen angles (0 right, 90 down), ⇊ first. */
        const val TREND_DOWN = -160.0
        const val TREND_STEP = 38.0 / 6
        val ARROW_ANGLES = listOf(90f, 90f, 45f, 0f, -45f, -90f, -90f)
        val TREND_BAND_BOX = RectF(66f, 274f, 150f, 347f)

        /** The pointers: a picture of the whole dial but the edge, the spike from [NEEDLE_INNER] to [NEEDLE_OUTER]. */
        val NEEDLE_BOX = RectF(10f, 10f, 390f, 390f)
        const val NEEDLE_INNER = 138f
        const val NEEDLE_OUTER = 188f

        /** The views and their dynData. */
        const val HR_VIEW = "complication1"
        const val STEPS_VIEW = "complication2"
        const val EXTRA_VIEW = "complication3"
        const val BG_NEEDLE_VIEW = "patient_name"
        const val TREND_NEEDLE_VIEW = "freetext1"
        const val MOON_VIEW = "freetext2"
        const val MONTH_VIEW = "freetext3"
        const val COLON_VIEW = "freetext4"
        const val WATCH_NUMBER_VIEW = "rig_battery"
        const val PHONE_NUMBER_VIEW = "uploader_battery"
        const val WATCH_NUMBER_DYN = "watchBatteryNumber"
        const val PHONE_NUMBER_DYN = "phoneBatteryNumber"
        const val LOOP_DYN = "loopAge"
        const val AGE_DYN = "bgAge"
        const val MOON_DYN = "moonPhase"
        const val MONTH_DYN = "monthMark"
        const val BG_NEEDLE_DYN = "bgNeedle"
        const val TREND_NEEDLE_DYN = "trendNeedle"
        const val LOOP_LIMIT = 15.0
        const val AGE_LIMIT = 10.0

        /** The pictures. */
        const val NEEDLE = "Needle"
        const val MONTH_MARK = "MonthMark"
        const val PERIOD_FRAME = "PeriodFrame"
        const val MOON_STEPS = 16

        const val HR_SAMPLE = 72L
        const val STEPS_SAMPLE = 6375L

        /** The moment of the preview and of the moon: 13 November 2026 at 10:08:32, a waxing crescent. */
        val PREVIEW_MS: Long = LocalDateTime.of(2026, 11, 13, 10, 8, 32).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
