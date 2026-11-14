// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.provider.Settings
import androidx.core.graphics.createBitmap
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.shared.impl.weardata.toDrawable
import app.aaps.wear.R
import app.aaps.wear.health.HealthKind
import app.aaps.wear.health.HealthReading
import app.aaps.wear.health.healthComplicationData
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.daynight.DayOverlay
import app.aaps.wear.watchfaces.utils.ComplicationStyleValues
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What every design test of a Custom watch face shares: sample data, a render-only face that shows a
 * design the way the watch does, the zip, and helpers to measure the pictures.
 *
 * No tests here: [CyberpunkDesignBase] has the tests of the four Cyberpunk and Wilds designs (one
 * layout), [GrandComplicationDesignBase] those of the two Grand Complication designs.
 *
 * Each test class writes its zip and previews to `build/cwf-design`, and checks the copy kept in the
 * repository (`wear/cwf-designs`) is the same design. To change a design: change it, run its test and
 * copy `build/cwf-design/<file>.zip` over the kept one.
 *
 * Sample values only, no real data.
 */
abstract class CwfDesignBase {

    /** The name the phone and the watch show. */
    protected abstract val displayName: String

    /** The name of the zip, without `.zip`. */
    protected abstract val fileName: String

    /** The design, built on the Default design that the real code generates. */
    protected abstract fun design(default: CwfData): JSONObject

    /** The pictures of the design. */
    protected abstract fun images(): Map<String, ByteArray>

    /** The fonts the zip brings, by name. None: the design uses the fonts of the watch. */
    protected open fun fonts(): Map<String, ByteArray> = emptyMap()

    /** The font of the values in the complication slots, as the watch draws them for this design. */
    protected open fun valueTypeface(): Typeface = Typeface.DEFAULT

    /**
     * A render-only face has no complication renderer: a design draws what its slots show here, with
     * [drawSlot]. Nothing by default.
     */
    protected open fun drawComplicationValues(canvas: Canvas, design: JSONObject, type: ComplicationType = ComplicationType.RANGED_VALUE) {}

    protected val out = File("build/cwf-design").apply { mkdirs() }

    /** One glucose sample: text, arrow, mg/dL value and level (1 high, 0 in range, -1 low). */
    protected data class Sample(val text: String = "6,7", val arrow: String = "→", val mgdl: Double = 120.0, val level: Long = 0)

    protected fun newFace() = CustomWatchface().apply { prepareForRendering(RuntimeEnvironment.getApplication()) }

    protected fun storeSampleData(face: CustomWatchface, sample: Sample = Sample(), bgAgeMin: Long = 2, loopAgeMin: Long = 3, phoneBattery: String = PHONE_SAMPLE) = runBlocking {
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
                battery = phoneBattery, rigBattery = "--", openApsStatus = now - loopAgeMin * 60_000L, bgi = "-0.4", batteryLevel = 1,
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

    /**
     * A text that only shows, in [color], when [valueKey] (an age in minutes) is [limit] or more:
     * below that its colour is transparent. `invalidFontColor` is the colour when there is no value.
     */
    protected fun ageDynData(valueKey: String, limit: Double, color: String): JSONObject = JSONObject()
        .put("valueKey", valueKey).put("minData", 0).put("maxData", 2 * limit)
        .put("invalidFontColor", color).put("fontColor1", CLEAR).put("fontColor2", color)

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
     * [instant] in [weather], with the watch battery at [watchBattery] %.
     */
    protected fun storedFace(
        design: JSONObject, default: CwfData, images: Map<String, ByteArray>, instant: Long, weather: String? = null, watchBattery: Int? = WATCH_SAMPLE
    ): CustomWatchface {
        setWatchBattery(watchBattery)
        val face = newFace()
        face.setRenderInstant(instant)
        face.sp.putBoolean(R.string.key_show_date, true)
        // What the watch sets from the metadata of the design (see layout)
        for (key in listOf(R.string.key_show_delta, R.string.key_show_avg_delta, R.string.key_show_uploader_battery, R.string.key_show_rig_battery))
            face.sp.putBoolean(key, true)
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
     * Gives the watch battery [percent] (null: the system tells no level), as the last battery broadcast
     * that the system keeps. A face reads it once and keeps it for a while, so set it before the face is made.
     */
    protected fun setWatchBattery(percent: Int?) {
        RuntimeEnvironment.getApplication().sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, percent ?: -1).putExtra(BatteryManager.EXTRA_SCALE, 100)
        )
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

    /** The wake-up animation of [design] as the watch reads it, with the pictures of [images]. */
    protected fun readAnimation(design: JSONObject, images: Map<String, ByteArray> = images()): WakeAnimationSpec {
        val resources = RuntimeEnvironment.getApplication().resources
        return CustomWatchface().readWakeAnimation(design) { key -> images[key]?.let { ResData(it, ResFormat.PNG).toDrawable(resources) } }!!
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

    protected fun block(design: JSONObject, vararg keys: String): RectF = keys.map { rectOf(design.getJSONObject(it)) }.reduce { a, b -> RectF(a).apply { union(b) } }

    protected companion object {

        /**
         * The largest zip the phone can send to the watch. The phone sends it in one Wear message;
         * 628 KB never arrived at the watch, with no error on either side, while 321 KB did.
         */
        const val MAX_ZIP_BYTES = 320_000

        const val SCALE = 2f

        const val CLEAR = "#00000000"

        /** The value of the phone battery, as the phone sends it. */
        const val PHONE_VALUE_KEY = "uploader_battery"

        /** The battery levels of the samples. */
        const val PHONE_SAMPLE = "78"
        const val WATCH_SAMPLE = 66

        const val BLANK = "Blank"

        /** The moments the pictures show: 11 November 2026 at 23:00 (night), at 09:00 and at noon (day), local time. */
        val NIGHT_MS: Long = LocalDateTime.of(2026, 11, 11, 23, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val MORNING_MS: Long = LocalDateTime.of(2026, 11, 11, 9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val DAY_MS: Long = LocalDateTime.of(2026, 11, 11, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
