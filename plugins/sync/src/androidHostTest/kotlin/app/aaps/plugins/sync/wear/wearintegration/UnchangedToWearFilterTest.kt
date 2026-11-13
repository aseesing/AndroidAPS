// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UnchangedToWearFilterTest {

    private val t0 = 1_700_000_000_000L
    private val filter = UnchangedToWearFilter()

    private fun bg(time: Long, sgv: Double, dataset: Int = 0) =
        EventData.SingleBg(dataset = dataset, timeStamp = time, sgvString = sgv.toInt().toString(), sgv = sgv, high = 180.0, low = 70.0)

    private fun preferences(time: Long, wearControl: Boolean = true) = EventData.Preferences(
        timeStamp = time, wearControl = wearControl, unitsMgdl = true, bolusPercentage = 100, maxCarbs = 100, maxBolus = 10.0,
        insulinButtonIncrement1 = 0.5, insulinButtonIncrement2 = 1.0, carbsButtonIncrement1 = 5, carbsButtonIncrement2 = 10
    )

    @Test
    fun theSameStateIsSentOnce() {
        assertThat(filter.shouldSend(EventData.SceneList(arrayListOf()), t0)).isTrue()
        assertThat(filter.shouldSend(EventData.SceneList(arrayListOf()), t0 + 30_000)).isFalse()
    }

    @Test
    fun aChangedStateIsSent() {
        assertThat(filter.shouldSend(bg(t0, 100.0), t0)).isTrue()
        assertThat(filter.shouldSend(bg(t0 + 300_000, 104.0), t0 + 300_000)).isTrue()
    }

    @Test
    fun aCorrectedValueWithTheSameTimeIsSent() {
        // SingleBg.equals looks only at time and colour; the filter compares the whole content
        assertThat(filter.shouldSend(bg(t0, 100.0), t0)).isTrue()
        assertThat(filter.shouldSend(bg(t0, 104.0), t0 + 1000)).isTrue()
    }

    @Test
    fun theTimeThePreferencesWereSentDoesNotCount() {
        assertThat(filter.shouldSend(preferences(t0), t0)).isTrue()
        assertThat(filter.shouldSend(preferences(t0 + 60_000), t0 + 60_000)).isFalse()
        assertThat(filter.shouldSend(preferences(t0 + 120_000, wearControl = false), t0 + 120_000)).isTrue()
    }

    @Test
    fun eachDatasetIsItsOwnState() {
        assertThat(filter.shouldSend(bg(t0, 100.0, dataset = 0), t0)).isTrue()
        assertThat(filter.shouldSend(bg(t0, 100.0, dataset = 1), t0)).isTrue()
        assertThat(filter.shouldSend(bg(t0, 100.0, dataset = 1), t0)).isFalse()
    }

    @Test
    fun anUnchangedStateGoesOutAgainWhenOld() {
        assertThat(filter.shouldSend(EventData.SceneList(arrayListOf()), t0)).isTrue()
        assertThat(filter.shouldSend(EventData.SceneList(arrayListOf()), t0 + UnchangedToWearFilter.MAX_AGE_MS - 1)).isFalse()
        assertThat(filter.shouldSend(EventData.SceneList(arrayListOf()), t0 + UnchangedToWearFilter.MAX_AGE_MS)).isTrue()
    }

    @Test
    fun otherMessagesAlwaysGoOut() {
        repeat(3) { assertThat(filter.shouldSend(EventData.BolusProgress(50, "SMB"), t0)).isTrue() }
        repeat(3) { assertThat(filter.shouldSend(EventData.ActionPing(t0), t0)).isTrue() }
        // the running modes answer the watch's request, an answer must always come
        repeat(3) { assertThat(filter.shouldSend(EventData.RunningModeList(t0, emptyList()), t0)).isTrue() }
    }

    @Test
    fun theStatusAlwaysGoesOut() {
        // The watch refreshes its complications on a Status, also when only the BG changed
        val status = EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "0.00", iobDetail = "", cob = "", currentBasal = "", battery = "", rigBattery = "",
            openApsStatus = t0, bgi = "", batteryLevel = 1, tempTarget = "", tempTargetLevel = 0, reservoirString = "", reservoir = 0.0, reservoirLevel = 0
        )
        repeat(3) { assertThat(filter.shouldSend(status, t0 + it * 60_000L)).isTrue() }
    }

    @Test
    fun resetSendsEverythingAgain() {
        filter.shouldSend(EventData.SceneList(arrayListOf()), t0)
        filter.reset()
        assertThat(filter.shouldSend(EventData.SceneList(arrayListOf()), t0 + 1000)).isTrue()
    }

    /**
     * The second resend of a BG cycle, a minute after the first: the phone sends the same BG, graph,
     * preferences, quick wizard, actions and scenes again. Only the status and treatments changed.
     */
    @Test
    fun theSecondResendOfACycleSendsOnlyWhatChanged() {
        fun resend(now: Long, iob: String, treatments: Int): Int {
            val messages = listOf(
                bg(t0, 120.0), preferences(now), EventData.QuickWizard(arrayListOf()), EventData.UserAction(arrayListOf()),
                EventData.SceneList(arrayListOf()), EventData.ActiveSceneState(active = false),
                EventData.GraphData(arrayListOf(bg(t0, 120.0))),
                EventData.TreatmentData(arrayListOf(), arrayListOf(), arrayListOf(), ArrayList(List(treatments) { bg(t0 + it * 300_000L, 120.0) })),
                EventData.Status(
                    dataset = 0, externalStatus = "", iobSum = iob, iobDetail = "", cob = "", currentBasal = "", battery = "", rigBattery = "",
                    openApsStatus = now, bgi = "", batteryLevel = 1, tempTarget = "", tempTargetLevel = 0, reservoirString = "", reservoir = 0.0, reservoirLevel = 0
                )
            )
            return messages.count { filter.shouldSend(it, now) }
        }
        val first = resend(t0, iob = "1.20", treatments = 3)
        val second = resend(t0 + 60_000, iob = "1.15", treatments = 4)
        println("PERF phone to watch: first resend $first messages, second resend $second of 9")
        assertThat(first).isEqualTo(9)
        assertThat(second).isEqualTo(2)
    }

    @Test
    fun theLastValueOfEachKindIsKeptForThePreviewAlsoWhenHeldBack() {
        val status = EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "1.2", iobDetail = "", cob = "10g", currentBasal = "0.8U/h", battery = "80",
            rigBattery = "", openApsStatus = t0, bgi = "", batteryLevel = 1, tempTarget = "", tempTargetLevel = 0,
            reservoirString = "", reservoir = 0.0, reservoirLevel = 0
        )
        filter.shouldSend(bg(t0, 100.0), t0)
        filter.shouldSend(bg(t0 + 300_000, 104.0), t0 + 300_000)
        filter.shouldSend(status, t0)
        filter.shouldSend(EventData.SceneList(arrayListOf()), t0)
        assertThat(filter.shouldSend(preferences(t0), t0)).isTrue()
        assertThat(filter.shouldSend(preferences(t0 + 60_000), t0 + 60_000)).isFalse()

        val seen = filter.lastSeen()
        assertThat(seen.filterIsInstance<EventData.SingleBg>().single().sgv).isEqualTo(104.0)
        assertThat(seen.filterIsInstance<EventData.Status>().single()).isEqualTo(status)
        assertThat(seen.filterIsInstance<EventData.Preferences>().single().timeStamp).isEqualTo(t0 + 60_000)
        assertThat(seen.filterIsInstance<EventData.SceneList>()).isEmpty()
    }
}
