// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.DATABASE_GAP_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.FAST_DELAY_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.FOLLOW_WINDOW_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.MAX_DELAY_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.MAX_RETRIES
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.REASON_CALCULATION
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.REASON_NEW_BG
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.RETRY_DELAY_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.SETTLE_QUIET_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Kind
import app.aaps.plugins.sync.wear.WearResendSchedule.Outcome
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
    private val givenUp = mutableListOf<List<String>>()

    /** Starts the schedule; [outcome] says what happens to the resend with this number (from 1). */
    private fun TestScope.start(outcome: (Int) -> Outcome = { Outcome.SENT }) {
        schedule = WearResendSchedule { testScheduler.currentTime }
        backgroundScope.launch {
            schedule.run({ reasons ->
                resends += Resend(testScheduler.currentTime, reasons)
                outcome(resends.size)
            }) { reasons -> givenUp += reasons }
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
        request(1_000, "TempTargetChange", Kind.DATABASE_CHANGE)
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
        request(30_000, "BolusChange", Kind.DATABASE_CHANGE)
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
        request(30_000, "TempTargetChange", Kind.DATABASE_CHANGE)
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
        start { number -> if (number > 1) Outcome.SENT else Outcome.NOT_READY }
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
        start { number -> if (number == 1) throw IllegalStateException("test") else Outcome.SENT }
        request(0, "CarbsChange", Kind.DATABASE_CHANGE)
        at(2_000 + RETRY_DELAY_MS)
        request(100_000, "TempTargetChange", Kind.DATABASE_CHANGE)
        at(110_000)
        assertThat(times()).containsExactly(2_000L, 2_000L + RETRY_DELAY_MS, 102_000L).inOrder()
        assertThat(resends[1].reasons).containsExactly("CarbsChange")
    }

    /** A resend that keeps failing does not wake the watch every 5 s for ever: two more tries, then quiet. */
    @Test
    fun `a resend that keeps failing is given up after two more tries`() = runTest {
        start { throw IllegalStateException("test") }
        request(0, "CarbsChange", Kind.DATABASE_CHANGE)
        at(10 * 60_000L)
        assertThat(times()).containsExactly(2_000L, 2_000L + RETRY_DELAY_MS, 2_000L + 2 * RETRY_DELAY_MS).inOrder()
        assertThat(resends).hasSize(1 + MAX_RETRIES)
        assertThat(givenUp).containsExactly(listOf("CarbsChange"))
    }

    /** After giving up, a new reason starts a resend again, with new tries. */
    @Test
    fun `a new reason after giving up gives a resend with new tries`() = runTest {
        // The first three fail and are given up, the fourth (the new reason) fails once, then it works
        start { number -> if (number <= 1 + MAX_RETRIES + 1) throw IllegalStateException("test") else Outcome.SENT }
        request(0, "CarbsChange", Kind.DATABASE_CHANGE)
        at(60_000)
        request(100_000, "TempTargetChange", Kind.DATABASE_CHANGE)
        at(200_000)
        assertThat(times()).containsExactly(2_000L, 7_000L, 12_000L, 102_000L, 102_000L + RETRY_DELAY_MS).inOrder()
        assertThat(resends.last().reasons).containsExactly("TempTargetChange")
        assertThat(givenUp).containsExactly(listOf("CarbsChange"))
    }

    /** Before the app is initialized nothing can fail: the resend waits for as long as that lasts. */
    @Test
    fun `a resend keeps waiting while the app is not initialized`() = runTest {
        start { number -> if (number <= 20) Outcome.NOT_READY else Outcome.SENT }
        newBg(0)
        at(200_000)
        assertThat(resends).hasSize(21)
        assertThat(resends.last()).isEqualTo(Resend(2_000 + 20 * RETRY_DELAY_MS, listOf(REASON_NEW_BG)))
        assertThat(givenUp).isEmpty()
    }

    /**
     * The schedule runs on the steady clock it is given. The wall clock, here only in the BG times, is set
     * back by an hour within 5 s of a failed resend: the try again and the next BG are not held.
     */
    @Test
    fun `a wall clock set back does not hold a resend`() = runTest {
        var wallOffset = 1_700_000_000_000L
        val wall = { testScheduler.currentTime + wallOffset }
        start { number -> if (number == 1) Outcome.FAILED else Outcome.SENT }
        at(1_000)
        schedule.calculationFinished(triggeredByNewBG = true, newestBgTime = wall())
        at(4_000)
        wallOffset -= 3_600_000L
        at(10_000)
        assertThat(times()).containsExactly(3_000L, 3_000L + RETRY_DELAY_MS).inOrder()
        // A new BG with a time an hour before the last one still goes out within 2 s
        at(20_000)
        schedule.calculationFinished(triggeredByNewBG = true, newestBgTime = wall())
        at(20_000 + FAST_DELAY_MS)
        assertThat(times()).containsExactly(3_000L, 3_000L + RETRY_DELAY_MS, 22_000L).inOrder()
        assertThat(resends.last().reasons).containsExactly(REASON_NEW_BG)
    }

    /**
     * A sync writes the nsId back to 60 rows, one every second. Each write is a database change, and the
     * calculator runs again after each one. Every 300 ms after a write the calculation ends.
     */
    private fun TestScope.nsIdStorm(from: Long, count: Int, newBgAt: Long? = null) {
        for (i in 0 until count) {
            val ms = from + i * 1_000L
            if (newBgAt != null && newBgAt in ms - 1_000 + 1..ms) newBg(newBgAt)
            request(ms, "nsId$i", Kind.DATABASE_CHANGE)
            at(ms + 300)
            schedule.calculationFinished(triggeredByNewBG = false, newestBgTime = 0)
        }
    }

    /** A storm of database changes gives one resend every 10 s, not one every 2 s. */
    @Test
    fun `a storm of nsId updates is merged into one resend every 10 s`() = runTest {
        start()
        newBg(0, bgTime = 0)
        nsIdStorm(from = 30_000, count = 60)
        at(120_000)
        // The first change of the storm goes out within 2 s, then one resend every 10 s
        assertThat(times()).containsExactly(2_000L, 32_000L, 42_000L, 52_000L, 62_000L, 72_000L, 82_000L, 92_000L).inOrder()
        // No change waits longer than the gap
        for (i in 0 until 60) {
            val resend = resends.first { "nsId$i" in it.reasons }
            assertThat(resend.at - (30_000 + i * 1_000L)).isAtMost(DATABASE_GAP_MS)
        }
        // The first user change after the storm goes out within 2 s again
        request(120_000, "BolusChange", Kind.DATABASE_CHANGE)
        at(130_000)
        assertThat(resends.last()).isEqualTo(Resend(122_000, listOf("BolusChange")))
    }

    /** A new BG is never held by the gap: it goes out within 2 s, and takes the changes that wait with it. */
    @Test
    fun `a new BG during a storm of nsId updates goes out within 2 s`() = runTest {
        start()
        newBg(0, bgTime = 0)
        nsIdStorm(from = 30_000, count = 60, newBgAt = 55_500)
        at(120_000)
        val bgResend = resends.last { REASON_NEW_BG in it.reasons }
        assertThat(bgResend.at).isEqualTo(55_500 + FAST_DELAY_MS)
        assertThat(bgResend.reasons).contains("nsId25")
        assertThat(resends.size).isAtMost(9)
    }

    /** h: the new COB after carbs, the new predictions and TBR after a bolus come seconds after the resend. */
    @Test
    fun `a calculation or loop run shortly after a fast resend is fast too`() = runTest {
        start()
        newBg(0)
        request(30_000, "CarbsChange", Kind.DATABASE_CHANGE)       // resent at 32 s
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
        request(0, "BolusChange", Kind.DATABASE_CHANGE)
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
