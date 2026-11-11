package app.aaps.wear.watchfaces.animation

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.PictureDrawable
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws the heart image of [spec], scaled by the pulse, with a soft glow that follows the beat.
 *
 * The image and its glow are drawn once into pictures at the largest size the beat reaches, and each
 * frame only pastes them, scaled down. A blur is slow and not every hardware canvas can do it, so it
 * is made once here, in software.
 */
class HeartPulse(private val spec: PulseSpec) {

    private var picture: Bitmap? = null
    private var glow: Bitmap? = null
    private val glowOffset = IntArray(2)
    private var madeForScale = 0f

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { color = spec.glowColor }
    private val target = RectF()
    private val glowTarget = RectF()

    /** How much larger than at rest the heart is for this [pulse] (0..1). */
    fun sizeFactor(pulse: Float): Float = 1f + spec.amplitude * pulse.coerceIn(0f, 1f)

    /**
     * Draws the heart for a beat at [pulse] (0..1, see [PulseCurve]), scaled by [scale] from the design
     * space to the surface. At 0 it lies exactly on its rest rectangle.
     */
    fun draw(canvas: Canvas, pulse: Float, scale: Float) {
        val image = picture(scale) ?: return
        val size = sizeFactor(pulse)
        val rect = spec.rect
        val width = rect.width * scale * size
        val height = rect.height * scale * size
        val centerX = (rect.left + rect.width / 2) * scale
        val centerY = (rect.top + rect.height / 2) * scale
        target.set(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
        val glowBitmap = glow
        val glowAlpha = (spec.glowAlpha * pulse.coerceIn(0f, 1f)).roundToInt()
        if (glowBitmap != null && glowAlpha > 0) {
            val factor = target.width() / image.width
            val left = target.left + glowOffset[0] * factor
            val top = target.top + glowOffset[1] * factor
            glowPaint.alpha = glowAlpha
            glowTarget.set(left, top, left + glowBitmap.width * factor, top + glowBitmap.height * factor)
            canvas.drawBitmap(glowBitmap, null, glowTarget, glowPaint)
        }
        canvas.drawBitmap(image, null, target, imagePaint)
    }

    /** The image at the largest size of the beat, made again when the surface scale changes. */
    private fun picture(scale: Float): Bitmap? {
        if (picture != null && madeForScale == scale) return picture
        release()
        val drawable = spec.drawable ?: return null
        val largest = sizeFactor(1f)
        val width = ceil(spec.rect.width * scale * largest).toInt().coerceAtLeast(1)
        val height = ceil(spec.rect.height * scale * largest).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val pictureWidth = drawable.intrinsicWidth
        val pictureHeight = drawable.intrinsicHeight
        if (drawable is PictureDrawable && pictureWidth > 0 && pictureHeight > 0) {
            // An SVG from the zip: a PictureDrawable does not scale to its bounds, so scale the canvas
            canvas.scale(width.toFloat() / pictureWidth, height.toFloat() / pictureHeight)
            drawable.setBounds(0, 0, pictureWidth, pictureHeight)
        } else
            drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        picture = bitmap
        madeForScale = scale
        if (spec.glowRadius > 0f && spec.glowAlpha > 0)
            glow = runCatching {
                val blur = Paint().apply { maskFilter = BlurMaskFilter(spec.glowRadius * scale * largest, BlurMaskFilter.Blur.NORMAL) }
                bitmap.extractAlpha(blur, glowOffset)
            }.getOrNull()
        return bitmap
    }

    /** Frees the pictures; the next draw makes them again. */
    fun release() {
        picture?.recycle()
        glow?.recycle()
        picture = null
        glow = null
        madeForScale = 0f
    }
}
