// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.plugins.sync.wear.preview.PreviewSlot
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
    private val states = mutableListOf<WearFaceLoading>()
    private val sut = WearFaceLoadingTracker(scope, timeoutMs = 1_000L) { states += it }

    private val day = MutableStateFlow<CwfData?>(design("Old"))
    private val night = MutableStateFlow<CwfData?>(null)

    /** As the watch reports it: every report has its own copy of the picture bytes. */
    private fun design(name: String) =
        CwfData("{\"name\":\"$name\"}", mutableMapOf(), mutableMapOf("CustomWatchface" to ResData(byteArrayOf(1, 2), ResFormat.PNG)))

    private val now get() = states.lastOrNull() ?: WearFaceLoading()

    @Test
    fun `loads until the watch reports a face for that slot`() {
        sut.start(PreviewSlot.DAY, "New", day)
        assertThat(now).isEqualTo(WearFaceLoading(day = "New"))

        night.value = design("Night")
        assertThat(now.day).isEqualTo("New")

        day.value = design("New")
        assertThat(now).isEqualTo(WearFaceLoading())
    }

    @Test
    fun `the same design reported again also ends the load`() {
        sut.start(PreviewSlot.DAY, "Old", day)

        day.value = design("Old")

        assertThat(now.day).isNull()
    }

    @Test
    fun `gives up after the timeout when the watch does not answer`() {
        sut.start(PreviewSlot.NIGHT, "Night", night)

        scope.advanceTimeBy(999L)
        scope.runCurrent()
        assertThat(now.night).isEqualTo("Night")

        scope.advanceTimeBy(2L)
        scope.runCurrent()
        assertThat(now.night).isNull()
    }

    @Test
    fun `a second pick replaces the first and keeps its own timeout`() {
        sut.start(PreviewSlot.DAY, "First", day)
        scope.advanceTimeBy(800L)
        sut.start(PreviewSlot.DAY, "Second", day)
        assertThat(now.day).isEqualTo("Second")

        // The first timeout would end here; the second has 600 ms left
        scope.advanceTimeBy(400L)
        scope.runCurrent()
        assertThat(now.day).isEqualTo("Second")

        scope.advanceTimeBy(700L)
        scope.runCurrent()
        assertThat(now.day).isNull()
    }

    @Test
    fun `day and night load apart`() {
        sut.start(PreviewSlot.DAY, "Day", day)
        sut.start(PreviewSlot.NIGHT, "Night", night)
        assertThat(now).isEqualTo(WearFaceLoading(day = "Day", night = "Night"))

        night.value = design("Night")

        assertThat(now).isEqualTo(WearFaceLoading(day = "Day"))
    }
}
