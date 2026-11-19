package app.aaps.wear.watchfaces.daynight

import kotlin.math.floor

/**
 * Where the moon is in its cycle, from the date alone: no phone and no internet needed.
 *
 * Counts whole synodic months from a known new moon. The real cycle is a few hours longer or
 * shorter than the mean one, so the result can be off by up to about half a day. That is fine for
 * a picture of the moon.
 */
object MoonPhase {

    /** The mean time from one new moon to the next, in days. */
    const val SYNODIC_DAYS = 29.530588853

    /** A known new moon: 6 January 2000, 18:14 UTC. */
    private const val NEW_MOON_MS = 947_182_440_000L

    private const val DAY_MS = 86_400_000.0

    /**
     * The phase at [epochMs]: 0 is new moon, 0.25 first quarter, 0.5 full moon, 0.75 last
     * quarter, and back to 1 = 0. Always in 0 until 1.
     */
    fun at(epochMs: Long): Double {
        val cycles = (epochMs - NEW_MOON_MS) / DAY_MS / SYNODIC_DAYS
        val phase = cycles - floor(cycles)
        return if (phase >= 1.0) 0.0 else phase
    }
}
