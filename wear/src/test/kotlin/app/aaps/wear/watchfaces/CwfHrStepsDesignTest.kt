package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import com.google.common.truth.Truth.assertThat
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The "AAPS HR Steps" design: the built-in Default design with four round sub-dials in a row - carbs,
 * heart rate, steps and insulin, with icons instead of texts - the date at the top, and outline hands that start a little away from
 * the centre, so the text underneath stays readable. The graph keeps its full size.
 *
 * Built from the Default design that the real code generates, so it follows that design exactly. The
 * test writes the zip and a preview to `build/cwf-design`, and checks the copy kept in the repository
 * (`wear/cwf-designs`) is the same design. To change the design: change [design], run this test and
 * copy `build/cwf-design/AAPS_HR_Steps.zip` over the kept one.
 *
 * Sample values only, no real data.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfHrStepsDesignTest {

    private val out = File("build/cwf-design").apply { mkdirs() }
    private val kept = File("cwf-designs/$NAME.zip")

    private fun newFace() = CustomWatchface().apply { prepareForRendering(RuntimeEnvironment.getApplication()) }

    private fun storeSampleData(face: CustomWatchface) = runBlocking {
        val now = System.currentTimeMillis()
        val repository = face.complicationDataRepository
        repository.updateBgData(
            EventData.SingleBg(
                dataset = 0, timeStamp = now - 2 * 60_000L, sgvString = "10,4", glucoseUnits = "mmol", slopeArrow = "→",
                delta = "+0,2", deltaDetailed = "+0,2", avgDelta = "+0,1", avgDeltaDetailed = "+0,1", sgvLevel = 0, sgv = 187.0,
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
    private fun defaultDesign(): CwfData {
        val face = newFace()
        storeSampleData(face)
        face.renderToBitmap(400, 400)     // stores the Default design on first render
        return runBlocking { face.complicationDataRepository.getCustomWatchface(true) }!!
    }

    private fun JSONObject.place(key: String, left: Int, top: Int, size: Int) = place(key, left, top, size, size)
    private fun JSONObject.place(key: String, left: Int, top: Int, width: Int, height: Int): JSONObject =
        getJSONObject(key).put("leftmargin", left).put("topmargin", top).put("width", width).put("height", height).put("visibility", "visible")

    /** The Default design with the sub-dials, and the date moved to the top. */
    private fun design(default: CwfData): JSONObject = JSONObject(default.json).apply {
        getJSONObject("metadata")
            .put(CwfMetadataKey.CWF_NAME.key, "AAPS HR Steps")
            .put(CwfMetadataKey.CWF_FILENAME.key, NAME)
            .put(CwfMetadataKey.CWF_AUTHOR.key, "AAPS (local)")
            .put(CwfMetadataKey.CWF_CREATED_AT.key, "11/11/26")
            .put(CwfMetadataKey.CWF_COMMENT.key, "Default design with heart rate and steps sub-dials and the date at the top")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE.key, "true")
        // One row of four dials through the middle: carbs and insulin left, heart rate and steps right.
        // Heart rate right of the centre, steps outside it
        place("complication1", 212, 170, 58)
            .put("fontColor", "#FFFFFF").put("iconColor", "#E65050").put("ringPrimaryColor", "#E65050").put("ringSecondaryColor", RING_COLOR)
        place("complication2", 278, 170, 58)
            .put("fontColor", "#FFFFFF").put("iconColor", "#50C878").put("ringPrimaryColor", "#50C878").put("ringSecondaryColor", RING_COLOR)
        // Carbs on board outside, insulin on board left of the centre, with AAPS's own icons instead of the
        // "Carb" and "IOB" texts
        place("complication3", 64, 170, 58)
            .put("fontColor", "#FFFFFF").put("iconColor", CARBS_COLOR)
            .put("borderRadius", 29).put("borderColor", RING_COLOR).put("borderWidth", 3)
        place("complication4", 130, 170, 58)
            .put("fontColor", "#FFFFFF").put("iconColor", INSULIN_COLOR)
            .put("borderRadius", 29).put("borderColor", RING_COLOR).put("borderWidth", 3)
        for (text in listOf("cob1", "cob2", "iob1", "iob2")) getJSONObject(text).put("visibility", "gone")
        // The date at the top, around the 12 o'clock mark
        place("day_name", 130, 8, 36, 28).put("textsize", 20)
        place("day", 166, 8, 28, 28).put("textsize", 20)
        place("month", 206, 8, 44, 28).put("textsize", 20)
    }

    /** Renders [design] on black, with the two sub-dials drawn as an example, as the zip's preview image. */
    private fun preview(design: JSONObject, default: CwfData, images: Map<String, ByteArray> = emptyMap()): Bitmap {
        val face = newFace()
        face.sp.putBoolean(R.string.key_show_date, true)
        val resData = default.resData.toMutableMap()
        images.forEach { (name, png) -> resData[name] = ResData(png, ResFormat.PNG) }
        runBlocking { face.complicationDataRepository.storeCustomWatchface(CwfData(design.toString(4), ZipWatchfaceFormat.loadMetadata(design), resData)) }
        Thread.sleep(600)   // past the render data share window
        face.refreshRenderData()
        val image = createBitmap(400, 400).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(image)
        canvas.drawBitmap(face.renderToBitmap(400, 400), 0f, 0f, null)
        drawSubDial(canvas, design.getJSONObject("complication1"), "72", 0.25f, Color.rgb(230, 80, 80), R.drawable.ic_health_heart)
        drawSubDial(canvas, design.getJSONObject("complication2"), "6482", 0.65f, Color.rgb(80, 200, 120), R.drawable.ic_health_steps)
        drawSubDial(canvas, design.getJSONObject("complication3"), "12g", null, Color.parseColor(CARBS_COLOR), R.drawable.ic_carbs)
        drawSubDial(canvas, design.getJSONObject("complication4"), "1.25U", null, Color.parseColor(INSULIN_COLOR), R.drawable.ic_ins)
        return image
    }

    /** Roughly what the watch draws for a gauge complication: the ring, the app's own icon, the value. */
    private fun drawSubDial(canvas: Canvas, view: JSONObject, value: String, fill: Float?, color: Int, @DrawableRes icon: Int) {
        val r = rectOf(view)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; this.color = Color.parseColor(RING_COLOR) }
        canvas.drawOval(r, ring)
        if (fill != null) {    // a gauge; a plain value only has the ring
            ring.color = color
            canvas.drawArc(r, -90f, 360f * fill, false, ring)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textSize = r.height() * (if (value.length > 4) 0.27f else 0.32f); textAlign = Paint.Align.CENTER }
        canvas.drawText(value, r.centerX(), r.centerY() + r.height() * 0.22f, text)
        val half = (r.height() * 0.15f).toInt()
        val iconX = r.centerX().toInt()
        val iconY = (r.centerY() - r.height() * 0.16f).toInt()
        ContextCompat.getDrawable(RuntimeEnvironment.getApplication(), icon)!!.mutate().apply {
            setTint(color)
            setBounds(iconX - half, iconY - half, iconX + half, iconY + half)
            draw(canvas)
        }
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
        }
    }.toByteArray()

    private fun jsonInZip(zip: ByteArray): String = ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.first { it.name == "CustomWatchface.json" }
        input.readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun buildsTheDesignAndItsPreview() {
        val default = defaultDesign()
        val design = design(default)
        val json = design.toString(4)
        val hands = hands()
        val png = ByteArrayOutputStream().also { preview(design, default, hands).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val zip = zip(json, png, hands)
        File(out, "$NAME.zip").writeBytes(zip)
        File(out, "$NAME.png").writeBytes(png)

        // The phone accepts it: it needs a name and a preview image
        val loaded = ZipWatchfaceFormat.loadCustomWatchface(zip, "$NAME.zip", authorization = true)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.cwfData.metadata[CwfMetadataKey.CWF_NAME]).isEqualTo("AAPS HR Steps")
        // Switches the sub-dials and the date on, when the phone allows the design to set preferences
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4]).isEqualTo("true")
        assertThat(loaded.cwfData.metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE]).isEqualTo("true")
        // Brings its own hour and minute hands, the second hand stays the built-in one
        assertThat(loaded.cwfData.resData.keys).containsAtLeast(ResFileMap.HOUR_HAND.fileName, ResFileMap.MINUTE_HAND.fileName)
        assertThat(loaded.cwfData.resData.keys).doesNotContain(ResFileMap.SECOND_HAND.fileName)

        // The copy kept in the repository is this design
        assertThat(kept.exists()).isTrue()
        assertThat(JSONObject(jsonInZip(kept.readBytes())).toString(4)).isEqualTo(json)
        val keptEntries = ZipInputStream(kept.inputStream()).use { input -> generateSequence { input.nextEntry }.map { it.name }.toList() }
        assertThat(keptEntries).containsAtLeast("${ResFileMap.HOUR_HAND.fileName}.png", "${ResFileMap.MINUTE_HAND.fileName}.png")
    }

    @Test
    fun subDialsAndDateStayOnTheRoundScreenAndOffTheGraph() {
        val design = design(defaultDesign())
        val chart = rectOf(design.getJSONObject("chart"))
        assertThat(chart.height()).isEqualTo(170f)    // the graph keeps its full size
        for (key in listOf("complication1", "complication2")) {
            val r = rectOf(design.getJSONObject(key))
            // A circle inside the 400 px round screen, centre (200, 200)
            assertThat(hypot(r.centerX() - 200f, r.centerY() - 200f) + r.width() / 2).isAtMost(200f)
            assertThat(r.bottom).isAtMost(chart.top)
        }
        // One row: carbs, insulin | heart rate, steps; apart from each other, and from the hands' pivot
        val row = listOf("complication3", "complication4", "complication1", "complication2").map { rectOf(design.getJSONObject(it)) }
        row.zipWithNext().forEach { (left, right) -> assertThat(left.right).isLessThan(right.left) }
        assertThat(row[1].right).isLessThan(200f)
        assertThat(row[2].left).isGreaterThan(200f)
        row.forEach { r ->
            assertThat(hypot(r.centerX() - 200f, r.centerY() - 200f) + r.width() / 2).isAtMost(200f)
            assertThat(r.bottom).isAtMost(chart.top)
            assertThat(r.top).isAtLeast(rectOf(design.getJSONObject("delta")).bottom)    // below the delta row
        }
        // The "Carb" and "IOB" texts give way to the icons
        for (text in listOf("cob1", "cob2", "iob1", "iob2")) assertThat(design.getJSONObject(text).getString("visibility")).isEqualTo("gone")
        // The hands start past the two inner dials, so in no position they cross one (with room for their outline)
        for (key in listOf("complication4", "complication1")) {
            val r = rectOf(design.getJSONObject(key))
            assertThat(hypot(r.centerX() - 200f, r.centerY() - 200f) + r.width() / 2 + 2f).isAtMost(HAND_START)
        }
        // The date: the lower corners of its text boxes inside the round screen, above the glucose value
        for (key in listOf("day_name", "day", "month")) {
            val r = rectOf(design.getJSONObject(key))
            assertThat(hypot(r.left - 200f, r.bottom - 200f)).isAtMost(200f)
            assertThat(hypot(r.right - 200f, r.bottom - 200f)).isAtMost(200f)
            assertThat(r.bottom).isAtMost(rectOf(design.getJSONObject("sgv")).top + 10f)
        }
    }

    /** A hand image: 800 px square, pointing at 12 o'clock, turned about the centre by the watch face. */
    private fun handImage(draw: (Canvas, Float) -> Unit): ByteArray {
        val size = 800
        val image = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        draw(Canvas(image), size / 400f)   // the scale from the 400 px design space
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /**
     * A real hand along the 12 o'clock line: a shaft [width] wide from [from] (a little away from the
     * centre) to the start of the point, and a point ending at [to]. Drawn as an outline with a light
     * fill, so what lies underneath stays readable.
     */
    private fun hand(canvas: Canvas, scale: Float, from: Float, to: Float, width: Float, color: Int) {
        val c = 200f * scale
        val half = width / 2 * scale
        val pointStart = c - (to - width * 1.6f) * scale
        val path = android.graphics.Path().apply {
            moveTo(c - half, c - from * scale)
            lineTo(c - half, pointStart)
            lineTo(c, c - to * scale)
            lineTo(c + half, pointStart)
            lineTo(c + half, c - from * scale)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 55 })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * scale
            strokeJoin = Paint.Join.ROUND
        })
    }

    /**
     * The hands of this design: real hands with a shaft and a point, starting at [HAND_START], past the
     * sub-dials beside the centre, so they never cross them, and drawn as an outline with a light fill so the text
     * underneath stays readable. The second hand stays the built-in one.
     */
    private fun hands(): Map<String, ByteArray> = mapOf(
        ResFileMap.HOUR_HAND.fileName to handImage { c, s -> hand(c, s, HAND_START, 140f, 13f, Color.rgb(79, 195, 247)) },
        ResFileMap.MINUTE_HAND.fileName to handImage { c, s -> hand(c, s, HAND_START, 190f, 10f, Color.WHITE) }
    )

    private companion object {

        const val NAME = "AAPS_HR_Steps"

        const val RING_COLOR = "#3A3A3A"
        const val CARBS_COLOR = "#FB8C00"      // the design's own carbColor
        const val INSULIN_COLOR = "#64B5F6"    // AAPS's basal blue

        /** Where the hour and minute hands start, from the centre in the 400 px space. */
        const val HAND_START = 74f
    }
}
