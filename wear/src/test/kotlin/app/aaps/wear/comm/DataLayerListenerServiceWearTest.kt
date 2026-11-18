package app.aaps.wear.comm

import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.selfupdate.WatchAppSelfUpdate
import app.aaps.wear.watchlog.WatchLogUpload // PERSONAL PATCH: watch log download
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class DataLayerListenerServiceWearTest {

    private val rxBus: RxBus = mock()
    private val watchAppSelfUpdate: WatchAppSelfUpdate = mock()
    private val watchLogUpload: WatchLogUpload = mock() // PERSONAL PATCH: watch log download

    private fun service(): DataLayerListenerServiceWear =
        Robolectric.buildService(DataLayerListenerServiceWear::class.java).get().also {
            it.rxBus = rxBus
            it.aapsLogger = AAPSLoggerTest()
            it.sp = mock<SP>()
            it.aapsSchedulers = mock<AapsSchedulers>()
            it.watchAppSelfUpdate = watchAppSelfUpdate
            it.watchLogUpload = watchLogUpload // PERSONAL PATCH: watch log download
        }

    @Test
    fun `a command on the rx path is deserialized and republished tagged with the sender node`() {
        val sut = service()
        val rxPath = sut.getString(app.aaps.core.interfaces.R.string.path_rx_bridge)
        val command = EventData.ActionPing(1_000L)
        val message = mock<MessageEvent> {
            on { path } doReturn rxPath
            on { data } doReturn command.serialize().toByteArray()
            on { sourceNodeId } doReturn "node-1"
        }

        sut.onMessageReceived(message)

        val captor = argumentCaptor<Event>()
        verify(rxBus).send(captor.capture())
        val sent = captor.firstValue
        assertThat(sent).isInstanceOf(EventData.ActionPing::class.java)
        assertThat((sent as EventData).sourceNodeId).isEqualTo("node-1")
    }

    @Test
    fun `a message on an unknown path is ignored`() {
        val sut = service()
        val message = mock<MessageEvent> {
            on { path } doReturn "/some/other/path"
            on { data } doReturn ByteArray(0)
            on { sourceNodeId } doReturn "node-1"
        }

        sut.onMessageReceived(message)

        verify(rxBus, never()).send(any())
    }

    @Test
    fun `a channel from the phone goes to the watch app update`() {
        val sut = service()
        val channel = mock<ChannelClient.Channel>()

        sut.onChannelOpened(channel)

        verify(watchAppSelfUpdate).onChannelOpened(channel)
        verify(watchLogUpload).onChannelOpened(channel) // PERSONAL PATCH: watch log download
    }
}
