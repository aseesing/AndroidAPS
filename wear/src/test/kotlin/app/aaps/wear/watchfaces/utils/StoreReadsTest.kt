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
        val answer = waitForStore(logger, "test") { 42 }
        assertThat(answer.inTime).isTrue()
        assertThat(answer.value).isEqualTo(42)
    }

    @Test
    fun aNullAnswerInTimeIsNotATimeOut() {
        val answer = waitForStore<String?>(logger, "test") { null }
        assertThat(answer.inTime).isTrue()
        assertThat(answer.value).isNull()
    }

    @Test
    fun aReadThatNeverComesGivesUpAtTheLimit() {
        // As seen on a watch: the first read of the stores never came
        val never = CompletableDeferred<Int>()
        val started = System.currentTimeMillis()
        val answer = waitForStore(logger, "test", limitMs = 200) { never.await() }
        val waited = System.currentTimeMillis() - started
        assertThat(answer.inTime).isFalse()
        assertThat(answer.value).isNull()
        assertThat(waited).isAtLeast(200L)
        assertThat(waited).isLessThan(2_000L)
    }

    @Test
    fun aSlowReadPastTheLimitIsCancelled() {
        var finished = false
        val answer = waitForStore(logger, "test", limitMs = 100) {
            delay(1_000)
            finished = true
        }
        assertThat(answer.inTime).isFalse()
        assertThat(finished).isFalse()
    }
}
