// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The shape of one heart beat, as a value from 0 (rest) to 1 (top of the beat).
 *
 * Like a real pulse: a quick swell of about 120 ms, a slower fall, and a small second beat about
 * 250 ms after the first ("lub-dub"). At a fast heart rate the parts get shorter, so a beat always
 * fits in its period. Pure functions only.
 */
object PulseCurve {

    const val FALLBACK_BPM = 60
    const val MIN_BPM = 40
    const val MAX_BPM = 180

    fun clampBpm(bpm: Int): Int = bpm.coerceIn(MIN_BPM, MAX_BPM)

    /** The rate to beat at: the measured one when there is one, else [fallback]; always in 40..180. */
    fun bpmOrFallback(measured: Int?, fallback: Int = FALLBACK_BPM): Int = clampBpm(measured?.takeIf { it > 0 } ?: fallback)

    /** Time of one beat. */
    fun periodMs(bpm: Int): Long = 60_000L / clampBpm(bpm)

    /** How long the swell of a beat takes at [bpm]. */
    fun riseMs(bpm: Int): Float = min(MAX_RISE_MS, periodMs(bpm) * 0.3f)

    /**
     * The pulse [elapsedMs] after the first beat started, at [bpm], with a second beat of size
     * [secondBeat] (0..1).
     */
    fun value(elapsedMs: Long, bpm: Int, secondBeat: Float = 0.35f): Float {
        val period = periodMs(bpm).toFloat()
        val t = (elapsedMs.coerceAtLeast(0) % periodMs(bpm)).toFloat()
        val rise = riseMs(bpm)
        val decay = max(50f, period * 0.16f)
        val main = if (t < rise) sin(PI / 2 * t / rise).let { (it * it).toFloat() } else exp(-(t - rise) / decay)
        val secondAt = rise + min(250f, period * 0.3f)
        val secondWidth = max(25f, period * 0.06f)
        val d = (t - secondAt) / secondWidth
        val second = secondBeat * exp(-d * d)
        return (main + second).coerceIn(0f, 1f)
    }

    private const val MAX_RISE_MS = 120f
}
