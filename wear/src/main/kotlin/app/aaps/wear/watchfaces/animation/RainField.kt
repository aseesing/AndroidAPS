package app.aaps.wear.watchfaces.animation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * A field of falling rain streaks, the same every time for the same [RainSpec.seed].
 *
 * Every streak falls along the slant at its own speed and wraps to the top when it has left the
 * region at the bottom, so the rain never runs out. A streak is cut to the region and to the round
 * screen, so nothing is drawn where it cannot be seen. Positions are in the 400 x 400 design space.
 */
class RainField(private val spec: RainSpec) {

    private val count = spec.count
    private val region = spec.region
    private val sinA = sin(Math.toRadians(spec.angle.toDouble())).toFloat()
    private val cosA = cos(Math.toRadians(spec.angle.toDouble())).toFloat()
    private val tanA = tan(Math.toRadians(spec.angle.toDouble())).toFloat()

    private val startX = FloatArray(count)
    private val phase = FloatArray(count)
    private val speed = FloatArray(count)
    private val length = FloatArray(count)
    private val alpha = FloatArray(count)

    /** How far a streak head falls before it starts again at the top. */
    private val span: Float

    init {
        val random = Random(spec.seed)
        val longest = spec.length * (1 + spec.lengthVariation)
        span = region.height + longest * cosA + 1f
        val pad = longest * abs(sinA)
        val fromX = region.left - max(0f, region.height * tanA) - pad
        val toX = region.right + max(0f, -region.height * tanA) + pad
        for (i in 0 until count) {
            startX[i] = fromX + random.nextFloat() * (toX - fromX)
            phase[i] = random.nextFloat() * span
            speed[i] = spec.speed * (0.8f + 0.4f * random.nextFloat())
            length[i] = spec.length * (1 + spec.lengthVariation * (2 * random.nextFloat() - 1))
            alpha[i] = 0.45f + 0.55f * random.nextFloat()
        }
    }

    /**
     * Calls [action] for every streak that can be seen [elapsedMs] after the start, with its two end
     * points (tail, then head) in the design space and its own alpha factor (0..1).
     */
    inline fun forEachStreak(elapsedMs: Long, action: (x0: Float, y0: Float, x1: Float, y1: Float, alpha: Float) -> Unit) {
        val segment = FloatArray(4)
        for (i in 0 until streakCount) {
            if (streak(i, elapsedMs, segment)) action(segment[0], segment[1], segment[2], segment[3], streakAlpha(i))
        }
    }

    /** Number of streaks, seen or not. */
    val streakCount: Int get() = count

    fun streakAlpha(i: Int): Float = alpha[i]

    /**
     * Puts streak [i] at [elapsedMs] into [out] (tail x, tail y, head x, head y), already cut to the
     * region and the round screen. False when nothing of it can be seen.
     */
    fun streak(i: Int, elapsedMs: Long, out: FloatArray): Boolean {
        val fallen = (phase[i] + speed[i] * elapsedMs / 1000f) % span
        val headY = region.top + fallen
        val headX = startX[i] + fallen * tanA
        val tailX = headX - length[i] * sinA
        val tailY = headY - length[i] * cosA
        return clip(tailX, tailY, headX, headY, out)
    }

    /**
     * Draws the rain [elapsedMs] after the start onto [canvas], scaled by [scale] from the design
     * space to the surface, with every alpha multiplied by [fade] (0..1).
     */
    fun draw(canvas: Canvas, elapsedMs: Long, scale: Float, fade: Float) {
        if (fade <= 0f) return
        paint.strokeWidth = spec.thickness * scale
        val baseAlpha = Color.alpha(spec.color) / 255f * spec.alpha / 255f * fade.coerceAtMost(1f)
        forEachStreak(elapsedMs) { x0, y0, x1, y1, a ->
            paint.alpha = (255 * baseAlpha * a).toInt().coerceIn(0, 255)
            canvas.drawLine(x0 * scale, y0 * scale, x1 * scale, y1 * scale, paint)
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = spec.color
    }

    /** Cuts the line to the region and then to the round screen. */
    private fun clip(x0: Float, y0: Float, x1: Float, y1: Float, out: FloatArray): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        // Liang-Barsky against the region, one edge after the other (no arrays: this runs per streak per frame)
        clipFrom = 0f
        clipTo = 1f
        if (!edge(-dx, x0 - region.left) || !edge(dx, region.right - x0) || !edge(-dy, y0 - region.top) || !edge(dy, region.bottom - y0)) return false
        var from = clipFrom
        var to = clipTo
        // Against the circle of the round screen, a little inside so the round cap stays inside too
        val c = DESIGN_SIZE / 2
        val radius = c - spec.thickness
        val fx = x0 - c
        val fy = y0 - c
        val a = dx * dx + dy * dy
        if (a == 0f) return false
        val b = 2 * (fx * dx + fy * dy)
        val cc = fx * fx + fy * fy - radius * radius
        val disc = b * b - 4 * a * cc
        if (disc <= 0f) return false
        val root = sqrt(disc)
        from = max(from, (-b - root) / (2 * a))
        to = min(to, (-b + root) / (2 * a))
        if (from >= to) return false
        out[0] = x0 + dx * from
        out[1] = y0 + dy * from
        out[2] = x0 + dx * to
        out[3] = y0 + dy * to
        return true
    }

    private var clipFrom = 0f
    private var clipTo = 1f

    /** One edge of the Liang-Barsky clip: false when the line lies fully outside it. */
    private fun edge(p: Float, q: Float): Boolean {
        if (p == 0f) return q >= 0f
        val r = q / p
        if (p < 0f) clipFrom = max(clipFrom, r) else clipTo = min(clipTo, r)
        return true
    }
}
