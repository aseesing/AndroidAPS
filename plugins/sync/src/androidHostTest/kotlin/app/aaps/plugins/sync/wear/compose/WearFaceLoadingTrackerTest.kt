package app.aaps.plugins.sync.wear.compose

import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class WearFaceLoadingTrackerTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val states = mutableListOf<String?>()
    private val sut = WearFaceLoadingTracker(scope, timeoutMs = 1_000L) { states += it }

    private val reported = MutableStateFlow<CwfData?>(design("Old"))

    /** As the watch reports it: every report has its own copy of the picture bytes. */
    private fun design(name: String) =
        CwfData("{\"name\":\"$name\"}", mutableMapOf(), mutableMapOf("CustomWatchface" to ResData(byteArrayOf(1, 2), ResFormat.PNG)))

    private val now get() = states.lastOrNull()

    @Test
    fun `loads until the watch reports a face`() {
        sut.start("New", reported)
        assertThat(now).isEqualTo("New")

        reported.value = design("New")

        assertThat(now).isNull()
    }

    @Test
    fun `the same design reported again also ends the load`() {
        sut.start("Old", reported)

        reported.value = design("Old")

        assertThat(now).isNull()
    }

    @Test
    fun `gives up after the timeout when the watch does not answer`() {
        sut.start("New", reported)

        scope.advanceTimeBy(999L)
        scope.runCurrent()
        assertThat(now).isEqualTo("New")

        scope.advanceTimeBy(2L)
        scope.runCurrent()
        assertThat(now).isNull()
    }

    @Test
    fun `stop ends a load at once and does nothing when nothing loads`() {
        sut.stop()
        assertThat(states).isEmpty()

        sut.start("New", reported)
        sut.stop()
        assertThat(states).containsExactly("New", null).inOrder()

        // The cancelled load does not report again later
        scope.advanceTimeBy(2_000L)
        scope.runCurrent()
        assertThat(states).containsExactly("New", null).inOrder()
    }

    @Test
    fun `a second pick replaces the first and keeps its own timeout`() {
        sut.start("First", reported)
        scope.advanceTimeBy(800L)
        sut.start("Second", reported)
        assertThat(now).isEqualTo("Second")

        // The first timeout would end here; the second has 600 ms left
        scope.advanceTimeBy(400L)
        scope.runCurrent()
        assertThat(now).isEqualTo("Second")

        scope.advanceTimeBy(700L)
        scope.runCurrent()
        assertThat(now).isNull()
        // The first load was cancelled and did not end the second one early
        assertThat(states).containsExactly("First", "Second", null).inOrder()
    }
}
