// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.ViewEffects
import app.aaps.wear.watchfaces.animation.ViewPlaces

/**
 * The parts of a watch face, in paint order. Each part draws only its own views, exactly where they
 * sit in the whole face, so the parts drawn one after another give the whole face again.
 */
interface FrameParts : ViewPlaces {

    /** Called once at the start of every frame, before any part draws: lay the views out. */
    fun prepare() {}

    /**
     * The background image and colour. Changes with the design, and can change with the minute: the
     * design can colour it by a value, like the red background of an old glucose value.
     */
    fun background(canvas: Canvas)

    /**
     * The layers of [slot] that stay on the face while no wake plays, at the clock time [nowMs]. Only in
     * a normal frame: [LayerSlot.BACK] right above the background, [LayerSlot.VIEWS] above the data below
     * the clock text, [LayerSlot.FRONT] above the top, below the second hand.
     */
    fun rest(canvas: Canvas, nowMs: Long, slot: LayerSlot) {}

    /** Complications, chart and data views below the clock text, without the views in [leaveOut]. Change with the data. */
    fun data(canvas: Canvas, leaveOut: Set<String>)

    /** The clock text. Drawn live every frame. */
    fun clockText(canvas: Canvas)

    /**
     * Everything between the clock text and the second hand: the rest of the data, the hour and minute
     * hands, without the views in [leaveOut].
     */
    fun top(canvas: Canvas, leaveOut: Set<String>)

    /** The second hand. Drawn live every frame. */
    fun secondHand(canvas: Canvas)

    /**
     * Only the view `effects.keys[index]`, where it sits, moved, turned, scaled and faded as [effects]
     * says for it. Drawn live in a wake frame. The caller cuts it (see [ViewEffects.isClipped]).
     */
    fun view(canvas: Canvas, effects: ViewEffects, index: Int) {}

    /**
     * The views `effects.keys[i]` for which [members] is true and that are neither hidden nor cut, where
     * they sit, each changed as [effects] says, in one go, in the paint order of the face. Drawn live in
     * a wake frame.
     */
    fun views(canvas: Canvas, effects: ViewEffects, members: BooleanArray) {}
}

/** What a wake frame draws live, between the kept pictures. See [FrameLayers.drawWake]. */
interface WakeLayers {

    /** Above the background, below every data view: the rain, the glitch bands. */
    fun back(canvas: Canvas)

    /** Above the data below the clock text: the views that move, and the shapes of the views slot. */
    fun views(canvas: Canvas)

    /** Below the second hand: the heart, the light. */
    fun front(canvas: Canvas)
}

/**
 * The kept pictures of a watch face frame, for the normal frame with a running second hand and for
 * the frames of the wake-up animation alike, so a frame only pastes them and draws the few parts that
 * change every frame. Three pictures, about 0.9 MB each on a 466 px screen:
 *
 * - The **background**: the background image and colour. Drawn again when the content, the size or
 *   the minute changes.
 * - The **data**: everything from the background up to the clock text. In a normal frame it starts
 *   as a copy of the background, then the resting layers, complications, chart and data views are
 *   drawn on it, so it is the whole picture below the clock text, exactly as the face draws it in
 *   one go. In a wake frame it is transparent and has only the complications and the data views,
 *   without the views the animation draws live, because the rain falls between the background and
 *   the data. Drawn again when the minute, the content, the complications or the kind of frame change.
 * - The **top**: everything between the clock text and the second hand, transparent. Drawn again
 *   with the data, and every [TOP_PERIOD_MS] in between, so the minute hand keeps creeping forward.
 *
 * Paint order of a normal frame: data, clock text, top, second hand. Of a wake frame: background,
 * [WakeLayers.back], data, [WakeLayers.views], clock text, top, [WakeLayers.front], second hand.
 *
 * The content (a number that changes when the face changes) and the complications decide, compared
 * by value and by identity: nothing is made new to compare them.
 *
 * Not thread safe: one cache draws on one thread.
 */
class FrameLayers(private val newBitmap: (Int, Int) -> Bitmap = { width, height -> createBitmap(width, height) }) {

    /** One kept picture with the canvas that draws on it. */
    private inner class Kept {

        var bitmap: Bitmap? = null
        private var canvas: Canvas? = null
        var valid = false
        var draws = 0

        /**
         * The canvas for a new drawing at [width] x [height], cleared to [clear]: a new bitmap only for a
         * new size. The picture is not valid until [commit]: a drawing that throws is not used again.
         */
        fun begin(width: Int, height: Int, clear: Int): Canvas {
            valid = false
            val kept = bitmap
            val target = if (kept == null || kept.isRecycled || kept.width != width || kept.height != height) {
                kept?.recycle()
                newBitmap(width, height).also {
                    bitmap = it
                    canvas = Canvas(it)
                }
            } else kept
            target.eraseColor(clear)
            draws++
            return canvas!!.also { it.restoreToCount(1) }
        }

        /** The drawing begun with [begin] is complete. */
        fun commit() {
            valid = true
        }

        fun fits(width: Int, height: Int): Boolean = valid && bitmap?.let { !it.isRecycled && it.width == width && it.height == height } == true

        fun release() {
            bitmap?.recycle()
            bitmap = null
            canvas = null
            valid = false
        }
    }

    private val background = Kept()
    private val data = Kept()
    private val top = Kept()

    private var backgroundContent: Any = 0
    private var backgroundMinute = 0L

    private var dataMinute = 0L
    private var dataContent: Any = 0
    private var dataLeaveOut: Set<String>? = null
    private var dataIsWake = false
    private val dataComplications = ArrayList<Any?>()

    private var topPeriod = 0L
    private var topContent: Any = 0
    private var topLeaveOut: Set<String>? = null
    private var topRest = false

    /** How often each kept picture was drawn, for tests and logging. */
    val backgroundDraws: Int get() = background.draws
    val dataDraws: Int get() = data.draws
    val topDraws: Int get() = top.draws

    /**
     * Draws a normal frame at [nowMs] onto [canvas]: the kept data picture (background, resting layers,
     * data), the clock text, the kept top picture and the second hand.
     */
    fun drawNormal(canvas: Canvas, width: Int, height: Int, nowMs: Long, content: Any, complications: List<Any?>, parts: FrameParts) {
        parts.prepare()
        val minute = Math.floorDiv(nowMs, MINUTE_MS)
        if (!dataFits(width, height, minute, content, complications, NO_VIEWS, wake = false)) {
            val back = background(width, height, minute, content, parts)
            val c = data.begin(width, height, Color.BLACK)
            // The whole picture below the clock text, as the face draws it in one go
            c.drawBitmap(back, 0f, 0f, null)
            parts.rest(c, nowMs, LayerSlot.BACK)
            parts.data(c, NO_VIEWS)
            parts.rest(c, nowMs, LayerSlot.VIEWS)
            data.commit()
            keepData(minute, content, complications, NO_VIEWS, wake = false)
        }
        canvas.drawBitmap(data.bitmap!!, 0f, 0f, null)
        parts.clockText(canvas)
        canvas.drawBitmap(top(width, height, nowMs, content, NO_VIEWS, parts, rest = true), 0f, 0f, null)
        parts.secondHand(canvas)
    }

    /**
     * Draws a wake frame at [nowMs] onto [canvas]. The views in [leaveOut] are left out of the kept
     * pictures; [live] draws what it wants of them, and the rain and the heart, between them.
     */
    fun drawWake(
        canvas: Canvas, width: Int, height: Int, nowMs: Long, content: Any, complications: List<Any?>,
        parts: FrameParts, leaveOut: Set<String>, live: WakeLayers
    ) {
        parts.prepare()
        val minute = Math.floorDiv(nowMs, MINUTE_MS)
        canvas.drawBitmap(background(width, height, minute, content, parts), 0f, 0f, null)
        live.back(canvas)
        if (!dataFits(width, height, minute, content, complications, leaveOut, wake = true)) {
            parts.data(data.begin(width, height, Color.TRANSPARENT), leaveOut)
            data.commit()
            keepData(minute, content, complications, leaveOut, wake = true)
        }
        canvas.drawBitmap(data.bitmap!!, 0f, 0f, null)
        live.views(canvas)
        parts.clockText(canvas)
        canvas.drawBitmap(top(width, height, nowMs, content, leaveOut, parts, rest = false), 0f, 0f, null)
        live.front(canvas)
        parts.secondHand(canvas)
    }

    /** Forgets the pictures, so the next frame draws them again. The bitmaps are kept. */
    fun invalidate() {
        background.valid = false
        data.valid = false
        top.valid = false
    }

    /** Frees the pictures. The next frame makes them again. */
    fun release() {
        background.release()
        data.release()
        top.release()
        dataComplications.clear()
        dataLeaveOut = null
        topLeaveOut = null
    }

    /**
     * The background picture, drawn again for a new [content], size or [minute]. The minute too: every
     * minute the face styles its background again, and the design can colour it by a value (the red
     * background of an old glucose value is a safety cue), without telling anyone.
     */
    private fun background(width: Int, height: Int, minute: Long, content: Any, parts: FrameParts): Bitmap {
        if (!background.fits(width, height) || backgroundContent != content || backgroundMinute != minute) {
            parts.background(background.begin(width, height, Color.BLACK))
            background.commit()
            backgroundContent = content
            backgroundMinute = minute
        }
        return background.bitmap!!
    }

    private fun dataFits(width: Int, height: Int, minute: Long, content: Any, complications: List<Any?>, leaveOut: Set<String>, wake: Boolean): Boolean =
        data.fits(width, height) && dataMinute == minute && dataContent == content && dataLeaveOut === leaveOut && dataIsWake == wake &&
            sameComplications(complications)

    private fun keepData(minute: Long, content: Any, complications: List<Any?>, leaveOut: Set<String>, wake: Boolean) {
        dataMinute = minute
        dataContent = content
        dataLeaveOut = leaveOut
        dataIsWake = wake
        dataComplications.clear()
        for (i in complications.indices) dataComplications.add(complications[i])
        // What lies on top was drawn against the old data: the hands may have moved with the minute
        top.valid = false
    }

    /** True when [complications] are the very objects the data picture was drawn with: the library gives a slot a new object for new data. */
    private fun sameComplications(complications: List<Any?>): Boolean {
        if (complications.size != dataComplications.size) return false
        for (i in complications.indices) if (complications[i] !== dataComplications[i]) return false
        return true
    }

    /** The top picture; with the resting layers of the front slot when [rest] (a normal frame). */
    private fun top(width: Int, height: Int, nowMs: Long, content: Any, leaveOut: Set<String>, parts: FrameParts, rest: Boolean): Bitmap {
        val period = Math.floorDiv(nowMs, TOP_PERIOD_MS)
        if (!top.fits(width, height) || topPeriod != period || topContent != content || topLeaveOut !== leaveOut || topRest != rest) {
            val c = top.begin(width, height, Color.TRANSPARENT)
            parts.top(c, leaveOut)
            if (rest) parts.rest(c, nowMs, LayerSlot.FRONT)
            top.commit()
            topRest = rest
            topPeriod = period
            topContent = content
            topLeaveOut = leaveOut
        }
        return top.bitmap!!
    }

    companion object {

        const val MINUTE_MS = 60_000L

        /** The minute hand moves 1 degree in 10 s, the smallest step anyone could see. */
        const val TOP_PERIOD_MS = 10_000L

        /** No view left out: the leave-out set of a normal frame. */
        val NO_VIEWS: Set<String> = emptySet()
    }
}
