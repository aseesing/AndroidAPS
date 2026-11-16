// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.utils.drawWithHidden
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The wake-up animation frame, with real pixels: put together from its layers it must look like the
 * face plus the rain under the data, and the data views must look the same with and without it.
 * Also renders a few frames of a sample design to `build/anim-frames` for a look by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class WakeFrameTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    /**
     * A small face in paint order: 0 background, 1 data box, 2 clock text, 3 minute hand, 4 see-through
     * cover, 5 second hand.
     */
    private class Face(context: Application, val size: Int, val views: List<View> = smallFace(context, size)) {

        val parent = FrameLayout(context)

        init {
            views.forEach { parent.addView(it) }
            layout()
        }

        fun layout() {
            val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            parent.measure(spec, spec)
            parent.layout(0, 0, size, size)
        }

        fun drawOnly(canvas: Canvas, vararg keep: Int) = drawWithHidden(parent, views.filterIndexed { index, _ -> index !in keep }, canvas)

        val parts = object : WakeFrameParts {
            override fun prepare() = layout()
            override fun background(canvas: Canvas) = drawOnly(canvas, 0)
            override fun data(canvas: Canvas, leaveOut: Set<String>) = drawOnly(canvas, *(listOf(1) - leaveOut.mapNotNull { it.toIntOrNull() }.toSet()).toIntArray())
            override fun views(canvas: Canvas, keys: Set<String>, turns: Map<String, Float>, shifts: Map<String, Float>) {
                val turned = turns.mapNotNull { (key, degrees) -> key.toIntOrNull()?.let { views[it] }?.let { view -> Triple(view, view.rotation, degrees) } }
                val shifted = shifts.mapNotNull { (key, dx) -> key.toIntOrNull()?.let { views[it] }?.let { view -> Triple(view, view.translationX, dx) } }
                turned.forEach { (view, rotation, degrees) -> view.rotation = rotation + degrees }
                shifted.forEach { (view, translation, dx) -> view.translationX = translation + dx * size / 120f }
                drawOnly(canvas, *keys.mapNotNull { it.toIntOrNull() }.toIntArray())
                turned.forEach { (view, rotation, _) -> view.rotation = rotation }
                shifted.forEach { (view, translation, _) -> view.translationX = translation }
            }

            override fun rotation(key: String): Float? = key.toIntOrNull()?.let { views.getOrNull(it) }?.rotation
            override fun offsetX(key: String): Float? = key.toIntOrNull()?.let { views.getOrNull(it) }?.let { it.left * 120f / size - LEFTS.getValue(key.toInt()) }
            override fun clockText(canvas: Canvas) = drawOnly(canvas, 2)
            override fun top(canvas: Canvas, leaveOut: Set<String>) = drawOnly(canvas, *(listOf(3, 4) - leaveOut.mapNotNull { it.toIntOrNull() }.toSet()).toIntArray())
            override fun secondHand(canvas: Canvas) = drawOnly(canvas, 5)
        }
    }

    private companion object {

        /** The left of each view of [smallFace] in the design, 120 wide. */
        val LEFTS = mapOf(0 to 0f, 1 to 10f, 2 to 20f, 3 to 30f, 4 to 40f, 5 to 58f)

        /** The small face of the layer tests, scaled to [size]. */
        fun smallFace(context: Application, size: Int): List<View> {
            fun box(color: Int, left: Int, top: Int, width: Int, height: Int) = View(context).apply {
                setBackgroundColor(color)
                layoutParams = FrameLayout.LayoutParams(width, height).apply { leftMargin = left; topMargin = top }
            }
            val s = size / 120f
            return listOf(
                box(Color.DKGRAY, 0, 0, size, size),
                box(Color.BLUE, (10 * s).toInt(), (10 * s).toInt(), (60 * s).toInt(), (60 * s).toInt()),
                TextView(context).apply {
                    text = "12:34:56"
                    setTextColor(Color.WHITE)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { leftMargin = (20 * s).toInt(); topMargin = (40 * s).toInt() }
                },
                box(Color.GREEN, (30 * s).toInt(), (50 * s).toInt(), (70 * s).toInt(), (20 * s).toInt()).apply { rotation = 30f },
                box(Color.YELLOW, (40 * s).toInt(), (20 * s).toInt(), (50 * s).toInt(), (50 * s).toInt()).apply { alpha = 0.5f },
                box(Color.RED, (58 * s).toInt(), (10 * s).toInt(), (4 * s).toInt(), (60 * s).toInt()).apply { rotation = 125f }
            )
        }
    }

    private fun largestDifference(expected: Bitmap, actual: Bitmap): Int {
        var largest = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val e = expected.getPixel(x, y)
            val a = actual.getPixel(x, y)
            largest = maxOf(largest, abs(Color.red(e) - Color.red(a)), abs(Color.green(e) - Color.green(a)), abs(Color.blue(e) - Color.blue(a)))
        }
        return largest
    }

    private val t0 = 1_700_000_040_000L
    private val size = 200

    private fun composed(face: Face, rain: RainField?, elapsed: Long): Bitmap {
        val frame = createBitmap(face.size, face.size)
        WakeFrameLayers().draw(
            Canvas(frame), face.size, face.size, t0, 1, emptyList(), face.parts,
            rain = { rain?.draw(it, elapsed, face.size / DESIGN_SIZE, 1f) }, heart = {}
        )
        return frame
    }

    @Test
    fun withoutRainAndHeartTheFrameIsTheWholeFace() {
        val face = Face(context, size)
        val whole = createBitmap(size, size).apply { eraseColor(Color.BLACK) }
        face.parent.draw(Canvas(whole))
        assertThat(largestDifference(whole, composed(face, null, 0))).isAtMost(1)
    }

    @Test
    fun theFrameIsBackgroundThenRainThenTheRest() {
        val face = Face(context, size)
        val rain = RainField(RainSpec(count = 120, alpha = 255, thickness = 3f))
        val expected = createBitmap(size, size).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(expected)
        face.drawOnly(canvas, 0)
        rain.draw(canvas, 1500, size / DESIGN_SIZE, 1f)
        face.drawOnly(canvas, 1, 2, 3, 4, 5)

        val frame = composed(face, rain, 1500)
        assertThat(largestDifference(expected, frame)).isAtMost(1)
        // and the rain is really there
        assertThat(largestDifference(composed(face, null, 0), frame)).isGreaterThan(50)
    }

    @Test
    fun theDataViewsLookTheSameWithAndWithoutRain() {
        val face = Face(context, size)
        val plain = composed(face, null, 0)
        val rainy = composed(face, RainField(RainSpec(count = 300, alpha = 255, thickness = 4f)), 2000)
        // inside the opaque blue data box, away from the views above it, every pixel is unchanged
        val s = size / 120f
        var changedOutside = 0
        for (y in 0 until size) for (x in 0 until size) {
            val inBox = x in (11 * s).toInt()..(30 * s).toInt() && y in (11 * s).toInt()..(36 * s).toInt()
            if (inBox) assertThat(rainy.getPixel(x, y)).isEqualTo(plain.getPixel(x, y))
            else if (rainy.getPixel(x, y) != plain.getPixel(x, y)) changedOutside++
        }
        assertThat(changedOutside).isGreaterThan(0)
    }

    @Test
    fun keptPicturesAreDrawnOncePerMinuteOrTenSeconds() {
        val face = Face(context, 120)
        val layers = WakeFrameLayers()
        val frame = createBitmap(120, 120)
        // 6 s at 24 fps, starting at a whole minute
        for (i in 0 until 144) layers.draw(Canvas(frame), 120, 120, t0 + i * 41L, 1, emptyList(), face.parts, {}, {})
        assertThat(layers.backgroundDraws).isEqualTo(1)
        assertThat(layers.dataDraws).isEqualTo(1)
        assertThat(layers.topDraws).isEqualTo(1)
        // new data: the data pictures again
        layers.draw(Canvas(frame), 120, 120, t0 + 6000, 2, emptyList(), face.parts, {}, {})
        assertThat(layers.dataDraws).isEqualTo(2)
        assertThat(layers.topDraws).isEqualTo(2)
    }

    // ---- WakeAnimator: the frame loop of one wake ----

    private class Loop(val animator: WakeAnimator, val face: Face) {

        val canvas = Canvas(createBitmap(face.size, face.size))

        fun frame(nowMs: Long, allowed: Boolean = true, ready: Boolean = true) =
            animator.drawFrame(canvas, face.size, face.size, nowMs, 1, emptyList(), face.parts, allowed, ready)
    }

    @Test
    fun aWakeDrawsItsFramesAndThenTheFaceIsNormalAgain() {
        var clock = 0L
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { clock })
        animator.setDesign(WakeAnimationSpec(rain = RainSpec()))
        val loop = Loop(animator, Face(context, 120))
        var drawn = 0
        var normalFrames = 0
        // the renderer: a frame, then wait as long as the face asks, for one minute
        while (clock < 60_000) {
            if (loop.frame(t0 + clock)) drawn++ else normalFrames++
            clock += animator.updateDelay(60_000)
        }
        println("PERF wake animation: $drawn animated frames, then $normalFrames normal frames in the rest of the minute")
        assertThat(drawn).isEqualTo(144)
        assertThat(animator.isRunning).isFalse()
        assertThat(animator.updateDelay(60_000)).isEqualTo(60_000)
        assertThat(animator.updateDelay(1000)).isEqualTo(1000)
        assertThat(animator.beatsPerMinute).isEqualTo(72)
    }

    @Test
    fun aWakeWaitsForTheLayoutAndNeverPlaysWhereItIsNotAllowed() {
        var clock = 0L
        val animator = WakeAnimator(heartRate = { null }, timer = WakeTimer { clock })
        animator.setDesign(WakeAnimationSpec(rain = RainSpec()))
        val loop = Loop(animator, Face(context, 120))
        assertThat(loop.frame(t0, ready = false)).isFalse()
        assertThat(animator.updateDelay(60_000)).isEqualTo(41)       // still waiting, so frames come soon
        clock += 41
        assertThat(loop.frame(t0 + clock)).isTrue()
        assertThat(animator.beatsPerMinute).isEqualTo(PulseCurve.FALLBACK_BPM)
        // the simple face or an image render: stops at once and asks for nothing more
        assertThat(loop.frame(t0 + clock, allowed = false)).isFalse()
        assertThat(animator.isRunning).isFalse()
        assertThat(animator.updateDelay(60_000)).isEqualTo(60_000)
        assertThat(animator.wakeOnTap()).isTrue()
        assertThat(loop.frame(t0 + clock, allowed = false)).isFalse()
        assertThat(animator.updateDelay(60_000)).isEqualTo(60_000)
    }

    @Test
    fun aDesignWithoutAnimationNeverDrawsAFrame() {
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { 0L })
        animator.setDesign(null)
        assertThat(Loop(animator, Face(context, 120)).frame(t0)).isFalse()
        animator.setDesign(WakeAnimationSpec())            // the key is there, but nothing in it
        assertThat(Loop(animator, Face(context, 120)).frame(t0)).isFalse()
        assertThat(animator.updateDelay(60_000)).isEqualTo(60_000)
    }

    // ---- Frames of a sample design, for a look by eye ----

    private val sampleJson = """
        {"metadata": {"name": "Rain sample"},
         "animation": {"durationMs": 6000, "fps": 24, "fadeMs": 600,
           "rain": {"count": 110, "speed": 560, "angle": 14, "length": 26, "thickness": 1.8, "color": "#B4D2FF", "alpha": 170},
           "pulse": {"image": "heart", "left": 176, "top": 52, "width": 48, "height": 44, "amplitude": 0.14, "glowColor": "#FF3050", "glowAlpha": 180, "glowRadius": 9}}}
    """.trimIndent()

    /** A red heart, like the one a design would put in its zip. */
    private fun heartDrawable(): Drawable {
        val bitmap = createBitmap(96, 88)
        val path = Path().apply {
            moveTo(48f, 84f)
            cubicTo(10f, 58f, 0f, 36f, 6f, 20f)
            cubicTo(14f, 2f, 40f, 0f, 48f, 22f)
            cubicTo(56f, 0f, 82f, 2f, 90f, 20f)
            cubicTo(96f, 36f, 86f, 58f, 48f, 84f)
            close()
        }
        Canvas(bitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, 88f, Color.rgb(255, 80, 100), Color.rgb(200, 10, 40), Shader.TileMode.CLAMP)
        })
        return BitmapDrawable(context.resources, bitmap)
    }

    /** A sample face at the watch's 466 px: dark sky, logo text, glucose, the time and a status line. */
    private fun sampleFace(size: Int): Face {
        val k = size / DESIGN_SIZE
        fun text(value: String, sp: Float, color: Int, top: Float) = TextView(context).apply {
            text = value
            textSize = sp
            setTextColor(color)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (top * k).toInt() }
        }
        return Face(
            context, size, listOf(
                View(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(10, 18, 40), Color.rgb(24, 40, 80)))
                    layoutParams = FrameLayout.LayoutParams(size, size)
                },
                text("1 2 3", 60f, Color.rgb(120, 230, 120), 150f),                      // data: glucose
                text("10:42", 30f, Color.WHITE, 245f),                                   // clock text
                text("IOB 1.25U  COB 12g", 14f, Color.rgb(220, 220, 220), 300f),         // top: more data
                text("AAPS", 16f, Color.rgb(255, 200, 210), 100f),                       // top: logo text
                View(context).apply { layoutParams = FrameLayout.LayoutParams(1, 1) }   // no second hand
            )
        )
    }

    @Test
    fun rendersSampleFramesToPng() {
        val size = 466
        val spec = CustomWatchface().readWakeAnimation(JSONObject(sampleJson)) { if (it == "heart") heartDrawable() else null }
        assertThat(spec).isNotNull()
        var clock = 0L
        val animator = WakeAnimator(heartRate = { 75 }, timer = WakeTimer { clock })
        animator.setDesign(spec)
        val face = sampleFace(size)
        val out = File("build/anim-frames").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val times = listOf(0L, 300L, 700L, 800L, 880L, 960L, 1040L, 1120L, 1240L, 1400L, 1600L, 3000L, 5000L, 5700L, 5950L)
        times.forEachIndexed { index, t ->
            clock = t
            val frame = createBitmap(size, size)
            assertThat(animator.drawFrame(Canvas(frame), size, size, t0 + t, 1, emptyList(), face.parts, allowed = true, ready = true)).isTrue()
            File(out, "frame_%02d.png".format(index)).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // after the animation the frame is drawn the normal way
        clock = 6000
        assertThat(animator.drawFrame(Canvas(createBitmap(size, size)), size, size, t0 + 6000, 1, emptyList(), face.parts, true, true)).isFalse()
        println("FRAMES written to ${out.absolutePath}: ${times.size} frames at ms $times")
    }

    @Test
    fun aLowValueBlinksItsViewsWhileTheWakePlaysAndOnlyThen() {
        var clock = 0L
        var level: Double? = 15.0
        val face = Face(context, 120)
        val bitmap = createBitmap(120, 120)
        val canvas = Canvas(bitmap)
        val blink = BlinkSpec(views = listOf("1"), valueKey = "battery", atOrBelow = 20.0)
        fun animator() = WakeAnimator(heartRate = { 72 }, value = { if (it == "battery") level else null }, timer = WakeTimer { clock })
            .apply { setDesign(WakeAnimationSpec(durationMs = 3000, blink = listOf(blink))) }

        /** The colour in a corner of the data box that nothing else covers, after a frame [ms] into the wake, or null for a normal frame. */
        fun WakeAnimator.boxAt(ms: Long): Int? {
            clock = ms
            return if (drawFrame(canvas, 120, 120, t0 + ms, 1, emptyList(), face.parts, true, true)) bitmap.getPixel(15, 15) else null
        }

        // Low: shown in the first half of each second, hidden (the background shows) in the second half
        animator().apply {
            assertThat(boxAt(0)).isEqualTo(Color.BLUE)
            assertThat(boxAt(400)).isEqualTo(Color.BLUE)
            assertThat(boxAt(600)).isEqualTo(Color.DKGRAY)
            assertThat(boxAt(1100)).isEqualTo(Color.BLUE)
            assertThat(boxAt(1700)).isEqualTo(Color.DKGRAY)
            // After the wake the face is drawn the normal way, with the box
            assertThat(boxAt(3100)).isNull()
            assertThat(hiddenAt(600)).isEmpty()
        }
        // At the limit it still blinks
        level = 20.0
        animator().apply {
            assertThat(boxAt(0)).isEqualTo(Color.BLUE)
            assertThat(boxAt(600)).isEqualTo(Color.DKGRAY)
        }
        // Above the limit, or with no value, it never blinks
        for (value in listOf(21.0, 100.0, null)) {
            level = value
            animator().apply {
                for (ms in listOf(0L, 600L, 1100L, 1700L, 2600L)) assertWithMessage("$value at $ms").that(boxAt(ms)).isEqualTo(Color.BLUE)
            }
        }
    }

    @Test
    fun aSweptHandTurnsUpAndComesBackToItsValue() {
        var clock = 0L
        val face = Face(context, 120)
        val bitmap = createBitmap(120, 120)
        val canvas = Canvas(bitmap)
        // The blue box turns from 0 to 45 degrees and back: turned, its corner shows the background
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { clock }).apply {
            setDesign(WakeAnimationSpec(durationMs = 1000, sweep = listOf(SweepSpec(listOf("1"), emptyAngle = 0f, fullAngle = 45f, upMs = 200, settleMs = 300))))
        }

        fun cornerAt(ms: Long): Int? {
            clock = ms
            return if (animator.drawFrame(canvas, 120, 120, t0 + ms, 1, emptyList(), face.parts, true, true)) bitmap.getPixel(15, 15) else null
        }
        assertThat(cornerAt(0)).isEqualTo(Color.BLUE)
        assertThat(cornerAt(200)).isEqualTo(Color.DKGRAY)
        assertThat(cornerAt(600)).isEqualTo(Color.BLUE)
        // The view itself is never left turned
        assertThat(face.views[1].rotation).isEqualTo(0f)
    }

    @Test
    fun aSlidViewAboveTheClockGoesRightAndComesBackToItsPlace() {
        var clock = 0L
        val face = Face(context, 120)
        val bitmap = createBitmap(120, 120)
        val canvas = Canvas(bitmap)
        // The yellow box (above the clock text, 40..90 px) slides 20 px right and back
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { clock }).apply {
            setDesign(WakeAnimationSpec(durationMs = 1000, slide = listOf(SlideSpec(listOf("4"), fullDx = 20f, upMs = 200, settleMs = 300))))
        }

        fun pixelAt(ms: Long): Int? {
            clock = ms
            return if (animator.drawFrame(canvas, 120, 120, t0 + ms, 1, emptyList(), face.parts, true, true)) bitmap.getPixel(100, 30) else null
        }
        val before = pixelAt(0)!!
        // At the end of the scale the box covers x 100; back at its value it does not
        assertThat(pixelAt(200)).isNotEqualTo(before)
        assertThat(Color.red(pixelAt(200)!!)).isGreaterThan(Color.red(before) + 40)
        assertThat(pixelAt(600)).isEqualTo(before)
        // The view itself is never left moved
        assertThat(face.views[4].translationX).isEqualTo(0f)
    }

    @Test
    fun aTracedViewShowsFromTheLeft() {
        var clock = 0L
        val face = Face(context, 120)
        val bitmap = createBitmap(120, 120)
        val canvas = Canvas(bitmap)
        // The blue box is 10..70 px of 120, so 33..233 of the design
        val trace = TraceSpec(view = "1", rect = DesignRect(33f, 33f, 200f, 200f), durationMs = 1000, alpha = 0)
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { clock }).apply { setDesign(WakeAnimationSpec(durationMs = 2000, trace = trace)) }

        fun at(ms: Long, x: Int): Int {
            clock = ms
            assertThat(animator.drawFrame(canvas, 120, 120, t0 + ms, 1, emptyList(), face.parts, true, true)).isTrue()
            return bitmap.getPixel(x, 15)
        }
        // Nothing at the start, the left half halfway, all of it at the end
        assertThat(at(0, 15)).isEqualTo(Color.DKGRAY)
        assertThat(at(500, 15)).isEqualTo(Color.BLUE)
        assertThat(at(500, 65)).isEqualTo(Color.DKGRAY)
        assertThat(at(1200, 65)).isEqualTo(Color.BLUE)
    }

    @Test
    fun theGlintLightsOnlyItsShapes() {
        val bitmap = createBitmap(200, 200).apply { eraseColor(Color.BLACK) }
        val glint = GlintSpec(rings = listOf(DesignRing(200f, 200f, 150f, 190f)), angle = 0f, width = 400f, alpha = 255)
        GlintBand(glint).draw(Canvas(bitmap), 0.5f, 0.5f)
        // On the ring at the top, not in the middle, not outside it in the corner
        assertThat(Color.red(bitmap.getPixel(100, 15))).isGreaterThan(150)
        assertThat(bitmap.getPixel(100, 100)).isEqualTo(Color.BLACK)
        assertThat(bitmap.getPixel(3, 3)).isEqualTo(Color.BLACK)
    }

    @Test
    fun blinkValuesAreKeptSafe() {
        val blink = BlinkSpec(views = listOf("a", "", "a", "b"), valueKey = "v", atOrBelow = 20.0, periodMs = 10).normalized()
        assertThat(blink.views).containsExactly("a", "b").inOrder()
        assertThat(blink.periodMs).isEqualTo(BlinkSpec.MIN_PERIOD_MS)
        assertThat(BlinkSpec(listOf("a"), "v", 20.0, periodMs = 60_000).normalized().periodMs).isEqualTo(BlinkSpec.MAX_PERIOD_MS)
        assertThat(blink.isLow(null)).isFalse()
        assertThat(blink.isLow(20.0)).isTrue()
        assertThat(blink.isLow(20.5)).isFalse()
        // A blink with no view or no value is dropped, and a blink alone is an animation
        val spec = WakeAnimationSpec(blink = listOf(BlinkSpec(emptyList(), "v", 20.0), BlinkSpec(listOf("a"), "", 20.0), BlinkSpec(listOf("b"), "v", 20.0))).normalized()
        assertThat(spec.blink).hasSize(1)
        assertThat(spec.hasContent).isTrue()
        assertThat(spec.blinkViews).containsExactly("b")
    }
}
