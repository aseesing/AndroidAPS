// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.max

/**
 * "Cyberpunk Yellow" (`Cyberpunk_Yellow.zip`), once called "Signaalgeel": a face for the day. Signal
 * yellow with a faint weave, and black panels for the glucose value, the table and the graph. On
 * those panels everything keeps its night colours, so the glucose value is neon green, yellow or red
 * on black, and the values are white. The time and the date are black on the yellow. Yellow and black
 * hazard stripes run along the bottom of the glucose panel.
 *
 * The sun goes along the edge from sunrise (left) to sunset (right). The wake-up animation: short
 * glitch bands and the beating drop of the logo; no rain. There are no looks for the weather.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfYellowDesignTest : CyberpunkDesignBase() {

    override val displayName = "Cyberpunk Yellow"
    override val fileName = "Cyberpunk_Yellow"
    override val previewInstant = DAY_MS
    override val ageColor = LOW_COLOR
    override val ownPictures = listOf(SUN)
    override val looks = listOf(
        Look("morning 09:00", MORNING_MS, null), Look("noon", DAY_MS, null), Look("afternoon 16:00", AFTERNOON_MS, null)
    )

    override fun hasRain(weather: String?) = false
    override fun hasGlitch(weather: String?) = true

    override fun design(default: CwfData): JSONObject = layout(
        default, "Cyberpunk HUD for the day: signal yellow with black panels, big time and glucose, the sun on the edge. No hands, no seconds"
    ).apply {
        // Only the texts on the yellow change colour; the panels are black, as at night
        mergeIn(JSONObject().apply {
            put("day_name", JSONObject().put("fontColor", YELLOW_MUTED))
            put("month", JSONObject().put("fontColor", YELLOW_MUTED))
            for (key in listOf("day", "hour", "minute", WATCH_NUMBER_VIEW, PHONE_NUMBER_VIEW)) put(key, JSONObject().put("fontColor", YELLOW_INK))
            // The batteries stand on yellow: the dark colours of the light looks
            put("dynData", JSONObject().put(WATCH_NUMBER_DYN, JSONObject().put("fontColor1", YELLOW_INK)).put(PHONE_NUMBER_DYN, JSONObject().put("fontColor1", YELLOW_INK))
                .mergeIn(batteryDayColours()))
            put("freetext1", JSONObject().put("fontColor", YELLOW_TEAL))
            for (label in listOf("iob1", "cob1", "freetext2", "freetext3")) put(label, JSONObject().put("fontColor", YELLOW_LABEL))
        })
        putSun()
        // Short glitch bands and the beating drop for a few seconds after the wrist is raised
        put("animation", animation(glitch = glitch(YELLOW_CYAN)))
    }

    override fun images(): Map<String, ByteArray> = mapOf(
        ResFileMap.BACKGROUND.fileName to backgroundYellow(),
        ResFileMap.COVER_PLATE.fileName to coverPlate(YELLOW_PLATE),
        LOGO_PULSE to logoPulse(YELLOW_LOGO),
        SUN to sunPng(),
        BLANK to blankPng()
    ) + arrowImages() + batteryImages()

    /**
     * The background: signal yellow with a faint carbon weave and the faint dashed track of the sun,
     * and black panels for the glucose value, the table and the graph, with faint scan lines. Yellow
     * and black hazard stripes along the bottom of the glucose panel.
     */
    private fun backgroundYellow(): ByteArray {
        val image = createBitmap(800, 800).apply { eraseColor(Color.parseColor(YELLOW)) }
        val canvas = Canvas(image)
        val weave = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(20, 0, 0, 0); strokeWidth = 1f * SCALE }
        var d = -800f
        while (d < 800f) {
            canvas.drawLine(d, 0f, d + 800f, 800f, weave)
            canvas.drawLine(d + 800f, 0f, d, 800f, weave)
            d += 4f * SCALE
        }
        drawTrack(canvas, Color.argb(110, 10, 10, 10))
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(YELLOW_INK) }
        for (panel in listOf(glucosePanel(), tablePanel(), chartPanel())) {
            canvas.drawPath(panel, fill)
            scanLines(canvas, panel, Color.parseColor("#141414"))
        }
        hazardStripes(canvas, YELLOW)
        return pngBytes(image)
    }

    override fun checkLooks(design: JSONObject, pictures: List<Bitmap>) {
        val noon = pictures[1]
        File(out, "${fileName}_noon.png").writeBytes(pngBytes(noon))
        // Left of the glucose panel: yellow
        assertThat(luminance(noon.getPixel(19, 200))).isGreaterThan(0.5)
        // The glucose panel and the table are black
        assertThat(luminance(noon.getPixel(226, 184))).isLessThan(0.02)
        assertThat(luminance(noon.getPixel(TABLE_LEFT + 2, TABLE_BOTTOM - 4))).isLessThan(0.05)
        // The sun stands on its arc: its dark ring on the yellow, more to the left in the morning
        val clock = DayNightClock(location = { null })
        for ((i, instant) in listOf(MORNING_MS, DAY_MS, AFTERNOON_MS).withIndex()) {
            val sun = orbit(JsonKeyValues.SUN_PROGRESS.key).point(clock.sunProgress(instant))
            assertWithMessage("sun at $i").that(darkestAround(pictures[i], sun, SUN_SIZE / 2)).isLessThan(0.02)
            // The warm yellow of the disc in its middle
            assertWithMessage("sun at $i").that(Color.red(pictures[i].getPixel(sun.first.toInt(), sun.second.toInt()))).isGreaterThan(200)
        }
        val morning = orbit(JsonKeyValues.SUN_PROGRESS.key).point(clock.sunProgress(MORNING_MS))
        val afternoon = orbit(JsonKeyValues.SUN_PROGRESS.key).point(clock.sunProgress(AFTERNOON_MS))
        assertThat(morning.first).isLessThan(afternoon.first)
    }

    @Test
    fun theTextsOnYellowAreDarkAndThePanelsKeepTheNightColours() {
        val design = design(defaultDesign())
        assertThat(design.has(DayOverlay.WEATHER_KEY)).isFalse()
        // The texts on yellow are dark, with enough contrast
        for (key in listOf("hour", "minute", "day", WATCH_NUMBER_VIEW, PHONE_NUMBER_VIEW))
            assertWithMessage(key).that(contrast(design.getJSONObject(key).getString("fontColor"), YELLOW)).isAtLeast(7.0)
        for (key in listOf("day_name", "month")) assertWithMessage(key).that(contrast(design.getJSONObject(key).getString("fontColor"), YELLOW)).isAtLeast(4.5)
        // The battery icons: every level colour can be seen on yellow
        for (dyn in listOf(WATCH_ICON_DYN, PHONE_ICON_DYN)) for (key in listOf("invalidColor", "color1", "color2", "color3")) {
            val colour = design.getJSONObject("dynData").getJSONObject(dyn).getString(key)
            assertWithMessage("$dyn $key").that(contrast(colour, YELLOW)).isAtLeast(3.0)
        }
        // The colon is big: 3 is enough
        assertThat(contrast(design.getJSONObject("freetext1").getString("fontColor"), YELLOW)).isAtLeast(3.0)
        // On the black panels: the night colours. White values, the neon glucose colours, readable labels
        for (key in listOf("iob2", "cob2", "basalRate", "reservoir", "complication1", "complication2"))
            assertWithMessage(key).that(design.getJSONObject(key).getString("fontColor")).isEqualTo(VALUE_COLOR)
        for (key in listOf("iob1", "cob1", "freetext2", "freetext3"))
            assertWithMessage(key).that(contrast(design.getJSONObject(key).getString("fontColor"), YELLOW_INK)).isAtLeast(4.5)
        assertThat(design.getString("midColor")).isEqualTo(MID_COLOR)
        assertThat(design.getString("highColor")).isEqualTo(HIGH_COLOR)
        assertThat(design.getString("lowColor")).isEqualTo(LOW_COLOR)
        for (key in listOf("midColor", "highColor", "lowColor"))
            assertWithMessage(key).that(contrast(design.getString(key), YELLOW_INK)).isAtLeast(4.5)
        // Green, yellow and red keep their meaning: no other text takes a glucose colour
        val glucose = listOf("midColor", "highColor", "lowColor").map { design.getString(it) }
        for (key in listOf("hour", "minute", "freetext1", "cob2", "iob2", "basalRate", "reservoir", "day", "day_name", "month"))
            assertWithMessage(key).that(design.getJSONObject(key).getString("fontColor")).isNotIn(glucose)
        // The sun on the edge, from sunrise to sunset; no moon
        val sun = design.getJSONObject("freetext4")
        assertThat(sun.getString("background")).isEqualTo(SUN)
        assertThat(sun.getJSONObject("orbit").getString("valueKey")).isEqualTo(JsonKeyValues.SUN_PROGRESS.key)
        assertThat(sun.has("dynData")).isFalse()
        // The pixels behind the table and above the stripes of the glucose panel are black
        val png = images()[ResFileMap.BACKGROUND.fileName]!!
        val background = BitmapFactory.decodeByteArray(png, 0, png.size)
        var worst = 0.0
        for (y in (TABLE_TOP * 2) until (TABLE_BOTTOM * 2) step 2)
            for (x in (TABLE_LEFT * 2) until (VAL_RIGHT * 2) step 2) worst = max(worst, luminance(background.getPixel(x, y)))
        for (y in ((GL_TOP + 16) * 2) until ((GL_BOTTOM - 8) * 2) step 2)
            for (x in ((GL_LEFT + 4) * 2) until ((GL_RIGHT - 16) * 2) step 2) worst = max(worst, luminance(background.getPixel(x, y)))
        assertWithMessage("brightest background pixel behind the data").that(worst).isLessThan(0.02)
    }

    private companion object {

        /** Signal yellow and black, a red accent and a teal one. */
        const val YELLOW = "#F5E400"
        const val YELLOW_INK = "#0A0A0A"
        const val YELLOW_MUTED = "#4A4500"
        const val YELLOW_LABEL = "#CFC64A"
        const val YELLOW_TEAL = "#007A86"
        const val YELLOW_CYAN = "#00B8C8"
        const val YELLOW_LINE = "#3A3600"

        val YELLOW_LOGO = Ink(line = YELLOW_INK, lineShadow = LOW_COLOR, text = YELLOW_INK, textShadow1 = LOW_COLOR, textShadow2 = YELLOW_CYAN)
        val YELLOW_PLATE = Plate(frame = LOW_COLOR, thin = YELLOW_LINE, label = YELLOW_LABEL, logo = YELLOW_LOGO)

        /** 11 November 2026 at 16:00, local time. */
        val AFTERNOON_MS: Long = DAY_MS + 4 * 3_600_000L
    }
}
