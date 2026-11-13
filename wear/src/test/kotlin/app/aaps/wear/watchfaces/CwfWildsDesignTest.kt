// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.JsonKeyValues
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.wear.watchfaces.daynight.DayNightClock
import app.aaps.wear.watchfaces.daynight.DayOverlay
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Wilds Weather" (`Wilds_Weather.zip`): a face for the day with a painted landscape of hills and far
 * mountains under the sky of the weather. Our own drawing, made from shapes only. The time, the date
 * and the glucose value stand on the landscape itself, which keeps a light, calm sky there; the table
 * and the graph sit on parchment. The texts are dark brown, the glucose colours the dark ones,
 * readable on parchment and on the sky. A warm sun goes along the edge from sunrise to sunset.
 *
 * The design itself is the sunny look; the looks for the weather (`partly`, `cloudy`, `rain`) swap the
 * landscape. The landscapes are separate jpg files the wearer may replace with own pictures (400 x
 * 400): `Background.jpg` (sunny, and when the weather is not known), `DayBackgroundPartly.jpg`,
 * `DayBackgroundCloudy.jpg` and `DayBackgroundRain.jpg`. The panels are not in them.
 *
 * The wake-up animation: the beating drop of the logo; rain only when it rains; no glitch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfWildsDesignTest : CyberpunkDesignBase() {

    override val displayName = "Wilds Weather"
    override val fileName = "Wilds_Weather"
    override val previewInstant = DAY_MS
    override val ageColor = DAY_LOW
    override val ownPictures = listOf(EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN).map { backgroundName(it) } +
        listOf(ResFileMap.COVER_CHART.fileName, CHART_PANEL, SUN)
    override val looks = listOf(
        Look("sunny", DAY_MS, EventData.Weather.CLEAR), Look("partly", DAY_MS, EventData.Weather.PARTLY),
        Look("cloudy", DAY_MS, EventData.Weather.CLOUDY), Look("rain", DAY_MS, EventData.Weather.RAIN),
        Look("no weather", DAY_MS, null), Look("morning 09:00, partly", MORNING_MS, EventData.Weather.PARTLY)
    )

    override fun hasRain(weather: String?) = weather == EventData.Weather.RAIN
    override fun hasGlitch(weather: String?) = false

    override fun design(default: CwfData): JSONObject = layout(
        default, "A painted landscape in the weather of now, with parchment panels: big time and glucose, the sun on the edge. No hands, no seconds"
    ).apply {
        mergeIn(paletteColors(WILDS))
        // Heart rate and steps lie below the panel layer: they get their own parchment, the panel has a hole there
        for (slot in listOf("complication1", "complication2")) getJSONObject(slot).put("color", PANEL_ARGB)
        // The panels in their own layer (cover_chart lies between the background and the texts), and the parchment under the graph
        getJSONObject("cover_chart").put("visibility", "visible")
        getJSONObject("chart").put("background", CHART_PANEL)
        putSun()
        // The beating drop for a few seconds after the wrist is raised
        put("animation", animation())
        put(DayOverlay.WEATHER_KEY, JSONObject().apply {
            for (weather in listOf(EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN))
                put(weather, JSONObject().put(DayOverlay.IMAGES_KEY, JSONObject().put(ResFileMap.BACKGROUND.fileName, backgroundName(weather))).apply {
                    if (weather == EventData.Weather.RAIN) put("animation", JSONObject().put("rain", rain(RAIN_COLOR, RAIN_ALPHA)))
                })
        })
    }

    /** The name of the landscape of [weather], the file the wearer may replace. */
    private fun backgroundName(weather: String?) = when (weather) {
        EventData.Weather.PARTLY -> "DayBackgroundPartly"
        EventData.Weather.CLOUDY -> "DayBackgroundCloudy"
        EventData.Weather.RAIN   -> "DayBackgroundRain"
        else                     -> ResFileMap.BACKGROUND.fileName
    }

    override fun images(): Map<String, ByteArray> =
        listOf(EventData.Weather.CLEAR, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN).associate { backgroundName(it) to landscape(it) } +
            mapOf(
                ResFileMap.COVER_CHART.fileName to panels(),
                CHART_PANEL to chartParchment(),
                ResFileMap.COVER_PLATE.fileName to coverPlate(WILDS.plate),
                LOGO_PULSE to logoPulse(WILDS.logo),
                SUN to wildsSunPng(),
                BLANK to blankPng()
            ) + arrowImages() + batteryImages()

    /**
     * The panel layer: only the table (the graph has its own parchment, see [chartParchment]). The
     * table has a hole where heart rate and steps are: those slots draw the same parchment
     * themselves, below this layer.
     */
    private fun panels(): ByteArray {
        val image = createBitmap(800, 800).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        val table = tablePanel()
        val slots = Path().apply {
            addRect(ROW_BOX_LEFT * SCALE, (TABLE_TOP + 4 * ROW_HEIGHT) * SCALE, VAL_RIGHT * SCALE, (TABLE_TOP + 6 * ROW_HEIGHT) * SCALE, Path.Direction.CW)
        }
        val withHole = Path(table).apply { op(slots, Path.Op.DIFFERENCE) }
        canvas.drawPath(withHole, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(PANEL_ARGB) })
        canvas.drawPath(table, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(WILDS.accent); style = Paint.Style.STROKE; strokeWidth = 2f * SCALE })
        return pngBytes(image)
    }

    /** The parchment under the graph: the chart panel with a bronze line, in the coordinates of the chart view. */
    private fun chartParchment(): ByteArray {
        val image = createBitmap(CHART_WIDTH * 2, CHART_HEIGHT * 2).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        canvas.translate(-CHART_LEFT * SCALE, -CHART_TOP * SCALE)
        val panel = chartPanel()
        canvas.drawPath(panel, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(PANEL_ARGB) })
        canvas.drawPath(panel, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(WILDS.accent); style = Paint.Style.STROKE; strokeWidth = 2f * SCALE })
        return pngBytes(image)
    }

    /** The sun: a golden disc with a soft ring of rays and a brown edge. */
    private fun wildsSunPng(): ByteArray {
        val image = createBitmap(SUN_PX, SUN_PX).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        canvas.scale(SUN_PX / SUN_SIZE.toFloat(), SUN_PX / SUN_SIZE.toFloat())
        val c = SUN_SIZE / 2f
        val r = SUN_SIZE / 2f - 4f
        canvas.drawCircle(c, c, r + 3.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 214, 102) })
        val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(GOLD); strokeWidth = 1.3f; strokeCap = Paint.Cap.ROUND }
        for (i in 0 until 12) {
            val a = i * PI / 6
            canvas.drawLine(c + (r + 1f) * cos(a).toFloat(), c + (r + 1f) * sin(a).toFloat(), c + (r + 3.5f) * cos(a).toFloat(), c + (r + 3.5f) * sin(a).toFloat(), ray)
        }
        canvas.drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD24A") })
        canvas.drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(WILDS.accent); style = Paint.Style.STROKE; strokeWidth = 1.2f })
        return pngBytes(image)
    }

    /** The colours of the sky and the land in one weather. */
    private data class Weathering(
        val skyTop: String, val skyLow: String, val mountain: String, val snow: String, val hill: String, val meadow: String, val tree: String,
        val clouds: Int, val cloud: String, val rain: Boolean
    )

    private fun weathering(weather: String): Weathering = when (weather) {
        EventData.Weather.PARTLY -> Weathering("#A5CDEC", "#E6F2F9", "#9DB5CC", "#F1F5F8", "#86B262", "#6A9C4A", "#2F5A26", 4, "#FFFFFF", false)
        EventData.Weather.CLOUDY -> Weathering("#C2CAD1", "#E4E8EB", "#A2AEB9", "#EDF0F2", "#7C9868", "#668553", "#33502C", 7, "#F2F4F6", false)
        EventData.Weather.RAIN   -> Weathering("#C8CED4", "#E0E4E7", "#96A2AD", "#E3E7EA", "#6E8A5D", "#5A7849", "#2A4424", 8, "#E7EAED", true)
        else                     -> Weathering("#9CCDF2", "#E6F4FC", "#A3BED6", "#F8FBFD", "#86BC62", "#69A748", "#2E5E25", 1, "#FFFFFF", false)
    }

    /**
     * A painted landscape of hills and far mountains under the sky of [weather], 400 x 400 as jpg (a
     * soft picture like this is much smaller as jpg). Drawn here, from shapes only. The horizon sits
     * where it can be seen: between the glucose value and the graph, and at the sides.
     */
    private fun landscape(weather: String): ByteArray {
        val w = weathering(weather)
        val size = 400
        val image = createBitmap(size, size).apply { eraseColor(Color.parseColor(w.skyLow)) }
        val canvas = Canvas(image)
        val horizon = 244f
        canvas.drawRect(0f, 0f, 400f, horizon + 20f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, horizon, Color.parseColor(w.skyTop), Color.parseColor(w.skyLow), Shader.TileMode.CLAMP) })
        // Soft clouds
        val random = Random(weather.hashCode().toLong())
        val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(w.cloud); maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL) }
        repeat(w.clouds) { i ->
            val cx = 30f + random.nextFloat() * 340f
            val cy = 26f + random.nextFloat() * 150f
            cloudPaint.alpha = 170 + random.nextInt(70)
            for (k in 0 until 4) canvas.drawOval(RectF(cx - 34f + k * 14f, cy - 9f - (k % 2) * 6f, cx - 4f + k * 14f, cy + 9f), cloudPaint)
            if (i % 3 == 2) canvas.drawOval(RectF(cx - 20f, cy - 18f, cx + 26f, cy + 4f), cloudPaint)
        }
        // Far mountains with snow on the highest tops. The tops stay below the glucose value and its labels (down to about y 204)
        val peaks = listOf(0f to 226f, 34f to 212f, 62f to 224f, 98f to 208f, 130f to 222f, 166f to 210f, 204f to 226f, 244f to 206f, 280f to 220f, 318f to 210f, 352f to 224f, 400f to 212f)
        val mountains = Path().apply {
            moveTo(0f, horizon + 10f)
            peaks.forEach { (x, y) -> lineTo(x, y) }
            lineTo(400f, horizon + 10f)
            close()
        }
        canvas.drawPath(mountains, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(w.mountain) })
        val snow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(w.snow) }
        peaks.zipWithNext().zipWithNext().forEach { (a, b) ->
            val (left, top) = a
            val (_, right) = b
            if (top.second < left.second && top.second < right.second && top.second < 214f) {
                val cap = Path().apply {
                    moveTo(top.first, top.second)
                    lineTo(top.first + (right.first - top.first) * 0.3f, top.second + (right.second - top.second) * 0.3f)
                    lineTo(top.first + 2f, top.second + 9f)
                    lineTo(top.first + (left.first - top.first) * 0.3f, top.second + (left.second - top.second) * 0.3f)
                    close()
                }
                canvas.drawPath(cap, snow)
            }
        }
        // Rolling hills, then the meadow in front
        fun hills(base: Float, amplitude: Float, waves: Float, phase: Float, fill: String) {
            val path = Path().apply {
                moveTo(0f, 400f)
                var x = 0f
                while (x <= 400f) {
                    lineTo(x, base - amplitude * sin((x / 400f * waves + phase) * 2 * PI).toFloat())
                    x += 4f
                }
                lineTo(400f, 400f)
                close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(fill) })
        }
        hills(horizon + 4f, 9f, 1.6f, 0.1f, w.hill)
        hills(horizon + 22f, 12f, 1.1f, 0.6f, w.meadow)
        // A winding path into the land, and a few trees on the hills
        val path = Path().apply {
            moveTo(150f, 400f)
            cubicTo(190f, 330f, 120f, 290f, 168f, horizon + 18f)
            lineTo(173f, horizon + 18f)
            cubicTo(132f, 290f, 210f, 330f, 182f, 400f)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9B27C") })
        val tree = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(w.tree) }
        for ((x, y, r) in listOf(Triple(18f, 250f, 9f), Triple(34f, 255f, 7f), Triple(214f, 256f, 8f), Triple(226f, 252f, 6f), Triple(372f, 258f, 10f), Triple(388f, 252f, 7f))) {
            canvas.drawRect(x - 1.2f, y, x + 1.2f, y + r + 4f, tree)
            canvas.drawCircle(x, y, r, tree)
        }
        if (w.rain) {
            val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 230, 236, 242); strokeWidth = 1f }
            repeat(160) {
                val x = random.nextFloat() * 420f
                val y = random.nextFloat() * 400f
                canvas.drawLine(x, y, x - 4f, y + 16f, streak)
            }
        }
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    }

    override fun checkLooks(design: JSONObject, pictures: List<Bitmap>) {
        val sunny = pictures[0]
        File(out, "${fileName}_sunny.png").writeBytes(pngBytes(sunny))
        // The table is parchment, and the sky behind the glucose value is light
        assertThat(luminance(sunny.getPixel(TABLE_LEFT + 2, TABLE_BOTTOM - 4))).isGreaterThan(0.6)
        assertThat(luminance(sunny.getPixel(40, 120))).isGreaterThan(0.5)
        // Each weather has its own sky; without weather it is the sunny one
        assertThat(pictures[2].getPixel(16, 60)).isNotEqualTo(sunny.getPixel(16, 60))
        assertThat(pictures[4].getPixel(16, 60)).isEqualTo(sunny.getPixel(16, 60))
        // The golden sun stands on its arc at noon
        val sun = orbit(JsonKeyValues.SUN_PROGRESS.key).point(DayNightClock(location = { null }).sunProgress(DAY_MS))
        assertThat(around(sunny, sun, 3).count { Color.red(it) > 230 && Color.blue(it) < 140 }).isGreaterThan(10)
    }

    @Test
    fun everyWeatherLookIsReadable() {
        val design = design(defaultDesign())
        val p = WILDS
        // The panels are a little transparent: count with the parchment over the darkest land
        val panel = Color.parseColor(PANEL_ARGB)
        val land = Color.parseColor("#2A4424")
        fun blend(c: (Int) -> Int) = (c(panel) * Color.alpha(panel) + c(land) * (255 - Color.alpha(panel))) / 255
        val parchment = String.format("#%02X%02X%02X", blend(Color::red), blend(Color::green), blend(Color::blue))
        for (weather in listOf(null, EventData.Weather.CLEAR, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN)) {
            val look = lookOf(design, weather)
            for (key in listOf("iob2", "cob2", "basalRate", "reservoir", "complication1", "complication2"))
                assertWithMessage("$weather $key").that(contrast(look.getJSONObject(key).getString("fontColor"), parchment)).isAtLeast(7.0)
            for (key in listOf("iob1", "cob1", "freetext2", "freetext3"))
                assertWithMessage("$weather $key").that(contrast(look.getJSONObject(key).getString("fontColor"), parchment)).isAtLeast(4.5)
            for (key in listOf("midColor", "highColor", "lowColor", "reservoirColor"))
                assertWithMessage("$weather $key").that(contrast(look.getString(key), parchment)).isAtLeast(4.5)
            // Its own landscape, the panels shown
            val swapped = look.optJSONObject(DayOverlay.IMAGES_KEY)?.optString(ResFileMap.BACKGROUND.fileName) ?: ResFileMap.BACKGROUND.fileName
            assertWithMessage("$weather landscape").that(swapped).isEqualTo(backgroundName(weather))
            assertThat(look.getJSONObject("cover_chart").getString("visibility")).isEqualTo("visible")
        }
        // The texts that stand on the landscape itself: against every pixel behind them, in every weather
        val darkTexts = listOf("hour", "minute", "day", "freetext1", "day_name", "month", WATCH_NUMBER_VIEW, PHONE_NUMBER_VIEW, "timestamp", "loop")
        for (weather in listOf(EventData.Weather.CLEAR, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN)) {
            val png = landscape(weather)
            assertWithMessage("$weather is a jpg").that(isJpeg(png)).isTrue()
            val picture = BitmapFactory.decodeByteArray(png, 0, png.size)
            val look = lookOf(design, weather)
            fun worst(key: String, color: String): Double {
                val r = rectOf(design.getJSONObject(key))
                var lowest = Double.MAX_VALUE
                for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) {
                    val pixel = picture.getPixel(x.coerceIn(0, 399), y.coerceIn(0, 399))
                    lowest = minOf(lowest, contrast(color, String.format("#%06X", pixel and 0xFFFFFF)))
                }
                return lowest
            }
            for (key in darkTexts) {
                val big = key in listOf("hour", "minute", "freetext1")
                assertWithMessage("$weather $key").that(worst(key, look.getJSONObject(key).getString("fontColor"))).isAtLeast(if (big) 3.0 else 4.5)
            }
            // The battery icons in every level colour, against the landscape behind them
            for ((view, dyn) in listOf(WATCH_ICON_VIEW to WATCH_ICON_DYN, PHONE_ICON_VIEW to PHONE_ICON_DYN))
                for (key in listOf("invalidColor", "color1", "color2", "color3"))
                    assertWithMessage("$weather $view $key").that(worst(view, look.getJSONObject("dynData").getJSONObject(dyn).getString(key))).isAtLeast(3.0)
            // The glucose value in all three colours
            for (color in listOf("midColor", "highColor", "lowColor")) assertWithMessage("$weather sgv $color").that(worst("sgv", look.getString(color))).isAtLeast(4.5)
        }
        // The slots of heart rate and steps draw the same parchment as the panel layer
        assertThat(design.getJSONObject("complication1").getString("color")).isEqualTo(PANEL_ARGB)
        assertThat(p.panel).isEqualTo("#" + PANEL_ARGB.takeLast(6))
    }

    private companion object {

        val WILDS = DayPalette("Wilds", bg = "#BFE0F2", panel = "#F3E9D2", ink = "#2B1D0E", muted = "#5C4426", label = "#6B5536", colon = "#8A5A00", accent = "#7A5A24", clouds = 0)

        /** The parchment, a little transparent so the land shows through. */
        const val PANEL_ARGB = "#F5F3E9D2"
        const val GOLD = "#C9A227"
        const val CHART_PANEL = "ChartPanel"
        const val RAIN_COLOR = "#3A4A5A"
        const val RAIN_ALPHA = 80
        const val JPEG_QUALITY = 82
    }
}
