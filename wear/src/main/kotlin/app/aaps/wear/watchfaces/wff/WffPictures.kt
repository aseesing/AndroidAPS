// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import android.graphics.Bitmap
import app.aaps.wear.watchfaces.animation.DESIGN_SIZE
import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.LayerBlend
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The rules for the pictures of a converted face: the file format of a picture, how a see-through
 * frame is made from the engine's frames, and which part of a picture is kept.
 *
 * Our engine bakes the pictures on a dev machine, see [WffDesignParts]. Pixels here are ARGB ints,
 * not premultiplied, row by row, as `Bitmap.getPixels` gives them, so the rules can be tested without
 * a real bitmap.
 */
object WffPictures {

    /** The pixels of a picture: [argb] row by row, [width] times [height]. */
    class Pixels(val width: Int, val height: Int, val argb: IntArray) {

        init {
            require(argb.size == width * height) { "${argb.size} pixels are not $width x $height" }
        }
    }

    /** A box of pixels. [right] and [bottom] are the first pixels outside the box. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {

        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** The file format of a picture in the face APK. */
    enum class Format(val extension: String) { WEBP("webp"), PNG("png") }

    /** A picture as the bytes of its file. */
    class Encoded(val format: Format, val bytes: ByteArray)

    /** The quality of a lossy WebP: a fifth of the size of a PNG, and the eye sees no difference on a watch. */
    const val WEBP_QUALITY = 80

    /**
     * Pictures are cut on a grid of this many pixels. The pictures are baked 450 pixels wide, and 9 of
     * 450 pixels are 8 design pixels (of 400), so every box is whole in design pixels too.
     */
    const val SNAP = 9

    /** True when some pixel of [pixels] is not fully opaque. */
    fun hasTransparency(pixels: Pixels): Boolean = pixels.argb.any { it ushr 24 != OPAQUE }

    /**
     * The format of the file of [pixels]. An opaque picture is a lossy WebP: a fifth of the size of a
     * PNG. A picture with any pixel that is not fully opaque must be a PNG: the Watch Face Push
     * validator (validator-push-cli 1.1.0-alpha01) crashes on a lossy WebP with transparency
     * ("Coordinate out of bounds"), so the face would not pass.
     *
     * Frames of light that adds to what lies below ([lightBlend], played with `PLUS` or `SCREEN`) are
     * PNG too, though they are opaque: the noise of a lossy picture in their black would add a faint
     * light over the whole box.
     */
    fun formatOf(pixels: Pixels, addsLight: Boolean = false): Format = if (addsLight || hasTransparency(pixels)) Format.PNG else Format.WEBP

    /** [bitmap] as the bytes of its file, in the format of [formatOf]. */
    fun encode(bitmap: Bitmap, addsLight: Boolean = false): Encoded {
        val format = formatOf(pixelsOf(bitmap), addsLight)
        val out = ByteArrayOutputStream()
        when (format) {
            Format.WEBP -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
            Format.PNG  -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Encoded(format, out.toByteArray())
    }

    /** The pixels of [bitmap]. */
    fun pixelsOf(bitmap: Bitmap): Pixels {
        val w = bitmap.width
        val h = bitmap.height
        return Pixels(w, h, IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) })
    }

    /** A bitmap with [pixels]. */
    fun bitmapOf(pixels: Pixels): Bitmap = Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)

    // ---------------------------------------------------------------------------------------------
    // See-through frames
    // ---------------------------------------------------------------------------------------------

    /** A PNG of one black pixel, the background of the first drawing of a frame, see [matte]. */
    val BLACK_PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==")

    /** A PNG of one white pixel, the background of the second drawing of a frame, see [matte]. */
    val WHITE_PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=")

    /**
     * A PNG of one clear pixel (RGBA 0, 0, 0, 0): the background of a copy of a design that must be
     * see-through. Without a background picture the face draws its own default background.
     */
    val CLEAR_PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNgAAIAAAUAAen63NgAAAAASUVORK5CYII=")

    /**
     * One see-through frame from the same frame drawn twice: [onBlack] on a black background
     * ([BLACK_PNG]) and [onWhite] on a white one ([WHITE_PNG]).
     *
     * Why: a frame of the engine's wake always has a background, even when the design's background is
     * clear. So the frame is drawn on black and on white. Where the two differ, the frame lets the
     * background through, and how much they differ is how see-through it is there (difference
     * matting): alpha = 255 - the largest difference of a channel (white - black), and the colour is
     * the colour on black divided by alpha. This is exact, also for the soft edges of a snow flake.
     */
    fun matte(onBlack: Pixels, onWhite: Pixels): Pixels {
        require(onBlack.width == onWhite.width && onBlack.height == onWhite.height) { "The two frames differ in size" }
        val b = onBlack.argb
        val l = onWhite.argb
        val out = IntArray(b.size)
        for (i in out.indices) {
            val pb = b[i]
            val pw = l[i]
            // How much of the background shows: the largest difference of a channel
            val through = maxOf((pw shr 16 and 0xff) - (pb shr 16 and 0xff), (pw shr 8 and 0xff) - (pb shr 8 and 0xff), (pw and 0xff) - (pb and 0xff)).coerceIn(0, 255)
            val alpha = 255 - through
            if (alpha == 0) continue
            // On black the colour is the frame's colour times its alpha
            fun channel(shift: Int) = ((pb shr shift and 0xff) * 255 / alpha).coerceAtMost(255)
            out[i] = (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return Pixels(onBlack.width, onBlack.height, out)
    }

    /**
     * The WFF `blendMode` that the frames of a layer with [blend] are played with: `PLUS` for
     * [LayerBlend.ADD], `SCREEN` for [LayerBlend.SCREEN]; null for any other layer.
     *
     * Why: a layer that adds light has no transparency of its own, so [matte] cannot make it
     * see-through. Its frames stay on black, and the face mixes them with what lies below with this
     * blend. Black adds nothing, so the black around the light does not show.
     */
    fun lightBlend(blend: LayerBlend?): String? = blend?.takeIf { it == LayerBlend.ADD || it == LayerBlend.SCREEN }?.wff

    // ---------------------------------------------------------------------------------------------
    // Boxes
    // ---------------------------------------------------------------------------------------------

    /** The pixels of [pixels] that are not fully clear, as a box snapped out to [snap]; null when all are clear. */
    fun inkBox(pixels: Pixels, snap: Int): Box? = boxOf(pixels.width, pixels.height, snap) { i -> pixels.argb[i] ushr 24 != 0 }

    /** The pixels of [pixels] that are not black, as [inkBox] does for the pixels that are not clear. For frames on black, see [lightBlend]. */
    fun lightBox(pixels: Pixels, snap: Int): Box? = boxOf(pixels.width, pixels.height, snap) { i -> pixels.argb[i] and 0xffffff != 0 }

    /** The pixels where [a] and [b] (of the same size) differ, as [inkBox] does for the pixels that are not clear. */
    fun diffBox(a: Pixels, b: Pixels, snap: Int): Box? {
        require(a.width == b.width && a.height == b.height) { "The two pictures differ in size" }
        return boxOf(a.width, a.height, snap) { i -> a.argb[i] != b.argb[i] }
    }

    /** What a frame shows: where it is not black for a frame on black ([onBlack], see [lightBlend]), else where it is not clear. */
    fun shownBox(pixels: Pixels, snap: Int, onBlack: Boolean): Box? = if (onBlack) lightBox(pixels, snap) else inkBox(pixels, snap)

    /** The smallest box that holds [a] and [b]; null when both are null. */
    fun union(a: Box?, b: Box?): Box? = when {
        a == null -> b
        b == null -> a
        else      -> Box(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    }

    /** [box] in pixels of a picture [size] pixels wide as a box of the design (400 wide). */
    fun designRect(box: Box, size: Int): DesignRect {
        val k = DESIGN_SIZE / size
        return DesignRect(box.left * k, box.top * k, box.width * k, box.height * k)
    }

    private inline fun boxOf(w: Int, h: Int, snap: Int, shows: (Int) -> Boolean): Box? {
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) for (x in 0 until w) if (shows(y * w + x)) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (right < 0) return null
        return Box(left / snap * snap, top / snap * snap, minOf(w, (right / snap + 1) * snap), minOf(h, (bottom / snap + 1) * snap))
    }

    private const val OPAQUE = 0xff
}
