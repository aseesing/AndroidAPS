// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.utils.DynamicImage
import app.aaps.wear.watchfaces.utils.DynamicImages

/**
 * The photo of the full moon that comes with the watch app (NASA's Scientific Visualization Studio,
 * public domain), cut round: north up, all of it lit, the rest see-through. Nothing else: no phase and
 * no turn. A design makes the moon of tonight from it with plain layers (a template of its own, see
 * chapter 13 of the manual, `docs/watchface-animation.html`).
 *
 * The app picture [NAME] of a design (see [DynamicImages]): a view or a layer asks for it with
 * `"image": "@moon"`, so a design zip does not need a moon picture. It never changes, so it is made
 * once for each size.
 *
 * @param loadPhoto reads the photo, once, when it is first needed. Null if it fails.
 */
class MoonPhoto(private val loadPhoto: () -> Bitmap?) : DynamicImage {

    private val photo: Bitmap? by lazy { runCatching { loadPhoto() }.getOrNull() }

    /** The picture never changes. */
    override val refreshMs: Long get() = Long.MAX_VALUE

    override fun draw(epochMs: Long, size: Int): Bitmap? {
        if (size <= 0) return null
        return round(photo ?: return null, size)
    }

    companion object {

        /** The name of the picture in [DynamicImages]. */
        const val NAME = "moon"

        /** The [photo] (the disc filling the square) in a square of [size] pixels, cut round with a soft edge. */
        fun round(photo: Bitmap, size: Int): Bitmap {
            val image = createBitmap(size, size)
            // A little bigger than the square, so the dark edge around the disc of the photo is cut off
            val over = size * PHOTO_OVERSIZE
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            paint.shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setRectToRect(RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()), RectF(-over, -over, size + over, size + over), Matrix.ScaleToFit.FILL)
                })
            }
            Canvas(image).drawCircle(size / 2f, size / 2f, size / 2f, paint)
            return image
        }

        /** How much the photo is drawn bigger than the square, on each side. */
        private const val PHOTO_OVERSIZE = 0.015f
    }
}
