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
import android.graphics.drawable.Icon
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.JsonKeyValues
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.shared.impl.weardata.toDrawable
import app.aaps.wear.R
import app.aaps.wear.health.HealthKind
import app.aaps.wear.health.HealthReading
import app.aaps.wear.health.healthComplicationData
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.daynight.DayOverlay
import app.aaps.wear.watchfaces.daynight.Orbit
import app.aaps.wear.watchfaces.utils.ComplicationStyleValues
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
 * (insulin, carbs, basal, reservoir, heart rate, steps). The watch battery sits in a small outline at
 * the top right, the graph in a framed panel at the bottom.
 *
 * Each test class writes its zip and previews to `build/cwf-design`, and checks the copy kept in the
 * repository (`wear/cwf-designs`) is the same design. To change a design: change it, run its test and
 * copy `build/cwf-design/<file>.zip` over the kept one.
 *
 * The font is `cwf-designs/fonts/Cyber.ttf` when that file exists (it is then added to the zip),
 * otherwise the built-in condensed bold font. It is Rajdhani Bold (OFL, see `Cyber-OFL.txt`) cut to
 * the Latin letters, so the zip stays small enough to send to the watch (see [MAX_ZIP_BYTES]):
 * `pyftsubset Rajdhani-Bold.ttf --unicodes="U+0020-024F,U+02B0-02FF,U+1E00-1EFF,U+2000-206F,U+20A0-20CF,U+2100-215F,U+2190-21FF,U+2212,U+2215,U+221E,U+2248,U+2260-2265" --layout-features='*' --no-hinting --drop-tables+=DSIG,FFTM`
 *
 * Sample values only, no real data.
 */
abstract class CyberpunkDesignBase {

    /** The name the phone and the watch show. */
    protected abstract val displayName: String

    /** The name of the zip, without `.zip`. */
    protected abstract val fileName: String

    /** The design, built on the Default design that the real code generates. */
    protected abstract fun design(default: CwfData): JSONObject

    /** The pictures of the design. */
    protected abstract fun images(): Map<String, ByteArray>

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

    protected val out = File("build/cwf-design").apply { mkdirs() }
    protected val fontFile = File("cwf-designs/fonts/$CUSTOM_FONT.ttf")

    /** The custom font when the file is there, else a built-in one. */
    protected val fontKey = if (fontFile.exists()) CUSTOM_FONT else "roboto_condensed_bold"
    protected fun fonts(): Map<String, ByteArray> = if (fontFile.exists()) mapOf(CUSTOM_FONT to fontFile.readBytes()) else emptyMap()

    /** One glucose sample: text, arrow, mg/dL value and level (1 high, 0 in range, -1 low). */
    protected data class Sample(val text: String = "6,7", val arrow: String = "→", val mgdl: Double = 120.0, val level: Long = 0)

    protected fun newFace() = CustomWatchface().apply { prepareForRendering(RuntimeEnvironment.getApplication()) }

    protected fun storeSampleData(face: CustomWatchface, sample: Sample = Sample(), bgAgeMin: Long = 2, loopAgeMin: Long = 3) = runBlocking {
        val now = System.currentTimeMillis()
        val repository = face.complicationDataRepository
        repository.updateBgData(
            EventData.SingleBg(
                dataset = 0, timeStamp = now - bgAgeMin * 60_000L, sgvString = sample.text, glucoseUnits = "mmol", slopeArrow = sample.arrow,
                delta = "+0,2", deltaDetailed = "+0,2", avgDelta = "+0,1", avgDeltaDetailed = "+0,1", sgvLevel = sample.level, sgv = sample.mgdl,
                high = 180.0, low = 70.0, color = 0
            )
        )
        repository.updateStatusData(
            EventData.Status(
                dataset = 0, externalStatus = "", iobSum = "1.25", iobDetail = "(0.90|0.35)", cob = "12g", currentBasal = "0.85U/h",
                battery = "78", rigBattery = "--", openApsStatus = now - loopAgeMin * 60_000L, bgi = "-0.4", batteryLevel = 1,
                tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0
            )
        )
        val points = ArrayList((0 until 36).map { i ->
            EventData.SingleBg(dataset = 0, timeStamp = now - (35 - i) * 5 * 60_000L, sgv = 125.0 + 35.0 * sin(i * PI / 14.0), high = 180.0, low = 70.0)
        })
        repository.updateGraphData(EventData.GraphData(points))
    }

    /** The Default design as the real code generates it, with its resources. */
    protected fun defaultDesign(sample: Sample = Sample()): CwfData {
        val face = newFace()
        storeSampleData(face, sample)
        face.renderToBitmap(400, 400)     // stores the Default design on first render
        return runBlocking { face.complicationDataRepository.getCustomWatchface(true) }!!
    }

    protected fun JSONObject.place(key: String, left: Int, top: Int, size: Int) = place(key, left, top, size, size)
    protected fun JSONObject.place(key: String, left: Int, top: Int, width: Int, height: Int): JSONObject =
        getJSONObject(key).put("leftmargin", left).put("topmargin", top).put("width", width).put("height", height).put("visibility", "visible")

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
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE.key, "true")
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
            "time", "second", "timePeriod", "week_number", "patient_name", "status", "tempTarget", "uploader_battery", "rig_battery",
            "bgi", "avg_delta", "delta", "complication3", "complication4", "hour_hand", "minute_hand", "second_hand"
        )) getJSONObject(hidden).put("visibility", "gone")

        // The background picture
        put("background", JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put("visibility", "visible"))

        // The date, small, at the top
        text("day_name", 154, DATE_TOP, 38, DATE_HEIGHT, DATE_SIZE, "right", DATE_COLOR).put("allCaps", true)
        text("day", 192, DATE_TOP, 20, DATE_HEIGHT, DATE_SIZE, "center", "#FFFFFF")
        text("month", 212, DATE_TOP, 34, DATE_HEIGHT, DATE_SIZE, "left", DATE_COLOR).put("allCaps", true)
        // The big digital time: hour, a fixed ":" and minute (the "time" view would show seconds).
        // The views are taller than the text line (1.15 x size), so no digit is cut.
        text("hour", 114, TIME_TOP, 76, TIME_HEIGHT, TIME_SIZE, "right", TIME_COLOR)
        text("freetext1", 190, TIME_TOP, 20, TIME_HEIGHT, TIME_SIZE, "center", TIME_COLOR).put("textvalue", ":")
        text("minute", 210, TIME_TOP, 76, TIME_HEIGHT, TIME_SIZE, "left", TIME_COLOR)
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
        // Watch battery: only the text shows (ring and icon are transparent). It sits inside the battery
        // outline that the cover plate draws. The box starts left of the outline: the watch keeps a square at
        // the left of the box for the ring or icon, and puts the text right of it.
        place("complication5", BATT_BOX_LEFT, BATT_TOP, BATT_BOX_WIDTH, BATT_HEIGHT)
            .put("color", CLEAR).put("iconColor", CLEAR).put("ringPrimaryColor", CLEAR).put("ringSecondaryColor", CLEAR)
            .put("fontColor", "#FFFFFF").put("textsize", BATT_TEXT_SIZE)
            .put("font", fontKey).put("fontStyle", "normal").put("gravity", "left")

        // The graph in its framed panel
        place("chart", CHART_LEFT, CHART_TOP, CHART_WIDTH, CHART_HEIGHT)

        (optJSONObject("dynData") ?: JSONObject().also { put("dynData", it) })
            .put(AGE_DYN, ageDynData("timestamp", 5.0, ageColor))
            .put(LOOP_DYN, ageDynData("loop", 15.0, ageColor).put("invalidImage", BLANK).put("image1", BLANK).put("image2", BLANK).put("invalidTextValue", "LOOP --"))
    }

    /**
     * Lays [overlay] over this object, the way the watch lays a weather look over a design: an object
     * is merged key by key into the object of the same key, `null` removes the key, every other value
     * replaces the one here.
     */
    protected fun JSONObject.mergeIn(overlay: JSONObject): JSONObject {
        overlay.keys().forEach { key ->
            val value = overlay.get(key)
            val current = optJSONObject(key)
            when {
                value == JSONObject.NULL               -> remove(key)
                value is JSONObject && current != null -> current.mergeIn(value)
                value is JSONObject                    -> put(key, JSONObject(value.toString()))
                else                                   -> put(key, value)
            }
        }
        return this
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
     * A text that only shows, in [color], when [valueKey] (an age in minutes) is [limit] or more:
     * below that its colour is transparent. `invalidFontColor` is the colour when there is no value.
     */
    protected fun ageDynData(valueKey: String, limit: Double, color: String): JSONObject = JSONObject()
        .put("valueKey", valueKey).put("minData", 0).put("maxData", 2 * limit)
        .put("invalidFontColor", color).put("fontColor1", CLEAR).put("fontColor2", color)

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
        val plate get() = Plate(frame = accent, thin = "#3C000000", battery = ink, label = label, logo = logo)
    }

    /**
     * The texts of [p]: dark on the background and on the light panels, and the dark glucose colours.
     * This is the json part of a light look; the pictures and the animation are not in it.
     */
    protected fun paletteColors(p: DayPalette): JSONObject = JSONObject().apply {
        put("day_name", JSONObject().put("fontColor", p.muted))
        put("month", JSONObject().put("fontColor", p.muted))
        for (key in listOf("day", "hour", "minute", "complication5")) put(key, JSONObject().put("fontColor", p.ink))
        put("freetext1", JSONObject().put("fontColor", p.colon))
        for (label in listOf("iob1", "cob1", "freetext2", "freetext3")) put(label, JSONObject().put("fontColor", p.label))
        for (value in listOf("iob2", "cob2", "basalRate", "reservoir", "complication1", "complication2")) put(value, JSONObject().put("fontColor", p.ink))
        put("timestamp", JSONObject().put("fontColor", DAY_LOW))
        put("loop", JSONObject().put("fontColor", DAY_LOW))
        put("dynData", JSONObject().put(AGE_DYN, JSONObject().put("invalidFontColor", DAY_LOW).put("fontColor2", DAY_LOW))
            .put(LOOP_DYN, JSONObject().put("invalidFontColor", DAY_LOW).put("fontColor2", DAY_LOW)))
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
        }.put(
            "pulse", JSONObject().put("image", LOGO_PULSE).put("left", box.left).put("top", box.top).put("width", box.width).put("height", box.height)
                .put("amplitude", PULSE_AMPLITUDE).put("secondBeat", 0.45).put("glowColor", HEART_COLOR).put("glowAlpha", 255).put("glowRadius", 11)
        )
    }

    /** The resources of a design: those of the Default design, [images] and the font. */
    protected fun resources(default: CwfData, images: Map<String, ByteArray>): MutableMap<String, ResData> = default.resData.toMutableMap().apply {
        images.forEach { (name, bytes) -> this[name] = ResData(bytes, formatOf(bytes)) }
        fonts().forEach { (name, bytes) -> this[name] = ResData(bytes, ResFormat.TTF) }
    }

    private fun formatOf(bytes: ByteArray) = if (isJpeg(bytes)) ResFormat.JPG else ResFormat.PNG

    /** [design] in [weather], as the watch lays the weather look over it. These designs have no day look, so the watch asks for the night. */
    protected fun lookOf(design: JSONObject, weather: String?): JSONObject = DayOverlay.apply(design, false, weather)

    /** The pictures the watch uses for [look] (made by [lookOf]): [images] with the swaps the look names. */
    protected fun lookImages(look: JSONObject, images: Map<String, ByteArray>): Map<String, ByteArray> =
        DayOverlay.resources(images.mapValues { ResData(it.value, formatOf(it.value)) }.toMutableMap(), look, false).mapValues { it.value.value }

    /**
     * A render-only face that shows [design], with 24 hour time as on the watch, as it looks at
     * [instant] in [weather].
     */
    protected fun storedFace(design: JSONObject, default: CwfData, images: Map<String, ByteArray>, instant: Long, weather: String? = null): CustomWatchface {
        val face = newFace()
        face.setRenderInstant(instant)
        face.sp.putBoolean(R.string.key_show_date, true)
        face.sp.putString(R.string.key_weather_kind, weather ?: "")
        face.sp.putLong(R.string.key_weather_time, instant)
        val resData = resources(default, images)
        runBlocking { face.complicationDataRepository.storeCustomWatchface(CwfData(design.toString(4), ZipWatchfaceFormat.loadMetadata(design), resData)) }
        Thread.sleep(600)   // past the render data share window
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "24")
        face.refreshRenderData()
        return face
    }

    /** Renders [design] on black, with the boxes drawn as an example, as the zip's preview image. */
    protected fun preview(design: JSONObject, default: CwfData, images: Map<String, ByteArray>, instant: Long, weather: String? = null): Bitmap {
        val face = storedFace(design, default, images, instant, weather)
        val image = createBitmap(400, 400).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(image)
        canvas.drawBitmap(face.renderToBitmap(400, 400), 0f, 0f, null)
        // The slots styled as the watch styles them in this weather
        drawComplicationValues(canvas, lookOf(design, weather))
        return image
    }

    /**
     * A render-only face has no complication renderer, so this draws heart rate, steps and the watch
     * battery with the library's own [ComplicationDrawable], styled the way the face styles its slots,
     * and with the data the heart rate and steps sources send. In the design space.
     */
    protected fun drawComplicationValues(canvas: Canvas, design: JSONObject, type: ComplicationType = ComplicationType.RANGED_VALUE) {
        drawSlot(canvas, design.getJSONObject("complication1"), health(HealthKind.HEART_RATE, 72, type))
        drawSlot(canvas, design.getJSONObject("complication2"), health(HealthKind.STEPS, 6482, type))
        drawSlot(canvas, design.getJSONObject("complication5"), battery(66, type))
    }

    /**
     * What a watch battery source sends: the level as a gauge or as text, the number without "%", and an
     * icon. The icon here stands in for the system's battery icon; the design makes it transparent.
     */
    protected fun battery(percent: Int, type: ComplicationType): ComplicationData {
        val text = PlainComplicationText.Builder(percent.toString()).build()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(RuntimeEnvironment.getApplication(), R.drawable.ic_health_heart)).build()
        return if (type == ComplicationType.RANGED_VALUE)
            RangedValueComplicationData.Builder(percent.toFloat(), 0f, 100f, text).setText(text).setMonochromaticImage(icon).build()
        else ShortTextComplicationData.Builder(text, text).setMonochromaticImage(icon).build()
    }

    /** What the heart rate or steps source sends for [value] in a slot that asked for [type]. */
    protected fun health(kind: HealthKind, value: Long, type: ComplicationType): ComplicationData =
        healthComplicationData(RuntimeEnvironment.getApplication(), HealthReading(kind, value, missingPermission = false), type, null)!!

    /**
     * Draws [data] in the slot [view] of the design with the library's [ComplicationDrawable]. The style is
     * read from [view] the way the face reads it; the keys this design leaves out get the library values.
     */
    protected fun drawSlot(canvas: Canvas, view: JSONObject, data: ComplicationData) {
        fun color(key: String, default: Int) = view.optString(key).takeIf { it.isNotEmpty() }?.let { Color.parseColor(it) } ?: default
        val defaults = ComplicationStyleValues.LIBRARY_DEFAULTS
        val textColor = color("fontColor", defaults.textColor)
        val typeface = valueTypeface()
        val drawable = ComplicationDrawable(RuntimeEnvironment.getApplication())
        ComplicationStyleValues(
            textColor = textColor,
            titleColor = defaults.titleColor,
            iconColor = color("iconColor", textColor),
            backgroundColor = color("color", defaults.backgroundColor),
            textTypeface = typeface,
            titleTypeface = typeface,
            borderRadius = view.optInt("borderRadius"),
            ringWidth = max(view.optInt("ringWidth"), ComplicationStyleValues.MIN_RING_WIDTH_PX),
            textSize = view.optInt("textsize").takeIf { it > 0 } ?: ComplicationStyleValues.TEXT_SIZE_FIT_BOX,
            titleSize = ComplicationStyleValues.TEXT_SIZE_FIT_BOX,
            ringPrimaryColor = color("ringPrimaryColor", defaults.ringPrimaryColor),
            ringSecondaryColor = color("ringSecondaryColor", defaults.ringSecondaryColor),
            borderColor = color("borderColor", defaults.borderColor),
            borderWidth = view.optInt("borderWidth")
        ).applyTo(drawable)
        drawable.setComplicationData(data, false)
        drawable.currentTime = Instant.now()
        val r = rectOf(view)
        drawable.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        drawable.draw(canvas)
    }

    /** The font of the table values. */
    protected fun valueTypeface(): Typeface =
        if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.create("sans-serif-condensed", Typeface.BOLD)

    protected fun rectOf(view: JSONObject) = RectF(
        view.getInt("leftmargin").toFloat(), view.getInt("topmargin").toFloat(),
        (view.getInt("leftmargin") + view.getInt("width")).toFloat(), (view.getInt("topmargin") + view.getInt("height")).toFloat()
    )

    /**
     * The zip: the json, the pictures (png, or jpg for soft pictures like a landscape), the preview as
     * jpg ([previewJpg]: a photo-like picture is three times smaller as jpg, and the phone and the
     * watch read both) and the font.
     */
    protected fun zip(json: String, previewJpg: ByteArray, images: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("CustomWatchface.json"))
            zip.write(json.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("${ResFileMap.CUSTOM_WATCHFACE.fileName}.jpg"))
            zip.write(previewJpg)
            zip.closeEntry()
            images.forEach { (name, picture) ->
                zip.putNextEntry(ZipEntry("$name.${if (isJpeg(picture)) "jpg" else "png"}"))
                zip.write(picture)
                zip.closeEntry()
            }
            fonts().forEach { (name, font) ->
                zip.putNextEntry(ZipEntry("$name.ttf"))
                zip.write(font)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    /** True for the bytes of a jpg picture (they start with FF D8). */
    protected fun isJpeg(bytes: ByteArray): Boolean = bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    protected fun jsonInZip(zip: ByteArray): String = ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.first { it.name == "CustomWatchface.json" }
        input.readBytes().toString(Charsets.UTF_8)
    }

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
     * the rows, the [battery] outline, the [label] icons of heart rate and steps, and the [logo].
     */
    protected data class Plate(val frame: String, val thin: String, val battery: String, val label: String, val logo: Ink)

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
     * line on top of the graph with cut corners, the small battery outline the watch battery text sits
     * in, the labels of heart rate and steps, and the logo. In the colours of [plate].
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
        // The battery outline with its small nub on the right
        val battery = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(plate.battery); strokeWidth = 2f * s; style = Paint.Style.STROKE }
        canvas.drawRect(BATT_OUT_LEFT * s, BATT_TOP * s, (BATT_OUT_LEFT + BATT_OUT_WIDTH) * s, (BATT_TOP + BATT_HEIGHT) * s, battery)
        battery.style = Paint.Style.FILL
        canvas.drawRect((BATT_OUT_LEFT + BATT_OUT_WIDTH) * s, (BATT_TOP + 6f) * s, (BATT_OUT_LEFT + BATT_OUT_WIDTH + 4f) * s, (BATT_TOP + BATT_HEIGHT - 6f) * s, battery)
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

    protected fun pngBytes(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** A grid of labelled previews, three per row. */
    protected fun sheet(cells: List<Pair<String, Bitmap>>, fileName: String) {
        val cell = 300
        val label = 26
        val cols = 3
        val rows = (cells.size + cols - 1) / cols
        val sheet = createBitmap(cols * cell, rows * (cell + label)).apply { eraseColor(Color.parseColor("#15151F")) }
        val canvas = Canvas(sheet)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 17f; textAlign = Paint.Align.CENTER }
        cells.forEachIndexed { i, (name, bitmap) ->
            val x = (i % cols) * cell
            val y = (i / cols) * (cell + label)
            canvas.drawBitmap(Bitmap.createScaledBitmap(bitmap, cell, cell, true), x.toFloat(), y.toFloat(), null)
            canvas.drawText(name, x + cell / 2f, y + cell + 19f, text)
        }
        File(out, fileName).writeBytes(pngBytes(sheet))
    }

    protected fun luminance(pixel: Int): Double {
        fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(Color.red(pixel)) + 0.7152 * lin(Color.green(pixel)) + 0.0722 * lin(Color.blue(pixel))
    }

    protected fun contrast(a: String, b: String): Double {
        val la = luminance(Color.parseColor(a))
        val lb = luminance(Color.parseColor(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    protected fun textWidth(text: String, size: Int): Float = Paint().apply {
        textSize = size.toFloat()
        typeface = valueTypeface()
    }.measureText(text)

    /** The wake-up animation of [design] as the watch reads it, with the pictures of [images]. */
    protected fun readAnimation(design: JSONObject, images: Map<String, ByteArray> = images()): WakeAnimationSpec {
        val resources = RuntimeEnvironment.getApplication().resources
        return CustomWatchface().readWakeAnimation(design) { key -> images[key]?.let { ResData(it, ResFormat.PNG).toDrawable(resources) } }!!
    }

    /** The number of pixels in [box] (design units) of [bitmap] that are clearly red. */
    private fun redPixels(bitmap: Bitmap, box: RectF): Int {
        var red = 0
        for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
            val c = bitmap.getPixel(x, y)
            if (Color.red(c) > 150 && Color.green(c) < 80 && Color.blue(c) < 110) red++
        }
        return red
    }

    /** The highest luminance in a square of [half] around [point] of [bitmap]. */
    protected fun brightestAround(bitmap: Bitmap, point: Pair<Float, Float>, half: Int): Double = around(bitmap, point, half).maxOf { luminance(it) }

    /** The lowest luminance in a square of [half] around [point] of [bitmap]. */
    protected fun darkestAround(bitmap: Bitmap, point: Pair<Float, Float>, half: Int): Double = around(bitmap, point, half).minOf { luminance(it) }

    /** The pixels in a square of [half] around [point] of [bitmap], on the picture. */
    protected fun around(bitmap: Bitmap, point: Pair<Float, Float>, half: Int): List<Int> {
        val pixels = mutableListOf<Int>()
        for (y in (point.second.toInt() - half)..(point.second.toInt() + half)) for (x in (point.first.toInt() - half)..(point.first.toInt() + half))
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) pixels += bitmap.getPixel(x, y)
        return pixels
    }

    protected fun insideRound(key: String, r: RectF, margin: Float = 0f) {
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom))
            assertWithMessage(key).that(hypot(x - 200f, y - 200f)).isAtMost(200f - margin)
    }

    private fun disjoint(name: String, a: RectF, b: RectF) =
        assertWithMessage(name).that(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top).isTrue()

    protected fun block(design: JSONObject, vararg keys: String): RectF = keys.map { rectOf(design.getJSONObject(it)) }.reduce { a, b -> RectF(a).apply { union(b) } }

    protected val rowBoxKeys = listOf("complication1", "complication2")
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
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_DETAILED_IOB]).isEqualTo("false")
        // Brings its own background, plate, arrows and beating drop; no hand pictures
        assertThat(loaded.cwfData.resData.keys).containsAtLeast(ResFileMap.BACKGROUND.fileName, ResFileMap.COVER_PLATE.fileName, LOGO_PULSE, BLANK)
        assertThat(loaded.cwfData.resData.keys).doesNotContain(ResFileMap.SECOND_HAND.fileName)
        assertThat(loaded.cwfData.resData.keys).containsAtLeast("ArrowFlat", "ArrowDoubleDown", "ArrowSingleUp")
        assertThat(loaded.cwfData.resData.keys).containsAtLeastElementsIn(ownPictures)
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
        val battery = rectOf(design.getJSONObject("complication5"))
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
        }
    }

    protected companion object {

        /**
         * The largest zip the phone can send to the watch. The phone sends it in one Wear message;
         * 628 KB never arrived at the watch, with no error on either side, while 321 KB did.
         */
        const val MAX_ZIP_BYTES = 320_000

        const val CUSTOM_FONT = "Cyber"
        const val SCALE = 2f

        const val CYAN = "#00F0FF"
        const val CLEAR = "#00000000"
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

        const val BATT_OUT_LEFT = 298
        const val BATT_OUT_WIDTH = 38
        const val BATT_TOP = 70
        const val BATT_HEIGHT = 20
        const val BATT_BOX_LEFT = 286
        const val BATT_BOX_WIDTH = 44
        const val BATT_TEXT_SIZE = 17

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
        const val BLANK = "Blank"

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
        val NIGHT_PLATE = Plate(frame = CYAN, thin = LINE_COLOR, battery = "#E6E6F2", label = LABEL_COLOR, logo = NIGHT_INK)

        /** The moments the pictures show: 11 November 2026 at 23:00 (night), at 09:00 and at noon (day), local time. */
        val NIGHT_MS: Long = LocalDateTime.of(2026, 11, 11, 23, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val MORNING_MS: Long = LocalDateTime.of(2026, 11, 11, 9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val DAY_MS: Long = LocalDateTime.of(2026, 11, 11, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
