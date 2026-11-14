// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import kotlin.test.Test
import kotlin.test.assertEquals

class LastLoopEnactTest {

    private val minute = 60_000L
    private val t0 = 1_000_000_000L

    @Test
    fun `before the first enact there is no time`() {
        val enact = LastLoopEnact()
        assertEquals(LastLoopEnact.NONE, enact.remember(0L))
        assertEquals(LastLoopEnact.NONE, enact.remember(-1L))
    }

    @Test
    fun `a run in progress keeps the enact of the run before`() {
        val enact = LastLoopEnact()
        assertEquals(t0, enact.remember(t0))
        // The next run has set its time to 0 and waits for the pod
        assertEquals(t0, enact.remember(0L))
        // Confirmed: the new time
        assertEquals(t0 + 5 * minute, enact.remember(t0 + 5 * minute))
    }

    @Test
    fun `a loop that stops enacting keeps its old time so it ages`() {
        val enact = LastLoopEnact()
        enact.remember(t0)
        // Runs that never reach the pump: the time stays, the watch sees it grow old and warns
        repeat(6) { assertEquals(t0, enact.remember(0L)) }
    }

    @Test
    fun `an older time never replaces a newer one`() {
        val enact = LastLoopEnact()
        enact.remember(t0 + 10 * minute)
        assertEquals(t0 + 10 * minute, enact.remember(t0))
    }
}
