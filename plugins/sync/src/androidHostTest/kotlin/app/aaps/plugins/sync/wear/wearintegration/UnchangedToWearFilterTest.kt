package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UnchangedToWearFilterTest {

    private val t0 = 1_700_000_000_000L
    private val filter = UnchangedToWearFilter()

    private fun bg(time: Long, sgv: Double, dataset: Int = 0) =
        EventData.SingleBg(dataset = dataset, timeStamp = time, sgvString = sgv.toInt().toString(), sgv = sgv, high = 180.0, low = 70.0)

    private fun graph(vararg values: Double) = EventData.GraphData(ArrayList(values.mapIndexed { i, v -> bg(t0 + i * 300_000L, v) }))

    private fun preferences(time: Long, wearControl: Boolean = true) = EventData.Preferences(
        timeStamp = time, wearControl = wearControl, unitsMgdl = true, bolusPercentage = 100, maxCarbs = 100, maxBolus = 10.0,
        insulinButtonIncrement1 = 0.5, insulinButtonIncrement2 = 1.0, carbsButtonIncrement1 = 5, carbsButtonIncrement2 = 10
    )

    private fun status(time: Long, iob: String = "0.00") = EventData.Status(
        dataset = 0, externalStatus = "", iobSum = iob, iobDetail = "", cob = "", currentBasal = "", battery = "", rigBattery = "",
        openApsStatus = time, bgi = "", batteryLevel = 1, tempTarget = "", tempTargetLevel = 0, reservoirString = "", reservoir = 0.0, reservoirLevel = 0
    )

    private fun UnchangedToWearFilter.shouldSend(event: EventData, now: Long) = messageToSend(event, now) != null

    @Test
    fun theMessageToSendIsTheWholeEvent() {
        // Also the time of the preferences goes out, it only does not count in the compare
        val prefs = preferences(t0)
        assertThat(filter.messageToSend(prefs, t0)).isEqualTo(prefs.serialize())
    }

    @Test
    fun theSameStateIsSentOnce() {
        assertThat(filter.shouldSend(EventData.SceneList(arrayListOf()), t0)).isTrue()
        assertThat(filter.shouldSend(EventData.SceneList(arrayListOf()), t0 + 30_000)).isFalse()
    }

    @Test
    fun aChangedStateIsSent() {
        assertThat(filter.shouldSend(graph(100.0), t0)).isTrue()
        assertThat(filter.shouldSend(graph(100.0, 104.0), t0 + 300_000)).isTrue()
    }

    @Test
    fun aCorrectedValueWithTheSameTimeIsSent() {
        // SingleBg.equals looks only at time and colour; the filter compares the whole content
        assertThat(filter.shouldSend(graph(100.0), t0)).isTrue()
        assertThat(filter.shouldSend(graph(104.0), t0 + 1000)).isTrue()
    }

    @Test
    fun theBgAlwaysGoesOut() {
        // Glucose is never held back: sending it again repairs a value that was handed over but never arrived
        repeat(3) { assertThat(filter.shouldSend(bg(t0, 100.0), t0 + it * 1000L)).isTrue() }
        repeat(3) { assertThat(filter.shouldSend(bg(t0, 100.0, dataset = 1), t0)).isTrue() }
    }

    @Test
    fun theTimeThePreferencesWereSentDoesNotCount() {
        assertThat(filter.shouldSend(preferences(t0), t0)).isTrue()
        assertThat(filter.shouldSend(preferences(t0 + 60_000), t0 + 60_000)).isFalse()
        assertThat(filter.shouldSend(preferences(t0 + 120_000, wearControl = false), t0 + 120_000)).isTrue()
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
        val status = status(t0)
        repeat(3) { assertThat(filter.shouldSend(status, t0 + it * 60_000L)).isTrue() }
    }

    @Test
    fun resetSendsEverythingAgain() {
        filter.shouldSend(EventData.SceneList(arrayListOf()), t0)
        filter.reset()
        assertThat(filter.shouldSend(EventData.SceneList(arrayListOf()), t0 + 1000)).isTrue()
    }

    /**
     * What the filter alone saves, with the resends as the phone sends them today. A model of 30 minutes
     * (6 BG cycles of 5 minutes), measured on a phone: 5 resends per cycle, at 12, 26, 31, 53 and 57 s
     * after the BG (new BG, loop result, NS, a new TBR, NS). Each resend is the 10 messages of
     * `DataHandlerMobile.resendData`. What changes: the BG and the graph with each new BG; the
     * treatments (predictions, TBR) at the new BG, the loop result and the new TBR; the status every
     * time. The preferences, quick wizard, actions and scenes do not change.
     *
     * The sizes are those of this model: a 6 hour graph (72 values), 30 treatments and 48 predictions.
     */
    @Test
    fun theFilterAloneSavesThisInABgCycle() {
        val resendOffsets = longArrayOf(12, 26, 31, 53, 57)
        val treatmentsChangeAt = setOf(0, 1, 3)  // new BG, loop result, new TBR
        val sentWith = Counter()
        val sentWithout = Counter()
        for (cycle in 0..6) {
            val bgTime = t0 + cycle * 300_000L
            resendOffsets.forEachIndexed { resend, offset ->
                val now = bgTime + offset * 1000
                val treatmentVersion = cycle * 10 + treatmentsChangeAt.count { it <= resend }
                val messages = listOf(
                    bg(bgTime, 120.0 + cycle),
                    preferences(now),
                    EventData.QuickWizard(arrayListOf()),
                    EventData.UserAction(arrayListOf()),
                    EventData.SceneList(arrayListOf()),
                    EventData.ActiveSceneState(active = false),
                    EventData.GraphData(ArrayList(List(72) { bg(bgTime - (71 - it) * 300_000L, 100.0 + (it + cycle) % 40) })),
                    treatments(bgTime, treatmentVersion),
                    status(now, iob = "1.${resend}${cycle}"),
                    EventData.RunningModeList(now, emptyList())
                )
                // Cycle 0 fills the filter; the 6 cycles after it are counted
                messages.forEach { event ->
                    val message = filter.messageToSend(event, now)
                    if (cycle > 0) {
                        sentWithout.add(event.serialize())
                        message?.let { sentWith.add(it) }
                    }
                }
            }
        }
        // 6 cycles x 5 resends x 10 messages
        assertThat(sentWithout.messages).isEqualTo(300)
        // Per cycle: BG, status and running modes 5 times, the graph once, the treatments 3 times, and the
        // 5 unchanged kinds again every 10 minutes (in 3 of the 6 cycles)
        assertThat(sentWith.messages).isEqualTo(6 * (15 + 1 + 3) + 3 * 5)
        // Bytes of payload in this model: 489660 without the filter, 209397 (43 %) with it
        assertThat(sentWithout.bytes).isEqualTo(489_660L)
        assertThat(sentWith.bytes).isEqualTo(209_397L)
    }

    private fun treatments(bgTime: Long, version: Int) = EventData.TreatmentData(
        temps = ArrayList(List(10) { EventData.TreatmentData.TempBasal(bgTime - it * 1_800_000L, 0.8, bgTime - it * 1_800_000L + 1_200_000L, 0.8, 1.2 + version % 3) }),
        basals = ArrayList(List(6) { EventData.TreatmentData.Basal(bgTime - (it + 1) * 3_600_000L, bgTime - it * 3_600_000L, 0.8) }),
        boluses = ArrayList(List(30) { EventData.TreatmentData.Treatment(bgTime - it * 600_000L, 0.3, 0.0, isSMB = it > 3, isValid = true) }),
        predictions = ArrayList(List(48) { bg(bgTime + it * 300_000L, 120.0 + (it * version) % 30) })
    )

    private class Counter {

        var messages = 0
        var bytes = 0L

        fun add(message: String) {
            messages++
            bytes += message.toByteArray().size
        }
    }
}
