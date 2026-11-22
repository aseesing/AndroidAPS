// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * `[MOON_PHASE_POSITION]` of the Watch Face Format: where the moon is in its month, from 0 at new
 * moon through 7 (first quarter), 14 (full moon) and 21 (last quarter) to 28. Between two of these
 * moments it grows evenly, so it is not a count of days: a quarter of the moon's month can last 6.5
 * or 8.5 days.
 *
 * The moments are the true ones, worked out as in "Astronomical Algorithms" by Jean Meeus, chapter 49
 * (good to a few minutes), with 69 seconds of Delta T. Checked against the WFF runtime on the Wear OS
 * 6 emulator at 12 moments from 2020 to 2027: the same to 0.0001, one moment 0.003 off.
 */
internal object MoonQuarters {

    private const val DAY_MS = 86_400_000.0
    private const val UNIX_EPOCH_JD = 2440587.5
    private const val DELTA_T_DAYS = 69.0 / 86_400.0

    /** The position at [epochMs], 0 until 28. */
    fun position(epochMs: Long): Double {
        val jd = epochMs / DAY_MS + UNIX_EPOCH_JD + DELTA_T_DAYS
        var k = floor((jd - 2451550.09766) / 29.530588861 * 4) / 4 - 0.5
        while (phaseJde(k + 0.25) <= jd) k += 0.25
        while (phaseJde(k) > jd) k -= 0.25
        val start = phaseJde(k)
        val end = phaseJde(k + 0.25)
        return 7.0 * (quarter(k) + (jd - start) / (end - start))
    }

    /** 0 new moon, 1 first quarter, 2 full moon, 3 last quarter, for the lunation number [k]. */
    private fun quarter(k: Double): Int = ((k - floor(k)) * 4).roundToInt() % 4

    /** One term: coefficient, power of E, and the multiples of M, M', F and Omega. */
    private class Term(val c: Double, val e: Int, val m: Int, val mp: Int, val f: Int, val om: Int)

    private val TAIL = listOf(
        Term(-0.00111, 0, 0, 1, -2, 0), Term(-0.00057, 0, 0, 1, 2, 0), Term(0.00056, 1, 1, 2, 0, 0), Term(-0.00042, 0, 0, 3, 0, 0),
        Term(0.00042, 1, 1, 0, 2, 0), Term(0.00038, 1, 1, 0, -2, 0), Term(-0.00024, 1, -1, 2, 0, 0), Term(-0.00017, 0, 0, 0, 0, 1),
        Term(-0.00007, 0, 2, 1, 0, 0), Term(0.00004, 0, 0, 2, -2, 0), Term(0.00004, 0, 3, 0, 0, 0), Term(0.00003, 0, 1, 1, -2, 0),
        Term(0.00003, 0, 0, 2, 2, 0), Term(-0.00003, 0, 1, 1, 2, 0), Term(0.00003, 0, -1, 1, 2, 0), Term(-0.00002, 0, -1, 1, -2, 0),
        Term(-0.00002, 0, 1, 3, 0, 0), Term(0.00002, 0, 0, 4, 0, 0)
    )

    private val NEW = listOf(
        Term(-0.40720, 0, 0, 1, 0, 0), Term(0.17241, 1, 1, 0, 0, 0), Term(0.01608, 0, 0, 2, 0, 0), Term(0.01039, 0, 0, 0, 2, 0),
        Term(0.00739, 1, -1, 1, 0, 0), Term(-0.00514, 1, 1, 1, 0, 0), Term(0.00208, 2, 2, 0, 0, 0)
    ) + TAIL

    private val FULL = listOf(
        Term(-0.40614, 0, 0, 1, 0, 0), Term(0.17302, 1, 1, 0, 0, 0), Term(0.01614, 0, 0, 2, 0, 0), Term(0.01043, 0, 0, 0, 2, 0),
        Term(0.00734, 1, -1, 1, 0, 0), Term(-0.00515, 1, 1, 1, 0, 0), Term(0.00209, 2, 2, 0, 0, 0)
    ) + TAIL

    private val QUARTER = listOf(
        Term(-0.62801, 0, 0, 1, 0, 0), Term(0.17172, 1, 1, 0, 0, 0), Term(-0.01183, 1, 1, 1, 0, 0), Term(0.00862, 0, 0, 2, 0, 0),
        Term(0.00804, 0, 0, 0, 2, 0), Term(0.00454, 1, -1, 1, 0, 0), Term(0.00204, 2, 2, 0, 0, 0), Term(-0.00180, 0, 0, 1, -2, 0),
        Term(-0.00070, 0, 0, 1, 2, 0), Term(-0.00040, 0, 0, 3, 0, 0), Term(-0.00034, 1, -1, 2, 0, 0), Term(0.00032, 1, 1, 0, 2, 0),
        Term(0.00032, 1, 1, 0, -2, 0), Term(-0.00028, 2, 2, 1, 0, 0), Term(0.00027, 1, 1, 2, 0, 0), Term(-0.00017, 0, 0, 0, 0, 1),
        Term(-0.00005, 0, -1, 1, -2, 0), Term(0.00004, 0, 0, 2, 2, 0), Term(-0.00004, 0, 1, 1, 2, 0), Term(0.00004, 0, -2, 1, 0, 0),
        Term(0.00003, 0, 1, 1, -2, 0), Term(0.00003, 0, 3, 0, 0, 0), Term(0.00002, 0, 0, 2, -2, 0), Term(0.00002, 0, -1, 1, 2, 0),
        Term(-0.00002, 0, 1, 3, 0, 0)
    )

    /** The planetary arguments: start, change per lunation, size of the correction. */
    private val PLANETS = listOf(
        doubleArrayOf(299.77, 0.107408, 0.000325), doubleArrayOf(251.88, 0.016321, 0.000165), doubleArrayOf(251.83, 26.651886, 0.000164),
        doubleArrayOf(349.42, 36.412478, 0.000126), doubleArrayOf(84.66, 18.206239, 0.000110), doubleArrayOf(141.74, 53.303771, 0.000062),
        doubleArrayOf(207.14, 2.453732, 0.000060), doubleArrayOf(154.84, 7.306860, 0.000056), doubleArrayOf(34.52, 27.261239, 0.000047),
        doubleArrayOf(207.19, 0.121824, 0.000042), doubleArrayOf(291.34, 1.844379, 0.000040), doubleArrayOf(161.72, 24.198154, 0.000037),
        doubleArrayOf(239.56, 25.513099, 0.000035), doubleArrayOf(331.55, 3.592518, 0.000023)
    )

    /** The Julian Ephemeris Day of the phase [k]: whole numbers are new moons, .25 first quarters, .5 full moons, .75 last quarters. */
    private fun phaseJde(k: Double): Double {
        val t = k / 1236.85
        val t2 = t * t
        var jde = 2451550.09766 + 29.530588861 * k + 0.00015437 * t2 - 0.000000150 * t2 * t + 0.00000000073 * t2 * t2
        val e = 1 - 0.002516 * t - 0.0000074 * t2
        val m = Math.toRadians(2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t2 * t)
        val mp = Math.toRadians(201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t2 * t - 0.000000058 * t2 * t2)
        val f = Math.toRadians(160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t2 * t + 0.000000011 * t2 * t2)
        val om = Math.toRadians(124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t2 * t)
        val q = quarter(k)
        val terms = when (q) {
            0    -> NEW
            2    -> FULL
            else -> QUARTER
        }
        for (term in terms) jde += term.c * e.pow(term.e) * sin(term.m * m + term.mp * mp + term.f * f + term.om * om)
        if (q == 1 || q == 3) {
            val w = 0.00306 - 0.00038 * e * cos(m) + 0.00026 * cos(mp) - 0.00002 * cos(mp - m) + 0.00002 * cos(mp + m) + 0.00002 * cos(2 * f)
            jde += if (q == 1) w else -w
        }
        PLANETS.forEachIndexed { i, p ->
            val angle = p[0] + p[1] * k - if (i == 0) 0.009173 * t2 else 0.0
            jde += p[2] * sin(Math.toRadians(angle))
        }
        return jde
    }
}
