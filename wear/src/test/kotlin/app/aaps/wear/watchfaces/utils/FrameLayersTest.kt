package app.aaps.wear.watchfaces.utils

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import app.aaps.wear.watchfaces.animation.LayerSlot
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The kept frame pictures: the face drawn in layers must look exactly like the face drawn in one go,
 * and the kept pictures must be drawn again exactly when something in them changed, for the normal
 * frames and the wake frames that share them.
 *
 * Real pixels: Robolectric's native graphics draws views the way the watch does in software.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class FrameLayersTest {

    private val size = 120

    /** A small face: views that overlap, a rotated one, a see-through one and text, in paint order. */
    private class Face(context: Application, size: Int) {

        val parent = FrameLayout(context)
        val views: List<View>
        val painter = ViewPainter(parent)

        init {
            fun box(color: Int, left: Int, top: Int, width: Int, height: Int) = View(context).apply {
                setBackgroundColor(color)
                layoutParams = FrameLayout.LayoutParams(width, height).apply { leftMargin = left; topMargin = top }
            }
            views = listOf(
                box(Color.DKGRAY, 0, 0, size, size),                                      // background
                box(Color.BLUE, 10, 10, 60, 60),                                         // data
                TextView(context).apply {                                                // clock text
                    text = "12:34:56"
                    setTextColor(Color.WHITE)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { leftMargin = 20; topMargin = 40 }
                },
                box(Color.GREEN, 30, 50, 70, 20).apply { rotation = 30f },               // minute hand
                box(Color.YELLOW, 40, 20, 50, 50).apply { alpha = 0.5f },                // cover, see-through
                box(Color.RED, 58, 10, 4, 60).apply { rotation = 125f }                  // second hand
            )
            views.forEach { parent.addView(it) }
            val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            parent.measure(spec, spec)
            parent.layout(0, 0, size, size)
        }

        fun drawOnly(canvas: Canvas, vararg keep: Int) = painter.draw(canvas, keep.map { views[it] }.toTypedArray())

        /** The parts of a frame, counting how often each kept part is drawn. */
        inner class Parts : FrameParts {

            var restDraws = 0

            /** The colour of the background, so a test can change it as a style pass does every minute. */
            var backgroundColor: Int? = null
            override fun background(canvas: Canvas) = backgroundColor?.let { canvas.drawColor(it) } ?: drawOnly(canvas, 0)
            override fun rest(canvas: Canvas, nowMs: Long, slot: LayerSlot) {
                if (slot == LayerSlot.BACK) restDraws++
            }

            override fun data(canvas: Canvas, leaveOut: Set<String>) = drawOnly(canvas, *intArrayOf(1).filter { "$it" !in leaveOut }.toIntArray())
            override fun clockText(canvas: Canvas) = drawOnly(canvas, 2)
            override fun top(canvas: Canvas, leaveOut: Set<String>) = drawOnly(canvas, *intArrayOf(3, 4).filter { "$it" !in leaveOut }.toIntArray())
            override fun secondHand(canvas: Canvas) = drawOnly(canvas, 5)
            override fun rotation(key: String): Float = Float.NaN
            override fun offsetX(key: String): Float = Float.NaN
        }
    }

    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun largestDifference(expected: Bitmap, actual: Bitmap): Int {
        var largest = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val e = expected.getPixel(x, y)
            val a = actual.getPixel(x, y)
            largest = maxOf(largest, abs(Color.red(e) - Color.red(a)), abs(Color.green(e) - Color.green(a)), abs(Color.blue(e) - Color.blue(a)))
        }
        return largest
    }

    private fun whole(face: Face): Bitmap = createBitmap(size, size).apply {
        eraseColor(Color.BLACK)
        face.parent.draw(Canvas(this))
    }

    // ---- ViewPainter ----

    @Test
    fun eachViewIsDrawnExactlyAsTheParentDrawsIt() {
        // Without the see-through view every view is drawn on its own: the same pixels as the parent
        val face = Face(context, size)
        face.views[4].visibility = View.GONE
        val drawnOneByOne = createBitmap(size, size).apply { eraseColor(Color.BLACK) }
        face.drawOnly(Canvas(drawnOneByOne), 0, 1, 2, 3, 5)
        assertThat(drawnOneByOne.sameAs(whole(face))).isTrue()
    }

    @Test
    fun facePastedFromLayersLooksLikeTheWholeFace() {
        val face = Face(context, size)
        // Like the watch face: base (0, 1) and top (3, 4) kept, clock text (2) and second hand (5) live
        val base = createBitmap(size, size).apply { eraseColor(Color.BLACK) }
        face.drawOnly(Canvas(base), 0, 1)
        val top = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        face.drawOnly(Canvas(top), 3, 4)
        val frame = createBitmap(size, size)
        val canvas = Canvas(frame)
        canvas.drawBitmap(base, 0f, 0f, null)
        face.drawOnly(canvas, 2)
        canvas.drawBitmap(top, 0f, 0f, null)
        face.drawOnly(canvas, 5)

        // One unit of rounding is allowed where a see-through view is blended in a layer first
        assertThat(largestDifference(whole(face), frame)).isAtMost(1)
        // and something was drawn at all: the background, not the black start
        assertThat(frame.getPixel(size - 2, size - 2)).isEqualTo(Color.DKGRAY)
    }

    @Test
    fun drawingWithASeeThroughViewGivesEveryVisibilityBack() {
        // The see-through view (4) is drawn by the parent, with the others made invisible for the draw
        val face = Face(context, size)
        face.views[1].visibility = View.INVISIBLE
        face.views[0].visibility = View.GONE

        face.drawOnly(Canvas(createBitmap(size, size)), 4, 5)

        assertThat(face.views.map { it.visibility })
            .containsExactly(View.GONE, View.INVISIBLE, View.VISIBLE, View.VISIBLE, View.VISIBLE, View.VISIBLE).inOrder()
    }

    @Test
    fun aViewLeftOutIsNotPainted() {
        val face = Face(context, size)
        val bitmap = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        face.drawOnly(Canvas(bitmap), 1)       // the blue box only
        assertThat(bitmap.getPixel(15, 15)).isEqualTo(Color.BLUE)
        assertThat(bitmap.getPixel(size - 2, size - 2)).isEqualTo(Color.TRANSPARENT)
    }

    // ---- FrameLayers ----

    private val t0 = 1_700_000_040_000L    // a whole minute

    private val noLive = object : WakeLayers {
        override fun back(canvas: Canvas) {}
        override fun views(canvas: Canvas) {}
        override fun front(canvas: Canvas) {}
    }

    @Test
    fun theNormalFrameLooksLikeTheWholeFace() {
        val face = Face(context, size)
        val frame = createBitmap(size, size)
        FrameLayers().drawNormal(Canvas(frame), size, size, t0, 1, emptyList(), face.Parts())
        assertThat(largestDifference(whole(face), frame)).isAtMost(1)
    }

    @Test
    fun twoMinutesWithSecondsDrawTheBackgroundOnceTheDataEachMinuteAndTheTopEveryTenSeconds() {
        val face = Face(context, size)
        val parts = face.Parts()
        val cache = FrameLayers()
        val frame = Canvas(createBitmap(size, size))
        for (second in 0 until 120) cache.drawNormal(frame, size, size, t0 + second * 1000L, 1, emptyList(), parts)
        println("PERF second hand: 120 frames -> background ${cache.backgroundDraws} x, data ${cache.dataDraws} x, top ${cache.topDraws} x (before: whole face 120 x, base 2 x)")
        // The background is drawn again with the minute: the face styles it again every minute
        assertThat(cache.backgroundDraws).isEqualTo(2)
        assertThat(cache.dataDraws).isEqualTo(2)
        assertThat(parts.restDraws).isEqualTo(2)
        assertThat(cache.topDraws).isEqualTo(12)
    }

    @Test
    fun aWakeSharesTheBackgroundWithTheNormalFrames() {
        val face = Face(context, size)
        val parts = face.Parts()
        val cache = FrameLayers()
        val frame = Canvas(createBitmap(size, size))
        val leaveOut = setOf("3")
        cache.drawNormal(frame, size, size, t0, 1, emptyList(), parts)
        // 6 s at 24 fps
        for (i in 0 until 144) cache.drawWake(frame, size, size, t0 + 1000 + i * 41L, 1, emptyList(), parts, leaveOut, noLive)
        assertThat(cache.backgroundDraws).isEqualTo(1)
        // The data and the top without the live view once for the whole wake
        assertThat(cache.dataDraws).isEqualTo(2)
        assertThat(cache.topDraws).isEqualTo(2)
        // Back to normal frames: the data and the top with the view again, the background stays
        cache.drawNormal(frame, size, size, t0 + 8000, 1, emptyList(), parts)
        cache.drawNormal(frame, size, size, t0 + 9000, 1, emptyList(), parts)
        assertThat(cache.backgroundDraws).isEqualTo(1)
        assertThat(cache.dataDraws).isEqualTo(3)
        assertThat(cache.topDraws).isEqualTo(3)
        // The resting layers are only drawn in the normal frames
        assertThat(parts.restDraws).isEqualTo(2)
    }

    @Test
    fun aBackgroundRestyledWithTheMinuteShowsWhileTheContentStaysTheSame() {
        // Every minute the face styles its background again, for example red for an old glucose value,
        // without a new content: the next minute must show it, also in a wake
        val face = Face(context, size)
        val parts = face.Parts()
        val cache = FrameLayers()
        val frame = createBitmap(size, size)
        cache.drawNormal(Canvas(frame), size, size, t0, 1, emptyList(), parts)
        parts.backgroundColor = Color.RED
        cache.drawNormal(Canvas(frame), size, size, t0 + 30_000, 1, emptyList(), parts)
        assertThat(frame.getPixel(size - 2, size - 2)).isEqualTo(Color.DKGRAY)
        cache.drawNormal(Canvas(frame), size, size, t0 + 60_000, 1, emptyList(), parts)
        assertThat(frame.getPixel(size - 2, size - 2)).isEqualTo(Color.RED)
        parts.backgroundColor = Color.GREEN
        cache.drawWake(Canvas(frame), size, size, t0 + 120_000, 1, emptyList(), parts, emptySet(), noLive)
        assertThat(frame.getPixel(size - 2, size - 2)).isEqualTo(Color.GREEN)
    }

    @Test
    fun aDrawingThatThrowsIsNotUsedAgain() {
        val face = Face(context, size)
        val parts = face.Parts()
        val cache = FrameLayers()
        val broken = object : FrameParts by parts {
            override fun background(canvas: Canvas) = error("broken")
        }
        runCatching { cache.drawNormal(Canvas(createBitmap(size, size)), size, size, t0, 1, emptyList(), broken) }
        val frame = createBitmap(size, size)
        cache.drawNormal(Canvas(frame), size, size, t0, 1, emptyList(), parts)
        assertThat(cache.backgroundDraws).isEqualTo(2)
        assertThat(frame.getPixel(size - 2, size - 2)).isEqualTo(Color.DKGRAY)
    }

    @Test
    fun changedContentDrawsEverythingAgain() {
        val face = Face(context, size)
        val parts = face.Parts()
        val cache = FrameLayers()
        val frame = Canvas(createBitmap(size, size))
        cache.drawNormal(frame, size, size, t0, 1, emptyList(), parts)
        cache.drawNormal(frame, size, size, t0 + 1000, 2, emptyList(), parts)
        assertThat(cache.backgroundDraws).isEqualTo(2)
        assertThat(cache.dataDraws).isEqualTo(2)
        assertThat(cache.topDraws).isEqualTo(2)
    }

    @Test
    fun newComplicationDataDrawsTheDataAgainComparedByIdentity() {
        val face = Face(context, size)
        val parts = face.Parts()
        val cache = FrameLayers()
        val frame = Canvas(createBitmap(size, size))
        val a = StringBuilder("a")
        // The same objects in a new list: nothing changed
        cache.drawNormal(frame, size, size, t0, 1, listOf(a), parts)
        cache.drawNormal(frame, size, size, t0 + 1000, 1, listOf(a), parts)
        assertThat(cache.dataDraws).isEqualTo(1)
        // A new object, even with the same text: the library gives a slot a new object for new data
        cache.drawNormal(frame, size, size, t0 + 2000, 1, listOf(StringBuilder("a")), parts)
        assertThat(cache.dataDraws).isEqualTo(2)
        assertThat(cache.backgroundDraws).isEqualTo(1)
    }

    @Test
    fun invalidateAndANewSizeDrawAgain() {
        val face = Face(context, size)
        val parts = face.Parts()
        val cache = FrameLayers()
        cache.drawNormal(Canvas(createBitmap(size, size)), size, size, t0, 1, emptyList(), parts)
        cache.invalidate()
        cache.drawNormal(Canvas(createBitmap(size, size)), size, size, t0, 1, emptyList(), parts)
        cache.drawNormal(Canvas(createBitmap(size * 2, size * 2)), size * 2, size * 2, t0, 1, emptyList(), parts)
        assertThat(cache.backgroundDraws).isEqualTo(3)
        assertThat(cache.dataDraws).isEqualTo(3)
    }

    @Test
    fun releaseFreesThePicturesAndTheNextFrameMakesThemAgain() {
        val face = Face(context, size)
        val parts = face.Parts()
        val cache = FrameLayers()
        cache.drawWake(Canvas(createBitmap(size, size)), size, size, t0, 1, emptyList(), parts, emptySet(), noLive)
        cache.release()
        val frame = createBitmap(size, size)
        cache.drawNormal(Canvas(frame), size, size, t0, 1, emptyList(), parts)
        assertThat(largestDifference(whole(face), frame)).isAtMost(1)
    }
}
