// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.wear.watchface.complications.data.ComplicationType
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.JsonKeyValues
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.wear.health.HealthKind
import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.WakeAnimator
import app.aaps.wear.watchfaces.animation.WakeTimer
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
import java.util.Base64
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Cyberpunk Tech" (`Cyberpunk_Tech.zip`): the dark neon HUD, a face for the night. The time in cyan,
 * the glucose value in neon green, yellow or red, white values in the table, a dark navy background
 * with a faint weave.
 *
 * The moon goes along the edge through the night, from sunset (left) to sunrise (right), and shows
 * the picture nearest to the phase of now. When the weather is cloudy a cloud covers part of it
 * (partly) or most of it (cloudy, rain).
 *
 * The wake-up animation: rain under the data and the drop of the logo beating at the heart rate. The
 * test also writes frames of it, drawn by the real face, and a flipbook page that plays them to
 * `build/cwf-design/anim`.
 *
 * The layout is the same in all four Cyberpunk designs (see [CyberpunkDesignBase.layout]), so the
 * layout tests are here only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfTechDesignTest : CyberpunkDesignBase() {

    override val displayName = "Cyberpunk Tech"
    override val fileName = "Cyberpunk_Tech"
    override val previewInstant = NIGHT_MS
    override val ageColor = LOW_COLOR
    override val ownPictures get() = (1..MOON_STEPS).map { moonName(it) } + listOf(CLOUD_NIGHT_PARTLY, CLOUD_NIGHT_FULL)
    override val looks = listOf(
        Look("night clear", NIGHT_MS, EventData.Weather.CLEAR), Look("night partly", NIGHT_MS, EventData.Weather.PARTLY),
        Look("night cloudy", NIGHT_MS, EventData.Weather.CLOUDY), Look("night rain", NIGHT_MS, EventData.Weather.RAIN),
        Look("night, no weather", NIGHT_MS, null), Look("evening 20:00", EVENING_MS, null)
    )

    /** The rain always falls, also on a clear night. */
    override fun hasRain(weather: String?) = true
    override fun hasGlitch(weather: String?) = false

    override fun design(default: CwfData): JSONObject = layout(
        default, "Cyberpunk HUD for the night: big time and glucose, a small data table and the graph, the moon on the edge. No hands, no seconds"
    ).apply {
        // The moon goes along the edge through the night, from sunset (left) to sunrise (right). It
        // shows one of MOON_STEPS pictures, chosen by the phase of today. The place in the json is the
        // middle of its way; the watch moves it (see orbit).
        val top = orbit(JsonKeyValues.NIGHT_PROGRESS.key).point(0.5)
        place("freetext4", (top.first - MOON_SIZE / 2f).toInt(), (top.second - MOON_SIZE / 2f).toInt(), MOON_SIZE)
            .put("textvalue", "").put("dynData", MOON_DYN).put("orbit", orbitJson(JsonKeyValues.NIGHT_PROGRESS.key))
        // A cloud in front of the moon, only when the weather is cloudy (see the weather looks)
        place("patient_name", (top.first - CLOUD_SIZE / 2f).toInt(), (top.second - CLOUD_SIZE / 2f).toInt(), CLOUD_SIZE)
            .put("visibility", "gone").put("textvalue", "").put("fontColor", CLEAR).put("background", CLOUD_NIGHT_PARTLY)
            .put("orbit", orbitJson(JsonKeyValues.NIGHT_PROGRESS.key))
        getJSONObject("dynData").put(MOON_DYN, moonDynData())

        // Rain and the beating drop for a few seconds after the wrist is raised
        put("animation", animation(rain = rain(RAIN_COLOR, RAIN_ALPHA)))

        // A cloud covers part (partly) or most (cloudy, rain) of the moon
        put(
            DayOverlay.WEATHER_KEY, JSONObject()
                .put(EventData.Weather.PARTLY, cloud(CLOUD_NIGHT_PARTLY))
                .put(EventData.Weather.CLOUDY, cloud(CLOUD_NIGHT_FULL))
                .put(EventData.Weather.RAIN, cloud(CLOUD_NIGHT_FULL))
        )
    }

    private fun cloud(picture: String): JSONObject =
        JSONObject().put("patient_name", JSONObject().put("visibility", "visible").put("background", picture))

    override fun images(): Map<String, ByteArray> = techImages()

    private fun techImages(bg: Char = 'G'): Map<String, ByteArray> = mapOf(
        ResFileMap.BACKGROUND.fileName to background(bg),
        ResFileMap.COVER_PLATE.fileName to coverPlate(),
        LOGO_PULSE to logoPulse()
    ) + arrowImages() + moonImages() + mapOf(
        CLOUD_NIGHT_PARTLY to cloudNightPng(full = false),
        CLOUD_NIGHT_FULL to cloudNightPng(full = true),
        BLANK to blankPng()
    ) + batteryImages()

    override fun checkLooks(design: JSONObject, pictures: List<Bitmap>) {
        val clear = pictures[0]
        File(out, "${fileName}_night.png").writeBytes(pngBytes(clear))
        // Left of the glucose panel: dark
        assertThat(luminance(clear.getPixel(16, 200))).isLessThan(0.05)
        // On 11 November at 23:00 the moon is 2 days old, so the watch itself must pick the thin crescent, not the dark new moon picture
        val clock = DayNightClock(location = { null })
        val moon = orbit(JsonKeyValues.NIGHT_PROGRESS.key).point(clock.nightProgress(NIGHT_MS))
        assertThat(brightestAround(clear, moon, MOON_SIZE / 2)).isGreaterThan(0.6)
        // A cloud in front of the moon when it is cloudy: the place of the moon changes
        val box = Rect(moon.first.toInt() - CLOUD_SIZE / 2, moon.second.toInt() - CLOUD_SIZE / 2, moon.first.toInt() + CLOUD_SIZE / 2, moon.second.toInt() + CLOUD_SIZE / 2)
        assertThat(maxDifference(pictures[2], clear, only = box)).isGreaterThan(60)
        assertThat(maxDifference(pictures[4], clear, only = box)).isEqualTo(0)
        // In the evening the moon is on the left
        val evening = orbit(JsonKeyValues.NIGHT_PROGRESS.key).point(clock.nightProgress(EVENING_MS))
        assertThat(evening.first).isLessThan(moon.first)
        assertThat(brightestAround(pictures[5], evening, MOON_SIZE / 2)).isGreaterThan(0.6)
    }

    // ------------------------------------------------------------------------------------------
    // The moon
    // ------------------------------------------------------------------------------------------

    /**
     * The moon view shows the picture nearest to the phase of now. Picture k shows the phase
     * (k - 1) / [MOON_STEPS], so the watch must round, not cut: the data range starts half a step
     * before 0 and ends half a step after 1, and there is one entry more than there are pictures. The
     * last entry, around the next new moon, is the new moon picture again.
     *
     * `invalidImage` is needed even though the phase is always known: without it the watch counts one
     * step less than there are entries (`DynProvider.stepDraw` is the number of pictures minus one,
     * the invalid picture included), so the last entry is never shown and every step is too wide.
     */
    private fun moonDynData(): JSONObject = JSONObject().put("valueKey", JsonKeyValues.MOON_PHASE.key)
        .put("minData", -0.5 / MOON_STEPS).put("maxData", 1 + 0.5 / MOON_STEPS).put("invalidImage", moonName(1)).apply {
            for (k in 1..MOON_STEPS) put("image$k", moonName(k))
            put("image${MOON_STEPS + 1}", moonName(1))
        }

    /** The picture [phase] gets on the watch, in the same way the watch picks it (see `DynProvider.stepValue`). */
    private fun moonPictureFor(phase: Double): Int {
        val min = -0.5 / MOON_STEPS
        val max = 1 + 0.5 / MOON_STEPS
        val steps = MOON_STEPS + 1
        val entry = 1 + ((phase - min) * steps / (max - min)).toInt()
        return if (entry > MOON_STEPS) 1 else entry
    }

    private fun moonImages(): Map<String, ByteArray> = (1..MOON_STEPS).associate { moonName(it) to moonPng(moonPhaseOf(it)) }

    /** The name of moon picture [k] (1 .. [MOON_STEPS]). */
    private fun moonName(k: Int) = "Moon%02d".format(k)

    /** The phase moon picture [k] shows: 0 (new moon) for the first, then one step more for each. */
    private fun moonPhaseOf(k: Int): Double = (k - 1.0) / MOON_STEPS

    /**
     * The part of the moon that is lit at [phase] (0 new, 0.5 full), as seen from the north: the light
     * grows from the right. A half circle on the lit side and half an ellipse (the line between light
     * and dark), in a circle of radius [r] around [c], [c]. A crescent is never thinner than
     * [MOON_MIN_CRESCENT] (in design units), so it can be seen on the small screen; the new moon stays dark.
     */
    private fun moonLit(phase: Double, c: Float, r: Float): Path {
        val litRight = phase < 0.5
        val k = cos(2 * PI * phase).toFloat()
        val rx = when {
            phase <= 0.0 -> r                               // new moon: nothing lit
            k > 0        -> min(k * r, r - MOON_MIN_CRESCENT)
            else         -> abs(k) * r
        }
        // A crescent bulges to the lit side, a gibbous moon to the dark side
        val bulgeRight = litRight == (k > 0)
        return Path().apply {
            moveTo(c, c - r)
            arcTo(RectF(c - r, c - r, c + r, c + r), -90f, if (litRight) 180f else -180f)
            arcTo(RectF(c - rx, c - r, c + rx, c + r), 90f, if (bulgeRight) -180f else 180f)
            close()
        }
    }

    /**
     * One moon picture, [MOON_PX] pixels for the [MOON_SIZE] view: a dark navy disc with a faint cyan
     * ring, and the lit part in ice white drawn over the ring, so a thin crescent at the edge is not
     * hidden by it. No glow: a soft glow makes each picture about three times larger, and the zip must
     * stay small.
     */
    private fun moonPng(phase: Double): ByteArray {
        val image = createBitmap(MOON_PX, MOON_PX).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        canvas.scale(MOON_PX / MOON_SIZE.toFloat(), MOON_PX / MOON_SIZE.toFloat())
        val c = MOON_SIZE / 2f
        val r = MOON_RADIUS
        canvas.drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141B30") })
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.argb(115, 0, 240, 255) }
        canvas.drawCircle(c, c, r, ring)
        canvas.drawPath(moonLit(phase, c, r + 0.75f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(MOON_LIGHT) })
        return pngBytes(image)
    }

    /**
     * The cloud in front of the moon: [CLOUD_SIZE] square, with the moon in its middle. When not
     * [full] it covers the lower part of the moon; when [full] nearly all of it.
     */
    private fun cloudNightPng(full: Boolean): ByteArray {
        val image = createBitmap(CLOUD_PX, CLOUD_PX).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        canvas.scale(CLOUD_PX / CLOUD_SIZE.toFloat(), CLOUD_PX / CLOUD_SIZE.toFloat())
        val unit = if (full) 3.6f else 3f
        val width = 9 * unit
        val left = (CLOUD_SIZE - width) / 2f
        val top = if (full) CLOUD_SIZE / 2f - 2.2f * unit else CLOUD_SIZE / 2f
        drawCloud(canvas, left, top, unit, Color.parseColor("#2A3147"), Color.argb(200, 0, 240, 255), Color.argb(160, 255, 0, 60))
        return pngBytes(image)
    }

    /** Moon picture [phase] drawn on black, the way it shows at night. */
    private fun moonOnBlack(phase: Double): Bitmap {
        val png = moonPng(phase)
        val moon = BitmapFactory.decodeByteArray(png, 0, png.size)
        return createBitmap(MOON_PX, MOON_PX).apply { eraseColor(Color.BLACK); Canvas(this).drawBitmap(moon, 0f, 0f, null) }
    }

    /** The part of the disc of [bitmap] that is lit (on the [side] given), from 0 to 1. */
    private fun litShare(bitmap: Bitmap, side: IntRange = 0 until MOON_PX): Double {
        val radius = MOON_PX / 2f * MOON_RADIUS / (MOON_SIZE / 2f)
        var lit = 0
        var all = 0
        for (y in 0 until MOON_PX) for (x in side) {
            if (hypot(x + 0.5f - MOON_PX / 2f, y + 0.5f - MOON_PX / 2f) > radius) continue
            all++
            if (luminance(bitmap.getPixel(x, y)) > 0.5) lit++
        }
        return lit.toDouble() / all
    }

    /** The widest run of lit pixels on the middle row of [bitmap]. */
    private fun litWidth(bitmap: Bitmap): Int {
        var widest = 0
        var run = 0
        for (x in 0 until MOON_PX) {
            run = if (luminance(bitmap.getPixel(x, MOON_PX / 2)) > 0.5) run + 1 else 0
            widest = max(widest, run)
        }
        return widest
    }

    @Test
    fun theMoonPicturesFollowThePhase() {
        // New moon is dark; from the next picture on there is always light to see
        assertThat(litShare(moonOnBlack(0.0))).isLessThan(0.01)
        for (k in 2..MOON_STEPS) {
            val moon = moonOnBlack(moonPhaseOf(k))
            // At least about 3 pixels on a 466 pixel watch, where the moon is about 28 pixels wide
            assertWithMessage("moon $k").that(litWidth(moon) * 28f / MOON_PX).isAtLeast(2.5f)
        }
        assertThat(litShare(moonOnBlack(0.5))).isGreaterThan(0.9)
        assertThat(litShare(moonOnBlack(0.25))).isWithin(0.08).of(0.5)
        // Waxing: light on the right; waning: on the left
        val left = 0 until MOON_PX / 2
        val right = MOON_PX / 2 until MOON_PX
        assertThat(litShare(moonOnBlack(0.25), right)).isGreaterThan(0.9)
        assertThat(litShare(moonOnBlack(0.25), left)).isLessThan(0.1)
        assertThat(litShare(moonOnBlack(0.75), left)).isGreaterThan(0.9)
        assertThat(litShare(moonOnBlack(0.75), right)).isLessThan(0.1)
        // Gibbous is more than half, crescent less
        assertThat(litShare(moonOnBlack(0.375))).isGreaterThan(0.7)
        assertThat(litShare(moonOnBlack(0.125))).isLessThan(0.3)
        File(out, "${fileName}_moons.png").writeBytes(pngBytes(createBitmap(MOON_PX * MOON_STEPS, MOON_PX).apply {
            val canvas = Canvas(this)
            for (k in 1..MOON_STEPS) canvas.drawBitmap(moonOnBlack(moonPhaseOf(k)), (k - 1f) * MOON_PX, 0f, null)
        }))
    }

    @Test
    fun theWatchShowsTheNearestMoonPicture() {
        // The phase on 12 November 2026 at 09:34 local time: 2.4 days after new moon
        assertThat(moonPictureFor(0.0827)).isEqualTo(2)
        assertThat(moonPictureFor(0.0)).isEqualTo(1)
        assertThat(moonPictureFor(0.04)).isEqualTo(1)
        assertThat(moonPictureFor(0.5)).isEqualTo(MOON_STEPS / 2 + 1)
        // Just before the next new moon: the new moon picture again
        assertThat(moonPictureFor(0.97)).isEqualTo(1)
        // Never more than half a step off
        var phase = 0.0
        while (phase < 1.0) {
            val shown = moonPhaseOf(moonPictureFor(phase))
            val off = abs(phase - shown).let { minOf(it, 1 - it) }
            assertWithMessage("phase $phase").that(off).isAtMost(0.5 / MOON_STEPS + 1e-9)
            phase += 0.001
        }
        val dyn = design(defaultDesign()).getJSONObject("dynData").getJSONObject(MOON_DYN)
        assertThat(dyn.getString("valueKey")).isEqualTo(JsonKeyValues.MOON_PHASE.key)
        assertThat(dyn.getString("image${MOON_STEPS + 1}")).isEqualTo(moonName(1))
        // Without it the watch would use one step less (see moonDynData)
        assertThat(dyn.has("invalidImage")).isTrue()
        assertThat(dyn.has("image${MOON_STEPS + 2}")).isFalse()
    }

    @Test
    fun aCloudCoversTheMoonOnlyWhenItIsCloudy() {
        val design = design(defaultDesign())
        for (weather in listOf(null, EventData.Weather.CLEAR, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN)) {
            val look = lookOf(design, weather)
            // The moon stays on the edge, in the same colours
            assertThat(look.getJSONObject("freetext4").getJSONObject("orbit").getString("valueKey")).isEqualTo(JsonKeyValues.NIGHT_PROGRESS.key)
            assertThat(look.getJSONObject("hour").getString("fontColor")).isEqualTo(TIME_COLOR)
            val cloud = look.getJSONObject("patient_name")
            val cloudy = weather in listOf(EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN)
            assertWithMessage("$weather cloud").that(cloud.getString("visibility")).isEqualTo(if (cloudy) "visible" else "gone")
            if (cloudy) assertWithMessage("$weather cloud").that(cloud.getString("background"))
                .isEqualTo(if (weather == EventData.Weather.PARTLY) CLOUD_NIGHT_PARTLY else CLOUD_NIGHT_FULL)
        }
    }

    // ------------------------------------------------------------------------------------------
    // The background
    // ------------------------------------------------------------------------------------------

    /**
     * The background picture. 'G' (used): a very dark navy with a faint carbon weave, the faint dashed
     * track of the moon and a soft cyan glow at the bottom. Two slightly darker panels (the table and the
     * graph) carry thin, faint scan lines. All lines are only a little lighter than what is under them,
     * so the text stays clear. The other letters are the earlier choices, kept to compare (see
     * [renderBackgroundVariants]): 'A' navy with hexagons and a vignette, 'B' pure black (for AMOLED),
     * 'C' carbon weave, 'D' a horizon grid, 'E' a purple circuit, 'F' rain streaks and a glow.
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
                // Only the scan lines: the rain is the moving rain of the animation, so no still streaks that never move
                rainPattern(canvas, "#26385A", "#0E131D", count = 0)
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
        val lines = if (variant == 'G') Color.argb(120, 12, 16, 24) else Color.parseColor(if (dark) SCAN_DARK else SCAN_COLOR)
        val tablePath = tablePanel()
        for (panel in listOf(tablePath, chartPanel())) {
            canvas.drawPath(panel, fill)
            // The weave shows behind the table too, a bit dimmed. Its pixels stay dark (see the contrast test).
            if (variant == 'G' && panel === tablePath) {
                canvas.save()
                canvas.clipPath(panel)
                weavePattern(canvas, "#262E40", "#161B28")
                canvas.restore()
            }
            scanLines(canvas, panel, lines)
        }
        // The dashed track of the moon along the edge, faint
        if (variant == 'G') drawTrack(canvas, Color.argb(70, 0, 240, 255))
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
        return pngBytes(image)
    }

    /** A fine diagonal weave, like carbon fibre. Only a little lighter than the base. */
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

    /** Faint slanted rain streaks ([count] of them) and very faint scan lines. */
    private fun rainPattern(canvas: Canvas, streak: String = "#101828", scanColor: String = "#090C14", count: Int = 170) {
        val random = Random(7)
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

    /** A soft neon glow at the bottom edge. */
    private fun bottomGlow(canvas: Canvas, alpha: Int = 80, from: Float = 700f) {
        val glow = Paint().apply {
            shader = LinearGradient(0f, from, 0f, 800f, Color.TRANSPARENT, Color.argb(alpha, 0, 240, 255), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, from, 800f, 800f, glow)
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
        val alpha = Color.alpha(rain.color) / 255.0 * rain.alpha / 255.0
        fun blend(under: Int, over: Int) = (over * alpha + under * (1 - alpha)).toInt()
        var worst = 0.0
        for (y in (TABLE_TOP * 2) until (TABLE_BOTTOM * 2) step 2)
            for (x in (TABLE_LEFT * 2) until (VAL_RIGHT * 2) step 2) {
                val under = bitmap.getPixel(x, y)
                val lit = Color.rgb(blend(Color.red(under), Color.red(rain.color)), blend(Color.green(under), Color.green(rain.color)), blend(Color.blue(under), Color.blue(rain.color)))
                worst = max(worst, luminance(lit))
            }
        assertWithMessage("brightest pixel behind the table under a rain streak").that(worst).isLessThan(0.06)
    }

    @Test
    fun renderBackgroundVariants() {
        val default = defaultDesign()
        val design = design(default)
        val backgrounds = listOf('A' to "A  navy hex + vignette", 'B' to "B  pure black", 'C' to "C  carbon weave", 'D' to "D  horizon grid", 'E' to "E  purple circuit", 'F' to "F  rain + glow", 'G' to "G  weave + glow (used)")
        sheet(backgrounds.map { (id, name) -> name to preview(design, default, techImages(id), NIGHT_MS) }, "${fileName}_backgrounds.png")
        assertThat(File(out, "${fileName}_backgrounds.png").length()).isGreaterThan(10_000L)
    }

    // ------------------------------------------------------------------------------------------
    // The layout, the same in all four designs
    // ------------------------------------------------------------------------------------------

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
    }

    /** Where the ink of [value] starts, in the design space, when a text view like [view] shows it aligned left. */
    private fun textStart(value: String, view: JSONObject): Float {
        val drawn = createBitmap(800, 800).apply { eraseColor(Color.TRANSPARENT) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = view.getInt("textsize") * SCALE; typeface = valueTypeface() }
        Canvas(drawn).drawText(value, view.getInt("leftmargin") * SCALE, (view.getInt("topmargin") + view.getInt("height") / 2f) * SCALE, paint)
        return leftmostInk(drawn) / SCALE
    }

    /** The first column of [bitmap] with a pixel that can be seen (alpha above a half). */
    private fun leftmostInk(bitmap: Bitmap): Float {
        for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) if (Color.alpha(bitmap.getPixel(x, y)) > 128) return x.toFloat()
        return Float.NaN
    }

    @Test
    fun allSixValuesStartInOneColumnWithOneColour() {
        val design = design(defaultDesign())
        for (key in listOf("iob2", "cob2", "basalRate", "reservoir")) {
            val view = design.getJSONObject(key)
            assertWithMessage(key).that(view.getString("gravity")).isEqualTo("left")
            assertWithMessage(key).that(view.getInt("leftmargin")).isEqualTo(VAL_LEFT)
        }
        // Heart rate and steps, drawn by the library as the watch draws them, in both types the slot can get:
        // the value starts where a text value starts, and nothing else of the complication shows. (Both
        // sources list RANGED_VALUE before GOAL_PROGRESS, and so does every type priority, so the slot never
        // gets GOAL_PROGRESS.)
        val plain = textStart("72", design.getJSONObject("iob2"))
        for (type in listOf(ComplicationType.RANGED_VALUE, ComplicationType.SHORT_TEXT))
            for ((key, kind) in listOf("complication1" to HealthKind.HEART_RATE, "complication2" to HealthKind.STEPS)) {
                val view = design.getJSONObject(key)
                val drawn = createBitmap(800, 800).apply { eraseColor(Color.TRANSPARENT) }
                Canvas(drawn).withScale(SCALE, SCALE) { drawSlot(this, view, health(kind, if (kind == HealthKind.HEART_RATE) 72 else 7282, type)) }
                val start = leftmostInk(drawn) / SCALE
                println("$key as $type: value starts at $start, a text value at $plain")
                File(out, "slot_${key}_$type.png").writeBytes(pngBytes(drawn))
                assertWithMessage("$key as $type").that(start).isWithin(1.5f).of(plain)
            }
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
        for (key in listOf("hour_hand", "minute_hand", "second_hand", "time", "second", "complication3", "complication4", "complication5", "bgi"))
            assertWithMessage(key).that(design.getJSONObject(key).getString("visibility")).isEqualTo("gone")
        assertThat(design.getJSONObject("freetext1").getString("textvalue")).isEqualTo(":")
        for (key in listOf("hour", "minute", "loop", "complication1", "complication2") + batteryKeys)
            assertWithMessage(key).that(design.getJSONObject(key).getString("visibility")).isEqualTo("visible")
        for (key in rowBoxKeys) {
            val r = rectOf(design.getJSONObject(key))
            assertThat(r.width()).isAtLeast(100f)
            assertThat(r.height()).isAtLeast(ROW_HEIGHT.toFloat())
            assertWithMessage(key).that(design.getJSONObject(key).getInt("borderRadius")).isAtMost(6)
            assertWithMessage(key).that(design.getJSONObject(key).getInt("textsize")).isAtLeast(16)
            assertWithMessage(key).that(design.getJSONObject(key).has("borderColor")).isFalse()
        }    }

    private fun renderSample(sample: Sample, fileName: String, bg: Char = 'G') {
        val default = defaultDesign(sample)
        File(out, fileName).writeBytes(pngBytes(preview(design(default), default, techImages(bg), NIGHT_MS)))
    }

    @Test
    fun fourCharacterGlucoseAndWidestTimeFit() {
        renderSample(Sample("13,8", "↗", 248.0, 1), "${fileName}_high.png")
        renderSample(Sample("10,4", "→", 187.0, 1), "${fileName}_104.png")
        renderSample(Sample("3,4", "⇊", 61.0, -1), "${fileName}_down.png")
        renderSample(Sample(), "${fileName}_black.png", bg = 'B')
        val design = design(defaultDesign())
        // Widest glucose texts and the widest time, measured with the real font at the design's size
        val sgv = design.getJSONObject("sgv")
        for (text in listOf("13,8", "10,4", "22,2", "33,3")) {
            assertWithMessage(text).that(textWidth(text, sgv.getInt("textsize"))).isAtMost(sgv.getInt("width") - 4f)
        }
        val paint = Paint().apply { textSize = sgv.getInt("textsize").toFloat(); typeface = valueTypeface() }
        assertThat(paint.fontMetrics.descent - paint.fontMetrics.ascent).isAtMost(sgv.getInt("height") - 2f)
        for ((key, text) in listOf("hour" to "20", "minute" to "48", "freetext1" to ":", "hour" to "00", "minute" to "08")) {
            val view = design.getJSONObject(key)
            assertWithMessage(text).that(textWidth(text, view.getInt("textsize"))).isAtMost(view.getInt("width") - 4f)
        }
        // The arrow stays right of the glucose box
        assertThat(design.getJSONObject("direction").getInt("leftmargin")).isAtLeast(sgv.getInt("leftmargin") + sgv.getInt("width"))
    }

    // ------------------------------------------------------------------------------------------
    // The wake-up animation
    // ------------------------------------------------------------------------------------------

    @Test
    fun theAnimationIsReadAndTheDropSitsOnTheLogo() {
        val design = design(defaultDesign())
        val spec = readAnimation(design)
        // Every value is inside the safe limits, so nothing is changed on the watch
        assertThat(spec.normalized().withoutImage()).isEqualTo(spec.withoutImage())
        assertThat(spec.durationMs).isEqualTo(ANIMATION_MS)
        assertThat(spec.fps).isEqualTo(24)
        assertThat(spec.fadeMs).isEqualTo(600)
        val rain = spec.rain!!
        assertThat(rain.count).isEqualTo(140)
        assertThat(rain.color).isEqualTo(Color.parseColor(RAIN_COLOR))
        // Slanted 1 to the left for 4 down, the look the design was made with
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
        val face = storedFace(design, default, images, NIGHT_MS)
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
            assertWithMessage("frame at $clock ms is animated").that(drawn).isEqualTo(clock < ANIMATION_MS)
            if (drawn) canvas.withScale(size / 400f, size / 400f) { drawComplicationValues(this, design) }
            else canvas.drawBitmap(still, 0f, 0f, null)
            jpegs += ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
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
            |<title>Cyberpunk wake</title>
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

    private companion object {

        const val PANEL_COLOR = "#0B0B12"
        const val BASE_DARK = "#08090F"
        const val PANEL_DARK = "#0E1120"
        const val SCAN_DARK = "#141829"
        const val HEX_COLOR = "#10142A"
        const val HEX_RADIUS = 15f
        const val SCAN_COLOR = "#12121C"

        const val RAIN_COLOR = "#5E86B8"
        /** At 120 a streak over the brightest pixel of the table gives a luminance of 0.093, too bright for white text; 64 gives about 0.056. */
        const val RAIN_ALPHA = 64

        /** The animation frames: 466 px and 24 per second like the watch, over the whole animation. */
        const val FRAME_SIZE = 466
        const val FRAME_FPS = 24
        const val FRAME_COUNT = (ANIMATION_MS * FRAME_FPS / 1000).toInt()

        /** The time of frame [i]. */
        fun frameMs(i: Int): Long = i * 1000L / FRAME_FPS

        /** The moon and its pictures: one per 1/12 of the cycle, about 2.5 days. */
        const val MOON_SIZE = 24
        const val MOON_RADIUS = 10.5f
        const val MOON_PX = 48
        const val MOON_STEPS = 12
        /** The thinnest crescent, in design units: about 3 pixels on a 466 pixel watch. */
        const val MOON_MIN_CRESCENT = 2.8f
        const val MOON_LIGHT = "#DDF9FF"
        const val MOON_DYN = "moon"

        /** The cloud in front of the moon. */
        const val CLOUD_SIZE = 40
        const val CLOUD_PX = 80
        const val CLOUD_NIGHT_PARTLY = "CloudNightPartly"
        const val CLOUD_NIGHT_FULL = "CloudNightFull"

        /** 11 November 2026 at 20:00, local time: early in the night. */
        val EVENING_MS: Long = NIGHT_MS - 3 * 3_600_000L
    }
}
