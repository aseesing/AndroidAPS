package app.aaps.wear.comm

import android.content.Intent
import android.net.Uri
import android.os.Looper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchLogProtocol
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.selfupdate.WatchAppSelfUpdate
import app.aaps.wear.watchfaces.WatchFacePushHelper
import app.aaps.wear.watchlog.WatchLogUpload
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class DataLayerListenerServiceWearTest {

    private val rxBus: RxBus = mock()
    private val watchAppSelfUpdate: WatchAppSelfUpdate = mock()
    private val watchLogUpload: WatchLogUpload = mock()
    private val watchFacePushHelper: WatchFacePushHelper = mock()

    private fun service(): DataLayerListenerServiceWear =
        Robolectric.buildService(DataLayerListenerServiceWear::class.java).get().also {
            it.rxBus = rxBus
            it.aapsLogger = AAPSLoggerTest()
            it.sp = mock<SP>()
            it.aapsSchedulers = mock<AapsSchedulers>()
            it.watchAppSelfUpdate = watchAppSelfUpdate
            it.watchLogUpload = watchLogUpload
            it.watchFacePushHelper = watchFacePushHelper
        }

    private fun rxMessage(rxPath: String, node: String) = mock<MessageEvent> {
        on { path } doReturn rxPath
        on { data } doReturn EventData.ActionResendData("test").serialize().toByteArray()
        on { sourceNodeId } doReturn node
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
    fun `a channel from the phone goes to the watch app update and to the watch log`() {
        val sut = service()
        val channel = mock<ChannelClient.Channel>()

        sut.onChannelOpened(channel)

        verify(watchAppSelfUpdate).onChannelOpened(channel)
        verify(watchLogUpload).onChannelOpened(channel)
    }

    // PERSONAL PATCH: the status at the start of the app, see StartupStatusReport
    @Test
    fun `the first message from the phone sends the status once`() {
        val sut = service()
        val rxPath = sut.getString(app.aaps.core.interfaces.R.string.path_rx_bridge)

        sut.onMessageReceived(rxMessage(rxPath, "node-1"))
        sut.onMessageReceived(rxMessage(rxPath, "node-1"))
        shadowOf(Looper.getMainLooper()).idle()

        verifyBlocking(watchFacePushHelper, times(1)) { reportStatus() }
    }

    // PERSONAL PATCH: a channel reaches the service only through a CHANNEL_EVENT filter with its path
    @Test
    fun `the manifest gives the service a channel on every path the watch reads`() {
        val context = RuntimeEnvironment.getApplication()
        val paths = listOf(
            WatchAppUpdateProtocol.PATH,
            WatchLogProtocol.PATH,
            context.getString(app.aaps.core.interfaces.R.string.path_wff_face)
        )
        paths.forEach { path ->
            assertWithMessage(path).that(channelServices(path)).contains(DataLayerListenerServiceWear::class.java.name)
        }
        // The match is by path: a filter without data (there is one) matches no channel at all
        assertThat(channelServices("/not/a/channel/of/aaps")).isEmpty()
    }

    /** The services that get a channel event for [path], the way Google Play services addresses it. */
    private fun channelServices(path: String): List<String> {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(CHANNEL_EVENT, Uri.parse("wear://phone-node$path")).setPackage(context.packageName)
        return context.packageManager.queryIntentServices(intent, 0).map { it.serviceInfo.name }
    }

    private companion object {

        const val CHANNEL_EVENT = "com.google.android.gms.wearable.CHANNEL_EVENT"
    }
}
