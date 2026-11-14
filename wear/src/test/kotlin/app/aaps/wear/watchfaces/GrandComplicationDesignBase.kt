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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.wear.watchface.complications.data.ComplicationType
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.JsonKeyValues
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.health.HealthKind
import app.aaps.wear.watchfaces.animation.BlinkSpec
import app.aaps.wear.watchfaces.daynight.DayNightClock
import app.aaps.wear.watchfaces.daynight.DayOverlay
import app.aaps.wear.watchfaces.daynight.Orbit
import app.aaps.wear.watchfaces.utils.WatchBatteryLevel
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Grand Complication": an analog dress watch, made after the grand complications of the old watch
 * houses. A guilloché dial with Roman numerals and blued (by night golden) Breguet hands, and:
 *
 * - a **glucose scale** under 12 o'clock with its own hand, like a retrograde dial: the hand turns from
 *   left (low) to right (high) over a band that is red, green and amber; the value itself big in a
 *   window under the centre, with its arrow;
 * - a **sky disc** at 9 o'clock with a horizon: the sun and the moon go round it as they really do
 *   (`sun_cycle`, `moon_cycle`): over the horizon while they are up, under it, darker, while they are
 *   down. The moon shows its real phase. A cloud or rain lies over them in that weather;
 * - the **date** in a window at 3 o'clock;
 * - the **steps** in a window at 6 o'clock (complication 2);
 * - two small **power reserve** dials for the battery of the watch and of the phone, with a hand and
 *   no number; at 20 % or less the hand is red, and it blinks while the wake-up animation plays.
 *
 * The age of the glucose value and a loop warning show in red under the window, only when old. No
 * graph, no seconds hand, no IOB and COB: the face stays calm. Two designs share all of this, a day
 * face ([CwfGrandDayDesignTest], cream and blue) and a night face ([CwfGrandNightDesignTest], midnight
 * blue and gold).
 *
 * All type is Bodoni Moda, a Didone like the big date of the Lange 1: the texts in the bold cut that
 * the zip brings ([FONT]), so the watch draws the same letters as the previews; the printing on the
 * dial in the semibold cut ([PRINT_FONT], in the dial picture only). Both are cut to Latin-1, see
 * `cwf-designs/fonts/BodoniModa-OFL.txt`. Every text is at least [MIN_TEXT] high.
 */
abstract class GrandComplicationDesignBase : CwfDesignBase() {

    /** The colours of one face. */
    data class Palette(
        /** The gold rim around the dial. */
        val case: String,
        /** The dial, and the rings of its guilloché. */
        val dial: String, val dialRing: String,
        /** The printing: numerals, scales, labels and the date. */
        val ink: String,
        /** The applied metal: the frames of the windows and the caps of the hands. */
        val gold: String,
        /** The hands of the time, the glucose and the batteries. */
        val hand: String,
        /** The inside of the windows. */
        val window: String,
        /** The glucose band and the glucose colours on the window: low, in range, high. */
        val bandLow: String, val bandMid: String, val bandHigh: String,
        val sgvLow: String, val sgvMid: String, val sgvHigh: String,
        /** The red of the old glucose value, the loop warning and the low battery. */
        val alarm: String,
        /** A battery without a value. */
        val noValue: String,
        /** The sky in clear weather (top and horizon), when it is cloudy and when it rains. */
        val skyTop: String, val skyLow: String, val skyCloudy: String, val skyRain: String,
        /** Stars on the clear sky. */
        val stars: Boolean,
        /** The land under the horizon, far and near. Drawn see-through ([LAND_ALPHA]), so the sun and the moon show darker below it. */
        val land: String, val landFar: String,
        /** The clouds and the rain. */
        val cloud: String, val cloudShade: String, val rain: String
    )

    protected abstract val palette: Palette

    /** The moment the preview of the zip shows. */
    protected abstract val previewInstant: Long

    /** The moments drawn on the render sheet `<file>_looks.png`. */
    protected abstract val looks: List<Look>

    data class Look(val name: String, val instant: Long, val weather: String?)

    private val fontFile = File("cwf-designs/fonts/$FONT.ttf")
    private val printFile = File("cwf-designs/fonts/$PRINT_FONT.ttf")

    /** The zip brings its own font, so the watch draws the same letters as these previews and as the printing on the dial. */
    override fun fonts(): Map<String, ByteArray> = mapOf(FONT to fontFile.readBytes())

    override fun valueTypeface(): Typeface = serif()

    /** The font of the texts on the watch: Bodoni Moda Bold. */
    private fun serif(): Typeface = Typeface.createFromFile(fontFile)

    /** The font of the printing on the dial picture: Bodoni Moda SemiBold, a little finer than the texts. */
    private fun printFace(): Typeface = Typeface.createFromFile(printFile)

    /**
     * Where a text view of [size] goes so that its digits and capitals stand in the middle of [box]: its
     * top and its height. Measured with the font itself, the way the watch lays the text out:
     *
     * - A TextView centres its whole line, with the room the font keeps above for accents and below for
     *   descenders (the font padding). Digits and capitals have neither, so they would stand too low: the
     *   view is moved up by the difference.
     * - A TextView only centres when the line fits in the view; otherwise it puts the text at the top. So
     *   the view is as high as the whole line, which can be higher than [box].
     */
    private fun centredText(box: RectF, size: Int): Pair<Int, Int> {
        val paint = Paint().apply { textSize = size.toFloat(); typeface = serif() }
        val line = paint.fontMetricsInt
        val height = maxOf(box.height().toInt(), line.bottom - line.top + 2)
        val glyphs = Rect().also { paint.getTextBounds("0H", 0, 2, it) }
        val baseline = (height - (line.bottom - line.top)) / 2 - line.top
        val middle = baseline + (glyphs.top + glyphs.bottom) / 2f
        return Pair(Math.round(box.centerY() - middle), height)
    }

    /** Only the steps: complication 2. */
    override fun drawComplicationValues(canvas: Canvas, design: JSONObject, type: ComplicationType) {
        drawSlot(canvas, design.getJSONObject(STEPS_VIEW), health(HealthKind.STEPS, STEPS_SAMPLE, type))
    }

    // ------------------------------------------------------------------------------------------
    // The design json
    // ------------------------------------------------------------------------------------------

    /** A text view in the font of the zip ([FONT]), its digits and capitals in the middle of [r] (see [centredText]). */
    private fun JSONObject.text(key: String, r: RectF, size: Int, gravity: String, color: String): JSONObject {
        val (top, height) = centredText(r, size)
        return place(key, r.left.toInt(), top, r.width().toInt(), height)
            .put("textsize", size).put("gravity", gravity).put("fontColor", color).put("font", FONT).put("fontStyle", "normal")
    }

    /** A view that only shows a picture (no text), placed as a square of [size] around [x], [y]. */
    private fun JSONObject.picture(key: String, x: Float, y: Float, size: Int, picture: String): JSONObject =
        place(key, (x - size / 2f).toInt(), (y - size / 2f).toInt(), size).put("textvalue", "").put("background", picture)

    override fun design(default: CwfData): JSONObject = JSONObject(default.json).apply {
        val p = palette
        getJSONObject("metadata")
            .put(CwfMetadataKey.CWF_NAME.key, displayName)
            .put(CwfMetadataKey.CWF_FILENAME.key, fileName)
            .put(CwfMetadataKey.CWF_AUTHOR.key, "AAPS (local)")
            .put(CwfMetadataKey.CWF_CREATED_AT.key, "13/11/26")
            .put(CwfMetadataKey.CWF_COMMENT.key, "A dress watch: glucose scale and window, sun and moon over a horizon, date, steps and two power reserves. No graph, no seconds")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE.key, "true")
            // The two battery hands are the delta views: they only show when these are on
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DELTA.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_AVG_DELTA.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_UPLOADER_BATTERY.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_RIG_BATTERY.key, "false")

        // No seconds: the watch draws once a minute
        put("enableSecond", false)
        put("highColor", p.sgvHigh).put("midColor", p.sgvMid).put("lowColor", p.sgvLow)

        // Every view this design does not use is off
        val used = setOf(
            "background", "cover_chart", "cover_plate", "hour_hand", "minute_hand", "sgv", "direction", "timestamp", "loop",
            "day_name", "day", STEPS_VIEW, SUN_VIEW, MOON_VIEW, SUN_CLOUD_VIEW, MOON_CLOUD_VIEW, BG_HAND_VIEW, WATCH_HAND_VIEW, PHONE_HAND_VIEW
        )
        keys().asSequence().toList().forEach { key ->
            val view = optJSONObject(key) ?: return@forEach
            if (view.has("visibility") && key !in used) view.put("visibility", "gone")
        }

        put("background", JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put("visibility", "visible"))
        place("cover_plate", 0, 0, 400)
        place("hour_hand", 0, 0, 400)
        place("minute_hand", 0, 0, 400)

        // The glucose value and its arrow in the window; the age and the loop warning under it, red, only when old
        text("sgv", SGV_BOX, SGV_SIZE, "center", p.sgvMid)
        place("direction", ARROW_BOX.left.toInt(), ARROW_BOX.top.toInt(), ARROW_BOX.width().toInt())
        text("timestamp", AGE_BOX, ALARM_SIZE, "center", p.alarm).put("dynData", AGE_DYN)
        text("loop", LOOP_BOX, ALARM_SIZE, "center", p.alarm)
            .put("textvalue", "LOOP %.0f'").put("dynValue", false).put("dynData", LOOP_DYN)

        // The date window at 3 o'clock
        text("day_name", DAY_NAME_BOX, DATE_SIZE, "center", p.ink).put("allCaps", true)
        text("day", DAY_BOX, DATE_SIZE, "center", p.ink)

        // The steps window at 6 o'clock: the slot shows the icon of the source and the number, nothing else
        place(STEPS_VIEW, STEPS_BOX.left.toInt(), STEPS_BOX.top.toInt(), STEPS_BOX.width().toInt(), STEPS_BOX.height().toInt())
            .put("color", CLEAR).put("borderRadius", 0).put("ringPrimaryColor", CLEAR).put("ringSecondaryColor", CLEAR)
            .put("fontColor", p.ink).put("iconColor", p.ink).put("textsize", STEPS_SIZE).put("font", FONT).put("fontStyle", "normal").put("gravity", "center")

        // The sky disc: the sky behind, the sun and the moon on their round, a cloud over each in that weather
        place("cover_chart", (SKY_X - SKY_R).toInt(), (SKY_Y - SKY_R).toInt(), (2 * SKY_R).toInt()).put("visibility", "visible")
        for ((view, key, size, picture) in listOf(
            Quad(SUN_VIEW, JsonKeyValues.SUN_CYCLE.key, BODY_SIZE, SUN),
            Quad(MOON_VIEW, JsonKeyValues.MOON_CYCLE.key, BODY_SIZE, moonName(MOON_STEPS / 2 + 1)),
            Quad(SUN_CLOUD_VIEW, JsonKeyValues.SUN_CYCLE.key, CLOUD_SIZE, CLOUD),
            Quad(MOON_CLOUD_VIEW, JsonKeyValues.MOON_CYCLE.key, CLOUD_SIZE, CLOUD)
        )) {
            val top = skyOrbit(key).point(0.25)
            picture(view, top.first, top.second, size, picture).put("orbit", skyOrbitJson(key))
        }
        getJSONObject(MOON_VIEW).put("dynData", MOON_DYN)

        // The glucose hand on its scale, and the two battery hands: pictures that the watch turns with the value
        picture(BG_HAND_VIEW, FAN_X, FAN_Y, BG_HAND_BOX, BG_HAND).put("rotationOffset", true).put("dynData", BG_HAND_DYN)
        picture(WATCH_HAND_VIEW, WATCH_X, BATT_Y, BATT_HAND_BOX, BATT_HAND).put("rotationOffset", true).put("dynData", WATCH_HAND_DYN)
        picture(PHONE_HAND_VIEW, PHONE_X, BATT_Y, BATT_HAND_BOX, BATT_HAND).put("rotationOffset", true).put("dynData", PHONE_HAND_DYN)

        (optJSONObject("dynData") ?: JSONObject().also { put("dynData", it) })
            .put(AGE_DYN, ageDynData("timestamp", 5.0, p.alarm))
            .put(
                LOOP_DYN, ageDynData("loop", 15.0, p.alarm).put("invalidImage", BLANK).put("image1", BLANK).put("image2", BLANK)
                    .put("textvalue1", "LOOP %.0f'").put("invalidTextvalue", "LOOP --")
            )
            .put(MOON_DYN, moonDynData())
            .put(BG_HAND_DYN, bgHandDynData())
            .put(WATCH_HAND_DYN, batteryHandDynData(WatchBatteryLevel.KEY))
            .put(PHONE_HAND_DYN, batteryHandDynData(PHONE_VALUE_KEY))

        // A low battery blinks for a few seconds after the wrist is raised; nothing else moves
        put(
            "animation", JSONObject().put("durationMs", ANIMATION_MS).put("fps", 24).put("fadeMs", 600).put(
                "blink", JSONArray()
                    .put(JSONObject().put("views", JSONArray().put(WATCH_HAND_VIEW)).put("valueKey", WatchBatteryLevel.KEY).put("atOrBelow", BATT_LOW))
                    .put(JSONObject().put("views", JSONArray().put(PHONE_HAND_VIEW)).put("valueKey", PHONE_VALUE_KEY).put("atOrBelow", BATT_LOW))
            )
        )

        // The weather: a cloud over the sun and the moon, a grey sky when it is cloudy or rains
        put(DayOverlay.WEATHER_KEY, JSONObject().apply {
            put(EventData.Weather.PARTLY, JSONObject().put(DayOverlay.IMAGES_KEY, JSONObject().put(CLOUD, CLOUD_PARTLY)))
            put(EventData.Weather.CLOUDY, JSONObject().put(DayOverlay.IMAGES_KEY, JSONObject().put(CLOUD, CLOUD_CLOUDY).put(ResFileMap.COVER_CHART.fileName, SKY_CLOUDY)))
            put(EventData.Weather.RAIN, JSONObject().put(DayOverlay.IMAGES_KEY, JSONObject().put(CLOUD, CLOUD_RAIN).put(ResFileMap.COVER_CHART.fileName, SKY_RAIN)))
        })
    }

    private data class Quad(val view: String, val key: String, val size: Int, val picture: String)

    /** The round of the sun or the moon in the sky disc: from the left horizon over the top and under the horizon back to the left. */
    protected fun skyOrbit(valueKey: String) = Orbit(valueKey, centerX = SKY_X, centerY = HORIZON_Y, radius = SKY_ORBIT, startAngle = 180f, endAngle = 540f)

    private fun skyOrbitJson(valueKey: String): JSONObject = JSONObject().put("valueKey", valueKey)
        .put("centerX", SKY_X.toDouble()).put("centerY", HORIZON_Y.toDouble()).put("radius", SKY_ORBIT.toDouble()).put("startAngle", 180).put("endAngle", 540)

    /** The angle of the glucose hand for [mgdl], in degrees from straight up, clockwise. The scale is drawn with the same function. */
    protected fun bgAngle(mgdl: Double): Float = (FAN_MIN_ANGLE + (mgdl.coerceIn(BG_MIN, BG_MAX) - BG_MIN) * (FAN_MAX_ANGLE - FAN_MIN_ANGLE) / (BG_MAX - BG_MIN)).toFloat()

    /** The glucose hand: turned from [FAN_MIN_ANGLE] (at [BG_MIN] or lower) to [FAN_MAX_ANGLE] (at [BG_MAX] or higher). Without a value no hand. */
    private fun bgHandDynData(): JSONObject = JSONObject().put("valueKey", "sgv").put("minData", BG_MIN).put("maxData", BG_MAX)
        .put("rotationOffset", JSONObject().put("minValue", FAN_MIN_ANGLE).put("maxValue", FAN_MAX_ANGLE).put("invalidValue", FAN_MIN_ANGLE))
        .put("invalidImage", BLANK).put("image1", BG_HAND)

    /** The angle of a battery hand at [percent], from straight up, clockwise. */
    protected fun batteryAngle(percent: Double): Float =
        (BATT_MIN_ANGLE + (percent.coerceIn(1.0, 101.0) - 1.0) * (BATT_MAX_ANGLE - BATT_MIN_ANGLE) / 100.0).toFloat()

    /**
     * A battery hand: turned with the level, red at 20 % or less, grey at the empty end without a value.
     * Five colour steps over 1 to 101 (as the battery icons of the Cyberpunk designs), so the first step
     * ends exactly at 20 %.
     */
    private fun batteryHandDynData(valueKey: String): JSONObject = JSONObject().put("valueKey", valueKey).put("minData", 1).put("maxData", 101)
        .put("rotationOffset", JSONObject().put("minValue", BATT_MIN_ANGLE).put("maxValue", BATT_MAX_ANGLE).put("invalidValue", BATT_MIN_ANGLE))
        .put("invalidColor", palette.noValue).put("color1", palette.alarm)
        .apply { for (step in 2..5) put("color$step", palette.hand) }

    /**
     * The moon shows the picture nearest to its phase. Picture k shows the phase (k - 1) / [MOON_STEPS]; the
     * last one, around the next new moon, is the new moon again.
     */
    private fun moonDynData(): JSONObject = JSONObject().put("valueKey", JsonKeyValues.MOON_PHASE.key)
        .put("minData", -0.5 / MOON_STEPS).put("maxData", 1 + 0.5 / MOON_STEPS).put("invalidImage", moonName(MOON_STEPS / 2 + 1))
        .apply { for (k in 1..MOON_STEPS + 1) put("image$k", moonName(if (k == MOON_STEPS + 1) 1 else k)) }

    protected fun moonName(k: Int) = "Moon" + k.toString().padStart(2, '0')

    // ------------------------------------------------------------------------------------------
    // The pictures
    // ------------------------------------------------------------------------------------------

    override fun images(): Map<String, ByteArray> = mapOf(
        ResFileMap.BACKGROUND.fileName to dial(),
        ResFileMap.COVER_PLATE.fileName to coverPlate(),
        ResFileMap.COVER_CHART.fileName to sky(palette.skyTop, palette.skyLow, palette.stars),
        SKY_CLOUDY to sky(palette.skyCloudy, palette.skyCloudy, false),
        SKY_RAIN to sky(palette.skyRain, palette.skyRain, false),
        ResFileMap.HOUR_HAND.fileName to breguetHand(HOUR_LENGTH, 7.5f),
        ResFileMap.MINUTE_HAND.fileName to breguetHand(MINUTE_LENGTH, 5.5f),
        BG_HAND to gaugeHand(BG_HAND_BOX, BG_HAND_LENGTH, 2f, palette.hand),
        BATT_HAND to gaugeHand(BATT_HAND_BOX, BATT_HAND_LENGTH, 1.8f, "#FFFFFF"),
        SUN to sunPicture(),
        CLOUD to blankPng(),
        CLOUD_PARTLY to cloudPicture(CloudKind.PARTLY),
        CLOUD_CLOUDY to cloudPicture(CloudKind.CLOUDY),
        CLOUD_RAIN to cloudPicture(CloudKind.RAIN),
        BLANK to blankPng()
    ) + (1..MOON_STEPS).associate { moonName(it) to moonPicture((it - 1).toDouble() / MOON_STEPS) } + arrowImages()

    private fun color(hex: String) = Color.parseColor(hex)
    private fun paint(hex: String, style: Paint.Style = Paint.Style.FILL, width: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(hex); this.style = style; strokeWidth = width * SCALE }

    protected fun blankPng(): ByteArray = pngBytes(createBitmap(1, 1).apply { eraseColor(Color.TRANSPARENT) })

    /** A point at [radius] and [angle] (degrees from straight up, clockwise) around [cx], [cy], in the design space. */
    private fun polar(cx: Float, cy: Float, radius: Float, angle: Float): Pair<Float, Float> {
        val a = Math.toRadians(angle.toDouble())
        return Pair(cx + radius * sin(a).toFloat(), cy - radius * cos(a).toFloat())
    }

    /** An arc in design units from [from] to [to] degrees (from straight up, clockwise). */
    private fun Canvas.arc(cx: Float, cy: Float, radius: Float, from: Float, to: Float, paint: Paint) {
        val s = SCALE
        drawArc(RectF((cx - radius) * s, (cy - radius) * s, (cx + radius) * s, (cy + radius) * s), from - 90f, to - from, false, paint)
    }

    private fun Canvas.label(text: String, x: Float, y: Float, size: Float, hex: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(hex); textSize = size * SCALE; textAlign = Paint.Align.CENTER; typeface = printFace() }
        drawText(text, x * SCALE, (y - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f / SCALE) * SCALE, p)
    }

    /** A window with a gold frame, in design units. */
    private fun Canvas.window(r: RectF) {
        val s = SCALE
        val box = RectF(r.left * s, r.top * s, r.right * s, r.bottom * s)
        drawRoundRect(box, 3f * s, 3f * s, paint(palette.window))
        // A shadow along the top inside, as if the window lies below the dial
        drawRect(RectF(box.left, box.top, box.right, box.top + 3f * s), Paint().apply { color = Color.argb(40, 0, 0, 0) })
        drawRoundRect(box, 3f * s, 3f * s, paint(palette.gold, Paint.Style.STROKE, 2.5f))
    }

    /**
     * The dial, 800 x 800, as jpg: the gold rim, the guilloché, the minute track, the numerals, the
     * glucose scale, the battery scales and the windows. All that does not move.
     */
    private fun dial(): ByteArray {
        val p = palette
        val s = SCALE
        val image = createBitmap(800, 800).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(image)
        val c = 200f * s
        // The rim: a gold ring with light from the top left
        canvas.drawCircle(c, c, 200f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 800f, 800f, intArrayOf(lighter(p.case, 0.35f), color(p.case), darker(p.case, 0.35f)), null, Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(c, c, DIAL_R * s, paint(p.dial))
        // Guilloché: fine rings, light and dark in turn, and a soft light from the top
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * s }
        var r = 4f
        var k = 0
        while (r < TRACK_INNER - 2f) {
            ring.color = if (k % 2 == 0) color(p.dialRing) else lighter(p.dial, 0.06f)
            canvas.drawCircle(c, c, r * s, ring)
            r += 2.6f
            k++
        }
        canvas.drawCircle(c, c, DIAL_R * s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(c * 0.7f, c * 0.55f, DIAL_R * s * 1.3f, Color.argb(36, 255, 255, 255), Color.argb(30, 0, 0, 0), Shader.TileMode.CLAMP)
        })
        // The minute track
        canvas.drawCircle(c, c, TRACK_INNER * s, paint(p.ink, Paint.Style.STROKE, 0.8f))
        canvas.drawCircle(c, c, TRACK_OUTER * s, paint(p.ink, Paint.Style.STROKE, 0.8f))
        for (i in 0 until 60) {
            val (x1, y1) = polar(200f, 200f, TRACK_OUTER, i * 6f)
            val (x2, y2) = polar(200f, 200f, if (i % 5 == 0) TRACK_INNER - 4f else TRACK_INNER, i * 6f)
            canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, paint(p.ink, width = if (i % 5 == 0) 2.4f else 1.1f).apply { strokeCap = Paint.Cap.ROUND })
        }
        // Roman numerals where nothing else is
        for ((text, hour) in listOf("XII" to 0, "I" to 1, "II" to 2, "X" to 10, "XI" to 11)) {
            val (x, y) = polar(200f, 200f, NUMERAL_R, hour * 30f)
            canvas.label(text, x, y, NUMERAL_SIZE, p.ink)
        }
        glucoseScale(canvas)
        batteryScale(canvas, WATCH_X, BatteryKind.WATCH)
        batteryScale(canvas, PHONE_X, BatteryKind.PHONE)
        // The windows
        canvas.window(BG_WINDOW)
        canvas.window(DATE_WINDOW)
        canvas.window(STEPS_WINDOW)
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    }

    /** The glucose scale: a thin arc, the band in three colours, ticks at 1 mmol/L and the numbers 4, 7, 10 and 15. */
    private fun glucoseScale(canvas: Canvas) {
        val p = palette
        val s = SCALE
        canvas.arc(FAN_X, FAN_Y, FAN_R, FAN_MIN_ANGLE, FAN_MAX_ANGLE, paint(p.ink, Paint.Style.STROKE, 1.2f))
        for ((from, to, hex) in listOf(Triple(BG_MIN, BG_LOW, p.bandLow), Triple(BG_LOW, BG_HIGH, p.bandMid), Triple(BG_HIGH, BG_MAX, p.bandHigh)))
            canvas.arc(FAN_X, FAN_Y, BAND_R, bgAngle(from), bgAngle(to), paint(hex, Paint.Style.STROKE, BAND_WIDTH).apply { strokeCap = Paint.Cap.BUTT })
        var mmol = 3.0
        while (mmol * MGDL_PER_MMOL <= BG_MAX) {
            val angle = bgAngle(mmol * MGDL_PER_MMOL)
            val major = mmol in LABELS
            val (x1, y1) = polar(FAN_X, FAN_Y, FAN_R, angle)
            val (x2, y2) = polar(FAN_X, FAN_Y, FAN_R + if (major) 8f else 4f, angle)
            canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, paint(p.ink, width = if (major) 2f else 1f))
            if (major) {
                val (x, y) = polar(FAN_X, FAN_Y, FAN_LABEL_R, angle)
                canvas.label(mmol.toInt().toString(), x, y, FAN_LABEL_SIZE, p.ink)
            }
            mmol += 1.0
        }
    }

    private enum class BatteryKind { WATCH, PHONE }

    /** A power reserve dial: a thin arc, a red part up to 20 %, ticks every 25 %, and a small picture of a watch or a phone under the pivot. */
    private fun batteryScale(canvas: Canvas, cx: Float, kind: BatteryKind) {
        val p = palette
        val s = SCALE
        canvas.arc(cx, BATT_Y, BATT_R, BATT_MIN_ANGLE, BATT_MAX_ANGLE, paint(p.ink, Paint.Style.STROKE, 1.2f))
        canvas.arc(cx, BATT_Y, BATT_R - 4f, batteryAngle(1.0), batteryAngle(BATT_LOW), paint(p.bandLow, Paint.Style.STROKE, 5f).apply { strokeCap = Paint.Cap.BUTT })
        for (percent in listOf(1.0, 26.0, 51.0, 76.0, 101.0)) {
            val angle = batteryAngle(percent)
            val (x1, y1) = polar(cx, BATT_Y, BATT_R, angle)
            val (x2, y2) = polar(cx, BATT_Y, BATT_R + 6f, angle)
            canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, paint(p.ink, width = 2f))
        }
        val icon = paint(p.ink, Paint.Style.STROKE, 2f)
        val top = BATT_Y + 9f
        when (kind) {
            BatteryKind.WATCH -> {
                // A watch: the case, the strap above and below
                canvas.drawRoundRect(RectF((cx - 4f) * s, top * s, (cx + 4f) * s, (top + 20f) * s), 1.5f * s, 1.5f * s, paint(p.ink))
                canvas.drawCircle(cx * s, (top + 10f) * s, 7f * s, paint(p.window))
                canvas.drawCircle(cx * s, (top + 10f) * s, 7f * s, icon)
            }

            BatteryKind.PHONE -> {
                canvas.drawRoundRect(RectF((cx - 6f) * s, top * s, (cx + 6f) * s, (top + 20f) * s), 2.5f * s, 2.5f * s, icon)
                canvas.drawLine((cx - 2.5f) * s, (top + 16.5f) * s, (cx + 2.5f) * s, (top + 16.5f) * s, icon)
            }
        }
    }

    /**
     * The cover plate, above all values: the land of the sky disc, a little see-through (so the sun and
     * the moon show darker below the horizon), the frame of the disc, and the caps over the pivots of the
     * small hands.
     */
    private fun coverPlate(): ByteArray {
        val p = palette
        val s = SCALE
        val image = createBitmap(800, 800).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val disc = Path().apply { addCircle(SKY_X * s, SKY_Y * s, SKY_R * s, Path.Direction.CW) }
        // The land in one layer, laid on with one alpha: where the hills overlap it is not darker
        canvas.saveLayerAlpha(RectF((SKY_X - SKY_R) * s, (SKY_Y - SKY_R) * s, (SKY_X + SKY_R) * s, (SKY_Y + SKY_R) * s), LAND_ALPHA)
        canvas.clipPath(disc)
        // Far hills at the horizon, then the land in front
        fun hills(base: Float, amplitude: Float, waves: Float, phase: Float, hex: String) {
            val path = Path().apply {
                moveTo((SKY_X - SKY_R) * s, (SKY_Y + SKY_R) * s)
                var x = SKY_X - SKY_R
                while (x <= SKY_X + SKY_R) {
                    lineTo(x * s, (base - amplitude * sin(((x - SKY_X) / (2 * SKY_R) * waves + phase) * 2 * PI).toFloat()) * s)
                    x += 1f
                }
                lineTo((SKY_X + SKY_R) * s, (SKY_Y + SKY_R) * s)
                close()
            }
            canvas.drawPath(path, paint(hex))
        }
        hills(HORIZON_Y - 1f, 2.5f, 1.4f, 0.1f, p.landFar)
        hills(HORIZON_Y + 1.5f, 1.5f, 0.9f, 0.6f, p.land)
        canvas.restore()
        // The frame of the disc
        canvas.drawCircle(SKY_X * s, SKY_Y * s, SKY_R * s, paint(p.gold, Paint.Style.STROKE, 3f))
        // Caps on the pivots of the glucose and battery hands
        for ((x, y) in listOf(FAN_X to FAN_Y, WATCH_X to BATT_Y, PHONE_X to BATT_Y)) {
            canvas.drawCircle(x * s, y * s, 4.5f * s, paint(p.gold))
            canvas.drawCircle(x * s, y * s, 4.5f * s, paint(shade(p.gold, 0.3f), Paint.Style.STROKE, 0.8f))
        }
        return pngBytes(image)
    }

    /** The sky of the disc in one weather: [top] to [low] at the horizon, with a few stars when [stars]. 2 x the disc. */
    private fun sky(top: String, low: String, stars: Boolean): ByteArray {
        val size = (2 * SKY_R * SCALE).toInt()
        val image = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val horizon = (HORIZON_Y - SKY_Y + SKY_R) * SCALE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, horizon, color(top), color(low), Shader.TileMode.CLAMP)
        })
        if (stars) {
            val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("#FFF3C4") }
            for ((x, y, r) in listOf(Triple(0.30f, 0.22f, 1.1f), Triple(0.62f, 0.16f, 0.9f), Triple(0.78f, 0.34f, 1.2f), Triple(0.18f, 0.42f, 0.8f), Triple(0.48f, 0.30f, 0.7f), Triple(0.86f, 0.50f, 0.8f), Triple(0.40f, 0.10f, 0.8f)))
                canvas.drawCircle(x * size, y * size, r * SCALE, star)
        }
        return pngBytes(image)
    }

    /** The sun: a golden disc with a soft glow, [BODY_SIZE] at 2 x. */
    private fun sunPicture(): ByteArray {
        val size = (BODY_SIZE * SCALE).toInt()
        val image = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val c = size / 2f
        canvas.drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(c, c, c, intArrayOf(Color.argb(160, 255, 214, 102), Color.argb(0, 255, 214, 102)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(c, c, c * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("#FFC93A") })
        canvas.drawCircle(c, c, c * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("#C98A00"); style = Paint.Style.STROKE; strokeWidth = 1.2f })
        return pngBytes(image)
    }

    /**
     * The moon at [phase] (0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter): the lit part in pale
     * gold, lit on the right while it grows (as seen from the north). The dark part is only a faint disc,
     * so the moon can be found even at new moon. [BODY_SIZE] at 2 x.
     */
    private fun moonPicture(phase: Double): ByteArray {
        val size = (BODY_SIZE * SCALE).toInt()
        val image = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val c = size / 2f
        val r = c * 0.7f
        canvas.drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 241, 230, 192) })
        val k = cos(2 * PI * phase).toFloat()
        val waxing = phase < 0.5
        val lit = Path()
        val steps = 40
        // The lit edge: half the rim on the lit side, from the top to the bottom
        for (i in 0..steps) {
            val a = -PI / 2 + PI * i / steps
            val x = r * cos(a).toFloat()
            val y = r * sin(a).toFloat()
            val px = if (waxing) c + x else c - x
            if (i == 0) lit.moveTo(px, c + y) else lit.lineTo(px, c + y)
        }
        // Back along the shadow line, an ellipse, from the bottom to the top
        for (i in 0..steps) {
            val y = r - 2 * r * i / steps
            val half = k * sqrt((r * r - y * y).coerceAtLeast(0f))
            lit.lineTo(if (waxing) c + half else c - half, c + y)
        }
        lit.close()
        canvas.drawPath(lit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("#F1E6C0") })
        return pngBytes(image)
    }

    private enum class CloudKind { PARTLY, CLOUDY, RAIN }

    /** A cloud over the sun or the moon: small and low on the right (partly), large (cloudy), large with drops under it (rain). [CLOUD_SIZE] at 2 x. */
    private fun cloudPicture(kind: CloudKind): ByteArray {
        val p = palette
        val size = (CLOUD_SIZE * SCALE).toInt()
        val image = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val u = size / 40f
        fun cloud(left: Float, top: Float, scale: Float) {
            val shade = paint(p.cloudShade)
            val fill = paint(p.cloud)
            for ((paintOf, dy) in listOf(shade to 1.2f, fill to 0f)) {
                for ((x, y, rr) in listOf(Triple(5f, 9f, 5f), Triple(12f, 5f, 7f), Triple(20f, 8f, 5.5f), Triple(12.5f, 10f, 5.5f)))
                    canvas.drawCircle((left + x * scale) * u, (top + (y + dy) * scale) * u, rr * scale * u, paintOf)
                canvas.drawRoundRect(RectF((left + 1f * scale) * u, (top + (9f + dy) * scale) * u, (left + 24f * scale) * u, (top + (15f + dy) * scale) * u), 3f * u, 3f * u, paintOf)
            }
        }
        when (kind) {
            CloudKind.PARTLY -> cloud(15f, 17f, 0.95f)
            CloudKind.CLOUDY -> cloud(3f, 7f, 1.4f)
            CloudKind.RAIN   -> {
                cloud(3f, 5f, 1.4f)
                val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(p.rain); strokeWidth = 1.6f * u; strokeCap = Paint.Cap.ROUND }
                for (i in 0 until 5) {
                    val x = 10f + i * 5f
                    val y = 29f + (i % 2) * 3f
                    canvas.drawLine(x * u, y * u, (x - 1.2f) * u, (y + 5f) * u, drop)
                }
            }
        }
        return pngBytes(image)
    }

    /**
     * A Breguet hand, 800 x 800, from the centre up: a slim body, the open ring ("pomme") near the tip,
     * a pointed tip and the round gold cap in the centre.
     */
    private fun breguetHand(length: Float, width: Float): ByteArray {
        val p = palette
        val s = SCALE
        val image = createBitmap(800, 800).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val c = 200f
        val fill = paint(p.hand)
        val ringY = c - length + 30f
        val ringR = width + 1.5f
        val body = Path().apply {
            moveTo((c - width / 3f) * s, (c + 18f) * s)
            lineTo((c - width / 3f) * s, (ringY + ringR) * s)
            lineTo((c + width / 3f) * s, (ringY + ringR) * s)
            lineTo((c + width / 3f) * s, (c + 18f) * s)
            close()
            moveTo((c - width / 3.2f) * s, (ringY - ringR) * s)
            lineTo(c * s, (c - length) * s)
            lineTo((c + width / 3.2f) * s, (ringY - ringR) * s)
            close()
        }
        canvas.drawPath(body, fill)
        canvas.drawCircle(c * s, ringY * s, ringR * s, paint(p.hand, Paint.Style.STROKE, 2.6f))
        // A thin line of light along the hand
        canvas.drawLine(c * s, (c + 10f) * s, c * s, (ringY + ringR) * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255); strokeWidth = 0.7f * s })
        canvas.drawCircle(c * s, c * s, 7f * s, paint(p.gold))
        canvas.drawCircle(c * s, c * s, 7f * s, paint(shade(p.gold, 0.3f), Paint.Style.STROKE, 0.8f))
        canvas.drawCircle(c * s, c * s, 2f * s, paint(shade(p.gold, 0.4f)))
        return pngBytes(image)
    }

    /** A thin pointed hand from the middle of a square of [box] up to [length], with a short tail, in [hex]. */
    private fun gaugeHand(box: Int, length: Float, width: Float, hex: String): ByteArray {
        val s = SCALE
        val image = createBitmap((box * s).toInt(), (box * s).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val c = box / 2f
        val hand = Path().apply {
            moveTo((c - width) * s, (c + 8f) * s)
            lineTo((c - width * 0.6f) * s, (c - length + 10f) * s)
            lineTo(c * s, (c - length) * s)
            lineTo((c + width * 0.6f) * s, (c - length + 10f) * s)
            lineTo((c + width) * s, (c + 8f) * s)
            close()
        }
        canvas.drawPath(hand, paint(hex))
        return pngBytes(image)
    }

    /**
     * The arrows of the glucose value: a slim shaft with a classic head, white, so the watch colours them
     * like the value. Double arrows have two heads.
     */
    private fun arrowImages(): Map<String, ByteArray> = mapOf(
        ResFileMap.ARROW_NONE.fileName to blankPng(),
        ResFileMap.ARROW_DOUBLE_UP.fileName to arrow(-90f, 2),
        ResFileMap.ARROW_SINGLE_UP.fileName to arrow(-90f, 1),
        ResFileMap.ARROW_FORTY_FIVE_UP.fileName to arrow(-45f, 1),
        ResFileMap.ARROW_FLAT.fileName to arrow(0f, 1),
        ResFileMap.ARROW_FORTY_FIVE_DOWN.fileName to arrow(45f, 1),
        ResFileMap.ARROW_SINGLE_DOWN.fileName to arrow(90f, 1),
        ResFileMap.ARROW_DOUBLE_DOWN.fileName to arrow(90f, 2)
    )

    private fun arrow(angle: Float, heads: Int): ByteArray {
        val size = 120
        val image = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND }
        canvas.rotate(angle, size / 2f, size / 2f)
        canvas.drawLine(18f, 60f, 92f, 60f, white)
        for (h in 0 until heads) {
            val tip = 104f - h * 26f
            canvas.drawPath(Path().apply {
                moveTo(tip, 60f)
                lineTo(tip - 34f, 40f)
                lineTo(tip - 26f, 60f)
                lineTo(tip - 34f, 80f)
                close()
            }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        return pngBytes(image)
    }

    private fun lighter(hex: String, amount: Float): Int {
        val c = color(hex)
        fun up(v: Int) = (v + (255 - v) * amount).toInt()
        return Color.rgb(up(Color.red(c)), up(Color.green(c)), up(Color.blue(c)))
    }

    private fun darker(hex: String, amount: Float): Int {
        val c = color(hex)
        fun down(v: Int) = (v * (1 - amount)).toInt()
        return Color.rgb(down(Color.red(c)), down(Color.green(c)), down(Color.blue(c)))
    }

    /** [hex] made darker by [amount], as a hex colour. */
    private fun shade(hex: String, amount: Float): String = String.format("#%06X", darker(hex, amount) and 0xFFFFFF)

    // ------------------------------------------------------------------------------------------
    // Rendering helpers of the tests
    // ------------------------------------------------------------------------------------------

    private fun render(instant: Long, weather: String? = null, sample: Sample = Sample(), bgAgeMin: Long = 2, loopAgeMin: Long = 3, watch: Int? = WATCH_SAMPLE, phone: String = PHONE_SAMPLE): Bitmap {
        val default = defaultDesign()
        val design = design(default)
        val face = storedFace(design, default, images(), instant, weather, watch)
        storeSampleData(face, sample, bgAgeMin, loopAgeMin, phone)
        Thread.sleep(600)   // past the render data share window
        face.refreshRenderData()
        val picture = face.renderToBitmap(400, 400)
        drawComplicationValues(Canvas(picture), lookOf(design, weather))
        return picture
    }

    private fun hex(pixel: Int) = String.format("#%06X", pixel and 0xFFFFFF)

    private fun near(pixel: Int, hex: String, tolerance: Int = 40): Boolean {
        val c = color(hex)
        return abs(Color.red(pixel) - Color.red(c)) <= tolerance && abs(Color.green(pixel) - Color.green(c)) <= tolerance && abs(Color.blue(pixel) - Color.blue(c)) <= tolerance
    }

    private fun countNear(bitmap: Bitmap, point: Pair<Float, Float>, half: Int, hex: String, tolerance: Int = 40) = around(bitmap, point, half).count { near(it, hex, tolerance) }

    private fun textWidth(text: String, size: Int): Float = Paint().apply { textSize = size.toFloat(); typeface = serif() }.measureText(text)

    // ------------------------------------------------------------------------------------------
    // The tests
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
        println("$fileName.zip: ${zip.size} bytes")
        assertThat(zip.size).isAtMost(MAX_ZIP_BYTES)
        // No picture ends with "Day": the watch would show it in the place of another one
        for (name in images.keys) assertWithMessage(name).that(name.endsWith(DayOverlay.IMAGE_SUFFIX)).isFalse()

        val loaded = ZipWatchfaceFormat.loadCustomWatchface(zip, "$fileName.zip", authorization = true)
        assertThat(loaded).isNotNull()
        val metadata = loaded!!.cwfData.metadata
        assertThat(metadata[CwfMetadataKey.CWF_NAME]).isEqualTo(displayName)
        assertThat(metadata[CwfMetadataKey.CWF_FILENAME]).isEqualTo(fileName)
        assertThat(metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2]).isEqualTo("true")
        for (key in listOf(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1, CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3, CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4, CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5))
            assertWithMessage("$key").that(metadata[key]).isEqualTo("false")
        for (key in listOf(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DELTA, CwfMetadataKey.CWF_PREF_WATCH_SHOW_AVG_DELTA, CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE))
            assertWithMessage("$key").that(metadata[key]).isEqualTo("true")
        // The font is in the zip, and every text uses it
        assertThat(loaded.cwfData.resData[FONT]?.format).isEqualTo(ResFormat.TTF)
        for (key in listOf("sgv", "timestamp", "loop", "day_name", "day", STEPS_VIEW)) assertWithMessage(key).that(design.getJSONObject(key).getString("font")).isEqualTo(FONT)
        // Every picture a view, a dynData or a weather look names is in the zip
        assertThat(loaded.cwfData.resData.keys).containsAtLeastElementsIn(images.keys)
        design.getJSONObject(DayOverlay.WEATHER_KEY).let { weatherLooks ->
            weatherLooks.keys().forEach { weather ->
                weatherLooks.getJSONObject(weather).getJSONObject(DayOverlay.IMAGES_KEY).let { swaps ->
                    swaps.keys().forEach { assertWithMessage("$weather $it").that(images.keys).containsAtLeast(it, swaps.getString(it)) }
                }
            }
        }

        // The copy kept in the repository is this design
        val kept = File("cwf-designs/$fileName.zip")
        assertWithMessage("$kept").that(kept.exists()).isTrue()
        assertThat(JSONObject(jsonInZip(kept.readBytes())).toString(4)).isEqualTo(json)
    }

    @Test
    fun everythingStaysOnTheDialAndApart() {
        val design = design(defaultDesign())
        // Every visible view that does not move or turn lies on the dial, inside the minute track
        val still = listOf("sgv", "direction", "timestamp", "loop", "day_name", "day", STEPS_VIEW, "cover_chart")
        for (key in still) {
            val r = rectOf(design.getJSONObject(key))
            for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom))
                assertWithMessage(key).that(hypot(x - 200f, y - 200f)).isAtMost(TRACK_INNER)
        }
        // The turning hands stay inside their own square, so the watch never cuts them
        for ((key, length) in listOf(BG_HAND_VIEW to BG_HAND_LENGTH, WATCH_HAND_VIEW to BATT_HAND_LENGTH, PHONE_HAND_VIEW to BATT_HAND_LENGTH))
            assertWithMessage(key).that(design.getJSONObject(key).getInt("width") / 2f).isAtLeast(length)
        // The sun, the moon and their clouds stay in the sky disc all around
        for (key in listOf(SUN_VIEW, MOON_VIEW, SUN_CLOUD_VIEW, MOON_CLOUD_VIEW)) {
            val view = design.getJSONObject(key)
            val orbit = Orbit.read(view)!!
            val half = if (key == SUN_VIEW || key == MOON_VIEW) BODY_SIZE * 0.35f else 0f
            var v = 0.0
            while (v <= 1.0) {
                val (x, y) = orbit.point(v)
                assertWithMessage("$key at $v").that(hypot(x - SKY_X, y - SKY_Y) + half).isAtMost(SKY_R)
                v += 0.05
            }
        }
        // The sky disc is round: no window or scale reaches into it
        val boxes = mapOf(
            "glucose window" to BG_WINDOW, "date window" to DATE_WINDOW, "watch battery" to batteryBox(WATCH_X),
            "glucose scale" to scaleBox()
        )
        for ((name, r) in boxes) {
            val nearX = SKY_X.coerceIn(r.left, r.right)
            val nearY = SKY_Y.coerceIn(r.top, r.bottom)
            assertWithMessage("sky / $name").that(hypot(nearX - SKY_X, nearY - SKY_Y)).isGreaterThan(SKY_R + 3f)
        }
        // The windows and the scales do not touch each other
        val blocks = mapOf(
            "glucose window" to BG_WINDOW, "age row" to RectF(AGE_BOX).apply { union(LOOP_BOX) }, "date window" to DATE_WINDOW,
            "steps window" to STEPS_WINDOW,
            "glucose scale" to scaleBox(),
            "watch battery" to batteryBox(WATCH_X), "phone battery" to batteryBox(PHONE_X)
        )
        val names = blocks.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            val a = blocks.getValue(names[i])
            val b = blocks.getValue(names[j])
            assertWithMessage("${names[i]} / ${names[j]}").that(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top).isTrue()
        }
        // The values sit inside their windows: side to side, and with their middle. (A text view may reach a
        // little above its window: it is moved up so its digits stand in the middle, see centredText.)
        for ((key, window) in listOf("sgv" to BG_WINDOW, "direction" to BG_WINDOW, "day_name" to DATE_WINDOW, "day" to DATE_WINDOW, STEPS_VIEW to STEPS_WINDOW)) {
            val r = rectOf(design.getJSONObject(key))
            assertWithMessage(key).that(r.left >= window.left && r.right <= window.right && window.contains(r.centerX(), r.centerY())).isTrue()
        }
    }

    /** The glucose scale with its numbers: a fan of 60 degrees to each side, so its lowest points are the two ends. */
    private fun scaleBox(): RectF {
        val reach = FAN_LABEL_R + 12f
        val side = reach * sin(Math.toRadians(FAN_MAX_ANGLE.toDouble())).toFloat()
        val bottom = FAN_Y - FAN_LABEL_R * cos(Math.toRadians(FAN_MAX_ANGLE.toDouble())).toFloat() + 12f
        return RectF(FAN_X - side, FAN_Y - reach, FAN_X + side, bottom)
    }

    /** A power reserve with its ticks (a fan of 50 degrees to each side) and the picture under the pivot. */
    private fun batteryBox(cx: Float): RectF {
        val side = (BATT_R + 6f) * sin(Math.toRadians(BATT_MAX_ANGLE.toDouble())).toFloat() + 1f
        return RectF(cx - side, BATT_Y - BATT_R - 7f, cx + side, BATT_Y + 30f)
    }

    @Test
    fun theTextsAreBigAndFitTheirViews() {
        val design = design(defaultDesign())
        // Every text is at least MIN_TEXT high, and its view is high enough that nothing is cut
        for (key in listOf("sgv", "timestamp", "loop", "day_name", "day")) {
            val view = design.getJSONObject(key)
            assertWithMessage(key).that(view.getInt("textsize")).isAtLeast(MIN_TEXT)
            assertWithMessage("$key height").that(view.getInt("height").toFloat()).isAtLeast(view.getInt("textsize") * 1.15f)
        }
        assertThat(design.getJSONObject(STEPS_VIEW).getInt("textsize")).isAtLeast(MIN_TEXT)
        assertThat(FAN_LABEL_SIZE).isAtLeast(MIN_TEXT.toFloat())
        // The widest texts fit: a high glucose value in mmol/L and in mg/dL, the widest day, the loop warning
        for (value in listOf("22,2", "18,8", "400")) assertWithMessage(value).that(textWidth(value, SGV_SIZE)).isAtMost(SGV_BOX.width())
        for (day in listOf("WED", "THU", "MON")) assertWithMessage(day).that(textWidth(day, DATE_SIZE)).isAtMost(DAY_NAME_BOX.width())
        assertThat(textWidth("30", DATE_SIZE)).isAtMost(DAY_BOX.width())
        assertThat(textWidth("LOOP 99'", ALARM_SIZE)).isAtMost(LOOP_BOX.width())
        assertThat(textWidth("99'", ALARM_SIZE)).isAtMost(AGE_BOX.width())
    }

    @Test
    fun theGlucoseAndTheDateAreCentred() {
        // The arrow picture is drawn from 18 to 104 of 120: so much of the arrow view it covers
        val arrowLeft = ARROW_BOX.left + ARROW_BOX.width() * 18f / 120f
        val arrowRight = ARROW_BOX.left + ARROW_BOX.width() * 104f / 120f
        val gap = arrowLeft - SGV_BOX.right
        for (value in listOf("45", "3,9", "6,7", "120", "10,4", "22,2", "400")) {
            val width = textWidth(value, SGV_SIZE)
            val left = SGV_BOX.centerX() - width / 2f
            val middle = (left + arrowRight) / 2f
            assertWithMessage("$value and arrow: middle $middle").that(abs(middle - BG_WINDOW.centerX())).isAtMost(CENTRE_TOLERANCE)
            assertWithMessage("$value: room before the arrow").that(SGV_BOX.centerX() + width / 2f).isAtMost(arrowLeft - 2f)
        }
        assertThat(gap).isAtLeast(0f)
        for (name in listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")) for (day in listOf("1", "13", "30")) {
            val left = DAY_NAME_BOX.centerX() - textWidth(name, DATE_SIZE) / 2f
            val right = DAY_BOX.centerX() + textWidth(day, DATE_SIZE) / 2f
            assertWithMessage("$name $day: middle ${(left + right) / 2f}").that(abs((left + right) / 2f - DATE_WINDOW.centerX())).isAtMost(CENTRE_TOLERANCE)
        }
    }

    @Test
    fun theTextsStandInTheMiddleOfTheirWindows() {
        // Digits only: the tail of a comma hangs below the line and would count as text
        val picture = render(previewInstant, sample = Sample(text = "120", mgdl = 120.0))
        /** The middle, from top to bottom, of the pixels of colour [hex] in [box]. */
        fun middleOf(box: RectF, hex: String): Float {
            val rows = (box.top.toInt() until box.bottom.toInt()).filter { y -> (box.left.toInt() until box.right.toInt()).any { x -> near(picture.getPixel(x, y), hex, 60) } }
            assertWithMessage("$hex in $box").that(rows).isNotEmpty()
            return (rows.first() + rows.last() + 1) / 2f
        }
        // Inside the frames, so the gold frame is not counted
        fun inner(r: RectF) = RectF(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f)
        val glucose = RectF(inner(BG_WINDOW)).apply { right = ARROW_BOX.left }
        assertWithMessage("glucose").that(middleOf(glucose, palette.sgvMid)).isWithin(1.5f).of(BG_WINDOW.centerY())
        assertWithMessage("date").that(middleOf(inner(DATE_WINDOW), palette.ink)).isWithin(1.5f).of(DATE_WINDOW.centerY())
        assertWithMessage("steps").that(middleOf(RectF(inner(STEPS_WINDOW)).apply { left = STEPS_WINDOW.centerX() - 10f }, palette.ink)).isWithin(1.5f).of(STEPS_WINDOW.centerY())
    }

    @Test
    fun theColoursCanBeRead() {
        val p = palette
        // Glucose on its window, in all three colours, and the date on its window
        for (hex in listOf(p.sgvLow, p.sgvMid, p.sgvHigh, p.ink)) assertWithMessage(hex).that(contrast(hex, p.window)).isAtLeast(4.5)
        // The printing and the red warnings on the dial
        assertThat(contrast(p.ink, p.dial)).isAtLeast(7.0)
        assertThat(contrast(p.alarm, p.dial)).isAtLeast(4.5)
        // The hands on the dial, and the red low battery hand
        assertThat(contrast(p.hand, p.dial)).isAtLeast(4.5)
        assertThat(contrast(p.alarm, p.dial)).isAtLeast(3.0)
    }

    @Test
    fun theGlucoseHandPointsAtTheValue() {
        val pictures = mutableListOf<Pair<String, Bitmap>>()
        for (mgdl in listOf(54.0, 120.0, 180.0, 300.0)) {
            val picture = render(previewInstant, sample = Sample(text = "%.1f".format(mgdl / MGDL_PER_MMOL), mgdl = mgdl))
            pictures += "$mgdl mg/dL" to picture
            // Near the tip of the hand at its angle: the colour of the hand
            val tip = polar(FAN_X, FAN_Y, BG_HAND_LENGTH - 8f, bgAngle(mgdl))
            assertWithMessage("hand at $mgdl").that(countNear(picture, tip, 2, palette.hand, 60)).isGreaterThan(0)
            // And not where another value would put it
            val other = polar(FAN_X, FAN_Y, BG_HAND_LENGTH - 8f, bgAngle(if (mgdl < 150) 260.0 else 60.0))
            assertWithMessage("no hand at the other side for $mgdl").that(countNear(picture, other, 1, palette.hand, 30)).isEqualTo(0)
        }
        sheet(pictures, "${fileName}_glucose.png")
        // The scale and the hand share one function: 40 and 300 mg/dL are the two ends
        assertThat(bgAngle(40.0)).isEqualTo(FAN_MIN_ANGLE)
        assertThat(bgAngle(300.0)).isEqualTo(FAN_MAX_ANGLE)
        assertThat(bgAngle(500.0)).isEqualTo(FAN_MAX_ANGLE)
        storeSampleData(newFace())
    }

    @Test
    fun theSunAndTheMoonShowDarkerBelowTheHorizon() {
        val clock = DayNightClock(location = { null })
        // In the evening the sun is down and the moon high
        val evening = EVENING_MS
        val sunBelow = skyOrbit(JsonKeyValues.SUN_CYCLE.key).point(clock.sunCycle(evening))
        val moonUp = skyOrbit(JsonKeyValues.MOON_CYCLE.key).point(clock.moonCycle(evening))
        assertThat(sunBelow.second).isGreaterThan(HORIZON_Y + 6f)
        assertThat(moonUp.second).isLessThan(HORIZON_Y - 10f)
        val night = render(evening)
        // In the afternoon the sun is up
        val afternoon = AFTERNOON_MS
        val sunUp = skyOrbit(JsonKeyValues.SUN_CYCLE.key).point(clock.sunCycle(afternoon))
        assertThat(sunUp.second).isLessThan(HORIZON_Y - 10f)
        val day = render(afternoon)
        sheet(listOf("afternoon" to day, "evening" to night), "${fileName}_sky.png")
        fun yellow(pixel: Int) = Color.red(pixel) - Color.blue(pixel)
        // The sun under the land still shows: more yellow than the land at the same height on the other side
        val mirror = Pair(2 * SKY_X - sunBelow.first, sunBelow.second)
        val sunThere = around(night, sunBelow, 1).maxOf { yellow(it) }
        val landThere = around(night, mirror, 1).maxOf { yellow(it) }
        assertWithMessage("sun below the horizon").that(sunThere).isGreaterThan(landThere + 25)
        // But darker than in the sky
        val bright = around(day, sunUp, 1).maxOf { luminance(it) }
        val dark = around(night, sunBelow, 1).maxOf { luminance(it) }
        assertWithMessage("darker below").that(dark).isLessThan(bright * 0.7)
        // The moon is light in the evening sky
        assertThat(brightestAround(night, moonUp, 3)).isGreaterThan(0.5)
    }

    @Test
    fun theWeatherPutsCloudsAndRainOnTheSunAndTheMoon() {
        val clock = DayNightClock(location = { null })
        val sun = skyOrbit(JsonKeyValues.SUN_CYCLE.key).point(clock.sunCycle(AFTERNOON_MS))
        val pictures = listOf(null, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN).associateWith { render(AFTERNOON_MS, it) }
        sheet(pictures.map { (weather, picture) -> (weather ?: "clear") to Bitmap.createBitmap(picture, 40, 140, 120, 120) }, "${fileName}_weather.png")
        fun sunPixels(picture: Bitmap) = countNear(picture, sun, 4, "#FFC93A", 50)
        val clear = sunPixels(pictures.getValue(null))
        assertThat(clear).isGreaterThan(20)
        // A small cloud covers a part of the sun, a big one most of it
        assertThat(sunPixels(pictures.getValue(EventData.Weather.PARTLY))).isLessThan(clear)
        assertThat(sunPixels(pictures.getValue(EventData.Weather.CLOUDY))).isLessThan(clear / 3)
        // The sky turns grey when it is cloudy, and there are drops when it rains
        val skyPoint = Pair(SKY_X - 26f, SKY_Y - 22f)
        assertThat(around(pictures.getValue(EventData.Weather.CLOUDY), skyPoint, 0).single()).isNotEqualTo(around(pictures.getValue(null), skyPoint, 0).single())
        val drops = Pair(sun.first, sun.second + 14f)
        assertThat(countNear(pictures.getValue(EventData.Weather.RAIN), drops, 5, palette.rain, 50)).isGreaterThan(3)
        assertThat(countNear(pictures.getValue(EventData.Weather.CLOUDY), drops, 5, palette.rain, 30)).isEqualTo(0)
    }

    @Test
    fun theAgeAndTheLoopOnlyShowWhenOld() {
        fun alarmPixels(picture: Bitmap, box: RectF): Int {
            var n = 0
            for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) if (near(picture.getPixel(x, y), palette.alarm, 50)) n++
            return n
        }
        val fresh = render(previewInstant, bgAgeMin = 2, loopAgeMin = 3)
        assertThat(alarmPixels(fresh, AGE_BOX)).isEqualTo(0)
        assertThat(alarmPixels(fresh, LOOP_BOX)).isEqualTo(0)
        val old = render(previewInstant, bgAgeMin = 9, loopAgeMin = 20)
        assertThat(alarmPixels(old, AGE_BOX)).isGreaterThan(10)
        assertThat(alarmPixels(old, LOOP_BOX)).isGreaterThan(10)
        sheet(listOf("fresh" to fresh, "old" to old), "${fileName}_ages.png")
        storeSampleData(newFace())
    }

    @Test
    fun theBatteryHandsFollowTheLevelAndTurnRed() {
        val pictures = mutableListOf<Pair<String, Bitmap>>()
        for ((watch, phone) in listOf(66 to 78, 15 to 95, 90 to 10)) {
            val picture = render(previewInstant, watch = watch, phone = phone.toString())
            pictures += "watch $watch, phone $phone" to picture
            for ((cx, level) in listOf(WATCH_X to watch, PHONE_X to phone)) {
                val tip = polar(cx, BATT_Y, BATT_HAND_LENGTH - 6f, batteryAngle(level.toDouble()))
                val colour = if (level <= BATT_LOW) palette.alarm else palette.hand
                assertWithMessage("hand at $level").that(countNear(picture, tip, 2, colour, 60)).isGreaterThan(0)
            }
        }
        sheet(pictures, "${fileName}_batteries.png")
        // A low battery blinks while the wake-up animation plays, and nothing else moves
        val spec = readAnimation(design(defaultDesign()))
        assertThat(spec.blink).containsExactly(
            BlinkSpec(listOf(WATCH_HAND_VIEW), WatchBatteryLevel.KEY, BATT_LOW),
            BlinkSpec(listOf(PHONE_HAND_VIEW), PHONE_VALUE_KEY, BATT_LOW)
        )
        assertThat(spec.rain).isNull()
        assertThat(spec.glitch).isNull()
        assertThat(spec.pulse).isNull()
        assertThat(spec.normalized().withoutImage()).isEqualTo(spec.withoutImage())
        storeSampleData(newFace())
    }

    @Test
    fun rendersEveryLook() {
        sheet(looks.map { it.name to render(it.instant, it.weather) }, "${fileName}_looks.png")
    }

    protected companion object {

        /** The smallest text on the face, in design units (about 23 pixels on a 466 pixel screen). */
        const val MIN_TEXT = 20

        /** The font in the zip, of every text (Bodoni Moda Bold), and the one of the printing on the dial (SemiBold, not in the zip). */
        const val FONT = "BodoniModaBold"
        const val PRINT_FONT = "BodoniModaSemiBold"

        const val JPEG_QUALITY = 90
        const val ANIMATION_MS = 6_000L

        const val DIAL_R = 191f
        const val TRACK_OUTER = 188f
        const val TRACK_INNER = 178f
        const val NUMERAL_R = 150f
        const val NUMERAL_SIZE = 30f

        /** The glucose scale: its pivot, the arc, the band and the numbers. The hand turns from -60 to 60 degrees over 40 to 300 mg/dL. */
        const val FAN_X = 200f
        const val FAN_Y = 172f
        const val FAN_R = 80f
        const val BAND_R = 73f
        const val BAND_WIDTH = 8f
        const val FAN_LABEL_R = 96f
        const val FAN_LABEL_SIZE = 20f
        const val FAN_MIN_ANGLE = -60f
        const val FAN_MAX_ANGLE = 60f
        const val BG_MIN = 40.0
        const val BG_MAX = 300.0
        const val BG_LOW = 70.0
        const val BG_HIGH = 180.0
        const val MGDL_PER_MMOL = 18.0
        val LABELS = listOf(4.0, 7.0, 10.0, 15.0)
        const val BG_HAND_LENGTH = 70f
        const val BG_HAND_BOX = 152

        /**
         * The glucose window under the centre, and what is in it and under it. The value is centred in
         * the part left of the arrow; the two parts are chosen so that value and arrow together stay
         * near the middle of the window for short and long values (see `theGlucoseAndTheDateAreCentred`).
         */
        val BG_WINDOW = RectF(124f, 222f, 276f, 280f)
        val SGV_BOX = RectF(127f, 224f, 226f, 278f)
        const val SGV_SIZE = 46
        val ARROW_BOX = RectF(226f, 231f, 266f, 271f)
        val AGE_BOX = RectF(124f, 284f, 180f, 308f)
        val LOOP_BOX = RectF(180f, 284f, 276f, 308f)
        const val ALARM_SIZE = 20

        /** How far a value with its arrow, or the date, may be from the middle of its window. */
        const val CENTRE_TOLERANCE = 6f

        /** The date window at 3 o'clock: the day name and the day, each centred in its part, so the two stay near the middle. */
        val DATE_WINDOW = RectF(262f, 183f, 360f, 217f)
        val DAY_NAME_BOX = RectF(264f, 185f, 322f, 215f)
        val DAY_BOX = RectF(324f, 185f, 358f, 215f)
        const val DATE_SIZE = 22

        /** The steps window at 6 o'clock: complication 2. */
        val STEPS_WINDOW = RectF(140f, 316f, 260f, 352f)
        val STEPS_BOX = RectF(142f, 318f, 258f, 350f)
        const val STEPS_SIZE = 24
        const val STEPS_VIEW = "complication2"
        const val STEPS_SAMPLE = 6482L

        /** The sky disc at 9 o'clock: its middle, its size, the horizon and the round of the sun and the moon. */
        const val SKY_X = 92f
        const val SKY_Y = 186f
        const val SKY_R = 42f
        const val HORIZON_Y = 192f
        const val SKY_ORBIT = 26f

        /** How much the land covers what is below the horizon: 0 nothing, 255 all. */
        const val LAND_ALPHA = 150
        const val BODY_SIZE = 26
        const val CLOUD_SIZE = 40

        /** The two power reserves at 4:30 and 7:30: the watch left, the phone right. */
        const val WATCH_X = 90f
        const val PHONE_X = 310f
        const val BATT_Y = 292f
        const val BATT_R = 34f
        const val BATT_MIN_ANGLE = -50f
        const val BATT_MAX_ANGLE = 50f
        const val BATT_HAND_LENGTH = 30f
        const val BATT_HAND_BOX = 64
        const val BATT_LOW = 20.0

        const val HOUR_LENGTH = 102f
        const val MINUTE_LENGTH = 160f

        /** The views that carry the moving pictures, and their dynData. */
        const val SUN_VIEW = "freetext1"
        const val MOON_VIEW = "freetext2"
        const val SUN_CLOUD_VIEW = "freetext3"
        const val MOON_CLOUD_VIEW = "freetext4"
        const val BG_HAND_VIEW = "patient_name"
        const val WATCH_HAND_VIEW = "delta"
        const val PHONE_HAND_VIEW = "avg_delta"
        const val AGE_DYN = "bgAge"
        const val LOOP_DYN = "loopAge"
        const val MOON_DYN = "moonPhase"
        const val BG_HAND_DYN = "bgHand"
        const val WATCH_HAND_DYN = "watchHand"
        const val PHONE_HAND_DYN = "phoneHand"

        /** The pictures. */
        const val SUN = "Sun"
        const val CLOUD = "Cloud"
        const val CLOUD_PARTLY = "CloudPartly"
        const val CLOUD_CLOUDY = "CloudCloudy"
        const val CLOUD_RAIN = "CloudRain"
        const val SKY_CLOUDY = "SkyCloudy"
        const val SKY_RAIN = "SkyRain"
        const val BG_HAND = "BgHand"
        const val BATT_HAND = "BattHand"
        const val MOON_STEPS = 16

        /** An afternoon with the sun and the moon both up, and an evening with the sun down and the moon high (moon about 11 days old). */
        val AFTERNOON_MS: Long = LocalDateTime.of(2026, 11, 16, 14, 10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val EVENING_MS: Long = LocalDateTime.of(2026, 11, 20, 21, 10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
