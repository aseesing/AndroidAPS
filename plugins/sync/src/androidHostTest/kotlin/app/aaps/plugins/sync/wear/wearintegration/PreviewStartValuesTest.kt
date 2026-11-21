// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** What the live preview starts with, see [previewStartValues]. */
class PreviewStartValuesTest {

    private fun bg(time: Long, dataset: Int = 0) = EventData.SingleBg(dataset = dataset, timeStamp = time, sgvString = "6,7", sgv = 120.0, high = 180.0, low = 70.0)

    private val status = EventData.Status(
        dataset = 0, externalStatus = "", iobSum = "0,43", iobDetail = "", cob = "0g", currentBasal = "", battery = "78", rigBattery = "--",
        openApsStatus = 0L, bgi = "", batteryLevel = 1, tempTarget = "", tempTargetLevel = 0, reservoirString = "", reservoir = 0.0, reservoirLevel = 0
    )
    private val graph = EventData.GraphData(arrayListOf(bg(1_000L)))
    private val newGraph = EventData.GraphData(arrayListOf(bg(2_000L)))

    @Test
    fun `a status without glucose gets the last glucose and the graph of the phone`() = runTest {
        // The watch asked for the values before the phone had its table of readings
        val values = previewStartValues(listOf(status), bg(5_000L)) { newGraph }

        assertThat(values).containsExactly(status, bg(5_000L), newGraph).inOrder()
    }

    @Test
    fun `a newer glucose of the phone takes the place of the one sent`() = runTest {
        val sent = bg(5_000L)
        val values = previewStartValues(listOf(sent, graph, status), bg(9_000L)) { error("the sent graph stays") }

        assertThat(values).hasSize(3)
        assertThat(values.filterIsInstance<EventData.SingleBg>().single().timeStamp).isEqualTo(9_000L)
        assertThat(values).contains(graph)
    }

    @Test
    fun `the glucose that was sent stays when the phone has no newer one`() = runTest {
        val sent = bg(5_000L)
        val follower = bg(7_000L, dataset = 1)

        assertThat(previewStartValues(listOf(sent, follower, graph), bg(5_000L)) { null }.filter { it === sent }).hasSize(1)
        assertThat(previewStartValues(listOf(sent, graph), null) { null }).containsExactly(sent, graph).inOrder()
        // The glucose of a follower is not the phone's own
        assertThat(previewStartValues(listOf(follower, graph), bg(6_000L)) { null }).containsExactly(follower, graph, bg(6_000L)).inOrder()
    }

    @Test
    fun `a phone without readings gives what was sent`() = runTest {
        assertThat(previewStartValues(listOf(status), null) { null }).containsExactly(status)
        assertThat(previewStartValues(emptyList(), null) { null }).isEmpty()
    }
}
