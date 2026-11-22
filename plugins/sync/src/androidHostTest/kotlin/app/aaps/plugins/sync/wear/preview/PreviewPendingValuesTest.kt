// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The values a live preview has not given to its renderer yet, see [PreviewPendingValues]. */
class PreviewPendingValuesTest {

    private val sut = PreviewPendingValues()

    private fun ping(time: Long) = EventData.ActionPing(time)

    @Test
    fun `the newest value of each kind waits, the oldest kind first`() {
        sut.put("a", ping(1))
        sut.put("b", ping(2))
        sut.put("a", ping(3))

        assertThat(sut.take()).containsExactly(ping(2), ping(3)).inOrder()
        // Taken once
        assertThat(sut.take()).isEmpty()
    }

    @Test
    fun `a value that was read before a newer one came does not replace it`() {
        val mark = sut.mark()
        // While the start values are read, a new value comes from the event bus
        sut.put("glucose", ping(20))

        sut.putUnlessNewer("glucose", ping(10), mark)
        sut.putUnlessNewer("status", ping(11), mark)

        assertThat(sut.take()).containsExactly(ping(20), ping(11)).inOrder()
    }

    @Test
    fun `a value read later replaces what waited, also after a take`() {
        sut.put("glucose", ping(1))
        sut.take()
        val mark = sut.mark()

        sut.putUnlessNewer("glucose", ping(2), mark)

        assertThat(sut.take()).containsExactly(ping(2))
    }
}
