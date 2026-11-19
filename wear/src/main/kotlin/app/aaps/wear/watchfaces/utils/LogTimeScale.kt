package app.aaps.wear.watchfaces.utils

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign

/**
 * The time scale of the glucose graph in log mode (see [LogTimeGraph]): the last minutes are wide and
 * detailed, and the further back a time is, the more it is squeezed, so one graph shows a whole day.
 *
 * A time `t` is at x = ln(1 + |t - now| / [DETAIL_MS]) * [UNIT], negative in the past. With a
 * [DETAIL_MS] of 30 minutes the last half hour takes 18 % of the past part, the last hour 28 %, 3 hours
 * half, 6 hours two thirds and 12 hours 82 %. Predictions use the same scale to the right of now, for
 * at most [FUTURE_MS].
 *
 * @param time the time the graph is made. The x 0 ([now]) is its whole minute, like the plain scale of the
 * graph, so the graph does not change between two minutes.
 */
class LogTimeScale(time: Long) {

    /** The time of the x 0: [time] rounded down to the minute. */
    val now: Long = time - time.mod(MINUTE_MS)

    /** The oldest time the graph shows. */
    val start: Long get() = now - PAST_MS

    /**
     * The newest time the graph shows: up to [predictionsUntil], the last prediction (null without
     * predictions), but at least [MIN_FUTURE_MS] and at most [FUTURE_MS] after now.
     */
    fun end(predictionsUntil: Long?): Long = (predictionsUntil ?: now).coerceIn(now + MIN_FUTURE_MS, now + FUTURE_MS)

    /** The x of [time]. */
    fun x(time: Long): Float = xOfOffset(time - now)

    /** True when the graph shows [time]. */
    fun shows(time: Long): Boolean = time in start..now + FUTURE_MS

    companion object {

        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS

        /** How far back the graph goes. */
        const val PAST_MS = 24 * HOUR_MS

        /** How far ahead predictions go at most. */
        const val FUTURE_MS = HOUR_MS

        /** A small room right of now, also without predictions, so the newest point is not on the edge. */
        const val MIN_FUTURE_MS = 5 * MINUTE_MS

        /** The time around now that is shown nearly as wide as a plain scale: the smaller, the stronger the squeeze. */
        const val DETAIL_MS = 30 * MINUTE_MS

        /** Near now one x unit is about one minute. */
        const val UNIT = DETAIL_MS / MINUTE_MS.toFloat()

        /** The ages with a line and a label on the time axis. */
        val LABEL_AGES_MS = listOf(HOUR_MS, 3 * HOUR_MS, 6 * HOUR_MS, 12 * HOUR_MS)

        /** The x of a time [offsetMs] from now (negative in the past). */
        fun xOfOffset(offsetMs: Long): Float = (sign(offsetMs.toDouble()) * ln(1 + abs(offsetMs) / DETAIL_MS.toDouble()) * UNIT).toFloat()
    }
}
