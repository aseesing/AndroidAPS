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
import androidx.wear.watchface.WatchState
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.utils.FrameLayers
import app.aaps.wear.watchfaces.utils.FrameParts
import app.aaps.wear.watchfaces.utils.ViewPainter
import app.aaps.wear.watchfaces.utils.WakeLayers
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

        private val painter = ViewPainter(parent)

        fun drawOnly(canvas: Canvas, vararg keep: Int) = painter.draw(canvas, keep.sorted().map { views[it] }.toTypedArray())

        val parts = object : FrameParts {
            override fun prepare() = layout()
            override fun background(canvas: Canvas) = drawOnly(canvas, 0)
            override fun data(canvas: Canvas, leaveOut: Set<String>) = drawOnly(canvas, *(listOf(1) - leaveOut.mapNotNull { it.toIntOrNull() }.toSet()).toIntArray())
            override fun view(canvas: Canvas, effects: ViewEffects, index: Int) = moved(canvas, effects, listOf(index))
            override fun views(canvas: Canvas, effects: ViewEffects, members: BooleanArray) =
                moved(canvas, effects, effects.keys.indices.filter { members[it] && !effects.isHidden(it) && !effects.isClipped(it) })

            /** Draws the views of the keys at [indices] of [effects], turned and moved as it says. */
            private fun moved(canvas: Canvas, effects: ViewEffects, indices: List<Int>) {
                val moved = indices.mapNotNull { i -> effects.keys[i].toIntOrNull()?.let { views.getOrNull(it) }?.let { view -> Triple(view, view.rotation, view.translationX) to i } }
                moved.forEach { (saved, i) ->
                    if (effects.isTurned(i)) saved.first.rotation = saved.second + effects.turn(i)
                    if (effects.isMoved(i)) saved.first.translationX = saved.third + effects.dx(i) * size / 120f
                }
                drawOnly(canvas, *indices.mapNotNull { effects.keys[it].toIntOrNull() }.toIntArray())
                moved.forEach { (saved, _) ->
                    saved.first.rotation = saved.second
                    saved.first.translationX = saved.third
                }
            }

            override fun rotation(key: String): Float = key.toIntOrNull()?.let { views.getOrNull(it) }?.rotation ?: Float.NaN
            override fun offsetX(key: String): Float = key.toIntOrNull()?.let { views.getOrNull(it) }?.let { it.left * 120f / size - LEFTS.getValue(key.toInt()) } ?: Float.NaN
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

    /** The rain of [spec], drawn by the layer system as the wake draws it. */
    private fun rainOf(vararg params: Pair<String, Any?>) = animatorOf(animationOf(use("rain", *params)))

    private fun LayerAnimator.drawRain(canvas: Canvas, elapsed: Long, size: Int) {
        setTime(elapsed, 6000, 1f, t0)
        drawSlot(canvas, LayerSlot.BACK, size / DESIGN_SIZE)
    }

    /** Draws only [back] live, as the rain of a wake. */
    private fun live(back: (Canvas) -> Unit = {}) = object : WakeLayers {
        override fun back(canvas: Canvas) = back(canvas)
        override fun views(canvas: Canvas) {}
        override fun front(canvas: Canvas) {}
    }

    private fun composed(face: Face, rain: LayerAnimator?, elapsed: Long): Bitmap {
        val frame = createBitmap(face.size, face.size)
        FrameLayers().drawWake(
            Canvas(frame), face.size, face.size, t0, 1, emptyList(), face.parts, emptySet(),
            live { rain?.drawRain(it, elapsed, face.size) }
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
        val rain = rainOf("count" to 120, "alpha" to 255, "thickness" to 3)
        val expected = createBitmap(size, size).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(expected)
        face.drawOnly(canvas, 0)
        rain.drawRain(canvas, 1500, size)
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
        val rainy = composed(face, rainOf("count" to 300, "alpha" to 255, "thickness" to 4), 2000)
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
        val layers = FrameLayers()
        val frame = createBitmap(120, 120)
        // 6 s at 24 fps, starting at a whole minute
        for (i in 0 until 144) layers.drawWake(Canvas(frame), 120, 120, t0 + i * 41L, 1, emptyList(), face.parts, emptySet(), live())
        assertThat(layers.backgroundDraws).isEqualTo(1)
        assertThat(layers.dataDraws).isEqualTo(1)
        assertThat(layers.topDraws).isEqualTo(1)
        // new data: the data pictures again
        layers.drawWake(Canvas(frame), 120, 120, t0 + 6000, 2, emptyList(), face.parts, emptySet(), live())
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
        animator.setDesign(readAnimation(animationOf(use("rain"))))
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
        animator.setDesign(readAnimation(animationOf(use("rain"))))
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

    private fun watchState(ambient: MutableStateFlow<Boolean?>) = WatchState(
        MutableStateFlow(0), ambient, MutableStateFlow(false), MutableStateFlow(true),
        hasLowBitAmbient = false, hasBurnInProtection = false, analogPreviewReferenceTimeMillis = 0, digitalPreviewReferenceTimeMillis = 0,
        chinHeight = 0, isHeadless = false, watchFaceInstanceId = MutableStateFlow("test")
    )

    @Test
    fun watchingTheSameStateAgainAddsNoCollectorsAndANewStateReplacesThem() {
        val job = Job()
        val scope = CoroutineScope(job + StandardTestDispatcher())
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { 0L }, newScope = { scope })
        val state = watchState(MutableStateFlow(false))
        animator.watch(state)
        animator.watch(state)
        assertThat(job.children.count { it.isActive }).isEqualTo(2)
        // The face made again: the collectors of the old state stop
        animator.watch(watchState(MutableStateFlow(false)))
        assertThat(job.children.count { it.isActive }).isEqualTo(2)
        animator.release()
        assertThat(job.isActive).isFalse()
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
         "animation": {"durationMs": 6000, "fps": 24, "fadeMs": 600, "layers": [
           {"use": "rain", "count": 110, "speed": 560, "angle": 14, "length": 26, "thickness": 1.8, "color": "#B4D2FF", "alpha": 170},
           {"use": "pulse", "image": "heart", "left": 176, "top": 52, "width": 48, "height": 44, "amplitude": 0.14, "glowColor": "#FF3050", "glowAlpha": 180, "glowRadius": 9}]}}
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
        val spec = CustomWatchface().readWakeAnimation(JSONObject(withTestTemplates(sampleJson))) { if (it == "heart") heartDrawable() else null }
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
        val blink = animationOf(use("blink", "view" to "1", "valueKey" to "battery", "atOrBelow" to 20)).put("durationMs", 3000)
        fun animator() = WakeAnimator(heartRate = { 72 }, value = { if (it == "battery") level else null }, timer = WakeTimer { clock })
            .apply { setDesign(readAnimation(blink)) }

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
            assertThat(isRunning).isFalse()
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
            setDesign(readAnimation(animationOf(use("sweep", "view" to "1", "emptyAngle" to 0, "fullAngle" to 45, "upMs" to 200, "settleMs" to 300)).put("durationMs", 1000)))
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
            setDesign(readAnimation(animationOf(use("slide", "view" to "4", "fullDx" to 20, "upMs" to 200, "settleMs" to 300)).put("durationMs", 1000)))
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
        val trace = use("trace", "view" to "1", "rect" to rectJson(DesignRect(33f, 33f, 200f, 200f)), "durationMs" to 1000, "alpha" to 0)
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { clock }).apply { setDesign(readAnimation(animationOf(trace).put("durationMs", 2000))) }

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
        val glint = use("glint", "rings" to JSONArray("[{\"cx\": 200, \"cy\": 200, \"inner\": 150, \"outer\": 190}]"), "angle" to 0, "width" to 400, "alpha" to 255, "durationMs" to 900)
        // Halfway through: the middle of the band on the middle of the face
        animatorOf(animationOf(glint)).apply {
            setTime(450, 6000, 1f, t0)
            drawSlot(Canvas(bitmap), LayerSlot.FRONT, 0.5f)
        }
        // On the ring at the top, not in the middle, not outside it in the corner
        assertThat(Color.red(bitmap.getPixel(100, 15))).isGreaterThan(150)
        assertThat(bitmap.getPixel(100, 100)).isEqualTo(Color.BLACK)
        assertThat(bitmap.getPixel(3, 3)).isEqualTo(Color.BLACK)
    }

    @Test
    fun aBlinkWithNoValueIsLeftOutAndABlinkAloneIsAnAnimation() {
        val spec = readAnimation(animationOf(use("blink", "view" to "b", "valueKey" to "v", "atOrBelow" to 20))).normalized()
        assertThat(spec.hasContent).isTrue()
        assertThat(animatorOf(animationOf(use("blink", "view" to "b", "valueKey" to "v", "atOrBelow" to 20))).viewKeys).containsExactly("b")
    }
}
