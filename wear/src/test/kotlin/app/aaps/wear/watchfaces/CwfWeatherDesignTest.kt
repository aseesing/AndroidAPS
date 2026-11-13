// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.File

/**
 * "Cyberpunk Weather" (`Cyberpunk_Weather.zip`): a light face for the day, in a colour per weather.
 * Sunny is cyan, partly cloudy is Synth Lilac with two clouds, cloudy is Chrome with three clouds,
 * rain is Chrome with clouds and dark rain. The glucose value, the table and the graph sit on light
 * panels with dark text, so the glucose colours are the dark ones: same meaning, readable on light.
 *
 * The design itself is the sunny look; the looks for the weather (`partly`, `cloudy`, `rain`) change
 * the colours and swap the pictures. The sun goes along the edge from sunrise to sunset. The wake-up
 * animation: short glitch bands and the beating drop of the logo; rain only when it rains.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfWeatherDesignTest : CyberpunkDesignBase() {

    override val displayName = "Cyberpunk Weather"
    override val fileName = "Cyberpunk_Weather"
    override val previewInstant = DAY_MS
    override val ageColor = DAY_LOW
    override val ownPictures = listOf(SUN) + listOf(PARTLY, CLOUDY).flatMap { p -> pictureNames.map { it + p.name } }
    override val looks = listOf(
        Look("sunny", DAY_MS, EventData.Weather.CLEAR), Look("partly", DAY_MS, EventData.Weather.PARTLY),
        Look("cloudy", DAY_MS, EventData.Weather.CLOUDY), Look("rain", DAY_MS, EventData.Weather.RAIN),
        Look("no weather", DAY_MS, null), Look("morning 09:00, partly", MORNING_MS, EventData.Weather.PARTLY)
    )

    override fun hasRain(weather: String?) = weather == EventData.Weather.RAIN
    override fun hasGlitch(weather: String?) = true

    /** The pictures each palette has its own of. */
    private val pictureNames get() = listOf(ResFileMap.BACKGROUND.fileName, ResFileMap.COVER_PLATE.fileName, LOGO_PULSE)

    override fun design(default: CwfData): JSONObject = layout(
        default, "Cyberpunk HUD for the day, in a colour per weather: big time and glucose on light panels, the sun on the edge. No hands, no seconds"
    ).apply {
        mergeIn(paletteColors(SUNNY))
        putSun()
        // Short glitch bands and the beating drop for a few seconds after the wrist is raised
        put("animation", animation(glitch = glitch(SUNNY.accent)))
        put(
            DayOverlay.WEATHER_KEY, JSONObject()
                .put(EventData.Weather.PARTLY, weatherLook(PARTLY))
                .put(EventData.Weather.CLOUDY, weatherLook(CLOUDY))
                .put(EventData.Weather.RAIN, weatherLook(CLOUDY, rain = true))
        )
    }

    /** The look of [p]: its colours, its pictures and its glitch colour, and dark rain when it [rain]s. */
    private fun weatherLook(p: DayPalette, rain: Boolean = false): JSONObject = paletteColors(p).apply {
        put(DayOverlay.IMAGES_KEY, JSONObject().apply { for (name in pictureNames) put(name, name + p.name) })
        put("animation", JSONObject().put("glitch", glitch(p.accent)).apply { if (rain) put("rain", rain(p.ink, DAY_RAIN_ALPHA)) })
    }

    override fun images(): Map<String, ByteArray> = palettePictures(SUNNY, "") + palettePictures(PARTLY, PARTLY.name) + palettePictures(CLOUDY, CLOUDY.name) +
        mapOf(SUN to sunPng(), BLANK to blankPng()) + arrowImages()

    /** The background, the cover plate and the beating drop of [p], with [suffix] after their names. */
    private fun palettePictures(p: DayPalette, suffix: String): Map<String, ByteArray> = mapOf(
        ResFileMap.BACKGROUND.fileName + suffix to backgroundDay(p),
        ResFileMap.COVER_PLATE.fileName + suffix to coverPlate(p.plate),
        LOGO_PULSE + suffix to logoPulse(p.logo)
    )

    /** Where the clouds of a background sit, in the free spaces: left of the time, top right, right of the logo. */
    private val cloudSpots = listOf(Triple(48f, 62f, 5f), Triple(288f, 40f, 4.5f), Triple(186f, 214f, 5f))

    /**
     * A background in [p]: the colour of the weather, light panels for the glucose value, the table
     * and the graph with a thin frame in the accent colour, hazard stripes, the dashed track of the
     * sun, and [DayPalette.clouds] clouds. Flat colours only, no texture, so the picture stays very small.
     */
    private fun backgroundDay(p: DayPalette): ByteArray {
        val image = createBitmap(800, 800).apply { eraseColor(Color.parseColor(p.bg)) }
        val canvas = Canvas(image)
        val ink = Color.parseColor(p.ink)
        drawTrack(canvas, Color.argb(110, Color.red(ink), Color.green(ink), Color.blue(ink)))
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(p.panel) }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(p.accent); style = Paint.Style.STROKE; strokeWidth = 1.5f * SCALE }
        for (panel in listOf(glucosePanel(), tablePanel(), chartPanel())) {
            canvas.drawPath(panel, fill)
            canvas.drawPath(panel, edge)
        }
        hazardStripes(canvas, p.ink)
        canvas.save()
        canvas.scale(SCALE, SCALE)
        for ((left, top, unit) in cloudSpots.take(p.clouds))
            drawCloud(canvas, left, top, unit, Color.WHITE, ink, Color.parseColor(p.accent))
        canvas.restore()
        return pngBytes(image)
    }

    override fun checkLooks(design: JSONObject, pictures: List<Bitmap>) {
        val sunny = pictures[0]
        File(out, "${fileName}_sunny.png").writeBytes(pngBytes(sunny))
        // Left of the glucose panel: the weather colour
        assertThat(luminance(sunny.getPixel(16, 200))).isGreaterThan(0.4)
        assertThat(luminance(pictures[1].getPixel(16, 200))).isGreaterThan(0.4)
        // The glucose panel and the table are light
        assertThat(luminance(sunny.getPixel(226, 184))).isGreaterThan(0.7)
        assertThat(luminance(sunny.getPixel(TABLE_LEFT + 2, TABLE_BOTTOM - 4))).isGreaterThan(0.7)
        // Each weather has its own colour: lilac when partly, grey when cloudy
        assertThat(pictures[1].getPixel(16, 200)).isNotEqualTo(sunny.getPixel(16, 200))
        assertThat(pictures[2].getPixel(16, 200)).isNotEqualTo(sunny.getPixel(16, 200))
        // Without weather it is the sunny look
        assertThat(pictures[4].getPixel(16, 200)).isEqualTo(sunny.getPixel(16, 200))
        // The sun stands on its arc at noon: the yellow of the sun, not the cyan background, so its red is high
        val clock = DayNightClock(location = { null })
        val sun = orbit(JsonKeyValues.SUN_PROGRESS.key).point(clock.sunProgress(DAY_MS))
        assertThat(around(sunny, sun, 4).maxOf { Color.red(it) }).isGreaterThan(200)
    }

    @Test
    fun everyWeatherLookIsReadable() {
        val design = design(defaultDesign())
        val images = images()
        val glucoseNight = listOf(MID_COLOR, HIGH_COLOR, LOW_COLOR)
        for ((weather, p) in listOf(null to SUNNY, EventData.Weather.CLEAR to SUNNY, EventData.Weather.PARTLY to PARTLY, EventData.Weather.CLOUDY to CLOUDY, EventData.Weather.RAIN to CLOUDY)) {
            val look = lookOf(design, weather)
            val name = "$weather"
            // Texts on the background
            for (key in listOf("hour", "minute", "day", "complication5"))
                assertWithMessage("$name $key").that(contrast(look.getJSONObject(key).getString("fontColor"), p.bg)).isAtLeast(7.0)
            for (key in listOf("day_name", "month")) assertWithMessage("$name $key").that(contrast(look.getJSONObject(key).getString("fontColor"), p.bg)).isAtLeast(4.5)
            // The colon is big: 3 is enough
            assertWithMessage("$name colon").that(contrast(look.getJSONObject("freetext1").getString("fontColor"), p.bg)).isAtLeast(3.0)
            // Texts and glucose colours on the light panels
            for (key in listOf("iob2", "cob2", "basalRate", "reservoir", "complication1", "complication2"))
                assertWithMessage("$name $key").that(contrast(look.getJSONObject(key).getString("fontColor"), p.panel)).isAtLeast(7.0)
            for (key in listOf("iob1", "cob1", "freetext2", "freetext3"))
                assertWithMessage("$name $key").that(contrast(look.getJSONObject(key).getString("fontColor"), p.panel)).isAtLeast(4.5)
            for (key in listOf("midColor", "highColor", "lowColor", "reservoirColor", "reservoirWarningColor", "reservoirUrgentColor"))
                assertWithMessage("$name $key").that(contrast(look.getString(key), p.panel)).isAtLeast(4.5)
            // Green, yellow and red keep their meaning: no text on the background takes a glucose colour
            val glucose = listOf("midColor", "highColor", "lowColor").map { look.getString(it) } + glucoseNight
            for (key in listOf("hour", "minute", "freetext1", "day", "day_name", "month"))
                assertWithMessage("$name $key").that(look.getJSONObject(key).getString("fontColor")).isNotIn(glucose)
            // The pictures of its palette, also the beating drop
            val pictures = lookImages(look, images)
            for (picture in pictureNames)
                assertWithMessage("$name $picture").that(pictures.getValue(picture)).isEqualTo(images.getValue(if (p == SUNNY) picture else picture + p.name))
            // The sun on the edge, no moon and no cloud before it
            assertThat(look.getJSONObject("freetext4").getString("background")).isEqualTo(SUN)
            assertThat(look.getJSONObject("freetext4").getJSONObject("orbit").getString("valueKey")).isEqualTo(JsonKeyValues.SUN_PROGRESS.key)
            assertThat(look.getJSONObject("freetext4").has("dynData")).isFalse()
            assertThat(look.getJSONObject("patient_name").getString("visibility")).isEqualTo("gone")
            // The glitch in the colour of the palette
            assertThat(look.getJSONObject("animation").getJSONObject("glitch").getString("color2")).isEqualTo(p.accent)
        }
    }

    private companion object {

        const val DAY_RAIN_ALPHA = 90

        /** The looks, by weather: sunny, partly cloudy, and cloudy or rain. */
        val SUNNY = DayPalette("Sunny", bg = "#00E5FF", panel = "#E6FCFF", ink = "#0A0A0A", muted = "#00454D", label = "#2F6F78", colon = "#6A00F4", accent = "#6A00F4", clouds = 0)
        val PARTLY = DayPalette("Partly", bg = "#C9B6FF", panel = "#F3EEFF", ink = "#12002E", muted = "#3D2A6B", label = "#5A4A85", colon = "#5B00B8", accent = "#FF2BD6", clouds = 2)
        val CLOUDY = DayPalette("Cloudy", bg = "#D7DDE5", panel = "#F5F7FA", ink = "#0D1020", muted = "#4A5268", label = "#5B6478", colon = "#006B78", accent = "#0099A8", clouds = 3)
    }
}
