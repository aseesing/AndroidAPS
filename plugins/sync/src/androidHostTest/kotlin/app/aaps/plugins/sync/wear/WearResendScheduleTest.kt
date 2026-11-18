// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.FAST_DELAY_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.FOLLOW_WINDOW_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.MAX_DELAY_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.REASON_CALCULATION
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.REASON_NEW_BG
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.RETRY_DELAY_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.SETTLE_QUIET_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Kind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The rule of [WearResendSchedule], in virtual time. Times are in ms after the test start. A resend
 * that is due at a time runs before a request made at that same time in the test.
 */
class WearResendScheduleTest {

    /** One resend: when it started and its reasons. */
    private data class Resend(val at: Long, val reasons: List<String>)

    private lateinit var schedule: WearResendSchedule
    private val resends = mutableListOf<Resend>()

    /** Starts the schedule; [sent] says whether a resend goes out. */
    private fun TestScope.start(sent: (Int) -> Boolean = { true }) {
        schedule = WearResendSchedule { testScheduler.currentTime }
        backgroundScope.launch {
            schedule.run { reasons ->
                resends += Resend(testScheduler.currentTime, reasons)
                sent(resends.size)
            }
        }
        runCurrent()
    }

    /** Moves the virtual time to [ms], and runs what is due until then. */
    private fun TestScope.at(ms: Long) {
        val step = ms - testScheduler.currentTime
        if (step > 0) advanceTimeBy(step)
        runCurrent()
    }

    private fun TestScope.request(ms: Long, reason: String, kind: Kind) {
        at(ms)
        schedule.request(reason, kind)
    }

    private fun TestScope.newBg(ms: Long, bgTime: Long = ms) {
        at(ms)
        schedule.calculationFinished(triggeredByNewBG = true, newestBgTime = bgTime)
    }

    private fun times() = resends.map { it.at }

    @Test
    fun `a fast reason goes out 2 s after it arrived, not earlier`() = runTest {
        start()
        request(1_000, "TempTargetChange", Kind.USER_CHANGE)
        at(1_000 + FAST_DELAY_MS - 1)
        assertThat(resends).isEmpty()
        at(1_000 + FAST_DELAY_MS)
        assertThat(resends).containsExactly(Resend(3_000, listOf("TempTargetChange")))
    }

    /** The first BG after the start has no BG before it to compare with: it is new, also without the flag. */
    @Test
    fun `the first BG after the start goes out within 2 s`() = runTest {
        start()
        at(5_000)
        schedule.calculationFinished(triggeredByNewBG = false, newestBgTime = 4_000)
        at(7_000)
        assertThat(resends).containsExactly(Resend(7_000, listOf(REASON_NEW_BG)))
    }

    /** a: more events do not move the 2 s on; the first of them sets the time. */
    @Test
    fun `events less than 2 s apart do not hold back a fast reason`() = runTest {
        start()
        newBg(0)
        at(2_000)
        // The cycle settles: a storm of NS events, 500 ms apart, and a new BG in the middle of it
        for (ms in 10_000L..40_000L step 500) {
            request(ms, "EventNsClientStatusUpdated", Kind.OTHER)
            if (ms == 20_000L) schedule.calculationFinished(triggeredByNewBG = true, newestBgTime = ms)
        }
        assertThat(times()).containsAtLeast(2_000L, 22_000L).inOrder()
        assertThat(resends[1].reasons).contains(REASON_NEW_BG)
    }

    /** a, and the two limits: a storm of events one second apart for five minutes. */
    @Test
    fun `a storm of events for five minutes keeps both limits`() = runTest {
        start()
        val arrived = mutableMapOf<String, Pair<Long, Kind>>()
        for (second in 0L until 300L) {
            val kind = when {
                second % 60 == 30L -> Kind.USER_CHANGE
                second % 7 == 0L   -> Kind.CALCULATION_OR_LOOP
                else               -> Kind.OTHER
            }
            val reason = "event$second"
            arrived[reason] = second * 1_000 to kind
            request(second * 1_000, reason, kind)
        }
        at(300_000 + MAX_DELAY_MS + 1_000)

        for ((reason, value) in arrived) {
            val (at, kind) = value
            val resend = resends.first { reason in it.reasons }
            val limit = if (kind == Kind.USER_CHANGE) FAST_DELAY_MS else MAX_DELAY_MS
            assertThat(resend.at - at).isAtMost(limit)
        }
        // Still merged: far fewer resends than events
        assertThat(resends.size).isAtMost(30)
        // Every reason went out once
        assertThat(resends.sumOf { it.reasons.size }).isEqualTo(300)
    }

    /** b: a bolus, carbs or a profile switch in the settle window of a resend. */
    @Test
    fun `a user change while the cycle settles goes out within 2 s`() = runTest {
        start()
        newBg(0)
        request(10_000, "EventNsClientStatusUpdated", Kind.OTHER)
        request(30_000, "BolusChange", Kind.USER_CHANGE)
        at(40_000)
        assertThat(resends).containsExactly(
            Resend(2_000, listOf(REASON_NEW_BG)),
            Resend(32_000, listOf("EventNsClientStatusUpdated", "BolusChange"))
        ).inOrder()
    }

    /** c: a new BG 40 s after the last resend, as with a CGM that sends every 1 or 2 minutes. */
    @Test
    fun `a new BG while the cycle settles goes out within 2 s`() = runTest {
        start()
        newBg(0)
        request(20_000, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        newBg(40_000)
        at(45_000)
        assertThat(times()).containsExactly(2_000L, 42_000L).inOrder()
        assertThat(resends[1].reasons).containsExactly("EventLoopUpdateGui", REASON_NEW_BG)
    }

    /** d: the slow event after the temp target does not set the time. */
    @Test
    fun `a temp target followed at once by a loop run goes out after 2 s`() = runTest {
        start()
        newBg(0)
        request(30_000, "TempTargetChange", Kind.USER_CHANGE)
        request(30_500, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        request(31_000, "EventNsClientStatusUpdated", Kind.OTHER)
        at(40_000)
        assertThat(times()).containsExactly(2_000L, 32_000L).inOrder()
        assertThat(resends[1].reasons).containsExactly("TempTargetChange", "EventLoopUpdateGui", "EventNsClientStatusUpdated").inOrder()
    }

    /** e: in recalculated mode the newest bucket keeps its time; the flag of the calculation says it is new. */
    @Test
    fun `a calculation for a new BG is fast also when the newest bucket keeps its time`() = runTest {
        start()
        newBg(0, bgTime = 1_000)
        newBg(40_000, bgTime = 1_000)
        at(45_000)
        assertThat(times()).containsExactly(2_000L, 42_000L).inOrder()
    }

    /** e: a newer BG without the flag (a calculation that merged several requests) is new too. */
    @Test
    fun `a newer BG is fast also without the flag`() = runTest {
        start()
        newBg(0, bgTime = 1_000)
        at(40_000)
        schedule.calculationFinished(triggeredByNewBG = false, newestBgTime = 61_000)
        at(45_000)
        assertThat(resends.map { it.reasons }).containsExactly(listOf(REASON_NEW_BG), listOf(REASON_NEW_BG)).inOrder()
        assertThat(times()).containsExactly(2_000L, 42_000L).inOrder()
    }

    /** e: no flag, no newer BG: a recalculation for a TBR or a treatment, it waits while the cycle settles. */
    @Test
    fun `a calculation without a new BG waits while the cycle settles`() = runTest {
        start()
        newBg(0, bgTime = 1_000)
        at(40_000)
        schedule.calculationFinished(triggeredByNewBG = false, newestBgTime = 1_000)
        at(40_000 + SETTLE_QUIET_MS - 1)
        assertThat(times()).containsExactly(2_000L)
        at(40_000 + SETTLE_QUIET_MS)
        assertThat(times()).containsExactly(2_000L, 70_000L).inOrder()
        assertThat(resends[1].reasons).containsExactly(REASON_CALCULATION)
    }

    /** g: the app is not initialized yet, so the resend did not go out. Its reasons wait and go out later. */
    @Test
    fun `a resend that did not go out is tried again with its reasons`() = runTest {
        start { number -> number > 1 }
        newBg(0)
        at(2_000 + RETRY_DELAY_MS + 60_000)
        assertThat(resends).containsExactly(
            Resend(2_000, listOf(REASON_NEW_BG)),
            Resend(2_000 + RETRY_DELAY_MS, listOf(REASON_NEW_BG))
        ).inOrder()
    }

    /** g: an exception is a resend that did not go out, and the schedule keeps running. */
    @Test
    fun `a resend that threw is tried again`() = runTest {
        start { number -> if (number == 1) throw IllegalStateException("test") else true }
        request(0, "CarbsChange", Kind.USER_CHANGE)
        at(2_000 + RETRY_DELAY_MS)
        request(100_000, "TempTargetChange", Kind.USER_CHANGE)
        at(110_000)
        assertThat(times()).containsExactly(2_000L, 2_000L + RETRY_DELAY_MS, 102_000L).inOrder()
        assertThat(resends[1].reasons).containsExactly("CarbsChange")
    }

    /** h: the new COB after carbs, the new predictions and TBR after a bolus come seconds after the resend. */
    @Test
    fun `a calculation or loop run shortly after a fast resend is fast too`() = runTest {
        start()
        newBg(0)
        request(30_000, "CarbsChange", Kind.USER_CHANGE)           // resent at 32 s
        request(36_000, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        at(38_000)
        schedule.calculationFinished(triggeredByNewBG = false, newestBgTime = 0) // the new COB
        at(45_000)
        assertThat(times()).containsExactly(2_000L, 32_000L, 38_000L, 40_000L).inOrder()
    }

    /** h: only for a short time; later in the cycle the loop waits again. */
    @Test
    fun `a loop run after the follow window waits while the cycle settles`() = runTest {
        start()
        request(0, "BolusChange", Kind.USER_CHANGE)
        request(2_000 + FOLLOW_WINDOW_MS + 1, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        at(30_000)
        assertThat(times()).containsExactly(2_000L)
        at(2_000 + FOLLOW_WINDOW_MS + 1 + SETTLE_QUIET_MS)
        assertThat(times()).containsExactly(2_000L, 47_001L).inOrder()
    }

    /**
     * An SMB is the loop's own, it takes the slow path. Events every 10 s after it do not hold it back
     * for longer than the limit. (The settle window ends 60 s after the last resend; an event after that
     * is fast again, so the limit is reached only by a slow reason right after a resend.)
     */
    @Test
    fun `an SMB waits while the cycle settles but not longer than the limit`() = runTest {
        start()
        request(0, "EventNsClientStatusUpdated", Kind.OTHER)
        request(3_000, "SmbChange", Kind.OTHER)
        for (ms in 13_000L..123_000L step 10_000) request(ms, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        at(130_000)
        val smb = resends.first { "SmbChange" in it.reasons }
        assertThat(smb.at).isEqualTo(3_000 + MAX_DELAY_MS)
    }

    /** A CGM that sends every minute: every BG goes out within 2 s, and the rest of each minute is merged. */
    @Test
    fun `a CGM that sends every minute`() = runTest {
        start()
        for (minute in 0L until 10L) {
            val t = minute * 60_000
            newBg(t)
            request(t + 12_000, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
            request(t + 20_000, "EventNsClientStatusUpdated", Kind.OTHER)
        }
        at(10 * 60_000L + MAX_DELAY_MS)
        for (minute in 0L until 10L) assertThat(times()).contains(minute * 60_000 + FAST_DELAY_MS)
        assertThat(resends.size).isAtMost(30)
    }

    /**
     * The events of a BG cycle measured on a Pixel (seconds after 19:15:00). Upstream gives one resend
     * per burst: 5. Here: the new BG, the loop result that follows it, and the rest of the cycle.
     */
    @Test
    fun `a measured BG cycle gives three resends`() = runTest {
        start()
        val calculation = { schedule.calculationFinished(triggeredByNewBG = false, newestBgTime = 10_000) }
        newBg(10_000)
        at(11_000); calculation()
        request(24_000, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        request(29_000, "EventNsClientStatusUpdated", Kind.OTHER)
        request(49_000, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        request(50_000, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        at(50_200); calculation()
        at(51_000); calculation()
        request(51_300, "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        request(55_000, "EventNsClientStatusUpdated", Kind.OTHER)
        at(200_000)
        assertThat(times()).containsExactly(12_000L, 26_000L, 85_000L).inOrder()
    }
}
