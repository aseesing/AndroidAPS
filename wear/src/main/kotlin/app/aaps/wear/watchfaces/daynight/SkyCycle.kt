// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import kotlin.math.abs

/**
 * Where the sun or the moon is in its round from rising to rising, as one number from 0 to 1:
 *
 * - 0 when it rises, 0.25 halfway its time in the sky, 0.5 when it sets,
 * - 0.75 halfway its time below the horizon, and 1 when it rises again.
 *
 * So a design can move a picture once around a circle: the half above the horizon by day (for the sun)
 * and the half below it when the body is down. The time up and the time down each get one half of the
 * circle, however long they are.
 *
 * Pure functions only: no clock, no state.
 */
object SkyCycle {

    /** The value when the body does not set within [SEARCH_MS]: high in the sky. */
    const val ALWAYS_UP = 0.25

    /** The value when the body does not rise within [SEARCH_MS]: deep below the horizon. */
    const val ALWAYS_DOWN = 0.75

    /** How far before and after the moment to look for the last and the next rising or setting. */
    const val SEARCH_MS = 2 * 24 * 60 * 60_000L

    /** The steps of the search. A body is never up or down for a shorter time than this (moon and sun: hours). */
    private const val STEP_MS = 10 * 60_000L

    /** How exact a rising or setting is found: half a minute. */
    private const val EXACT_MS = 30_000L

    /**
     * The place of a body in its round at [epochMs]. [height] says how high the body is above the
     * height where it rises and sets, at a moment: above 0 it is up.
     */
    fun at(epochMs: Long, height: (Long) -> Double): Double {
        val up = height(epochMs) > 0
        val last = crossing(epochMs, -STEP_MS, up, height)
        val next = crossing(epochMs, STEP_MS, up, height)
        if (last == null || next == null) return if (up) ALWAYS_UP else ALWAYS_DOWN
        val part = ((epochMs - last).toDouble() / (next - last)).coerceIn(0.0, 1.0)
        return if (up) part / 2 else 0.5 + part / 2
    }

    /**
     * The first moment from [epochMs] in the direction of [step] where the body is no longer [up],
     * or null when it stays so within [SEARCH_MS]. Found in steps, then made exact by halving.
     */
    private fun crossing(epochMs: Long, step: Long, up: Boolean, height: (Long) -> Double): Long? {
        var inside = epochMs
        var t = epochMs + step
        while (abs(t - epochMs) <= SEARCH_MS) {
            if (height(t) > 0 != up) {
                var outside = t
                while (abs(outside - inside) > EXACT_MS) {
                    val middle = (inside + outside) / 2
                    if (height(middle) > 0 == up) inside = middle else outside = middle
                }
                return (inside + outside) / 2
            }
            inside = t
            t += step
        }
        return null
    }
}
