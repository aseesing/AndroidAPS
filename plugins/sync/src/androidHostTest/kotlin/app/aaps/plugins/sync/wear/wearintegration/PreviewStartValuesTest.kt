// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** What the live preview starts with, see [PreviewStartValues]. */
class PreviewStartValuesTest {

    private fun bg(time: Long, dataset: Int = 0) = EventData.SingleBg(dataset = dataset, timeStamp = time, sgvString = "6,7", sgv = 120.0, high = 180.0, low = 70.0)

    private val status = EventData.Status(
        dataset = 0, externalStatus = "", iobSum = "0,43", iobDetail = "", cob = "0g", currentBasal = "", battery = "78", rigBattery = "--",
        openApsStatus = 0L, bgi = "", batteryLevel = 1, tempTarget = "", tempTargetLevel = 0, reservoirString = "", reservoir = 0.0, reservoirLevel = 0
    )

    /** The preferences of a phone that uses mmol/l */
    private val preferences = EventData.Preferences(
        timeStamp = 1L, wearControl = false, unitsMgdl = false, bolusPercentage = 100, maxCarbs = 48, maxBolus = 3.0,
        insulinButtonIncrement1 = 0.5, insulinButtonIncrement2 = 1.0, carbsButtonIncrement1 = 5, carbsButtonIncrement2 = 10
    )
    private val graph = EventData.GraphData(arrayListOf(bg(1_000L)))
    private val newGraph = EventData.GraphData(arrayListOf(bg(2_000L)))

    /** What the phone sent to the watch in this run of the app */
    private val sent = mutableListOf<EventData>()
    private var resends = 0

    /** A phone whose resend sends [resent], with [lastBg] as its last glucose */
    private fun sut(lastBg: EventData.SingleBg? = null, newGraph: EventData.GraphData? = null, resent: List<EventData> = emptyList()) =
        PreviewStartValues(
            sent = { sent.toList() },
            firstResend = {
                resends++
                sent += resent
            },
            lastBg = { lastBg },
            graph = { newGraph }
        )

    @Test
    fun `a phone that has sent nothing yet does its first resend, so the units and the status are there at once`() = runTest {
        // Right after a start of the app: the preview opens before the first resend
        val values = sut(lastBg = bg(5_000L), resent = listOf(bg(5_000L), preferences, graph, status)).now()

        assertThat(resends).isEqualTo(1)
        assertThat(values.filterIsInstance<EventData.Preferences>().single().unitsMgdl).isFalse()
        assertThat(values).contains(status)
        assertThat(values.filterIsInstance<EventData.SingleBg>()).hasSize(1)
    }

    @Test
    fun `the first resend is done once, and not when the phone has sent the units and the status`() = runTest {
        // A resend that brings nothing (the app is not ready): not asked for again at the next opening
        val sut = sut()
        sut.now()
        sut.now()
        assertThat(resends).isEqualTo(1)

        // The units and the status went out already: no resend for the preview
        resends = 0
        sent += listOf(preferences, status)
        assertThat(sut().now()).containsExactly(preferences, status).inOrder()
        assertThat(resends).isEqualTo(0)
    }

    @Test
    fun `a resend that fails still gives what the phone has`() = runTest {
        sent += status
        val sut = PreviewStartValues(sent = { sent.toList() }, firstResend = { error("no pump yet") }, lastBg = { bg(5_000L) }, graph = { newGraph })

        assertThat(sut.now()).containsExactly(status, bg(5_000L), newGraph).inOrder()
    }

    @Test
    fun `a status without glucose gets the last glucose and the graph of the phone`() = runTest {
        // The watch asked for the values before the phone had its table of readings
        sent += listOf(preferences, status)

        assertThat(sut(lastBg = bg(5_000L), newGraph = newGraph).now()).containsExactly(preferences, status, bg(5_000L), newGraph).inOrder()
        assertThat(resends).isEqualTo(0)
    }

    @Test
    fun `a newer glucose of the phone takes the place of the one sent`() = runTest {
        sent += listOf(bg(5_000L), preferences, graph, status)
        val values = PreviewStartValues(sent = { sent.toList() }, firstResend = {}, lastBg = { bg(9_000L) }, graph = { error("the sent graph stays") }).now()

        assertThat(values).hasSize(4)
        assertThat(values.filterIsInstance<EventData.SingleBg>().single().timeStamp).isEqualTo(9_000L)
        assertThat(values).contains(graph)
    }

    @Test
    fun `the glucose that was sent stays when the phone has no newer one`() = runTest {
        val sentBg = bg(5_000L)
        val follower = bg(7_000L, dataset = 1)
        sent += listOf(sentBg, follower, preferences, graph, status)

        assertThat(sut(lastBg = bg(5_000L)).now().filter { it === sentBg }).hasSize(1)
        assertThat(sut().now()).containsExactly(sentBg, follower, preferences, graph, status).inOrder()
        // The glucose of a follower is not the phone's own
        sent.remove(sentBg)
        assertThat(sut(lastBg = bg(6_000L)).now()).containsExactly(follower, preferences, graph, status, bg(6_000L)).inOrder()
    }

    @Test
    fun `a phone without readings gives what was sent`() = runTest {
        assertThat(sut().now()).isEmpty()
        sent += listOf(preferences, status)
        assertThat(sut().now()).containsExactly(preferences, status).inOrder()
    }
}
