package app.aaps.wear.watchfaces.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.core.graphics.createBitmap

/**
 * Draws [parent] with the views in [hide] left out, so only the rest paints.
 *
 * Views are hidden with `INVISIBLE` rather than `GONE`: `GONE` would change the layout of everything
 * around them, while `INVISIBLE` only skips the draw. So each part is drawn exactly where it sits in
 * the whole picture, and parts drawn one after another in paint order give the whole picture again.
 * Views that were not visible are left alone, and every view gets its own visibility back.
 */
fun drawWithHidden(parent: View, hide: List<View>, canvas: Canvas) {
    val hidden = hide.filter { it.visibility == View.VISIBLE }
    hidden.forEach { it.visibility = View.INVISIBLE }
    try {
        parent.draw(canvas)
    } finally {
        hidden.forEach { it.visibility = View.VISIBLE }
    }
}

/**
 * Keeps the parts of a watch face that do not change every second as two finished pictures, so a
 * frame with a running second hand only pastes them and draws the few views that do change.
 *
 * - The **base** is everything below the clock text: background, complications, chart and data. It
 *   is drawn again when the minute changes or anything else changed ([content], the complications).
 * - The **top** is everything between the clock text and the second hand: the rest of the data and
 *   the hour and minute hands. It is drawn again with the base, and every [TOP_PERIOD_MS] in between,
 *   so the minute hand keeps creeping forward in steps of one degree.
 *
 * Both are kept while the watch face lives, about 0.9 MB each on a 466 px screen, so a wrist raise
 * does not allocate them again.
 */
class LayeredFrameCache(private val newBitmap: (Int, Int) -> Bitmap = { width, height -> createBitmap(width, height) }) {

    private data class State(val width: Int, val height: Int, val period: Long, val content: Int, val complications: List<Any?>)

    private var base: Bitmap? = null
    private var top: Bitmap? = null
    private var baseState: State? = null
    private var topState: State? = null

    /** How often the base and the top were drawn, for tests and logging. */
    var baseDraws = 0
        private set
    var topDraws = 0
        private set

    /**
     * The base for a frame at [nowMs], drawn by [draw] first when the one kept is out of date. Starts
     * from black, like a cleared screen.
     */
    fun base(width: Int, height: Int, nowMs: Long, content: Int, complications: List<Any?>, draw: (Canvas) -> Unit): Bitmap {
        val state = State(width, height, nowMs / MINUTE_MS, content, complications)
        if (!fits(base, width, height)) {
            base?.recycle()
            base = newBitmap(width, height)
            baseState = null
        }
        val bitmap = base!!
        if (state != baseState) {
            bitmap.eraseColor(Color.BLACK)
            draw(Canvas(bitmap))
            baseState = state
            topState = null    // what lies on top was drawn against the old base
            baseDraws++
        }
        return bitmap
    }

    /** The top for a frame at [nowMs], drawn by [draw] first when the one kept is out of date. Transparent where nothing paints. */
    fun top(width: Int, height: Int, nowMs: Long, content: Int, draw: (Canvas) -> Unit): Bitmap {
        val state = State(width, height, nowMs / TOP_PERIOD_MS, content, emptyList())
        if (!fits(top, width, height)) {
            top?.recycle()
            top = newBitmap(width, height)
            topState = null
        }
        val bitmap = top!!
        if (state != topState) {
            bitmap.eraseColor(Color.TRANSPARENT)
            draw(Canvas(bitmap))
            topState = state
            topDraws++
        }
        return bitmap
    }

    /** Forgets both pictures, so the next frame draws them again. Call when frames are drawn the normal way meanwhile. */
    fun invalidate() {
        baseState = null
        topState = null
    }

    /** Frees both pictures. */
    fun release() {
        invalidate()
        base?.recycle()
        top?.recycle()
        base = null
        top = null
    }

    private fun fits(bitmap: Bitmap?, width: Int, height: Int): Boolean =
        bitmap != null && !bitmap.isRecycled && bitmap.width == width && bitmap.height == height

    companion object {

        const val MINUTE_MS = 60_000L

        /** The minute hand moves 1 degree in 10 s, the smallest step anyone could see. */
        const val TOP_PERIOD_MS = 10_000L
    }
}
