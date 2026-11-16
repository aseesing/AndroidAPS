// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Hands that swing when the wrist is raised, like the needles of a car when the engine starts: from
 * [emptyAngle] up to [fullAngle], then back to the value they show, with a small bounce.
 *
 * The angles are the rotations of the views, in degrees, as the design turns them (rotation plus the
 * `rotationOffset` of `dynData`). The value comes from the view itself, so the hand always ends where
 * the face shows it.
 *
 * @param views the keys of the views that swing together, for example `patient_name`
 * @param delayMs the hands wait at [emptyAngle] for this long after the wake starts
 * @param upMs the time from [emptyAngle] to [fullAngle]
 * @param settleMs the time from [fullAngle] back to the value, bounce included
 */
data class SweepSpec(
    val views: List<String>,
    val emptyAngle: Float,
    val fullAngle: Float,
    val delayMs: Long = 0,
    val upMs: Long = 600,
    val settleMs: Long = 900
) {

    fun normalized(): SweepSpec = copy(
        views = views.filter { it.isNotEmpty() }.distinct(),
        delayMs = delayMs.coerceIn(0, MAX_DELAY_MS),
        upMs = upMs.coerceIn(MIN_PART_MS, MAX_PART_MS),
        settleMs = settleMs.coerceIn(MIN_PART_MS, MAX_PART_MS)
    )

    /** The angle of a hand that shows [valueAngle], [elapsedMs] after the start of a wake. */
    fun angleAt(elapsedMs: Long, valueAngle: Float): Float {
        val t = elapsedMs - delayMs
        return when {
            t <= 0               -> emptyAngle
            t < upMs             -> emptyAngle + (fullAngle - emptyAngle) * easeInOut(t.toFloat() / upMs)
            t < upMs + settleMs  -> fullAngle + (valueAngle - fullAngle) * settle((t - upMs).toFloat() / settleMs)
            else                 -> valueAngle
        }
    }

    companion object {

        const val MAX_DELAY_MS = 5000L
        const val MIN_PART_MS = 50L
        const val MAX_PART_MS = 5000L

        /** Slow at the start and at the end, like a needle with some weight. */
        fun easeInOut(u: Float): Float = (0.5 - 0.5 * cos(PI * u.coerceIn(0f, 1f))).toFloat()

        /**
         * From 0 to 1 like a damped spring: past 1 by about a fifth, a smaller swing back, and exactly 1 at
         * the end, so the hand stops on its value without a jump.
         */
        fun settle(u: Float): Float {
            val x = u.coerceIn(0f, 1f).toDouble()
            val spring = 1 - exp(-5 * x) * cos(3 * PI * x)
            // The spring is not quite 1 at the end; pull the rest in over the last part
            val rest = (1 - exp(-5.0) * cos(3 * PI)) - 1
            return (spring - rest * x).toFloat()
        }
    }
}

/**
 * Views that slide sideways when the wrist is raised, like the hands of [SweepSpec]: from where the design
 * puts them to [fullDx] design pixels to the right, then back to where their value puts them, with a
 * small bounce. For a mark that moves along a scale, for example the mark over this month.
 *
 * @param views the keys of the views that slide together, for example `month`
 * @param fullDx how far right of the design place the end of the scale is, in design pixels
 * @param delayMs the views wait at the design place for this long after the wake starts
 * @param upMs the time from the design place to [fullDx]
 * @param settleMs the time from [fullDx] back to the value, bounce included
 */
data class SlideSpec(
    val views: List<String>,
    val fullDx: Float,
    val delayMs: Long = 0,
    val upMs: Long = 600,
    val settleMs: Long = 900
) {

    private val motion get() = SweepSpec(views, 0f, fullDx, delayMs, upMs, settleMs)

    fun normalized(): SlideSpec = motion.normalized().let { copy(views = it.views, delayMs = it.delayMs, upMs = it.upMs, settleMs = it.settleMs) }

    /** How far right of the design place a view is [elapsedMs] after the start of a wake, when its value puts it at [valueDx]. */
    fun dxAt(elapsedMs: Long, valueDx: Float): Float = motion.angleAt(elapsedMs, valueDx)
}

/**
 * Hands that shake when the wrist is raised, like the needle of a meter that is knocked, and settle on
 * the value they show: a swing of [amplitude] degrees at [hz], smaller by a factor e every [decayMs].
 *
 * @param views the keys of the views that shake together, for example `avg_delta`
 * @param delayMs the shaking starts this long after the wake starts
 */
data class WobbleSpec(
    val views: List<String>,
    val delayMs: Long = 0,
    val amplitude: Float = 16f,
    val hz: Float = 2.5f,
    val decayMs: Long = 350
) {

    fun normalized(): WobbleSpec = copy(
        views = views.filter { it.isNotEmpty() }.distinct(),
        delayMs = delayMs.coerceIn(0, SweepSpec.MAX_DELAY_MS),
        amplitude = amplitude.coerceIn(-MAX_AMPLITUDE, MAX_AMPLITUDE),
        hz = hz.coerceIn(0.2f, MAX_HZ),
        decayMs = decayMs.coerceIn(SweepSpec.MIN_PART_MS, SweepSpec.MAX_PART_MS)
    )

    /** How many degrees the hands are off their value [elapsedMs] after the start of a wake. 0 once it has died out. */
    fun offsetAt(elapsedMs: Long): Float {
        val t = elapsedMs - delayMs
        if (t <= 0 || t > decayMs * DECAYS) return 0f
        val seconds = t / 1000.0
        return (amplitude * exp(-t.toDouble() / decayMs) * sin(2 * PI * hz * seconds)).toFloat()
    }

    companion object {

        const val MAX_AMPLITUDE = 90f
        const val MAX_HZ = 10f

        /** After this many [decayMs] the swing is less than 1 % and stops. */
        const val DECAYS = 5
    }
}

/**
 * A view that draws itself in from left to right when the wrist is raised, like the trace of an
 * oscilloscope, with a soft bright line where it is being drawn.
 *
 * @param view the key of the view, for example `chart`
 * @param rect the part of the face that is drawn in; the view is cut at its left edge plus the part done
 * @param delayMs nothing of the view shows for this long after the wake starts
 * @param durationMs the time from the left edge to the right edge
 * @param color the colour of the bright line; its own alpha is multiplied with [alpha]
 * @param width the width of the bright line, in design pixels
 */
data class TraceSpec(
    val view: String = "chart",
    val rect: DesignRect,
    val delayMs: Long = 0,
    val durationMs: Long = 1000,
    val color: Int = Color.WHITE,
    val alpha: Int = 140,
    val width: Float = 8f
) {

    fun normalized(): TraceSpec = copy(
        delayMs = delayMs.coerceIn(0, SweepSpec.MAX_DELAY_MS),
        durationMs = durationMs.coerceIn(SweepSpec.MIN_PART_MS, SweepSpec.MAX_PART_MS),
        alpha = alpha.coerceIn(0, 255),
        width = width.coerceIn(0f, 40f)
    )

    /** How much of the view is drawn in [elapsedMs] after the start of a wake, 0 to 1. */
    fun progressAt(elapsedMs: Long): Float = ((elapsedMs - delayMs).toFloat() / durationMs).coerceIn(0f, 1f)
}

/** A ring in the 400 x 400 design space: the room between two circles round ([cx], [cy]). */
data class DesignRing(val cx: Float, val cy: Float, val inner: Float, val outer: Float)

/**
 * A light that slides once over the shiny parts of the face when the wrist is raised, as if the watch
 * was turned in the light: a soft band at [angle] degrees that crosses the whole face, seen only on
 * the [rings] and the [boxes].
 *
 * @param boxes rectangles with corners rounded by [cornerRadius], for example the glass of a window
 * @param delayMs the light starts this long after the wake starts
 * @param durationMs the time the band takes to cross the face
 * @param angle the slant of the band, in degrees from straight up; it moves to the right
 * @param width the width of the band, in design pixels
 * @param color the colour of the light; its own alpha is multiplied with [alpha]
 */
data class GlintSpec(
    val rings: List<DesignRing> = emptyList(),
    val boxes: List<DesignRect> = emptyList(),
    val cornerRadius: Float = 0f,
    val delayMs: Long = 0,
    val durationMs: Long = 900,
    val angle: Float = 30f,
    val width: Float = 60f,
    val color: Int = Color.WHITE,
    val alpha: Int = 90
) {

    fun normalized(): GlintSpec = copy(
        cornerRadius = cornerRadius.coerceIn(0f, DESIGN_SIZE / 2),
        delayMs = delayMs.coerceIn(0, SweepSpec.MAX_DELAY_MS),
        durationMs = durationMs.coerceIn(SweepSpec.MIN_PART_MS, SweepSpec.MAX_PART_MS),
        width = width.coerceIn(1f, DESIGN_SIZE),
        alpha = alpha.coerceIn(0, 255)
    )

    /** True when there is something to light. */
    val hasShape: Boolean get() = rings.isNotEmpty() || boxes.isNotEmpty()

    /**
     * Where the middle of the band is [elapsedMs] after the start of a wake, from 0 (before the face) to
     * 1 (past it). Null before it starts and after it has crossed.
     */
    fun progressAt(elapsedMs: Long): Float? {
        val t = elapsedMs - delayMs
        if (t < 0 || t > durationMs) return null
        return SweepSpec.easeInOut(t.toFloat() / durationMs)
    }
}
