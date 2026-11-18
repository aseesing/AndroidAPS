// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.core.interfaces.source

import kotlin.math.abs

/**
 * The session of the sensor in use as its glucose source reported it, ready for a countdown. The
 * status lights on the phone and the sensor hand of the D2 watch face both count with it, so they
 * show the same end.
 *
 * The countdown starts at [startMs]. The sensor works [lifetimeHours], then [graceHours] more until
 * it stops by itself at [endMs]. Whole hours, rounded down, so the end is never shown late. The
 * Dexcom ONE+ / G7 reports whole hours anyway (252 h or 372 h, with 12 h of grace).
 */
class CgmReportedSession(val startMs: Long, val lifetimeHours: Int, val graceHours: Int) {

    /** When the sensor stops, grace period included. */
    val endMs: Long get() = startMs + (lifetimeHours + graceHours) * HOUR_MS

    companion object {

        private const val HOUR_MS = 60L * 60L * 1000L

        /**
         * How far the sensor change therapy event may be from the start the source works with and
         * still be the same sensor. The Dexcom driver takes the start the sensor reports only when
         * it is more than 15 minutes from its own, so the two can be up to 15 minutes apart.
         */
        const val SAME_SENSOR_TOLERANCE_MS = HOUR_MS

        /**
         * The session that [source] reports for its sensor, or null when it reports none. Only a
         * source that implements [CgmSensorStatusProvider] can report one.
         *
         * @param source the active glucose source; null reports nothing.
         * @param sensorChangeMs the time of the newest sensor change therapy event, or null.
         */
        fun of(source: BgSource?, sensorChangeMs: Long?): CgmReportedSession? =
            of((source as? CgmSensorStatusProvider)?.lifecycle?.value, sensorChangeMs)

        /**
         * The countdown for [lifecycle], or null when it cannot be trusted. Then the caller does
         * what it did without this: its own table, the life the user set, or the age.
         *
         * Null when:
         * - the sensor did not report its session length (nothing is assumed here),
         * - the source knows no start,
         * - a sensor change was logged more than [SAME_SENSOR_TOLERANCE_MS] after the start of the
         *   source's sensor: that is a newer sensor the source does not know about yet,
         * - the start is not confirmed and the sensor change is not at the same time, so it is not
         *   sure that the event and the length are about the same sensor.
         *
         * The countdown starts at the confirmed start when there is one ([CgmSensorLifecycle.confirmedStartEpochMs]),
         * else at the sensor change, as without this.
         *
         * @param sensorChangeMs the time of the newest sensor change therapy event, or null.
         */
        fun of(lifecycle: CgmSensorLifecycle?, sensorChangeMs: Long?): CgmReportedSession? {
            lifecycle ?: return null
            val lengthMs = lifecycle.sessionLengthMs?.takeIf { it > 0L } ?: return null
            val graceMs = (lifecycle.gracePeriodMs ?: 0L).takeIf { it in 0L until lengthMs } ?: return null
            val confirmedStart = lifecycle.confirmedStartEpochMs?.takeIf { it > 0L }
            val sourceStart = confirmedStart ?: lifecycle.startedAtEpochMs?.takeIf { it > 0L } ?: return null
            if (sensorChangeMs != null && sensorChangeMs > sourceStart + SAME_SENSOR_TOLERANCE_MS) return null
            val startMs = confirmedStart
                ?: sensorChangeMs?.takeIf { abs(it - sourceStart) <= SAME_SENSOR_TOLERANCE_MS }
                ?: return null
            val totalHours = (lengthMs / HOUR_MS).toInt()
            if (totalHours < 1) return null
            val graceHours = (graceMs / HOUR_MS).toInt().coerceAtMost(totalHours - 1)
            return CgmReportedSession(startMs = startMs, lifetimeHours = totalHours - graceHours, graceHours = graceHours)
        }
    }
}
