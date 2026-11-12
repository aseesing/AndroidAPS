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
            override fun data(canvas: Canvas) = drawOnly(canvas, 1)
            override fun clockText(canvas: Canvas) = drawOnly(canvas, 2)
            override fun top(canvas: Canvas) = drawOnly(canvas, 3, 4)
            override fun secondHand(canvas: Canvas) = drawOnly(canvas, 5)
        }
    }

    private companion object {

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
}
