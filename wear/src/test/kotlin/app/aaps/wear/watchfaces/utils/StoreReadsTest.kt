// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test

/** The watch face waits for its stores at most a time limit, so a read that never comes cannot freeze it. */
class StoreReadsTest {

    private val logger = AAPSLoggerTest()

    @Test
    fun anAnswerInTimeIsGiven() {
        val answer = StoreReads().wait(logger, "test") { 42 }
        assertThat(answer.inTime).isTrue()
        assertThat(answer.value).isEqualTo(42)
    }

    @Test
    fun aNullAnswerInTimeIsNotATimeOut() {
        val answer = StoreReads().wait<String?>(logger, "test") { null }
        assertThat(answer.inTime).isTrue()
        assertThat(answer.value).isNull()
    }

    @Test
    fun aReadThatNeverComesGivesUpAtTheLimit() {
        // As on the watch on 2026-11-22: the first read of the stores never came
        val never = CompletableDeferred<Int>()
        val started = System.currentTimeMillis()
        val answer = StoreReads(limitMs = 200).wait(logger, "test") { never.await() }
        val waited = System.currentTimeMillis() - started
        assertThat(answer.inTime).isFalse()
        assertThat(answer.value).isNull()
        assertThat(waited).isAtLeast(200L)
        assertThat(waited).isLessThan(2_000L)
    }

    @Test
    fun aSlowReadPastTheLimitIsCancelled() {
        var finished = false
        val answer = StoreReads(limitMs = 100).wait(logger, "test") {
            delay(1_000)
            finished = true
        }
        assertThat(answer.inTime).isFalse()
        assertThat(finished).isFalse()
    }

    @Test
    fun aReadThatSwallowsItsCancellationIsStillNotInTime() {
        // As ComplicationDataRepository.getCustomWatchface: it catches Exception around the read, and the
        // cancellation of the time limit is an Exception too
        val never = CompletableDeferred<Int>()
        val reads = StoreReads(limitMs = 100)
        val answer = reads.wait<Int?>(logger, "test") {
            try {
                never.await()
            } catch (_: Exception) {
                null
            }
        }
        assertThat(answer.inTime).isFalse()
        assertThat(answer.value).isNull()
        assertThat(reads.gaveUp).isTrue()
    }

    @Test
    fun afterOneReadThatDidNotComeTheNextReadsDoNotWait() {
        // One start of the face reads its stores four times and more: the limit counts once, not for each
        val never = CompletableDeferred<Int>()
        val reads = StoreReads(limitMs = 200)
        assertThat(reads.wait(logger, "first") { never.await() }.inTime).isFalse()

        var ran = 0
        val started = System.currentTimeMillis()
        repeat(5) {
            val answer = reads.wait(logger, "later") {
                ran++
                never.await()
            }
            assertThat(answer.inTime).isFalse()
            assertThat(answer.value).isNull()
        }
        assertThat(System.currentTimeMillis() - started).isLessThan(150L)
        assertThat(ran).isEqualTo(0)
    }

    @Test
    fun readsWaitAgainOnceTheStoreAnswers() {
        val never = CompletableDeferred<Int>()
        val reads = StoreReads(limitMs = 100)
        reads.wait(logger, "first") { never.await() }
        assertThat(reads.gaveUp).isTrue()

        reads.storeAnswered()

        assertThat(reads.gaveUp).isFalse()
        val answer = reads.wait(logger, "after") { 7 }
        assertThat(answer.inTime).isTrue()
        assertThat(answer.value).isEqualTo(7)
    }

    @Test
    fun aFaceWithoutAFlowWaitsAgainOnlyAfterTheRetryTime() {
        var clock = 1_000L
        val never = CompletableDeferred<Int>()
        val reads = StoreReads(limitMs = 100, now = { clock })
        reads.wait(logger, "first") { never.await() }

        clock += STORE_RETRY_MS - 1
        reads.tryAgainAfter()
        assertThat(reads.gaveUp).isTrue()

        clock += 1
        reads.tryAgainAfter()
        assertThat(reads.gaveUp).isFalse()
        assertThat(reads.wait(logger, "after") { 7 }.value).isEqualTo(7)
    }
}
