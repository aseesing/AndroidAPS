// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.core.interfaces.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When the session a glucose source reports is used for a countdown, and from which start. */
class CgmReportedSessionTest {

    private val hour = 3_600_000L
    private val start = 1_000_000_000_000L

    /** A Dexcom ONE+ / G7 that reported 10.5 days, 12 hours of it grace. */
    private fun lifecycle(
        startedAt: Long? = start,
        lengthMs: Long? = 252 * hour,
        graceMs: Long? = 12 * hour,
        confirmedStart: Long? = null,
    ) = CgmSensorLifecycle(
        startedAtEpochMs = startedAt,
        ageMs = 0L,
        endOfLife = false,
        sessionLengthMs = lengthMs,
        gracePeriodMs = graceMs,
        confirmedStartEpochMs = confirmedStart,
    )

    @Test
    fun aReportedSessionSplitsIntoLifeAndGrace() {
        val session = CgmReportedSession.of(lifecycle(), sensorChangeMs = start)!!
        assertEquals(240, session.lifetimeHours)
        assertEquals(12, session.graceHours)
        assertEquals(start + 252 * hour, session.endMs)
    }

    @Test
    fun aFifteenDaySensorEndsAfterFifteenAndAHalfDays() {
        val session = CgmReportedSession.of(lifecycle(lengthMs = 372 * hour), sensorChangeMs = start)!!
        assertEquals(360, session.lifetimeHours)
        assertEquals(start + 372 * hour, session.endMs)
    }

    @Test
    fun withoutAReportedLengthThereIsNothing() {
        assertNull(CgmReportedSession.of(lifecycle(lengthMs = null, graceMs = null), sensorChangeMs = start))
        assertNull(CgmReportedSession.of(null as CgmSensorLifecycle?, sensorChangeMs = start))
        assertNull(CgmReportedSession.of(null as BgSource?, sensorChangeMs = start))
    }

    @Test
    fun aGraceAsLongAsTheSessionIsRefused() {
        assertNull(CgmReportedSession.of(lifecycle(graceMs = 252 * hour), sensorChangeMs = start))
    }

    @Test
    fun aConfirmedStartIsCountedFromEvenWithoutAnEvent() {
        val real = start - 3 * hour
        val session = CgmReportedSession.of(lifecycle(startedAt = real, confirmedStart = real), sensorChangeMs = null)!!
        assertEquals(real, session.startMs)
    }

    @Test
    fun aConfirmedStartWinsOverAnEventOfThePreviousSensor() {
        val session = CgmReportedSession.of(lifecycle(confirmedStart = start), sensorChangeMs = start - 240 * hour)!!
        assertEquals(start, session.startMs)
    }

    @Test
    fun anUnconfirmedStartCountsFromTheEventAtTheSameTime() {
        val event = start + 10 * 60_000L
        assertEquals(event, CgmReportedSession.of(lifecycle(), sensorChangeMs = event)!!.startMs)
    }

    @Test
    fun anUnconfirmedStartWithAnOldOrNoEventIsNotUsed() {
        assertNull(CgmReportedSession.of(lifecycle(), sensorChangeMs = start - 240 * hour))
        assertNull(CgmReportedSession.of(lifecycle(), sensorChangeMs = null))
    }

    @Test
    fun aSensorChangeAfterTheSourcesSensorIsANewerSensor() {
        val later = start + 2 * hour
        assertNull(CgmReportedSession.of(lifecycle(), sensorChangeMs = later))
        assertNull(CgmReportedSession.of(lifecycle(confirmedStart = start), sensorChangeMs = later))
    }

    @Test
    fun theLengthIsRoundedDownToWholeHours() {
        val session = CgmReportedSession.of(lifecycle(lengthMs = 252 * hour + 30 * 60_000L), sensorChangeMs = start)!!
        assertEquals(start + 252 * hour, session.endMs)
    }
}
