package app.aaps.wear.watchfaces.animation

/**
 * The heart rate of the variable `bpm`: the measured one, cut to 40..180, or a fallback when the watch
 * has measured none. The shape of a beat is a formula of the design (the `pulse` template of the
 * manual has one).
 */
object PulseCurve {

    const val FALLBACK_BPM = 60
    const val MIN_BPM = 40
    const val MAX_BPM = 180

    fun clampBpm(bpm: Int): Int = bpm.coerceIn(MIN_BPM, MAX_BPM)

    /** The rate to beat at: the measured one when there is one, else [fallback]; always in 40..180. */
    fun bpmOrFallback(measured: Int?, fallback: Int = FALLBACK_BPM): Int = clampBpm(measured?.takeIf { it > 0 } ?: fallback)
}
