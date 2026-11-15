// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import kotlin.math.sqrt

/** The colour [color] with its own alpha multiplied by [alpha] (0..255). */
private fun withAlpha(color: Int, alpha: Float): Int =
    Color.argb((Color.alpha(color) * alpha / 255f).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

/** The bright line of a [TraceSpec], where the view is being drawn in. */
class TraceBeam(private val spec: TraceSpec) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaderFor = 0f

    /** The x on the screen up to where the view shows, at [progress] (0..1) and [scale] (screen pixels per design pixel). */
    fun edge(progress: Float, scale: Float): Float = (spec.rect.left + spec.rect.width * progress) * scale

    /** Draws the line at [progress]; it fades away over the last part, so the view ends without it. */
    fun draw(canvas: Canvas, progress: Float, scale: Float) {
        if (progress <= 0f || progress >= 1f || spec.width <= 0f || spec.alpha == 0) return
        val half = spec.width * scale / 2
        if (shaderFor != scale) {
            val colour = withAlpha(spec.color, spec.alpha.toFloat())
            paint.shader = LinearGradient(-half, 0f, half, 0f, intArrayOf(Color.TRANSPARENT, colour, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            shaderFor = scale
        }
        paint.alpha = (255 * ((1f - progress) * FADE_PARTS).coerceAtMost(1f)).toInt()
        canvas.withTranslation(edge(progress, scale), 0f) {
            drawRect(-half, spec.rect.top * scale, half, spec.rect.bottom * scale, paint)
        }
    }

    private companion object {

        /** The line fades over the last 1 / FADE_PARTS of the way. */
        const val FADE_PARTS = 8f
    }
}

/** The light of a [GlintSpec]: a soft band, seen only on the rings and boxes. */
class GlintBand(private val spec: GlintSpec) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var madeFor = 0f
    private var shape = Path()

    /** The rings and boxes at [scale], as one shape. */
    internal fun shape(scale: Float): Path {
        if (madeFor != scale) {
            val all = Path()
            for (ring in spec.rings) {
                val outer = Path().apply { addCircle(ring.cx * scale, ring.cy * scale, ring.outer * scale, Path.Direction.CW) }
                val inner = Path().apply { addCircle(ring.cx * scale, ring.cy * scale, ring.inner * scale, Path.Direction.CW) }
                outer.op(inner, Path.Op.DIFFERENCE)
                all.op(outer, Path.Op.UNION)
            }
            for (box in spec.boxes) {
                val r = spec.cornerRadius * scale
                val path = Path().apply { addRoundRect(box.left * scale, box.top * scale, box.right * scale, box.bottom * scale, r, r, Path.Direction.CW) }
                all.op(path, Path.Op.UNION)
            }
            shape = all
            val half = spec.width * scale / 2
            val colour = withAlpha(spec.color, spec.alpha.toFloat())
            paint.shader = LinearGradient(-half, 0f, half, 0f, intArrayOf(Color.TRANSPARENT, colour, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            madeFor = scale
        }
        return shape
    }

    /** Draws the band at [progress] (0..1, see [GlintSpec.progressAt]) at [scale]. */
    fun draw(canvas: Canvas, progress: Float, scale: Float) {
        if (!spec.hasShape || spec.alpha == 0) return
        val path = shape(scale)
        val centre = DESIGN_SIZE / 2 * scale
        // Turned by the angle, the face spans its diagonal; the band starts and ends just outside it
        val diagonal = DESIGN_SIZE * sqrt(2f) * scale
        val half = spec.width * scale / 2
        val x = centre - diagonal / 2 - half + (diagonal + 2 * half) * progress
        canvas.withClip(path) {
            withRotation(spec.angle, centre, centre) {
                withTranslation(x, 0f) { drawRect(-half, centre - diagonal, half, centre + diagonal, paint) }
            }
        }
    }
}
