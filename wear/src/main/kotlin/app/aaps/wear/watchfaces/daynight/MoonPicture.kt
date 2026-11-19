// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.utils.DynamicImage
import app.aaps.wear.watchfaces.utils.DynamicImages
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The moon as you see it now, drawn on the watch from one photo of the full moon: the dark part is
 * made dark, and the moon is turned the way it stands in your sky (see [MoonLight]).
 *
 * The dynamic picture [NAME] of a design (see [DynamicImages]): a view or a layer asks for it with
 * `"image": "@moon"`. The photo comes with the watch app (NASA's
 * Scientific Visualization Studio, public domain), so a design does not need any moon pictures, and
 * it fits a view of any size. The photo is read once, for every size.
 *
 * A picture is right for [REDRAW_MS] and for the same place; [DynamicImages] keeps it that long, so
 * this costs nearly nothing.
 *
 * @param loadPhoto reads the photo of the full moon, once, when it is first needed. Null if it fails.
 * @param place where the wearer is, or null (then north is up)
 */
class MoonPicture(private val loadPhoto: () -> Bitmap?, private val place: () -> GeoPoint? = { null }) : DynamicImage {

    private val photo: Bitmap? by lazy { runCatching { loadPhoto() }.getOrNull() }

    override val refreshMs: Long get() = REDRAW_MS

    override fun inputs(): Any? = place()

    /** The moon at [epochMs] seen from the place now, [size] pixels wide; null if it cannot be made. */
    override fun draw(epochMs: Long, size: Int): Bitmap? {
        if (size <= 0) return null
        val photo = photo ?: return null
        return draw(photo, size, MoonLight.at(epochMs, place()))
    }

    companion object {

        /** The name of the picture in [DynamicImages]. */
        const val NAME = "moon"

        /** How often the moon is drawn again. The tilt changes the most: about 15 degrees an hour. */
        const val REDRAW_MS = 15 * 60_000L

        /** How bright the dark part is, so the moon can still be found at new moon (the light of the earth). */
        const val DARK = 0.3

        /**
         * How much the dark part covers what is behind it. Partly see-through, as in the real sky: by day
         * the blue shows through it, at night it is a faint grey (about [DARK] x [DARK_COVER] on black).
         */
        const val DARK_COVER = 0.45

        /**
         * The [photo] (the full moon, north up, the disc filling the square) as it looks with [look],
         * in a square of [size] pixels: round, with soft edges, the rest see-through.
         */
        fun draw(photo: Bitmap, size: Int, look: MoonLight.Look): Bitmap {
            val image = createBitmap(size, size)
            val canvas = Canvas(image)
            val c = size / 2f
            // The canvas turns with the clock, the angles of the look against it
            canvas.rotate(-look.northAngle.toFloat(), c, c)
            // A little bigger than the square, so the dark edge around the disc of the photo is cut off
            val over = size * PHOTO_OVERSIZE
            canvas.drawBitmap(photo, null, RectF(-over, -over, size + over, size + over), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val pixels = IntArray(size * size)
            image.getPixels(pixels, 0, size, 0, 0, size, size)
            val r = size / 2.0
            // The lit side on the screen: x to the right, y up
            val angle = Math.toRadians(look.limbAngle)
            val litX = -sin(angle)
            val litY = cos(angle)
            for (row in 0 until size) for (column in 0 until size) {
                val x = (column + 0.5 - r) / r
                val y = (r - row - 0.5) / r
                val distance = sqrt(x * x + y * y)
                val index = row * size + column
                // Soft over one pixel at the rim and at the line between light and dark
                val rim = ((1 - distance) * r + 0.5).coerceIn(0.0, 1.0)
                if (rim <= 0.0) {
                    pixels[index] = Color.TRANSPARENT
                    continue
                }
                val along = x * litX + y * litY
                val across = x * litY - y * litX
                val halfWidth = sqrt((1 - across * across).coerceAtLeast(0.0))
                val light = ((along - look.terminator * halfWidth) * r + 0.5).coerceIn(0.0, 1.0)
                val shade = DARK + (1 - DARK) * light
                val cover = DARK_COVER + (1 - DARK_COVER) * light
                val pixel = pixels[index]
                pixels[index] = Color.argb(
                    (rim * cover * 255).toInt(),
                    (Color.red(pixel) * shade).toInt(),
                    (Color.green(pixel) * shade).toInt(),
                    (Color.blue(pixel) * shade).toInt()
                )
            }
            image.setPixels(pixels, 0, size, 0, 0, size, size)
            return image
        }

        /** How much the photo is drawn bigger than the square, on each side. */
        private const val PHOTO_OVERSIZE = 0.015f
    }
}
