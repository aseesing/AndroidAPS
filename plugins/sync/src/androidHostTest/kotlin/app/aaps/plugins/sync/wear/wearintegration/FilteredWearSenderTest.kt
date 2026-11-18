package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FilteredWearSenderTest {

    private val filter = UnchangedToWearFilter()
    private var now = 1_700_000_000_000L

    /** What the transport was given, and how it answers. */
    private val sent = mutableListOf<String>()
    private var watchSelected = true
    private var failure: (() -> Unit)? = null
    private var throwOnSend = false

    private val sender = FilteredWearSender(
        filter,
        { message, onFailure ->
            if (throwOnSend) throw IllegalStateException("send failed at once")
            sent += message
            failure = onFailure
            watchSelected
        },
        { now }
    )

    private val scenes = EventData.SceneList(arrayListOf())

    private fun sendTwice(event: EventData) {
        sender.send(event)
        now += 1000
        sender.send(event)
    }

    @Test
    fun `the same state goes out once`() {
        sendTwice(scenes)
        assertThat(sent).hasSize(1)
    }

    @Test
    fun `after a failed send the same state goes out again`() {
        sender.send(scenes)
        failure!!.invoke() // the Data Layer reports the failure later
        now += 1000
        sender.send(scenes)
        assertThat(sent).hasSize(2)
    }

    @Test
    fun `without a selected watch the same state goes out again`() {
        watchSelected = false
        sender.send(scenes)
        watchSelected = true
        now += 1000
        sender.send(scenes)
        assertThat(sent).hasSize(2)
    }

    @Test
    fun `after a send that throws the same state goes out again`() {
        throwOnSend = true
        assertThrows<IllegalStateException> { sender.send(scenes) }
        throwOnSend = false
        now += 1000
        sender.send(scenes)
        assertThat(sent).hasSize(1)
    }

    @Test
    fun `a changed state always goes out`() {
        sender.send(EventData.QuickWizard(arrayListOf()))
        sender.send(EventData.QuickWizard(arrayListOf(EventData.QuickWizard.QuickWizardEntry("guid", "Lunch", 40, 0, 86_400))))
        assertThat(sent).hasSize(2)
    }

    @Test
    fun `the BG always goes out`() {
        sendTwice(EventData.SingleBg(dataset = 0, timeStamp = now, sgv = 100.0, high = 180.0, low = 70.0))
        assertThat(sent).hasSize(2)
    }

    @Test
    fun `the transport gets the serialized event`() {
        sender.send(scenes)
        assertThat(sent.single()).isEqualTo(scenes.serialize())
    }
}
