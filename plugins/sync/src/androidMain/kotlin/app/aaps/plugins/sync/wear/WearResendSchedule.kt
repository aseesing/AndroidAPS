// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides when the watch gets the next resend, and runs the resends at that time.
 *
 * Every resend builds the whole graph and all treatments and sends about ten messages, and every
 * message wakes the watch. After a BG the calculation, the loop and NS each send their event within
 * seconds, often twice. Upstream waits 2 s of quiet after each of them: about 5 resends per BG cycle.
 * Here the less urgent ones of a cycle are merged into one resend. Which messages of a resend really go
 * out is decided later, by [app.aaps.plugins.sync.wear.wearintegration.UnchangedToWearFilter]: this
 * class decides only WHEN a resend happens.
 *
 * Two hard limits, measured from the moment a reason arrives:
 * - [FAST_DELAY_MS] (2 s): a fast reason goes out at most 2 s after it arrived. The 2 s do not start
 *   again when more events come, so a storm of events cannot hold it back. Fast reasons are:
 *   - a new BG, see [calculationFinished];
 *   - a change the user made ([Kind.USER_CHANGE]: a bolus, carbs, a profile switch, a temp target, the
 *     running mode, the eat timer);
 *   - a calculation or a loop run up to [FOLLOW_WINDOW_MS] after a resend that had a new BG or a user
 *     change, so the new COB, IOB, predictions or TBR that follow it are not late;
 *   - any reason when there was no resend in the last [SETTLE_WINDOW_MS].
 * - [MAX_DELAY_MS] (60 s): nothing waits longer than 60 s. The other reasons (a calculation or loop run
 *   without a new BG, NS, an SMB) in the [SETTLE_WINDOW_MS] after a resend wait for [SETTLE_QUIET_MS]
 *   of quiet, so they go out together, but never longer than [MAX_DELAY_MS] after the first of them.
 *
 * Both limits count from the arrival of the reason to the start of the resend. They do not hold in two
 * cases, both visible in the log: while a resend runs, the next one waits for it to end; and a resend
 * that did not go out (the app is not initialized yet, or it threw) is tried again after
 * [RETRY_DELAY_MS], with all of its reasons.
 *
 * A measured BG cycle gives 3 resends instead of 5: the new BG, the loop result that follows it, and
 * the rest of the cycle (NS, a new TBR) about 30 s later. Glucose is never held back.
 *
 * Thread safe: the reasons come from several collectors, [run] reads them.
 *
 * @param now the time in milliseconds; in tests the virtual time
 */
class WearResendSchedule(private val now: () -> Long) {

    /** What a reason is about, see the class description for how long each one waits. */
    enum class Kind {

        /** A new BG. Always fast. */
        NEW_BG,

        /** A change the user made (or one from NS on a follower): a bolus, carbs, a profile switch, a temp target, the running mode, the eat timer. Always fast. */
        USER_CHANGE,

        /** A calculation without a new BG, or a loop run: fast shortly after a fast resend, else slow. */
        CALCULATION_OR_LOOP,

        /** Anything else: NS, an SMB. Slow while the BG cycle settles. */
        OTHER
    }

    /** The reasons of one resend, as taken by [takeRound]. Given back by [roundFailed] when it did not go out. */
    class Round internal constructor(
        val reasons: List<String>,
        internal val fastDue: Long?,
        internal val slowFirst: Long?,
        internal val slowLast: Long?,
        internal val bringsNews: Boolean
    )

    private val reasons = LinkedHashSet<String>()

    /** The latest start of the next resend for the fast reasons that wait, or null. */
    private var fastDue: Long? = null

    /** When the first and the last slow reason that wait arrived, or null. */
    private var slowFirst: Long? = null
    private var slowLast: Long? = null

    /** True when a reason that waits is a new BG or a user change. */
    private var bringsNews = false

    /** When the last resend started, or null before the first one. */
    private var lastRoundAt: Long? = null

    /** Until when a calculation or loop run is fast, see [FOLLOW_WINDOW_MS]. */
    private var followUntil = Long.MIN_VALUE

    /** No resend before this time: a resend did not go out, see [RETRY_DELAY_MS]. */
    private var retryAt: Long? = null

    /** The time of the newest BG seen so far, see [calculationFinished]. */
    private var newestBg: Long? = null

    /** Wakes [run] when a reason arrives, so it can move the next resend earlier. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Notes [reason] of [kind]; the next resend goes out within the limits of the class description. */
    fun request(reason: String, kind: Kind) {
        synchronized(this) {
            val at = now()
            reasons += reason
            if (kind == Kind.NEW_BG || kind == Kind.USER_CHANGE) bringsNews = true
            val settling = lastRoundAt?.let { at - it < SETTLE_WINDOW_MS } ?: false
            val fast = when (kind) {
                Kind.NEW_BG, Kind.USER_CHANGE -> true
                Kind.CALCULATION_OR_LOOP      -> at <= followUntil
                Kind.OTHER                    -> false
            } || !settling
            if (fast) {
                fastDue = minOf(fastDue ?: Long.MAX_VALUE, at + FAST_DELAY_MS)
            } else {
                if (slowFirst == null) slowFirst = at
                slowLast = at
            }
        }
        wake.trySend(Unit)
    }

    /**
     * A calculation ended. It brings a new BG when the calculation says so ([triggeredByNewBG]), or when
     * the newest BG ([newestBgTime]) is newer than any seen before. Both are checked: in recalculated
     * mode the newest bucket keeps its time while its value changes, and a calculation of several
     * merged requests may carry a new BG without the flag.
     */
    fun calculationFinished(triggeredByNewBG: Boolean, newestBgTime: Long?) {
        val newer = synchronized(this) {
            val newer = newestBgTime != null && newestBgTime > (newestBg ?: Long.MIN_VALUE)
            if (newer) newestBg = newestBgTime
            newer
        }
        if (triggeredByNewBG || newer) request(REASON_NEW_BG, Kind.NEW_BG)
        else request(REASON_CALCULATION, Kind.CALCULATION_OR_LOOP)
    }

    /** When the next resend must start, or null when no reason waits. */
    @Synchronized
    fun dueAt(): Long? {
        if (reasons.isEmpty()) return null
        val slowDue = slowFirst?.let { first -> minOf((slowLast ?: first) + SETTLE_QUIET_MS, first + MAX_DELAY_MS) }
        val due = minOf(fastDue ?: Long.MAX_VALUE, slowDue ?: Long.MAX_VALUE)
        return retryAt?.let { maxOf(due, it) } ?: due
    }

    /** Takes all waiting reasons for a resend that starts now. Null when nothing waits. */
    @Synchronized
    fun takeRound(): Round? {
        if (reasons.isEmpty()) return null
        val at = now()
        val round = Round(reasons.toList(), fastDue, slowFirst, slowLast, bringsNews)
        reasons.clear()
        fastDue = null
        slowFirst = null
        slowLast = null
        bringsNews = false
        lastRoundAt = at
        if (round.bringsNews) followUntil = at + FOLLOW_WINDOW_MS
        return round
    }

    /** The resend of the last [takeRound] went out. */
    @Synchronized
    fun roundSent() {
        retryAt = null
    }

    /** The resend of [round] did not go out: its reasons wait again, with their old times, and are tried again after [RETRY_DELAY_MS]. */
    fun roundFailed(round: Round) {
        synchronized(this) {
            reasons += round.reasons
            round.fastDue?.let { fastDue = minOf(fastDue ?: Long.MAX_VALUE, it) }
            round.slowFirst?.let { slowFirst = minOf(slowFirst ?: Long.MAX_VALUE, it) }
            round.slowLast?.let { slowLast = maxOf(slowLast ?: Long.MIN_VALUE, it) }
            if (round.bringsNews) bringsNews = true
            retryAt = now() + RETRY_DELAY_MS
        }
        wake.trySend(Unit)
    }

    /**
     * Runs the resends until cancelled: waits until [dueAt], then calls [send] with the reasons. [send]
     * returns true when the resend really went out; false or an exception makes the reasons wait again,
     * see [roundFailed].
     */
    suspend fun run(send: suspend (reasons: List<String>) -> Boolean) {
        while (true) {
            val due = dueAt()
            if (due == null) {
                wake.receive()
                continue
            }
            val wait = due - now()
            if (wait > 0) {
                withTimeoutOrNull(wait) { wake.receive() }
                continue
            }
            val round = takeRound() ?: continue
            val sent = try {
                send(round.reasons)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (sent) roundSent() else roundFailed(round)
        }
    }

    companion object {

        /** Hard limit: a fast reason starts a resend at most this long after it arrived. */
        const val FAST_DELAY_MS = 2_000L

        /** Hard limit: no reason waits longer than this for its resend. */
        const val MAX_DELAY_MS = 60_000L

        /** How long after a resend the BG cycle settles: the other reasons wait for quiet then. */
        const val SETTLE_WINDOW_MS = 60_000L

        /** The quiet the slow reasons wait for. Longer than any gap in a measured BG cycle (20 s). */
        const val SETTLE_QUIET_MS = 30_000L

        /** How long after a resend with a new BG or a user change a calculation or loop run is fast. */
        const val FOLLOW_WINDOW_MS = 15_000L

        /** When a resend did not go out, the next try comes this much later. */
        const val RETRY_DELAY_MS = 5_000L

        const val REASON_NEW_BG = "NewBG"
        const val REASON_CALCULATION = "EventAutosensCalculationFinished"
    }
}
