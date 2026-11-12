// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.utils.LayeredFrameCache

/**
 * The parts of a watch face, in paint order, as the wake-up animation needs them. Each part draws
 * only its own views, exactly where they sit in the whole face.
 */
interface WakeFrameParts {

    /** Called once at the start of every frame, before any part draws: lay the views out. */
    fun prepare() {}

    /** The background image and colour. Static. */
    fun background(canvas: Canvas)

    /** Complications, chart and data views below the clock text. Change with the data. */
    fun data(canvas: Canvas)

    /** The clock text. Drawn live every frame. */
    fun clockText(canvas: Canvas)

    /** Everything between the clock text and the second hand: the rest of the data, the hour and minute hands. */
    fun top(canvas: Canvas)

    /** The second hand. Drawn live every frame. */
    fun secondHand(canvas: Canvas)
}

/**
 * One kept picture of a part, drawn again only when its [state] changed.
 *
 * @param clearColor the colour the picture starts from: black for the bottom one, transparent for a
 *   picture that is laid on top of others
 */
class KeptLayer(private val clearColor: Int, private val newBitmap: (Int, Int) -> Bitmap) {

    private var bitmap: Bitmap? = null
    private var state: Any? = null

    /** How often the picture was drawn, for tests. */
    var draws = 0
        private set

    fun get(width: Int, height: Int, state: Any, draw: (Canvas) -> Unit): Bitmap {
        val current = bitmap
        val kept = if (current == null || current.isRecycled || current.width != width || current.height != height) {
            current?.recycle()
            this.state = null
            newBitmap(width, height).also { bitmap = it }
        } else current
        if (state != this.state) {
            kept.eraseColor(clearColor)
            draw(Canvas(kept))
            this.state = state
            draws++
        }
        return kept
    }

    fun invalidate() {
        state = null
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
        state = null
    }
}

/**
 * A wake-up animation frame, put together from kept pictures and live drawing:
 *
 * 1. the background (kept, opaque),
 * 2. the rain or the glitch bands (live) - above the background, below every data view, so the text stays readable,
 * 3. the data below the clock text (kept, transparent where nothing paints),
 * 4. the clock text (live),
 * 5. the rest of the data and the hour and minute hands (kept, transparent),
 * 6. the heart (live),
 * 7. the second hand (live).
 *
 * Without rain and heart this gives the same picture as the face drawn in one go, the same way
 * [LayeredFrameCache] does. The kept pictures follow the same rules as there: the data ones are
 * drawn again when the minute, the content or the complications change, the top one also every
 * [LayeredFrameCache.TOP_PERIOD_MS]. Three pictures of about 0.9 MB each on a 466 px screen.
 */
class WakeFrameLayers(newBitmap: (Int, Int) -> Bitmap = { width, height -> createBitmap(width, height) }) {

    private data class State(val width: Int, val height: Int, val period: Long, val content: Int, val complications: List<Any?>)

    private val background = KeptLayer(Color.BLACK, newBitmap)
    private val data = KeptLayer(Color.TRANSPARENT, newBitmap)
    private val top = KeptLayer(Color.TRANSPARENT, newBitmap)

    /** How often each kept picture was drawn, for tests. */
    val backgroundDraws: Int get() = background.draws
    val dataDraws: Int get() = data.draws
    val topDraws: Int get() = top.draws

    /**
     * Draws one frame at [nowMs] onto [canvas]. [rain] is drawn above the background, [heart] below
     * the second hand.
     */
    fun draw(
        canvas: Canvas, width: Int, height: Int, nowMs: Long, content: Int, complications: List<Any?>,
        parts: WakeFrameParts, rain: (Canvas) -> Unit, heart: (Canvas) -> Unit
    ) {
        parts.prepare()
        val minute = State(width, height, nowMs / LayeredFrameCache.MINUTE_MS, content, complications)
        canvas.drawBitmap(background.get(width, height, minute, parts::background), 0f, 0f, null)
        rain(canvas)
        canvas.drawBitmap(data.get(width, height, minute, parts::data), 0f, 0f, null)
        parts.clockText(canvas)
        val topState = State(width, height, nowMs / LayeredFrameCache.TOP_PERIOD_MS, content, complications)
        canvas.drawBitmap(top.get(width, height, topState, parts::top), 0f, 0f, null)
        heart(canvas)
        parts.secondHand(canvas)
    }

    /** Forgets the pictures, so the next frame draws them again. */
    fun invalidate() {
        background.invalidate()
        data.invalidate()
        top.invalidate()
    }

    /** Frees the pictures. */
    fun release() {
        background.release()
        data.release()
        top.release()
    }
}
