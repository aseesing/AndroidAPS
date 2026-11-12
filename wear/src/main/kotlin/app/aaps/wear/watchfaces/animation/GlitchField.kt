// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Glitch bands, the same every time for the same [GlitchSpec.seed].
 *
 * The time is cut into periods of [GlitchSpec.periodMs]. Each period gets its own random numbers from
 * the seed and the period number, so any frame can be drawn without knowing the frames before it.
 * A period has a burst or not ([GlitchSpec.chance]); a burst shows a few bands at a random moment
 * of the period, and the bands jump sideways a little while it shows. Positions are in the 400 x 400
 * design space, and every band is cut to the region and the round screen.
 */
class GlitchField(private val spec: GlitchSpec) {

    private val region = spec.region

    /**
     * Calls [action] for every band that shows [elapsedMs] after the start, with its edges in the
     * design space and its colour (without the alpha of the spec).
     */
    fun forEachBand(elapsedMs: Long, action: (left: Float, top: Float, right: Float, bottom: Float, color: Int) -> Unit) {
        if (elapsedMs < 0) return
        val period = elapsedMs / spec.periodMs
        val inPeriod = elapsedMs % spec.periodMs
        val random = Random(spec.seed * 1_000_003L + period)
        if (random.nextFloat() >= spec.chance) return
        val start = if (spec.periodMs > spec.burstMs) random.nextLong(spec.periodMs - spec.burstMs) else 0L
        if (inPeriod < start || inPeriod >= start + spec.burstMs) return
        // The bands jump to a new place every JUMP_MS of the burst
        val step = ((inPeriod - start) / JUMP_MS).toInt()
        val center = DESIGN_SIZE / 2
        for (i in 0 until spec.bands) {
            val height = spec.minHeight + random.nextFloat() * (spec.maxHeight - spec.minHeight)
            val width = spec.minWidth + random.nextFloat() * (spec.maxWidth - spec.minWidth)
            val top = region.top + random.nextFloat() * max(0f, region.height - height)
            val left0 = region.left - width / 2 + random.nextFloat() * region.width
            val shift = ((step + i) % 3 - 1) * spec.jump
            val bottom = top + height
            // The round screen at the band's farthest height from the centre
            val farthest = max(abs(top - center), abs(bottom - center))
            if (farthest >= center) continue
            val halfChord = sqrt(center * center - farthest * farthest)
            val left = max(max(left0 + shift, region.left), center - halfChord)
            val right = min(min(left0 + shift + width, region.right), center + halfChord)
            if (right <= left) continue
            action(left, top, right, bottom, if (i % 2 == 0) spec.color1 else spec.color2)
        }
    }

    /**
     * Draws the bands [elapsedMs] after the start onto [canvas], scaled by [scale] from the design
     * space to the surface, with every alpha multiplied by [fade] (0..1).
     */
    fun draw(canvas: Canvas, elapsedMs: Long, scale: Float, fade: Float) {
        if (fade <= 0f) return
        forEachBand(elapsedMs) { left, top, right, bottom, color ->
            paint.color = color
            paint.alpha = (Color.alpha(color) * spec.alpha / 255f * fade.coerceAtMost(1f)).toInt().coerceIn(0, 255)
            canvas.drawRect(left * scale, top * scale, right * scale, bottom * scale, paint)
        }
    }

    private val paint = Paint().apply { style = Paint.Style.FILL }

    companion object {

        /** How often the bands of a burst jump to a new place. */
        const val JUMP_MS = 40L
    }
}
