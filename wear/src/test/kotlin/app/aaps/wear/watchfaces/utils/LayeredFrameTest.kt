// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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
 * The second hand frame: the face drawn in layers must look exactly like the face drawn in one go,
 * and the kept layers must be drawn again exactly when something in them changed.
 *
 * Real pixels: Robolectric's native graphics draws views the way the watch does in software.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class LayeredFrameTest {

    private val size = 120

    /** A small face: views that overlap, a rotated one, a see-through one and text, in paint order. */
    private class Face(context: Application, size: Int) {

        val parent = FrameLayout(context)
        val views: List<View>

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
    }

    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun Face.drawOnly(canvas: Canvas, vararg keep: Int) =
        drawWithHidden(parent, views.filterIndexed { index, _ -> index !in keep }, canvas)

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap) {
        // One unit of rounding is allowed where a see-through view is blended in a layer first
        var largest = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val e = expected.getPixel(x, y)
            val a = actual.getPixel(x, y)
            largest = maxOf(largest, abs(Color.red(e) - Color.red(a)), abs(Color.green(e) - Color.green(a)), abs(Color.blue(e) - Color.blue(a)))
        }
        assertThat(largest).isAtMost(1)
    }

    @Test
    fun facePastedFromLayersLooksLikeTheWholeFace() {
        val face = Face(context, size)
        val whole = createBitmap(size, size).apply { eraseColor(Color.BLACK) }
        face.parent.draw(Canvas(whole))

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

        assertSamePixels(whole, frame)
        // and something was drawn at all: the background, not the black start
        assertThat(frame.getPixel(size - 2, size - 2)).isEqualTo(Color.DKGRAY)
    }

    @Test
    fun hidingRestoresEveryVisibility() {
        val face = Face(context, size)
        face.views[1].visibility = View.INVISIBLE
        face.views[4].visibility = View.GONE

        face.drawOnly(Canvas(createBitmap(size, size)), 0)

        assertThat(face.views.map { it.visibility })
            .containsExactly(View.VISIBLE, View.INVISIBLE, View.VISIBLE, View.VISIBLE, View.GONE, View.VISIBLE).inOrder()
    }

    @Test
    fun aHiddenViewIsNotPainted() {
        val face = Face(context, size)
        val bitmap = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        face.drawOnly(Canvas(bitmap), 1)       // the blue box only
        assertThat(bitmap.getPixel(15, 15)).isEqualTo(Color.BLUE)
        assertThat(bitmap.getPixel(size - 2, size - 2)).isEqualTo(Color.TRANSPARENT)
    }

    // ---- LayeredFrameCache ----

    private val t0 = 1_700_000_040_000L    // a whole minute

    @Test
    fun aMinuteWithSecondsDrawsTheBaseOnceAndTheTopEveryTenSeconds() {
        val cache = LayeredFrameCache()
        var baseDraws = 0
        var topDraws = 0
        for (second in 0 until 60) {
            val now = t0 + second * 1000L
            cache.base(size, size, now, content = 1, complications = emptyList()) { baseDraws++ }
            cache.top(size, size, now, content = 1) { topDraws++ }
        }
        println("PERF second hand: 60 frames -> base drawn $baseDraws x, top drawn $topDraws x (before: whole face 60 x)")
        assertThat(baseDraws).isEqualTo(1)
        assertThat(topDraws).isEqualTo(6)
        assertThat(cache.baseDraws).isEqualTo(1)
        assertThat(cache.topDraws).isEqualTo(6)
    }

    @Test
    fun aNewMinuteDrawsBothAgain() {
        val cache = LayeredFrameCache()
        cache.base(size, size, t0 + 59_000, 1, emptyList()) {}
        cache.top(size, size, t0 + 59_000, 1) {}
        cache.base(size, size, t0 + 60_000, 1, emptyList()) {}
        cache.top(size, size, t0 + 60_000, 1) {}
        assertThat(cache.baseDraws).isEqualTo(2)
        assertThat(cache.topDraws).isEqualTo(2)
    }

    @Test
    fun changedContentDrawsBothAgainAndTheTopFollowsTheBase() {
        val cache = LayeredFrameCache()
        cache.base(size, size, t0, 1, emptyList()) {}
        cache.top(size, size, t0, 1) {}
        cache.base(size, size, t0 + 1000, 2, emptyList()) {}
        cache.top(size, size, t0 + 1000, 2) {}
        assertThat(cache.baseDraws).isEqualTo(2)
        assertThat(cache.topDraws).isEqualTo(2)
    }

    @Test
    fun newComplicationDataDrawsTheBaseAgain() {
        val cache = LayeredFrameCache()
        cache.base(size, size, t0, 1, listOf("a")) {}
        cache.base(size, size, t0 + 1000, 1, listOf("a")) {}
        cache.base(size, size, t0 + 2000, 1, listOf("b")) {}
        assertThat(cache.baseDraws).isEqualTo(2)
    }

    @Test
    fun invalidateAndANewSizeDrawAgain() {
        val cache = LayeredFrameCache()
        cache.base(size, size, t0, 1, emptyList()) {}
        cache.invalidate()
        cache.base(size, size, t0, 1, emptyList()) {}
        val bigger = cache.base(size * 2, size * 2, t0, 1, emptyList()) {}
        assertThat(cache.baseDraws).isEqualTo(3)
        assertThat(bigger.width).isEqualTo(size * 2)
    }

    @Test
    fun theBaseStartsBlackAndTheTopTransparent() {
        val cache = LayeredFrameCache()
        val base = cache.base(size, size, t0, 1, emptyList()) {}
        val top = cache.top(size, size, t0, 1) {}
        assertThat(base.getPixel(5, 5)).isEqualTo(Color.BLACK)
        assertThat(top.getPixel(5, 5)).isEqualTo(Color.TRANSPARENT)
    }

    @Test
    fun theKeptBitmapIsReused() {
        val cache = LayeredFrameCache()
        val first = cache.base(size, size, t0, 1, emptyList()) {}
        val second = cache.base(size, size, t0 + 60_000, 1, emptyList()) {}
        assertThat(second).isSameInstanceAs(first)
        cache.release()
        assertThat(first.isRecycled).isTrue()
    }
}
