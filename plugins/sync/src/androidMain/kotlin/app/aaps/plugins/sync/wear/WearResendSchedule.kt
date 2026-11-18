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
 *   - a change the user made outside the database ([Kind.USER_CHANGE]: the eat timer);
 *   - a change in the database ([Kind.DATABASE_CHANGE]: a bolus that is not an SMB, carbs, a profile
 *     switch, a temp target, the running mode), see the gap below;
 *   - a calculation or a loop run up to [FOLLOW_WINDOW_MS] after a resend that had a new BG or a
 *     change, so the new COB, IOB, predictions or TBR that follow it are not late;
 *   - any reason when there was no resend in the last [SETTLE_WINDOW_MS].
 * - [MAX_DELAY_MS] (60 s): nothing waits longer than 60 s. The other reasons (a calculation or loop run
 *   without a new BG, an SMB) in the [SETTLE_WINDOW_MS] after a resend wait for [SETTLE_QUIET_MS]
 *   of quiet, so they go out together, but never longer than [MAX_DELAY_MS] after the first of them.
 *
 * The gap for repeated database changes ([DATABASE_GAP_MS], 10 s). A sync from NS, the nsId that NS
 * writes back, or a read of the pump history can change many rows, one after the other. Each change
 * is a fast reason, so without the gap the watch would get a resend about every 2 s. So:
 * - a database change that comes [DATABASE_GAP_MS] or more after the start of the last resend with a
 *   database change goes out within 2 s, as above. This is the first change a user makes;
 * - a database change that comes sooner is a repeat. It goes out at the end of the gap: 10 s after the
 *   start of that last resend (but not before its own 2 s). So it waits at most 10 s;
 * - after a repeat, for [DATABASE_GAP_MS], the calculations and loop runs that are fast by the follow
 *   window (the calculator runs again for every changed row) wait for the end of the gap too;
 * - a new BG, the eat timer and a reason when nothing went out for [SETTLE_WINDOW_MS] never wait for
 *   the gap. A new BG takes all reasons that wait with it.
 * So a storm of database changes gives one resend every 10 s, and a new BG in it still goes out in 2 s.
 *
 * Both limits count from the arrival of the reason to the start of the resend. They do not hold in two
 * cases, both visible in the log: while a resend runs, the next one waits for it to end; and a resend
 * that did not go out is tried again after [RETRY_DELAY_MS], with all of its reasons:
 * - when the app is not initialized yet ([Outcome.NOT_READY]), for as long as that lasts;
 * - when it failed ([Outcome.FAILED], or [run]'s send threw), at most [MAX_RETRIES] more times. Then
 *   its reasons are given up. A reason that comes later starts a new resend, with new tries.
 *
 * A measured BG cycle gives 3 resends instead of 5: the new BG, the loop result that follows it, and
 * the rest of the cycle (NS, a new TBR) about 30 s later. Glucose is never held back.
 *
 * Thread safe: the reasons come from several collectors, [run] reads them.
 *
 * @param now a steady clock in milliseconds, one that never goes back when the user or the network
 *   sets the wall clock (`SystemClock.elapsedRealtime` on Android); in tests the virtual time. Its
 *   times are never compared with a wall clock time, such as the time of a BG.
 */
class WearResendSchedule(private val now: () -> Long) {

    /** What a reason is about, see the class description for how long each one waits. */
    enum class Kind {

        /** A new BG. Always fast, never held by the gap. */
        NEW_BG,

        /** A change the user made that is not in the database: the eat timer. Always fast, never held by the gap. */
        USER_CHANGE,

        /**
         * A change in the database: a bolus that is not an SMB, carbs, a profile switch, a temp target, the
         * running mode. Made by the user, or by a sync (NS, an nsId written back, the pump history). Fast,
         * but a repeat waits for the end of the gap, see [DATABASE_GAP_MS].
         */
        DATABASE_CHANGE,

        /** A calculation without a new BG, a loop run, or new predictions from NS: fast shortly after a fast resend, else slow. */
        CALCULATION_OR_LOOP,

        /** Anything else: an SMB. Slow while the BG cycle settles. */
        OTHER
    }

    /** What happened to a resend. */
    enum class Outcome {

        /** It went out. */
        SENT,

        /** The app is not initialized yet, nothing was sent. Tried again for as long as that lasts. */
        NOT_READY,

        /** It failed. Tried again at most [MAX_RETRIES] more times. */
        FAILED
    }

    /** The reasons of one resend, as taken by [takeRound]. Given back by [roundNotSent] when it did not go out. */
    class Round internal constructor(
        val reasons: List<String>,
        internal val fastDue: Long?,
        internal val slowFirst: Long?,
        internal val slowLast: Long?,
        internal val bringsNews: Boolean,
        internal val hasDatabaseChange: Boolean
    )

    private val reasons = LinkedHashSet<String>()

    /** The latest start of the next resend for the fast reasons that wait, or null. */
    private var fastDue: Long? = null

    /** When the first and the last slow reason that wait arrived, or null. */
    private var slowFirst: Long? = null
    private var slowLast: Long? = null

    /** True when a reason that waits is a new BG or a change. */
    private var bringsNews = false

    /** True when a reason that waits is a database change. */
    private var hasDatabaseChange = false

    /** When the last resend started, or null before the first one. */
    private var lastRoundAt: Long? = null

    /** When the last resend with a database change started, or null. The gap counts from here. */
    private var lastDatabaseRoundAt: Long? = null

    /** Until when the follow window waits for the gap: [DATABASE_GAP_MS] after the last repeat. */
    private var repeatsUntil = Long.MIN_VALUE

    /** Until when a calculation or loop run is fast, see [FOLLOW_WINDOW_MS]. */
    private var followUntil = Long.MIN_VALUE

    /** No resend before this time: a resend did not go out, see [RETRY_DELAY_MS]. */
    private var retryAt: Long? = null

    /** How many times in a row the reasons that wait failed, see [MAX_RETRIES]. */
    private var failures = 0

    /** The time of the newest BG seen so far, see [calculationFinished]. A wall clock time, compared only with BG times. */
    private var newestBg: Long? = null

    /** Wakes [run] when a reason arrives, so it can move the next resend earlier. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Notes [reason] of [kind]; the next resend goes out within the limits of the class description. */
    fun request(reason: String, kind: Kind) {
        synchronized(this) {
            val at = now()
            reasons += reason
            if (kind == Kind.NEW_BG || kind == Kind.USER_CHANGE || kind == Kind.DATABASE_CHANGE) bringsNews = true
            if (kind == Kind.DATABASE_CHANGE) hasDatabaseChange = true
            val settling = lastRoundAt?.let { at - it < SETTLE_WINDOW_MS } ?: false
            // The end of the gap when this reason is a repeat, or null
            val gapEnd = lastDatabaseRoundAt?.let { it + DATABASE_GAP_MS }?.takeIf { at < it }
            val due = when {
                !settling                                                -> at + FAST_DELAY_MS
                kind == Kind.NEW_BG || kind == Kind.USER_CHANGE          -> at + FAST_DELAY_MS
                kind == Kind.DATABASE_CHANGE && gapEnd != null           -> {
                    repeatsUntil = at + DATABASE_GAP_MS
                    maxOf(at + FAST_DELAY_MS, gapEnd)
                }

                kind == Kind.DATABASE_CHANGE                             -> at + FAST_DELAY_MS
                kind == Kind.CALCULATION_OR_LOOP && at <= followUntil    ->
                    if (gapEnd != null && at < repeatsUntil) maxOf(at + FAST_DELAY_MS, gapEnd) else at + FAST_DELAY_MS

                else                                                     -> null
            }
            if (due != null) {
                fastDue = minOf(fastDue ?: Long.MAX_VALUE, due)
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
     * merged requests may carry a new BG without the flag. BG times are wall clock times: they are
     * compared only with each other, never with [now].
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
        val round = Round(reasons.toList(), fastDue, slowFirst, slowLast, bringsNews, hasDatabaseChange)
        if (hasDatabaseChange) lastDatabaseRoundAt = at
        reasons.clear()
        fastDue = null
        slowFirst = null
        slowLast = null
        bringsNews = false
        hasDatabaseChange = false
        lastRoundAt = at
        if (round.bringsNews) followUntil = at + FOLLOW_WINDOW_MS
        return round
    }

    /** The resend of the last [takeRound] went out. */
    @Synchronized
    fun roundSent() {
        retryAt = null
        failures = 0
    }

    /**
     * The resend of [round] did not go out ([outcome] is [Outcome.NOT_READY] or [Outcome.FAILED]). Its
     * reasons wait again, with their old times, and are tried again after [RETRY_DELAY_MS]. After
     * [MAX_RETRIES] failed tries more they are given up instead: then the result is true. The reasons
     * that came while the resend ran are not given up.
     */
    fun roundNotSent(round: Round, outcome: Outcome): Boolean {
        val gaveUp = synchronized(this) {
            if (outcome == Outcome.FAILED) failures++
            if (failures > MAX_RETRIES) {
                failures = 0
                retryAt = null
                true
            } else {
                reasons += round.reasons
                round.fastDue?.let { fastDue = minOf(fastDue ?: Long.MAX_VALUE, it) }
                round.slowFirst?.let { slowFirst = minOf(slowFirst ?: Long.MAX_VALUE, it) }
                round.slowLast?.let { slowLast = maxOf(slowLast ?: Long.MIN_VALUE, it) }
                if (round.bringsNews) bringsNews = true
                if (round.hasDatabaseChange) hasDatabaseChange = true
                retryAt = now() + RETRY_DELAY_MS
                false
            }
        }
        wake.trySend(Unit)
        return gaveUp
    }

    /**
     * Runs the resends until cancelled: waits until [dueAt], then calls [send] with the reasons. [send]
     * says what happened; an exception counts as [Outcome.FAILED], see [roundNotSent]. [gaveUp] is
     * called with the reasons that were given up.
     */
    suspend fun run(
        send: suspend (reasons: List<String>) -> Outcome,
        gaveUp: (reasons: List<String>) -> Unit = {}
    ) {
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
            val outcome = try {
                send(round.reasons)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Outcome.FAILED
            }
            if (outcome == Outcome.SENT) roundSent()
            else if (roundNotSent(round, outcome)) gaveUp(round.reasons)
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

        /** How long after a resend with a new BG or a change a calculation or loop run is fast. */
        const val FOLLOW_WINDOW_MS = 15_000L

        /** The shortest time between two resends for repeated database changes, see the class description. */
        const val DATABASE_GAP_MS = 10_000L

        /** When a resend did not go out, the next try comes this much later. */
        const val RETRY_DELAY_MS = 5_000L

        /** How many more times a failed resend is tried before its reasons are given up. */
        const val MAX_RETRIES = 2

        const val REASON_NEW_BG = "NewBG"
        const val REASON_CALCULATION = "EventAutosensCalculationFinished"
    }
}
