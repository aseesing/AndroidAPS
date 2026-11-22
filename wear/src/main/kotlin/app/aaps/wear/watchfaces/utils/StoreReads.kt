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
 * Runs [work] on the face's stores (a read or a write) and waits for it at most [limitMs].
 *
 * On 2026-11-22 a watch showed a frozen, black face for a minute: the face started, waited on the main
 * thread for its first read of the stores, and the read never came, while the system made a second copy
 * of the face at the same moment. After 60 s Android killed the app (an ANR). With a time limit the face
 * goes on without the answer: it draws without values, or keeps its last design, and gets them a moment
 * later through the store's own updates or the next refresh. A late answer is logged, never thrown.
 *
 * [what] names the work in the log.
 */
fun <T> waitForStore(logger: AAPSLogger, what: String, limitMs: Long = STORE_WAIT_LIMIT_MS, work: suspend () -> T): StoreAnswer<T> {
    val answer = runBlocking { withTimeoutOrNull(limitMs) { Answer(work()) } }
    if (answer == null) logger.error(LTag.WEAR, "$what took more than $limitMs ms: the watch face goes on without it")
    return StoreAnswer(answer?.value, answer != null)
}

/** A box for the answer, so a null answer is told apart from no answer in time. */
private class Answer<T>(val value: T)
