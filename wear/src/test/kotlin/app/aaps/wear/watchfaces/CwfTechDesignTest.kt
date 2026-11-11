package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.shared.impl.weardata.toDrawable
import app.aaps.wear.R
import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.animation.WakeAnimator
import app.aaps.wear.watchfaces.animation.WakeTimer
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The "AAPS Tech" design: a dark, neon "cyberpunk" HUD. There is no analog clock, no hand and no
 * round dial. The time (neon yellow) and the glucose value with its arrow are the big things. Next to
 * the glucose sits a small data table (insulin, carbs, basal, reservoir). Heart rate, steps and the
 * watch battery are three flat boxes with a neon frame. The loop status sits left of the time. The
 * graph sits in a framed panel at the bottom. There are no seconds, so the watch draws only once a
 * minute and uses less battery.
 *
 * Built from the Default design that the real code generates. The test writes the zip and a preview to
 * `build/cwf-design`, and checks the copy kept in the repository (`wear/cwf-designs`) is the same
 * design. To change the design: change [design], run this test and copy `build/cwf-design/AAPS_Tech.zip`
 * over the kept one.
 *
 * The font is `cwf-designs/fonts/Cyber.ttf` when that file exists (it is then added to the zip),
 * otherwise the built-in condensed bold font.
 *
 * The design has a wake-up animation (see [animation]): rain under the data and the drop of the logo
 * beating at the heart rate. The test also writes frames of it, drawn by the real face, and a flipbook
 * page that plays them to `build/cwf-design/anim`.
 *
 * Sample values only, no real data.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfTechDesignTest {

    private val out = File("build/cwf-design").apply { mkdirs() }
    private val kept = File("cwf-designs/$NAME.zip")
    private val fontFile = File("cwf-designs/fonts/$CUSTOM_FONT.ttf")

    /** The custom font when the file is there, else a built-in one. */
    private val fontKey = if (fontFile.exists()) CUSTOM_FONT else "roboto_condensed_bold"
    private fun fonts(): Map<String, ByteArray> = if (fontFile.exists()) mapOf(CUSTOM_FONT to fontFile.readBytes()) else emptyMap()

    /** One glucose sample: text, arrow, mg/dL value and level (1 high, 0 in range, -1 low). */
    private data class Sample(val text: String = "6,7", val arrow: String = "→", val mgdl: Double = 120.0, val level: Long = 0)

    private fun newFace() = CustomWatchface().apply { prepareForRendering(RuntimeEnvironment.getApplication()) }

    private fun storeSampleData(face: CustomWatchface, sample: Sample = Sample()) = runBlocking {
        val now = System.currentTimeMillis()
        val repository = face.complicationDataRepository
        repository.updateBgData(
            EventData.SingleBg(
                dataset = 0, timeStamp = now - 2 * 60_000L, sgvString = sample.text, glucoseUnits = "mmol", slopeArrow = sample.arrow,
                delta = "+0,2", deltaDetailed = "+0,2", avgDelta = "+0,1", avgDeltaDetailed = "+0,1", sgvLevel = sample.level, sgv = sample.mgdl,
                high = 180.0, low = 70.0, color = 0
            )
        )
        repository.updateStatusData(
            EventData.Status(
                dataset = 0, externalStatus = "", iobSum = "1.25", iobDetail = "(0.90|0.35)", cob = "12g", currentBasal = "0.85U/h",
                battery = "78", rigBattery = "--", openApsStatus = now - 3 * 60_000L, bgi = "-0.4", batteryLevel = 1,
                tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0
            )
        )
        val points = ArrayList((0 until 36).map { i ->
            EventData.SingleBg(dataset = 0, timeStamp = now - (35 - i) * 5 * 60_000L, sgv = 125.0 + 35.0 * sin(i * PI / 14.0), high = 180.0, low = 70.0)
        })
        repository.updateGraphData(EventData.GraphData(points))
    }

    /** The Default design as the real code generates it, with its resources. */
    private fun defaultDesign(sample: Sample = Sample()): CwfData {
        val face = newFace()
        storeSampleData(face, sample)
        face.renderToBitmap(400, 400)     // stores the Default design on first render
        return runBlocking { face.complicationDataRepository.getCustomWatchface(true) }!!
    }

    private fun JSONObject.place(key: String, left: Int, top: Int, size: Int) = place(key, left, top, size, size)
    private fun JSONObject.place(key: String, left: Int, top: Int, width: Int, height: Int): JSONObject =
        getJSONObject(key).put("leftmargin", left).put("topmargin", top).put("width", width).put("height", height).put("visibility", "visible")

    /** A text view: where it is, its size, how it is aligned and its colour. */
    private fun JSONObject.text(
        key: String, left: Int, top: Int, width: Int, height: Int, size: Int, gravity: String, color: String
    ): JSONObject = place(key, left, top, width, height)
        .put("textsize", size).put("gravity", gravity).put("fontColor", color).put("font", fontKey).put("fontStyle", "normal")

    /**
     * One row of the table: a small muted label in the label column and the value, white, centred in the
     * one value column that all six rows share (see [VAL_LEFT] and [VAL_RIGHT]).
     */
    private fun JSONObject.row(label: String, value: String, index: Int, labelText: String? = null) {
        val top = TABLE_TOP + index * ROW_HEIGHT
        text(label, TABLE_LEFT, top, ICON_WIDTH, ROW_HEIGHT, LABEL_SIZE, "left", LABEL_COLOR)
        if (labelText != null) getJSONObject(label).put("textvalue", labelText)
        text(value, VAL_LEFT, top, VAL_RIGHT - VAL_LEFT, ROW_HEIGHT, VALUE_SIZE, "center", VALUE_COLOR)
    }

    /** A row-shaped complication box that blends into the panel: transparent, no frame, the icon in [accent]. */
    private fun JSONObject.rowBox(key: String, index: Int, accent: String) {
        place(key, TABLE_LEFT, TABLE_TOP + index * ROW_HEIGHT, VAL_RIGHT - TABLE_LEFT, ROW_HEIGHT)
            .put("color", CLEAR).put("borderRadius", 0)
            .put("fontColor", VALUE_COLOR).put("iconColor", accent).put("textsize", VALUE_SIZE)
            .put("font", fontKey).put("fontStyle", "normal").put("gravity", "center")
    }

    private fun design(default: CwfData): JSONObject = JSONObject(default.json).apply {
        getJSONObject("metadata")
            .put(CwfMetadataKey.CWF_NAME.key, "AAPS Tech")
            .put(CwfMetadataKey.CWF_FILENAME.key, NAME)
            .put(CwfMetadataKey.CWF_AUTHOR.key, "AAPS (local)")
            .put(CwfMetadataKey.CWF_CREATED_AT.key, "11/11/26")
            .put(CwfMetadataKey.CWF_COMMENT.key, "Cyberpunk HUD: big time and glucose, a small data table, three framed boxes and the graph. No hands, no seconds")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE.key, "true")

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
            "bgi", "avg_delta", "delta", "freetext4", "complication3", "complication4", "hour_hand", "minute_hand", "second_hand"
        )) getJSONObject(hidden).put("visibility", "gone")

        // The background picture: dark panels with faint scan lines
        put("background", JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put("visibility", "visible"))

        // The date, small, at the top
        text("day_name", 154, DATE_TOP, 38, DATE_HEIGHT, DATE_SIZE, "right", DATE_COLOR).put("allCaps", true)
        text("day", 192, DATE_TOP, 20, DATE_HEIGHT, DATE_SIZE, "center", "#FFFFFF")
        text("month", 212, DATE_TOP, 34, DATE_HEIGHT, DATE_SIZE, "left", DATE_COLOR).put("allCaps", true)
        // The big digital time in cyan: hour, a fixed ":" and minute (the "time" view would show seconds).
        // The views are taller than the text line (1.15 x size), so no digit is cut.
        text("hour", 114, TIME_TOP, 76, TIME_HEIGHT, TIME_SIZE, "right", TIME_COLOR)
        text("freetext1", 190, TIME_TOP, 20, TIME_HEIGHT, TIME_SIZE, "center", TIME_COLOR).put("textvalue", ":")
        text("minute", 210, TIME_TOP, 76, TIME_HEIGHT, TIME_SIZE, "left", TIME_COLOR)
        // The loop status, small, left of the time
        text("loop", LOOP_LEFT, LOOP_TOP, LOOP_WIDTH, LOOP_HEIGHT, 20, "center", "#FFFFFF")

        // The glucose value: the biggest text, with the arrow and its age to the right. The app colours it
        // (and the arrow and the graph points) with green, yellow or red.
        text("sgv", SGV_LEFT, SGV_TOP, SGV_WIDTH, SGV_HEIGHT, SGV_SIZE, "right", "#CCCCCC")
        place("direction", ARROW_LEFT, ARROW_TOP, ARROW_SIZE)
        text("timestamp", ARROW_LEFT, ARROW_TOP + ARROW_SIZE + 2, ARROW_SIZE, 24, 18, "center", LABEL_COLOR)

        // The data table on the right. Each row ends where the round screen allows (see rowRight).
        row("iob1", "iob2", 0)
        row("cob1", "cob2", 1)
        row("freetext2", "basalRate", 2, "BAS")
        row("freetext3", "reservoir", 3, "RSV")

        // Heart rate and steps: two more rows of the same table, as row-shaped complication boxes. No fill,
        // no frame; the icon (left, in the label column) is the label, the value has the table's size.
        rowBox("complication1", 4, LABEL_COLOR)
        rowBox("complication2", 5, LABEL_COLOR)
        // The logo is a picture in the cover plate (see drawEmblem)
        // Watch battery: only the text shows (the icon is transparent). It sits inside the battery outline
        // that the cover plate draws. The box is wide: the watch puts the icon on the left, so the
        // text is the right part of the box, which is centred in the outline.
        place("complication5", BATT_BOX_LEFT, BATT_TOP, BATT_BOX_WIDTH, BATT_HEIGHT)
            .put("color", CLEAR).put("iconColor", CLEAR).put("fontColor", "#FFFFFF").put("textsize", BATT_TEXT_SIZE)
            .put("font", fontKey).put("fontStyle", "normal").put("gravity", "center")

        // The graph in its framed panel
        place("chart", CHART_LEFT, CHART_TOP, CHART_WIDTH, CHART_HEIGHT)

        // Rain and the beating drop for a few seconds after the wrist is raised
        put("animation", animation())
    }

    /** The resources of [design]: those of the Default design, [images] and the font. */
    private fun resources(default: CwfData, images: Map<String, ByteArray>): MutableMap<String, ResData> = default.resData.toMutableMap().apply {
        images.forEach { (name, png) -> this[name] = ResData(png, ResFormat.PNG) }
        fonts().forEach { (name, bytes) -> this[name] = ResData(bytes, ResFormat.TTF) }
    }

    /** A render-only face that shows [design], with 24 hour time as on the watch. */
    private fun storedFace(design: JSONObject, default: CwfData, images: Map<String, ByteArray>): CustomWatchface {
        val face = newFace()
        face.sp.putBoolean(R.string.key_show_date, true)
        val resData = resources(default, images)
        runBlocking { face.complicationDataRepository.storeCustomWatchface(CwfData(design.toString(4), ZipWatchfaceFormat.loadMetadata(design), resData)) }
        Thread.sleep(600)   // past the render data share window
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "24")
        face.refreshRenderData()
        return face
    }

    /** Renders [design] on black, with the boxes drawn as an example, as the zip's preview image. */
    private fun preview(design: JSONObject, default: CwfData, images: Map<String, ByteArray>): Bitmap {
        val face = storedFace(design, default, images)
        val image = createBitmap(400, 400).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(image)
        canvas.drawBitmap(face.renderToBitmap(400, 400), 0f, 0f, null)
        drawComplicationValues(canvas, design)
        return image
    }

    /**
     * A render-only face has no complication renderer: draws heart rate, steps and the watch battery
     * the way the watch roughly does, in the design space.
     */
    private fun drawComplicationValues(canvas: Canvas, design: JSONObject) {
        drawRowBox(canvas, design.getJSONObject("complication1"), "72", LABEL_COLOR, R.drawable.ic_health_heart)
        drawRowBox(canvas, design.getJSONObject("complication2"), "6482", LABEL_COLOR, R.drawable.ic_health_steps)
        // The battery text sits in the middle of the outline from the cover plate
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = BATT_TEXT_SIZE.toFloat(); textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        canvas.drawText("38%", BATT_OUT_LEFT + BATT_OUT_WIDTH / 2f, BATT_TOP + BATT_HEIGHT / 2f + BATT_TEXT_SIZE * 0.35f, text)
    }

    /** Roughly what the watch draws for a short text complication: the icon on the left, the value right of it. */
    private fun drawRowBox(canvas: Canvas, view: JSONObject, value: String, accent: String, @DrawableRes icon: Int) {
        val r = rectOf(view)
        val color = Color.parseColor(accent)
        val half = 11
        val iconX = (r.left + ICON_WIDTH / 2).toInt()
        val iconY = r.centerY().toInt()
        ContextCompat.getDrawable(RuntimeEnvironment.getApplication(), icon)!!.mutate().apply {
            setTint(color)
            setBounds(iconX - half, iconY - half, iconX + half, iconY + half)
            draw(canvas)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; textSize = VALUE_SIZE.toFloat(); textAlign = Paint.Align.CENTER
            typeface = if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        // The watch centres the text in the box part right of the icon
        canvas.drawText(value, r.left + ICON_WIDTH + (r.width() - ICON_WIDTH) / 2f, r.centerY() + VALUE_SIZE * 0.34f, text)
    }

    private fun rectOf(view: JSONObject) = RectF(
        view.getInt("leftmargin").toFloat(), view.getInt("topmargin").toFloat(),
        (view.getInt("leftmargin") + view.getInt("width")).toFloat(), (view.getInt("topmargin") + view.getInt("height")).toFloat()
    )

    private fun zip(json: String, previewPng: ByteArray, images: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("CustomWatchface.json"))
            zip.write(json.toByteArray())
            zip.closeEntry()
            (images + (ResFileMap.CUSTOM_WATCHFACE.fileName to previewPng)).forEach { (name, png) ->
                zip.putNextEntry(ZipEntry("$name.png"))
                zip.write(png)
                zip.closeEntry()
            }
            fonts().forEach { (name, font) ->
                zip.putNextEntry(ZipEntry("$name.ttf"))
                zip.write(font)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun jsonInZip(zip: ByteArray): String = ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.first { it.name == "CustomWatchface.json" }
        input.readBytes().toString(Charsets.UTF_8)
    }

    /** A closed shape through the given points (x, y in the 400x400 design space), scaled for the 800x800 pictures. */
    private fun shape(points: List<Pair<Float, Float>>): Path = Path().apply {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * SCALE, y * SCALE) else lineTo(x * SCALE, y * SCALE) }
        close()
    }

    /** The table panel. Its right edge follows the round screen, row by row, with a slanted step between rows. */
    private fun tablePanel(): Path {
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

    private fun chartPanel() = shape(
        listOf(
            CHART_LEFT + 16f to CHART_TOP - 2f, CHART_LEFT + CHART_WIDTH - 16f to CHART_TOP - 2f,
            CHART_LEFT + CHART_WIDTH.toFloat() to CHART_TOP + 14f, CHART_LEFT + CHART_WIDTH.toFloat() to 400f,
            CHART_LEFT.toFloat() to 400f, CHART_LEFT.toFloat() to CHART_TOP + 14f
        )
    )

    /**
     * The background picture. With [dark] it is a very dark navy with a faint hexagon pattern and a soft
     * vignette toward the round edge; otherwise pure black (for AMOLED). Two slightly lighter panels (the
     * table and the graph) carry thin, faint scan lines. All lines are only a little lighter than what
     * is under them, so the text stays clear.
     */
    private fun background(variant: Char): ByteArray {
        val dark = variant != 'B'
        val base = when (variant) { 'B' -> "#000000"; 'C' -> "#07080C"; 'D' -> "#07060F"; 'E' -> "#09061A"; 'F' -> "#06080E"; 'G' -> "#080A11"; else -> BASE_DARK }
        val image = createBitmap(800, 800).apply { eraseColor(Color.parseColor(base)) }
        val canvas = Canvas(image)
        when (variant) {
            'C' -> weavePattern(canvas)
            'G' -> {
                weavePattern(canvas, "#1A1F2B", "#11151E")
                rainPattern(canvas, "#26385A", "#0E131D", 240)
            }
            'E' -> circuitPattern(canvas)
            'F' -> rainPattern(canvas)
        }
        if (variant == 'A') {
            val hex = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(HEX_COLOR); strokeWidth = 1f * SCALE; style = Paint.Style.STROKE }
            val r = HEX_RADIUS * SCALE
            val w = r * sqrt(3f)
            var row = 0
            var y = 0f
            while (y < 800f + r) {
                var x = if (row % 2 == 0) 0f else w / 2f
                while (x < 800f + w) {
                    val path = Path()
                    for (i in 0 until 6) {
                        val a = Math.toRadians(60.0 * i + 30.0)
                        val px = x + r * cos(a).toFloat()
                        val py = y + r * sin(a).toFloat()
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, hex)
                    x += w
                }
                y += r * 1.5f
                row++
            }
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (variant == 'G') Color.argb(195, 5, 6, 10) else Color.parseColor(if (dark) PANEL_DARK else PANEL_COLOR) }
        val lines = Paint().apply { color = if (variant == 'G') Color.argb(120, 12, 16, 24) else Color.parseColor(if (dark) SCAN_DARK else SCAN_COLOR); strokeWidth = 1f * SCALE }
        val tablePath = tablePanel()
        for (panel in listOf(tablePath, chartPanel())) {
            canvas.save()
            canvas.clipPath(panel)
            canvas.drawPath(panel, fill)
            // The weave shows behind the table too, a bit dimmed. Its pixels stay dark (see the contrast test).
            if (variant == 'G' && panel === tablePath) weavePattern(canvas, "#262E40", "#161B28")
            var y = 0f
            while (y < 800f) {
                canvas.drawLine(0f, y, 800f, y, lines)
                y += 4f * SCALE
            }
            canvas.restore()
        }
        when (variant) {
            'D' -> horizonGrid(canvas)
            'F' -> bottomGlow(canvas)
            'G' -> bottomGlow(canvas, 130, 640f)
        }
        if (variant == 'A') {
            val vignette = Paint().apply {
                shader = RadialGradient(400f, 400f, 400f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(190, 0, 0, 0)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, 800f, 800f, vignette)
        }
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** C: a fine diagonal weave, like carbon fibre. Only a little lighter than the base. */
    private fun weavePattern(canvas: Canvas, light: String = "#0D0F15", dark: String = "#0A0C11") {
        val a = Paint().apply { color = Color.parseColor(light); strokeWidth = 2f * SCALE }
        val b = Paint().apply { color = Color.parseColor(dark); strokeWidth = 2f * SCALE }
        var d = -800f
        while (d < 800f) {
            canvas.drawLine(d, 0f, d + 800f, 800f, a)
            canvas.drawLine(d + 800f, 0f, d, 800f, b)
            d += 6f * SCALE
        }
    }

    /** D: a faint perspective grid below a horizon in the graph panel, with a thin magenta and cyan glow on the horizon. */
    private fun horizonGrid(canvas: Canvas) {
        val horizon = 300f * SCALE
        canvas.save()
        canvas.clipPath(chartPanel())
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * SCALE }
        for (i in -8..8) {
            line.color = Color.argb(34, 0, 240, 255)
            canvas.drawLine(400f + i * 10f * SCALE, horizon, 400f + i * 60f * SCALE, 800f, line)
        }
        var d = 4f
        while (horizon + d * SCALE < 800f) {
            line.color = Color.argb(30, 255, 0, 140)
            canvas.drawLine(0f, horizon + d * SCALE, 800f, horizon + d * SCALE, line)
            d *= 1.55f
        }
        val glow = Paint().apply {
            shader = LinearGradient(0f, horizon - 14f * SCALE, 0f, horizon + 6f * SCALE, intArrayOf(Color.TRANSPARENT, Color.argb(70, 255, 0, 140), Color.TRANSPARENT), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, horizon - 14f * SCALE, 800f, horizon + 6f * SCALE, glow)
        line.color = Color.argb(120, 0, 240, 255)
        canvas.drawLine(60f * SCALE, horizon, 340f * SCALE, horizon, line)
        canvas.restore()
    }

    /** E: a very dark purple-to-navy gradient with a few faint circuit traces near the left and right edge. */
    private fun circuitPattern(canvas: Canvas) {
        val gradient = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 800f, Color.parseColor("#0E0620"), Color.parseColor("#050A1A"), Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, 800f, 800f, gradient)
        val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#241A44"); strokeWidth = 1.5f * SCALE; style = Paint.Style.STROKE }
        val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E2358") }
        for (mirror in listOf(false, true)) {
            fun x(v: Float) = (if (mirror) 400f - v else v) * SCALE
            for ((y0, len) in listOf(120f to 60f, 160f to 90f, 205f to 40f)) {
                val path = Path().apply {
                    moveTo(x(14f), y0 * SCALE)
                    lineTo(x(26f), y0 * SCALE)
                    lineTo(x(36f), (y0 + 10f) * SCALE)
                    lineTo(x(36f), (y0 + len) * SCALE)
                }
                canvas.drawPath(path, trace)
                canvas.drawRect(x(36f) - 3f * SCALE, (y0 + len) * SCALE - 1f, x(36f) + 3f * SCALE, (y0 + len) * SCALE + 5f * SCALE, node)
            }
        }
    }

    /** F: faint slanted rain streaks and very faint scan lines. */
    private fun rainPattern(canvas: Canvas, streak: String = "#101828", scanColor: String = "#090C14", count: Int = 170) {
        val random = java.util.Random(7)
        val rain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(streak); strokeWidth = 1f * SCALE }
        repeat(count) {
            val x = random.nextFloat() * 400f
            val y = random.nextFloat() * 400f
            val len = 10f + random.nextFloat() * 22f
            canvas.drawLine(x * SCALE, y * SCALE, (x - len * 0.25f) * SCALE, (y + len) * SCALE, rain)
        }
        val scan = Paint().apply { color = Color.parseColor(scanColor); strokeWidth = 1f * SCALE }
        var y = 0f
        while (y < 800f) {
            canvas.drawLine(0f, y, 800f, y, scan)
            y += 3f * SCALE
        }
    }

    /** F: a soft neon glow at the bottom edge. */
    private fun bottomGlow(canvas: Canvas, alpha: Int = 80, from: Float = 700f) {
        val glow = Paint().apply {
            shader = LinearGradient(0f, from, 0f, 800f, Color.TRANSPARENT, Color.argb(alpha, 0, 240, 255), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, from, 800f, 800f, glow)
    }

    private fun logoTypeface(): Typeface =
        if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.create("sans-serif-condensed", Typeface.BOLD)

    /**
     * The emblem: an angular badge (cut corners) with a circuit-trace "A" and the small word "AAPS". Every
     * line has a red copy offset by one or two pixels behind it (the glitch). Drawn into the cover plate,
     * where nothing else is.
     */
    private fun drawEmblem(canvas: Canvas, logo: Int = 1) {
        if (logo != 1) return drawLogoOption(canvas, logo)
        val s = SCALE
        val l = LOGO_LEFT.toFloat()
        val t = LOGO_TOP.toFloat()
        val w = LOGO_WIDTH.toFloat()
        val h = LOGO_HEIGHT.toFloat()
        fun badge(dx: Float, dy: Float) = shape(listOf(l + 9 + dx to t + dy, l + w + dx to t + dy, l + w + dx to t + h - 11 + dy, l + w - 11 + dx to t + h + dy, l + dx to t + h + dy, l + dx to t + 9 + dy))
        fun symbol(dx: Float, dy: Float) = Path().apply {
            moveTo((l + 11 + dx) * s, (t + h - 10 + dy) * s)
            lineTo((l + 23 + dx) * s, (t + 10 + dy) * s)
            lineTo((l + 35 + dx) * s, (t + h - 10 + dy) * s)
            moveTo((l + 16 + dx) * s, (t + h - 20 + dy) * s)
            lineTo((l + 30 + dx) * s, (t + h - 20 + dy) * s)
            moveTo((l + 23 + dx) * s, (t + 10 + dy) * s)
            lineTo((l + 23 + dx) * s, (t + 5 + dy) * s)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E00B0B12"); style = Paint.Style.FILL }
        canvas.drawPath(badge(0f, 0f), fill)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * s; style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER; strokeCap = Paint.Cap.SQUARE }
        val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for ((dx, dy, color) in listOf(Triple(2f, 1f, LOW_COLOR), Triple(0f, 0f, CYAN))) {
            line.color = Color.parseColor(color)
            node.color = line.color
            canvas.drawPath(badge(dx, dy), line)
            canvas.drawPath(symbol(dx, dy), line)
            canvas.drawRect((l + 21 + dx) * s, (t + 2 + dy) * s, (l + 25 + dx) * s, (t + 6 + dy) * s, node)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = LOGO_SIZE * s; textAlign = Paint.Align.CENTER; typeface = logoTypeface() }
        val cx = (l + 41 + (w - 47) / 2f) * s
        val baseline = (t + h / 2f - 1f) * s - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
        text.color = Color.parseColor(LOW_COLOR)
        canvas.drawText("AAPS", cx + 1.5f * s, baseline + 1f * s, text)
        text.color = Color.parseColor(CYAN)
        canvas.drawText("AAPS", cx - 1.5f * s, baseline - 0.5f * s, text)
        text.color = Color.WHITE
        canvas.drawText("AAPS", cx, baseline, text)
    }

    /** Draws [block] twice, in design units: a red copy offset by 2,1 and the cyan one, as a glitch. */
    private fun glitched(canvas: Canvas, width: Float, fillShape: Boolean = false, block: (Canvas, Paint) -> Unit) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = width; style = if (fillShape) Paint.Style.FILL else Paint.Style.STROKE
            strokeJoin = Paint.Join.MITER; strokeCap = Paint.Cap.SQUARE
        }
        for ((dx, dy, color) in listOf(Triple(2f, 1f, LOW_COLOR), Triple(0f, 0f, CYAN))) {
            paint.color = Color.parseColor(color)
            canvas.save()
            canvas.translate(dx, dy)
            block(canvas, paint)
            canvas.restore()
        }
    }

    /** The word "AAPS" with a red and a cyan copy behind the white one; [stencil] cuts a thin gap through the letters. */
    private fun word(canvas: Canvas, cx: Float, cy: Float, size: Float, stencil: Boolean = false) {
        canvas.saveLayer(null, null)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; textAlign = Paint.Align.CENTER; typeface = logoTypeface() }
        val baseline = cy - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
        text.color = Color.parseColor(LOW_COLOR)
        canvas.drawText("AAPS", cx + 1.5f, baseline + 1f, text)
        text.color = Color.parseColor(CYAN)
        canvas.drawText("AAPS", cx - 1.5f, baseline - 0.5f, text)
        text.color = Color.WHITE
        canvas.drawText("AAPS", cx, baseline, text)
        if (stencil) {
            val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.drawRect(cx - size * 1.4f, cy - 0.9f, cx + size * 1.4f, cy + 0.9f, clear)
        }
        canvas.restore()
    }

    /**
     * The drop of logo 6, in the 48 high space of that logo (the drop spans x 3..27, y 2..43). The still
     * logo and the beating [logoPulse] picture both draw this one shape.
     */
    private fun logoDrop(): Path = Path().apply {
        val cx = 15f
        moveTo(cx, 2f)
        cubicTo(cx + 3f, 13f, cx + 12f, 21f, cx + 12f, 30f)
        cubicTo(cx + 12f, 38f, cx + 6f, 43f, cx, 43f)
        cubicTo(cx - 6f, 43f, cx - 12f, 38f, cx - 12f, 30f)
        cubicTo(cx - 12f, 21f, cx - 3f, 13f, cx, 2f)
        close()
    }

    /**
     * The picture of the beating drop: only the drop of logo 6, with the same red and cyan outline, at
     * [PULSE_PX_PER_UNIT] pixels per design unit. It fills the drop box ([DROP_BOX_LEFT] .. in the 48
     * high space), which is the pulse rectangle of the animation ([pulseRect]), so at rest it lies
     * exactly on the drop of the still logo.
     */
    private fun logoPulse(): ByteArray {
        val box = pulseBox()
        val width = ceil(box.width * PULSE_PX_PER_UNIT).toInt()
        val height = ceil(box.height * PULSE_PX_PER_UNIT).toInt()
        val image = createBitmap(width, height).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        // Each axis scaled so the box fills the picture exactly, also after rounding to whole pixels
        canvas.scale(width / (DROP_BOX_RIGHT - DROP_BOX_LEFT), height / (DROP_BOX_BOTTOM - DROP_BOX_TOP))
        canvas.translate(-DROP_BOX_LEFT, -DROP_BOX_TOP)
        glitched(canvas, 2f) { c, paint -> c.drawPath(logoDrop(), paint) }
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A rectangle in the design space, as the json gets it. */
    private data class Box(val left: Double, val top: Double, val width: Double, val height: Double)

    /** The drop box of [logoPulse] in the 400 x 400 design space: the logo's own transform, rounded to 3 decimals. */
    private fun pulseBox(): Box {
        val k = LOGO_HEIGHT / 48.0
        fun round(v: Double) = Math.round(v * 1000.0) / 1000.0
        return Box(
            round(LOGO_LEFT + DROP_BOX_LEFT * k), round(LOGO_TOP + DROP_BOX_TOP * k),
            round((DROP_BOX_RIGHT - DROP_BOX_LEFT) * k), round((DROP_BOX_BOTTOM - DROP_BOX_TOP) * k)
        )
    }

    /**
     * The wake-up animation: rain at the slant of the still streaks of the background (1 to the left for
     * 4 down, about -14 degrees), and the drop of the logo beating at the heart rate.
     */
    private fun animation(): JSONObject {
        val box = pulseBox()
        return JSONObject().put("durationMs", 6000).put("fps", 24).put("fadeMs", 600)
            .put(
                "rain", JSONObject().put("count", 90).put("seed", 11).put("speed", 470).put("angle", -14).put("length", 28)
                    .put("lengthVariation", 0.4).put("thickness", 1.6).put("color", RAIN_COLOR).put("alpha", RAIN_ALPHA)
            )
            .put(
                "pulse", JSONObject().put("image", LOGO_PULSE).put("left", box.left).put("top", box.top).put("width", box.width).put("height", box.height)
                    .put("amplitude", PULSE_AMPLITUDE).put("secondBeat", 0.45).put("glowColor", HEART_COLOR).put("glowAlpha", 255).put("glowRadius", 11)
            )
    }

    private fun logoPlate(canvas: Canvas, points: List<Pair<Float, Float>>) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E00B0B12"); style = Paint.Style.FILL }
        val path = Path().apply { points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }; close() }
        canvas.drawPath(path, fill)
    }

    /** Logo options 2 to 5, all in the slot of the first one, in design units. */
    private fun drawLogoOption(canvas: Canvas, logo: Int) {
        canvas.save()
        canvas.scale(SCALE, SCALE)
        val l = LOGO_LEFT.toFloat()
        val t = LOGO_TOP.toFloat()
        val w = LOGO_WIDTH.toFloat()
        val h = LOGO_HEIGHT.toFloat()
        when (logo) {
            2 -> {
                // A blood drop with a pulse line through it, and the small word
                val cx = l + 17f
                glitched(canvas, 2f) { c, paint ->
                    c.drawPath(Path().apply {
                        moveTo(cx, t + 3f)
                        cubicTo(cx + 3f, t + 14f, cx + 12f, t + 22f, cx + 12f, t + 31f)
                        cubicTo(cx + 12f, t + 39f, cx + 6f, t + 44f, cx, t + 44f)
                        cubicTo(cx - 6f, t + 44f, cx - 12f, t + 39f, cx - 12f, t + 31f)
                        cubicTo(cx - 12f, t + 22f, cx - 3f, t + 14f, cx, t + 3f)
                        close()
                    }, paint)
                    c.drawPath(Path().apply {
                        moveTo(l + 1f, t + 30f); lineTo(l + 10f, t + 30f); lineTo(l + 14f, t + 21f); lineTo(l + 19f, t + 39f)
                        lineTo(l + 22f, t + 30f); lineTo(l + w - 2f, t + 30f)
                    }, paint)
                }
                word(canvas, l + 58f, t + 15f, 20f)
            }
            3 -> {
                // A hexagon badge with a stencil word
                val hex = listOf(l + 13f to t, l + w - 13f to t, l + w to t + h / 2f, l + w - 13f to t + h, l + 13f to t + h, l to t + h / 2f)
                logoPlate(canvas, hex)
                glitched(canvas, 2f) { c, paint -> c.drawPath(Path().apply { hex.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }; close() }, paint) }
                word(canvas, l + w / 2f, t + h / 2f, 24f, stencil = true)
            }
            4 -> {
                // A minimal angular A: two slanted strokes, the crossbar is a cut, and a thin underline
                val cx = l + w / 2f
                canvas.saveLayer(null, null)
                glitched(canvas, 5f) { c, paint ->
                    c.drawPath(Path().apply { moveTo(cx - 17f, t + h - 11f); lineTo(cx, t + 4f); lineTo(cx + 17f, t + h - 11f) }, paint)
                }
                val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
                canvas.drawRect(cx - 12f, t + h - 24f, cx + 12f, t + h - 20f, clear)
                canvas.restore()
                glitched(canvas, 1.5f) { c, paint ->
                    c.drawPath(Path().apply { moveTo(l + 4f, t + h - 3f); lineTo(l + w - 12f, t + h - 3f); lineTo(l + w - 8f, t + h - 7f) }, paint)
                }
            }
            6 -> {
                // The drop with its pulse line (option 2) and the stencil word (option 3) as one emblem.
                // Drawn in a 48 high space that is scaled to the slot.
                canvas.save()
                canvas.translate(l, t)
                val k = h / 48f
                canvas.scale(k, k)
                val l = 0f
                val t = 0f
                val w = LOGO_WIDTH / k
                glitched(canvas, 2f) { c, paint ->
                    c.drawPath(logoDrop(), paint)
                    c.drawPath(Path().apply {
                        moveTo(l + 1f, t + 36f); lineTo(l + 8f, t + 36f); lineTo(l + 12f, t + 27f); lineTo(l + 17f, t + 45f)
                        lineTo(l + 20f, t + 36f); lineTo(l + w - 2f, t + 36f)
                    }, paint)
                }
                word(canvas, l + 71f, t + 17f, 30f, stencil = true)
                canvas.restore()
            }
            else -> {
                // A closed loop made of an angular arrow, and the word
                glitched(canvas, 2.5f) { c, paint ->
                    c.drawPath(Path().apply {
                        moveTo(l + 24f, t + 6f); lineTo(l + 30f, t + 6f); lineTo(l + 40f, t + 16f); lineTo(l + 40f, t + 32f); lineTo(l + 30f, t + 42f)
                        lineTo(l + 12f, t + 42f); lineTo(l + 4f, t + 32f); lineTo(l + 4f, t + 16f); lineTo(l + 12f, t + 6f); lineTo(l + 17f, t + 6f)
                    }, paint)
                }
                glitched(canvas, 1f, fillShape = true) { c, paint ->
                    c.drawPath(Path().apply { moveTo(l + 15f, t + 0f); lineTo(l + 27f, t + 6f); lineTo(l + 15f, t + 12f); close() }, paint)
                }
                word(canvas, l + 63f, t + h / 2f, 19f)
            }
        }
        canvas.restore()
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
            return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
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
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun arrowImages(): Map<String, ByteArray> = mapOf(
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
     * The HUD frames, transparent everywhere else: a cyan bar left of the table with thin row lines,
     * a cyan frame line on top of the graph with cut corners,
     * and the small battery outline the watch battery text sits in.
     */
    private fun coverPlate(logo: Int = 1): ByteArray {
        val image = createBitmap(800, 800).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val s = SCALE
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(CYAN); strokeWidth = 3f * s; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(LINE_COLOR); strokeWidth = 1f * s }
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
        val battery = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6E6F2"); strokeWidth = 2f * s; style = Paint.Style.STROKE }
        canvas.drawRect(BATT_OUT_LEFT * s, BATT_TOP * s, (BATT_OUT_LEFT + BATT_OUT_WIDTH) * s, (BATT_TOP + BATT_HEIGHT) * s, battery)
        battery.style = Paint.Style.FILL
        canvas.drawRect((BATT_OUT_LEFT + BATT_OUT_WIDTH) * s, (BATT_TOP + 6f) * s, (BATT_OUT_LEFT + BATT_OUT_WIDTH + 4f) * s, (BATT_TOP + BATT_HEIGHT - 6f) * s, battery)
        drawEmblem(canvas, logo)
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun images(bg: Char = 'G', logo: Int = 6): Map<String, ByteArray> = mapOf(
        ResFileMap.BACKGROUND.fileName to background(bg),
        ResFileMap.COVER_PLATE.fileName to coverPlate(logo),
        LOGO_PULSE to logoPulse()
    ) + arrowImages()

    @Test
    fun buildsTheDesignAndItsPreview() {
        val default = defaultDesign()
        val design = design(default)
        val json = design.toString(4)
        val images = images()
        val png = ByteArrayOutputStream().also { preview(design, default, images).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val zip = zip(json, png, images)
        File(out, "$NAME.zip").writeBytes(zip)
        File(out, "$NAME.png").writeBytes(png)

        // The phone accepts it: it needs a name and a preview image
        val loaded = ZipWatchfaceFormat.loadCustomWatchface(zip, "$NAME.zip", authorization = true)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.cwfData.metadata[CwfMetadataKey.CWF_NAME]).isEqualTo("AAPS Tech")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3]).isEqualTo("false")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4]).isEqualTo("false")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE]).isEqualTo("true")
        // Brings its own background and plate; no hand pictures
        assertThat(loaded.cwfData.resData.keys).containsAtLeast(ResFileMap.BACKGROUND.fileName, ResFileMap.COVER_PLATE.fileName)
        assertThat(loaded.cwfData.resData.keys).doesNotContain(ResFileMap.SECOND_HAND.fileName)
        assertThat(loaded.cwfData.resData.keys).containsAtLeast("ArrowFlat", "ArrowDoubleDown", "ArrowSingleUp")
        if (fontFile.exists()) assertThat(loaded.cwfData.resData[CUSTOM_FONT]?.format).isEqualTo(ResFormat.TTF)
        // The picture of the beating drop of the wake-up animation
        assertThat(loaded.cwfData.resData[LOGO_PULSE]?.format).isEqualTo(ResFormat.PNG)

        // The copy kept in the repository is this design
        assertThat(kept.exists()).isTrue()
        assertThat(JSONObject(jsonInZip(kept.readBytes())).toString(4)).isEqualTo(json)
        val keptEntries = ZipInputStream(kept.inputStream()).use { input -> generateSequence { input.nextEntry }.map { it.name }.toList() }
        assertThat(keptEntries).containsAtLeast("${ResFileMap.COVER_PLATE.fileName}.png", "${ResFileMap.BACKGROUND.fileName}.png")
        if (fontFile.exists()) assertThat(keptEntries).contains("$CUSTOM_FONT.ttf")
        assertThat(keptEntries).contains("$LOGO_PULSE.png")
    }

    private fun insideRound(key: String, r: RectF, margin: Float = 0f) {
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom))
            assertWithMessage(key).that(hypot(x - 200f, y - 200f)).isAtMost(200f - margin)
    }

    private fun disjoint(name: String, a: RectF, b: RectF) =
        assertWithMessage(name).that(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top).isTrue()

    private fun block(design: JSONObject, vararg keys: String): RectF = keys.map { rectOf(design.getJSONObject(it)) }.reduce { a, b -> RectF(a).apply { union(b) } }

    private val rowBoxKeys = listOf("complication1", "complication2")
    private val tableKeys = listOf("iob1", "iob2", "cob1", "cob2", "freetext2", "basalRate", "freetext3", "reservoir")

    @Test
    fun everythingStaysOnTheRoundScreenAndApart() {
        val design = design(defaultDesign())
        val visible = design.keys().asSequence().filter { design.optJSONObject(it)?.optString("visibility") == "visible" }
            .filter { it !in listOf("cover_plate", "background", "chart") }.toList()
        // Nothing is clipped by the round screen
        for (key in visible) insideRound(key, rectOf(design.getJSONObject(key)))

        val date = block(design, "day_name", "day", "month")
        val time = block(design, "hour", "freetext1", "minute")
        val bg = block(design, "sgv", "direction", "timestamp")
        val table = block(design, *(tableKeys + rowBoxKeys).toTypedArray())
        val loop = rectOf(design.getJSONObject("loop"))
        val battery = rectOf(design.getJSONObject("complication5"))
        val logo = RectF(LOGO_LEFT.toFloat(), LOGO_TOP.toFloat(), (LOGO_LEFT + LOGO_WIDTH).toFloat(), (LOGO_TOP + LOGO_HEIGHT).toFloat())
        insideRound("logo", logo, margin = 6f)
        val chart = rectOf(design.getJSONObject("chart"))
        // The graph keeps a good size: full width below the table
        assertThat(chart.width()).isAtLeast(300f)
        assertThat(chart.height()).isAtLeast(130f)
        // Full width (340) and at least 130 high, below the table

        val blocks = mapOf("date" to date, "time" to time, "bg" to bg, "table" to table, "loop" to loop, "battery" to battery, "logo" to logo, "chart" to chart)
        val names = blocks.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) disjoint("${names[i]} / ${names[j]}", blocks.getValue(names[i]), blocks.getValue(names[j]))
    }

    @Test
    fun everyTableRowKeepsAMarginToTheRoundEdge() {
        val design = design(defaultDesign())
        val pairs = listOf("iob1" to "iob2", "cob1" to "cob2", "freetext2" to "basalRate", "freetext3" to "reservoir")
        for ((k, pair) in pairs.withIndex()) {
            val row = block(design, pair.first, pair.second)
            insideRound("row $k", row, margin = 6f)
            // The panel edge is drawn at rowRight, a little outside the value box
            assertWithMessage("row $k").that(row.right).isAtMost(rowRight(k).toFloat())
            insideRound("row $k panel edge", RectF(row.left, row.top, rowRight(k).toFloat(), row.bottom), margin = 6f)
        }
        // Heart rate and steps are rows 4 and 5 of the table, inside the panel, with the same margin
        for ((i, key) in rowBoxKeys.withIndex()) {
            val r = rectOf(design.getJSONObject(key))
            insideRound(key, r, margin = 6f)
            assertWithMessage(key).that(r.left).isAtLeast(TABLE_LEFT.toFloat())
            assertWithMessage(key).that(r.right).isAtMost(rowRight(4 + i).toFloat())
            assertWithMessage(key).that(r.top).isEqualTo((TABLE_TOP + (4 + i) * ROW_HEIGHT).toFloat())
            insideRound("$key panel edge", RectF(r.left, r.top, rowRight(4 + i).toFloat(), r.bottom), margin = 6f)
        }
        insideRound("complication5", rectOf(design.getJSONObject("complication5")), margin = 6f)
    }

    @Test
    fun noTextIsCutAndTimeAndGlucoseAreBig() {
        val design = design(defaultDesign())
        fun size(key: String) = design.getJSONObject(key).getInt("textsize")
        fun height(key: String) = design.getJSONObject(key).getInt("height")
        // Every text view is higher than its text line (about 1.15 x the size), so no glyph is cut
        for (key in listOf("hour", "freetext1", "minute", "sgv", "iob2", "cob2", "basalRate", "reservoir", "iob1", "cob1", "freetext2", "freetext3", "timestamp", "day"))
            assertWithMessage(key).that(height(key).toFloat()).isAtLeast(size(key) * 1.15f)
        val valueSizes = listOf("iob2", "cob2", "basalRate", "reservoir").map { size(it) }
        val big = listOf("sgv", "hour", "minute").map { size(it) }
        assertThat(big.min()).isAtLeast(valueSizes.max() * 2)
        assertThat(size("sgv")).isAtLeast(size("hour"))
        // Labels may be 13 and bigger, values 16 and bigger
        assertThat(tableKeys.minOf { size(it) }).isAtLeast(13)
        assertThat(valueSizes.min()).isAtLeast(16)
        assertThat(size("timestamp")).isAtLeast(16)
        assertThat(design.getJSONObject("complication5").getInt("textsize")).isAtLeast(16)
    }

    @Test
    fun allSixValuesShareOneCentredColumnAndColour() {
        val design = design(defaultDesign())
        val centres = mutableListOf<Float>()
        for (key in listOf("iob2", "cob2", "basalRate", "reservoir")) {
            val view = design.getJSONObject(key)
            assertWithMessage(key).that(view.getString("gravity")).isEqualTo("center")
            centres += rectOf(view).centerX()
        }
        // A complication's text area: the box right of the icon
        for (key in rowBoxKeys) {
            val r = rectOf(design.getJSONObject(key))
            centres += (r.left + ICON_WIDTH + r.right) / 2f
            assertThat(r.left).isEqualTo(TABLE_LEFT.toFloat())
        }
        for (c in centres) assertThat(c).isWithin(1f).of(centres[0])
        // Same size, font and colour for all six values
        val views = (listOf("iob2", "cob2", "basalRate", "reservoir") + rowBoxKeys).map { design.getJSONObject(it) }
        assertThat(views.map { it.getInt("textsize") }.toSet()).hasSize(1)
        assertThat(views.map { it.getString("font") }.toSet()).hasSize(1)
        assertThat(views.map { it.getString("fontColor") }.toSet()).containsExactly(VALUE_COLOR)
        // Labels: one muted colour, and the text fits the label column
        for (key in listOf("iob1", "cob1", "freetext2", "freetext3")) {
            assertWithMessage(key).that(design.getJSONObject(key).getString("fontColor")).isEqualTo(LABEL_COLOR)
        }
        for (text in listOf("IOB", "Carb", "BAS", "RSV")) assertWithMessage(text).that(textWidth(text, LABEL_SIZE)).isAtMost(ICON_WIDTH.toFloat())
        // The widest values fit the column
        for (text in listOf("0.85U/h", "120U", "1.25", "12g", "6482")) assertWithMessage(text).that(textWidth(text, VALUE_SIZE)).isAtMost(VAL_RIGHT - VAL_LEFT - 4f)
    }

    @Test
    fun trendArrowsAreBigAndShippedInTheZip() {
        val design = design(defaultDesign())
        val arrow = rectOf(design.getJSONObject("direction"))
        assertThat(arrow.width()).isAtLeast(50f)
        assertThat(arrow.width()).isEqualTo(arrow.height())
        assertThat(images().keys).containsAtLeast(
            "ArrowNone", "ArrowDoubleUp", "ArrowSingleUp", "Arrow45Up", "ArrowFlat", "Arrow45Down", "ArrowSingleDown", "ArrowDoubleDown"
        )
        // The arrow sits between the glucose value and the table
        assertThat(arrow.left).isAtLeast(design.getJSONObject("sgv").getInt("leftmargin") + design.getJSONObject("sgv").getInt("width").toFloat())
        assertThat(arrow.right).isAtMost(TABLE_LEFT - 8f)
    }

    @Test
    fun coloursAreNotConfusedWithGlucose() {
        val design = design(defaultDesign())
        // The glucose colours: green in range, yellow high, red low. Time and other texts avoid them.
        assertThat(design.getString("midColor")).isEqualTo("#39FF14")
        assertThat(design.getString("highColor")).isEqualTo("#FCEE0A")
        assertThat(design.getString("lowColor")).isEqualTo("#FF003C")
        val glucose = listOf("midColor", "highColor", "lowColor").map { design.getString(it) }
        for (key in listOf("hour", "minute", "freetext1", "cob2", "iob2", "basalRate", "reservoir"))
            assertWithMessage(key).that(design.getJSONObject(key).getString("fontColor")).isNotIn(glucose)
        assertThat(design.getString("carbColor")).isNotIn(glucose)
    }

    @Test
    fun noHandsNoSecondsAndFlatBoxes() {
        val design = design(defaultDesign())
        assertThat(design.getBoolean("enableSecond")).isFalse()
        for (key in listOf("hour_hand", "minute_hand", "second_hand", "time", "second", "complication3", "complication4", "delta", "avg_delta", "bgi"))
            assertWithMessage(key).that(design.getJSONObject(key).getString("visibility")).isEqualTo("gone")
        assertThat(design.getJSONObject("freetext1").getString("textvalue")).isEqualTo(":")
        for (key in listOf("hour", "minute", "loop", "complication1", "complication2", "complication5"))
            assertWithMessage(key).that(design.getJSONObject(key).getString("visibility")).isEqualTo("visible")
        for (key in rowBoxKeys) {
            val r = rectOf(design.getJSONObject(key))
            assertThat(r.width()).isAtLeast(110f)
            assertThat(r.height()).isAtLeast(ROW_HEIGHT.toFloat())
            assertWithMessage(key).that(design.getJSONObject(key).getInt("borderRadius")).isAtMost(6)
            assertWithMessage(key).that(design.getJSONObject(key).getInt("textsize")).isAtLeast(16)
            assertWithMessage(key).that(design.getJSONObject(key).has("borderColor")).isFalse()
        }
        // Battery: the icon is invisible, only the text shows, inside the outline
        assertThat(design.getJSONObject("complication5").getString("iconColor")).isEqualTo(CLEAR)
    }

    private fun textWidth(text: String, size: Int): Float = Paint().apply {
        textSize = size.toFloat()
        typeface = if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }.measureText(text)

    private fun renderSample(sample: Sample, fileName: String, bg: Char = 'G') {
        val default = defaultDesign(sample)
        val design = design(default)
        val png = ByteArrayOutputStream().also { preview(design, default, images(bg)).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        File(out, fileName).writeBytes(png)
    }

    /** A grid of labelled previews, three per row. */
    private fun sheet(cells: List<Pair<String, Bitmap>>, fileName: String) {
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
        File(out, fileName).writeBytes(ByteArrayOutputStream().also { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
    }

    private fun luminance(pixel: Int): Double {
        fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(Color.red(pixel)) + 0.7152 * lin(Color.green(pixel)) + 0.0722 * lin(Color.blue(pixel))
    }

    /** The texture continues behind the table, but dimmed: white text keeps a contrast ratio of about 10:1. */
    @Test
    fun textStaysReadableOnTheBackground() {
        val png = images()[ResFileMap.BACKGROUND.fileName]!!
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        var worst = 0.0
        for (y in (TABLE_TOP * 2) until (TABLE_BOTTOM * 2) step 2)
            for (x in (TABLE_LEFT * 2) until (VAL_RIGHT * 2) step 2) worst = max(worst, luminance(bitmap.getPixel(x, y)))
        assertWithMessage("brightest background pixel behind the table").that(worst).isLessThan(0.06)
        assertThat(1.05 / (worst + 0.05)).isAtLeast(9.5)
    }

    /** The rain is drawn above the background, under the data: at its brightest it must still leave the table dark enough for white text. */
    @Test
    fun rainKeepsTheTableReadable() {
        val png = images()[ResFileMap.BACKGROUND.fileName]!!
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        val rain = readAnimation(design(defaultDesign())).rain!!
        // The brightest a streak gets: its colour at full alpha, the streak's own alpha (at most 1) and the full fade
        val worst = brightestUnderRain(bitmap, rain.color, Color.alpha(rain.color) / 255.0 * rain.alpha / 255.0)
        assertWithMessage("brightest pixel behind the table under a rain streak").that(worst).isLessThan(0.06)
    }

    /** The brightest pixel of the table panel of [background] with [color] laid over it at [alpha] (0..1). */
    private fun brightestUnderRain(background: Bitmap, color: Int, alpha: Double): Double {
        fun blend(under: Int, over: Int) = (over * alpha + under * (1 - alpha)).toInt()
        var worst = 0.0
        for (y in (TABLE_TOP * 2) until (TABLE_BOTTOM * 2) step 2)
            for (x in (TABLE_LEFT * 2) until (VAL_RIGHT * 2) step 2) {
                val under = background.getPixel(x, y)
                val lit = Color.rgb(blend(Color.red(under), Color.red(color)), blend(Color.green(under), Color.green(color)), blend(Color.blue(under), Color.blue(color)))
                worst = max(worst, luminance(lit))
            }
        return worst
    }

    private fun readAnimation(design: JSONObject, images: Map<String, ByteArray> = images()): WakeAnimationSpec {
        val resources = RuntimeEnvironment.getApplication().resources
        return CustomWatchface().readWakeAnimation(design) { key -> images[key]?.let { ResData(it, ResFormat.PNG).toDrawable(resources) } }!!
    }

    @Test
    fun theAnimationIsReadAndTheDropSitsOnTheLogo() {
        val design = design(defaultDesign())
        val spec = readAnimation(design)
        // Every value is inside the safe limits, so nothing is changed on the watch
        assertThat(spec.normalized().withoutImage()).isEqualTo(spec.withoutImage())
        assertThat(spec.durationMs).isEqualTo(6000)
        assertThat(spec.fps).isEqualTo(24)
        assertThat(spec.fadeMs).isEqualTo(600)
        val rain = spec.rain!!
        assertThat(rain.count).isEqualTo(90)
        assertThat(rain.color).isEqualTo(Color.parseColor(RAIN_COLOR))
        // The same slant as the still streaks of the background: 1 to the left for 4 down
        assertThat(rain.angle.toDouble()).isWithin(0.5).of(Math.toDegrees(atan(-0.25)))
        val pulse = spec.pulse!!
        assertThat(pulse.drawable).isNotNull()
        assertThat(pulse.rect).isEqualTo(DesignRect(50.75f, 199.417f, 41.167f, 60.667f))
        // The picture has the shape of its box
        val png = images()[LOGO_PULSE]!!
        val picture = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertThat(picture.width.toFloat() / picture.height).isWithin(0.01f).of(pulse.rect.width / pulse.rect.height)
        // The box holds the drop of the still logo: the logo's transform of the drop (x 3..27, y 2..43)
        val k = LOGO_HEIGHT / 48f
        assertThat(pulse.rect.left).isLessThan(LOGO_LEFT + 3 * k - 1)
        assertThat(pulse.rect.right).isGreaterThan(LOGO_LEFT + 29 * k + 1)
        assertThat(pulse.rect.top).isLessThan(LOGO_TOP + 2 * k - 3)
        assertThat(pulse.rect.bottom).isGreaterThan(LOGO_TOP + 44 * k + 1)
        // At the top of a beat the box stays on the round screen, and the drawn drop (down to y 45 with
        // its red copy and outline) stays above the graph's frame line (CHART_TOP - 3 .. - 1)
        val size = 1 + pulse.amplitude
        val cx = pulse.rect.left + pulse.rect.width / 2
        val cy = pulse.rect.top + pulse.rect.height / 2
        val largest = RectF(cx - pulse.rect.width * size / 2, cy - pulse.rect.height * size / 2, cx + pulse.rect.width * size / 2, cy + pulse.rect.height * size / 2)
        insideRound("drop box at the top of a beat", largest, margin = 6f)
        val dropBottom = cy + (LOGO_TOP + 45 * k - cy) * size
        assertThat(dropBottom).isAtMost(CHART_TOP - 3f)
    }

    /**
     * Frames of the wake-up animation of the real face: the design stored in a render-only face, drawn by
     * the real views and a [WakeAnimator] with a test clock. Written to `build/cwf-design/anim` with a
     * flipbook page that plays them.
     */
    @Test
    fun rendersTheWakeAnimationFrames() {
        val default = defaultDesign()
        val design = design(default)
        val images = images()
        val face = storedFace(design, default, images)
        val size = FRAME_SIZE
        val still = createBitmap(size, size).apply { eraseColor(Color.BLACK) }
        Canvas(still).apply {
            drawBitmap(face.renderToBitmap(size, size), 0f, 0f, null)
            withScale(size / 400f, size / 400f) { drawComplicationValues(this, design) }
        }
        var clock = 0L
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { clock })
        animator.setDesign(readAnimation(design, images))
        val dir = File(out, "anim").apply { deleteRecursively(); mkdirs() }
        val now = System.currentTimeMillis()
        // The frames the checks and the zoom sheet look at, by time; the top of a beat at 72 bpm is 120 ms after it starts
        val zoomMs = listOf(0L, 125L, 250L, 958L, 3000L, 5791L)
        val keptFrames = mutableMapOf<Long, Bitmap>()
        val jpegs = mutableListOf<ByteArray>()
        for (i in 0..FRAME_COUNT) {
            clock = frameMs(i)
            val frame = createBitmap(size, size).apply { eraseColor(Color.BLACK) }
            val canvas = Canvas(frame)
            val drawn = animator.drawFrame(canvas, size, size, now, 0, emptyList(), face.wakeParts, allowed = true, ready = true)
            // The last one is past the end: the face is drawn the normal way again
            assertWithMessage("frame at $clock ms is animated").that(drawn).isEqualTo(clock < 6000)
            if (drawn) canvas.withScale(size / 400f, size / 400f) { drawComplicationValues(this, design) }
            else canvas.drawBitmap(still, 0f, 0f, null)
            jpegs += ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            // A png of every quarter second to look at one by one
            if (clock % 250 == 0L) File(dir, "tech_%04dms.png".format(clock)).writeBytes(pngBytes(frame))
            if (clock in zoomMs) keptFrames[clock] = frame else frame.recycle()
        }
        val frames = zoomMs.map { keptFrames.getValue(it) }
        // Without rain and beat (the first frame) the animated face is the still face, up to the soft edge of the drop drawn twice
        val box = pulseBox()
        val f = size / 400f
        val logo = Rect((box.left * f).toInt(), (box.top * f).toInt(), ((box.left + box.width) * f).toInt() + 1, ((box.top + box.height) * f).toInt() + 1)
        val outsideDiff = maxDifference(frames[0], still, exclude = logo)
        // In the drop box the drop is drawn twice: its soft edges get stronger, and the red copy now lies
        // over the cyan pulse line where they cross. A drop one pixel off would change far more pixels.
        val changed = changedPixels(frames[0], still, logo, threshold = 40)
        val offByOne = changedPixels(createBitmap(size, size).apply { Canvas(this).drawBitmap(still, 1f, 0f, null) }, still, logo, threshold = 40)
        println("first frame vs still: outside the drop at most $outsideDiff; in the drop box $changed pixels changed, $offByOne for a drop one pixel off")
        assertThat(outsideDiff).isAtMost(2)
        assertThat(changed).isLessThan(offByOne / 2)
        // While it plays the rain shows on the background, so the frame is not the still face
        assertThat(maxDifference(frames[4], still, exclude = logo)).isGreaterThan(30)
        logoZoom(dir, logo, listOf("still" to still) + zoomMs.mapIndexed { i, ms -> "$ms ms" to frames[i] })
        flipbook(dir, jpegs)
    }

    /** The drop box of some frames side by side, 4 times larger, to check by eye that the drop returns onto the still one. */
    private fun logoZoom(dir: File, box: Rect, cells: List<Pair<String, Bitmap>>) {
        val zoom = 4
        val pad = 12
        val label = 22
        val w = (box.width() + 2 * pad) * zoom
        val h = (box.height() + 2 * pad) * zoom
        val sheet = createBitmap(cells.size * w, h + label).apply { eraseColor(Color.parseColor("#15151F")) }
        val canvas = Canvas(sheet)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16f; textAlign = Paint.Align.CENTER }
        val source = Rect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        cells.forEachIndexed { i, (name, bitmap) ->
            canvas.drawBitmap(bitmap, source, Rect(i * w, 0, (i + 1) * w, h), null)
            canvas.drawText(name, i * w + w / 2f, h + 17f, text)
        }
        File(dir, "tech_logo_zoom.png").writeBytes(pngBytes(sheet))
    }

    private fun pngBytes(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** How many pixels of [a] in [box] differ from [b] by more than [threshold] in one colour channel. */
    private fun changedPixels(a: Bitmap, b: Bitmap, box: Rect, threshold: Int): Int {
        var count = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            if (maxOf(abs(Color.red(p) - Color.red(q)), abs(Color.green(p) - Color.green(q)), abs(Color.blue(p) - Color.blue(q))) > threshold) count++
        }
        return count
    }

    /** The largest difference of one colour channel between [a] and [b], in [only], or everywhere but [exclude]. */
    private fun maxDifference(a: Bitmap, b: Bitmap, only: Rect? = null, exclude: Rect? = null): Int {
        var worst = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (only != null && !only.contains(x, y)) continue
            if (exclude != null && exclude.contains(x, y)) continue
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            worst = maxOf(worst, abs(Color.red(p) - Color.red(q)), abs(Color.green(p) - Color.green(q)), abs(Color.blue(p) - Color.blue(q)))
        }
        return worst
    }

    /**
     * A page that plays the frames in a loop, on black behind a round mask, at [FRAME_FPS] like the watch:
     * frame after frame, without fading between them, so it moves the way the watch draws it. Holds the
     * still face for a second at the end. Everything is in the page itself.
     */
    private fun flipbook(dir: File, jpegs: List<ByteArray>) {
        val encoder = Base64.getEncoder()
        val sources = jpegs.joinToString(",\n") { "\"data:image/jpeg;base64,${encoder.encodeToString(it)}\"" }
        val html = """
            |<!doctype html>
            |<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            |<title>AAPS Tech wake</title>
            |<style>
            |  html, body { margin: 0; background: #000; color: #9aa; font: 14px sans-serif; }
            |  .page { display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 16px; }
            |  .face { position: relative; width: min(466px, calc(100vw - 32px)); aspect-ratio: 1; border-radius: 50%; overflow: hidden; background: #000; }
            |  .face img { position: absolute; inset: 0; width: 100%; height: 100%; }
            |  button { background: #111; color: #0ff; border: 1px solid #0ff; border-radius: 4px; padding: 6px 18px; font: inherit; cursor: pointer; }
            |</style></head>
            |<body><div class="page">
            |  <div class="face"><img id="a" alt="frame"></div>
            |  <div><button id="play">Pause</button> <span id="time"></span></div>
            |</div>
            |<script>
            |const frames = [
            |$sources
            |];
            |const fps = $FRAME_FPS, hold = 1000, end = (frames.length - 1) * 1000 / fps, loop = end + hold;
            |const a = document.getElementById("a"), time = document.getElementById("time"), button = document.getElementById("play");
            |let playing = true, start = performance.now(), pausedAt = 0;
            |// Decode every frame before it is needed, so none is late
            |const decoded = frames.map(src => { const img = new Image(); img.src = src; return img; });
            |let shown = -1;
            |function show(ms) {
            |  const i = Math.min(Math.floor(ms * fps / 1000), frames.length - 1);
            |  if (i !== shown) { a.src = frames[i]; shown = i; }
            |  time.textContent = Math.min(ms, end).toFixed(0).padStart(4, " ") + " ms";
            |}
            |function tick(now) { if (playing) show((now - start) % loop); requestAnimationFrame(tick); }
            |button.onclick = () => {
            |  const now = performance.now();
            |  if (playing) { pausedAt = (now - start) % loop; button.textContent = "Play"; } else { start = now - pausedAt; button.textContent = "Pause"; }
            |  playing = !playing;
            |};
            |requestAnimationFrame(tick);
            |</script>
            |</body></html>
            |""".trimMargin()
        File(dir, "tech_flipbook.html").writeText(html)
    }

    @Test
    fun renderVariants() {
        val default = defaultDesign()
        val design = design(default)
        fun save(bitmap: Bitmap, fileName: String) = File(out, fileName).writeBytes(ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
        val backgrounds = listOf('A' to "A  navy hex + vignette", 'B' to "B  pure black", 'C' to "C  carbon weave", 'D' to "D  horizon grid", 'E' to "E  purple circuit", 'F' to "F  rain + glow", 'G' to "G  NEW: weave + rain + glow")
        sheet(backgrounds.map { (id, name) ->
            preview(design, default, images(bg = id, logo = 1)).also { save(it, "${NAME}_bg_$id.png") }.let { name to it }
        }, "${NAME}_backgrounds.png")
        val logos = listOf(1 to "1  circuit A badge", 2 to "2  drop + pulse", 3 to "3  hex stencil", 4 to "4  minimal A", 5 to "5  loop arrow", 6 to "6  NEW: drop + stencil")
        sheet(logos.map { (id, name) ->
            preview(design, default, images(bg = 'A', logo = id)).also { save(it, "${NAME}_logo_$id.png") }.let { name to it }
        }, "${NAME}_logos.png")
        sheet(
            listOf(
                "previous (A + logo 1)" to preview(design, default, images('A', 1)),
                "NEW main (G + logo 6)" to preview(design, default, images('G', 6))
            ), "${NAME}_choice.png"
        )
        for (name in listOf("${NAME}_backgrounds.png", "${NAME}_logos.png")) assertThat(File(out, name).length()).isGreaterThan(10_000L)
    }

    @Test
    fun fourCharacterGlucoseAndWidestTimeFit() {
        renderSample(Sample("13,8", "\u2197", 248.0, 1), "${NAME}_high.png")
        renderSample(Sample("10,4", "→", 187.0, 1), "${NAME}_104.png")
        renderSample(Sample("3,4", "\u21ca", 61.0, -1), "${NAME}_down.png")
        renderSample(Sample(), "${NAME}_black.png", bg = 'B')
        val design = design(defaultDesign())
        // Widest glucose texts and the widest time, measured with the real font at the design's size
        val sgv = design.getJSONObject("sgv")
        for (text in listOf("13,8", "10,4", "22,2", "33,3")) {
            assertWithMessage(text).that(textWidth(text, sgv.getInt("textsize"))).isAtMost(sgv.getInt("width") - 4f)
        }
        val paint = Paint().apply { textSize = sgv.getInt("textsize").toFloat(); typeface = if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.DEFAULT_BOLD }
        assertThat(paint.fontMetrics.descent - paint.fontMetrics.ascent).isAtMost(sgv.getInt("height") - 2f)
        for ((key, text) in listOf("hour" to "20", "minute" to "48", "freetext1" to ":", "hour" to "00", "minute" to "08")) {
            val view = design.getJSONObject(key)
            assertWithMessage(text).that(textWidth(text, view.getInt("textsize"))).isAtMost(view.getInt("width") - 4f)
        }
        // The arrow stays right of the glucose box
        assertThat(design.getJSONObject("direction").getInt("leftmargin")).isAtLeast(sgv.getInt("leftmargin") + sgv.getInt("width"))
    }

    private companion object {

        const val NAME = "AAPS_Tech"
        const val CUSTOM_FONT = "Cyber"
        const val SCALE = 2f

        const val CYAN = "#00F0FF"
        const val CLEAR = "#00000000"
        const val PANEL_COLOR = "#0B0B12"
        const val BASE_DARK = "#08090F"
        const val PANEL_DARK = "#0E1120"
        const val SCAN_DARK = "#141829"
        const val HEX_COLOR = "#10142A"
        const val HEX_RADIUS = 15f
        const val VALUE_COLOR = "#FFFFFF"
        const val SCAN_COLOR = "#12121C"
        const val LINE_COLOR = "#2A2A3C"
        const val LABEL_COLOR = "#8C93B8"
        const val DATE_COLOR = "#8C93B8"
        const val TIME_COLOR = "#00F0FF"
        const val HIGH_COLOR = "#FCEE0A"
        const val MID_COLOR = "#39FF14"
        const val LOW_COLOR = "#FF003C"
        const val GRID_COLOR = "#2A2F45"
        const val CARBS_COLOR = "#FF4FD8"
        const val BASAL_BACK_COLOR = "#7A1FA2"
        const val HEART_COLOR = "#FF4F7B"
        const val STEPS_COLOR = "#00F0FF"

        const val DATE_TOP = 6
        const val DATE_HEIGHT = 20
        const val DATE_SIZE = 16

        const val TIME_TOP = 26
        const val TIME_HEIGHT = 74
        const val TIME_SIZE = 64

        const val LOOP_LEFT = 58
        const val LOOP_TOP = 66
        const val LOOP_WIDTH = 42
        const val LOOP_HEIGHT = 36

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

        /**
         * The one value column. A SHORT_TEXT complication centres its text in the box part right of its icon,
         * which is estimated as [ICON_WIDTH] wide (the box is as high as the icon). The four text values use
         * the same part, so all six value centres are equal.
         */
        const val VAL_LEFT = TABLE_LEFT + ICON_WIDTH
        val VAL_RIGHT = rowRight(0) - ROW_PAD

        const val LOGO_LEFT = 54
        const val LOGO_TOP = 207
        const val LOGO_WIDTH = 124
        const val LOGO_HEIGHT = 52
        const val LOGO_SIZE = 19

        /** The picture of the beating drop, and its box in the 48 high space of logo 6: the drop with its outline, red copy and pointed tip. */
        const val LOGO_PULSE = "LogoPulse"
        const val DROP_BOX_LEFT = -3f
        const val DROP_BOX_TOP = -7f
        const val DROP_BOX_RIGHT = 35f
        const val DROP_BOX_BOTTOM = 49f
        const val PULSE_PX_PER_UNIT = 8

        const val RAIN_COLOR = "#5E86B8"
        /** At 120 a streak over the brightest pixel of the table gives a luminance of 0.093, too bright for white text; 64 gives about 0.056. */
        const val RAIN_ALPHA = 64

        /** How much larger the drop is at the top of a beat: the most that keeps it above the graph frame. */
        const val PULSE_AMPLITUDE = 0.20

        /** The animation frames: 466 px and 24 per second like the watch, from 0 to 6000 ms. */
        const val FRAME_SIZE = 466
        const val FRAME_FPS = 24
        const val FRAME_COUNT = 144

        /** The time of frame [i]. */
        fun frameMs(i: Int): Long = i * 1000L / FRAME_FPS

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
    }
}
