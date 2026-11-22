// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the watch face may wait for its stores on a thread that must not wait: the main thread while
 * the face starts or draws. A read of the stores takes milliseconds; a cached one less.
 */
const val STORE_WAIT_LIMIT_MS = 3_000L

/** The answer of [waitForStore]: [value] when it came [inTime], else null. */
class StoreAnswer<out T>(val value: T?, val inTime: Boolean)

/**
 * How long a face that only draws pictures stays without waiting after a read that did not come. Such a
 * face has no flow of the store that could tell it the store answers again, see [StoreReads.tryAgainAfter].
 */
const val STORE_RETRY_MS = 60_000L

/**
 * The waits of one watch face for its stores. One face holds one of these, so the time limit counts once
 * for each start of the face and not once for each read.
 *
 * On 2026-11-22 a watch showed a frozen, black face for a minute: the face started, waited on the main
 * thread for its first read of the stores, and the read never came, while the system made a second copy
 * of the face at the same moment. After 60 s Android killed the app (an ANR). With a time limit the face
 * goes on without the answer: it draws without values, or keeps its last design, and gets them a moment
 * later through the store's own updates or the next refresh. A late answer is logged, never thrown.
 *
 * A start of the face reads the stores four times or more, and each refresh reads them again. With a
 * limit for each read a store that never answers would still block the main thread for 12 s at the start
 * and for 3 s at each refresh. So after one read that did not come in time, the next ones do not wait at
 * all: they give "not in time" at once, until [storeAnswered] says the store works again.
 *
 * [limitMs] is the limit of one wait, [now] the clock in milliseconds.
 */
class StoreReads(private val limitMs: Long = STORE_WAIT_LIMIT_MS, private val now: () -> Long = System::currentTimeMillis) {

    /** When a read did not come in time, or 0 while the store answers. */
    @Volatile private var gaveUpAt = 0L

    /** True from a read that did not come in time until the store answers again. */
    val gaveUp: Boolean get() = gaveUpAt != 0L

    /**
     * Runs [work] on the face's stores (a read or a write) and waits for it at most the limit. After a
     * read that did not come in time it does not run [work] and does not wait: the answer is "not in time".
     *
     * [what] names the work in the log.
     */
    fun <T> wait(logger: AAPSLogger, what: String, work: suspend () -> T): StoreAnswer<T> {
        if (gaveUp) return StoreAnswer(null, false)
        val answer = runBlocking { withTimeoutOrNull(limitMs) { Answer(work()) } }
        if (answer == null) {
            gaveUpAt = now().coerceAtLeast(1L)
            logger.error(LTag.WEAR, "$what took more than $limitMs ms: the watch face goes on without it and does not wait for its stores until they answer")
        }
        return StoreAnswer(answer?.value, answer != null)
    }

    /** The store gave a value, for example through its own flow: the next read may wait again. */
    fun storeAnswered() {
        gaveUpAt = 0L
    }

    /**
     * Lets the next read wait again when the last one that did not come is [retryMs] or longer ago. For a
     * face without a flow of the store, where nothing else can call [storeAnswered].
     */
    fun tryAgainAfter(retryMs: Long = STORE_RETRY_MS) {
        val since = gaveUpAt
        if (since != 0L && now() - since >= retryMs) gaveUpAt = 0L
    }
}

/** A box for the answer, so a null answer is told apart from no answer in time. */
private class Answer<T>(val value: T)
