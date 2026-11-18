// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * One frame of the live preview, as the screen gets it: the [image] and its [number]. A new object for
 * every frame, also when the renderer drew it into a bitmap it used before, so every frame makes the
 * screen draw again (see [PreviewFramePainter]). [drawn] tells the [FrameHandOff] the screen drew it.
 */
@Immutable
class PreviewFrame(val image: ImageBitmap, val number: Long, private val handOff: FrameHandOff<*>) {

    /** Call when the screen draws this frame: older frames are then never drawn again. */
    fun drawn() = handOff.drawn(number)
}

/**
 * Says which of the bitmaps the renderer draws into the screen may still show, so a frame is never
 * drawn into a bitmap on the screen. A bitmap is free once the screen has drawn a newer frame than the
 * newest one it holds: the screen only goes forward, so it will not draw that one again. The frame on
 * the screen and the frames handed out but not drawn yet are never free.
 *
 * The screen reports from its draw ([drawn]), the renderer asks from its own thread ([isFree]).
 *
 * @param B the bitmap type (a type parameter so the rule can be tested on the JVM)
 */
class FrameHandOff<B : Any> {

    private val lock = Any()

    /** Per bitmap, the number of the newest frame drawn into it. */
    private val newest = HashMap<B, Long>()
    private var lastNumber = 0L
    private var drawnNumber = 0L

    /** True when the renderer may draw into [bitmap]: the screen drew a newer frame than any it holds. */
    fun isFree(bitmap: B): Boolean = synchronized(lock) { (newest[bitmap] ?: return true) < drawnNumber }

    /** The renderer drew a frame into [bitmap] and hands it out: its number. */
    fun handedOut(bitmap: B): Long = synchronized(lock) {
        lastNumber++
        newest[bitmap] = lastNumber
        lastNumber
    }

    /** The screen draws frame [number]. */
    fun drawn(number: Long) = synchronized(lock) {
        if (number > drawnNumber) drawnNumber = number
    }
}
