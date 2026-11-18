package app.aaps.wear.watchlog

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchLogProtocol
import app.aaps.shared.impl.weardata.WatchLogProtocol.Reply
import app.aaps.shared.impl.weardata.WatchLogProtocol.Request
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.selfupdate.PhoneChannelStreams
import com.google.android.gms.wearable.ChannelClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** One log download at a time on the watch: a second one gets a busy reply, and the lock and the zip are released */
internal class WatchLogUploadTest {

    @get:Rule val folder = TemporaryFolder()

    /** Streams per channel; the input of a channel waits until the test gives it */
    private class FakeStreams : PhoneChannelStreams {

        val inputs = ConcurrentHashMap<ChannelClient.Channel, CompletableDeferred<InputStream>>()
        val outputs = ConcurrentHashMap<ChannelClient.Channel, ByteArrayOutputStream>()
        val closed: MutableSet<ChannelClient.Channel> = ConcurrentHashMap.newKeySet()

        override suspend fun input(channel: ChannelClient.Channel): InputStream = inputs.getOrPut(channel) { CompletableDeferred() }.await()
        override suspend fun output(channel: ChannelClient.Channel): OutputStream = outputs.getOrPut(channel) { ByteArrayOutputStream() }
        override fun close(channel: ChannelClient.Channel) {
            closed += channel
        }
    }

    private val streams = FakeStreams()

    private fun channel(path: String = WatchLogProtocol.PATH): ChannelClient.Channel = mock { on { this.path } doReturn path }

    private fun request(): InputStream =
        ByteArrayInputStream(ByteArrayOutputStream().also { WatchLogProtocol.writeRequest(it, Request(0, 10)) }.toByteArray())

    private fun replies(channel: ChannelClient.Channel): List<Reply> {
        val input = ByteArrayInputStream(streams.outputs.getValue(channel).toByteArray())
        return buildList {
            while (input.available() > 0) {
                val reply = WatchLogProtocol.readReply(input)
                add(reply)
                if (reply is Reply.ChannelBody) input.skip(reply.zip.size)
            }
        }
    }

    @Test
    fun `a second download while one runs gets a busy reply, and the next one runs again`() = runBlocking {
        val zipFile = folder.newFile("watch_log.zip")
        val zipper = LogZipSource {
            zipFile.writeBytes(ByteArray(100))
            LogZip(zipFile, WatchLogProtocol.Zip(100, WatchAppUpdateProtocol.sha256(ByteArrayInputStream(ByteArray(100)))), 1)
        }
        val sut = WatchLogUpload(AAPSLoggerTest(), streams, { throw AssertionError("no Wi-Fi") }, zipper, zipFile, CoroutineScope(SupervisorJob() + Dispatchers.IO), 0)

        assertThat(sut.onChannelOpened(channel("/aaps/watch_app_update"))).isNull()

        val first = channel()
        val firstJob = checkNotNull(sut.onChannelOpened(first))
        val second = channel()
        withTimeout(5_000) { checkNotNull(sut.onChannelOpened(second)).join() }
        assertThat(replies(second)).containsExactly(Reply.Failed(WatchLogProtocol.BUSY))
        assertThat(streams.closed).contains(second)

        // The first one runs and ends: the zip is deleted and the lock is released
        streams.inputs.getOrPut(first) { CompletableDeferred() }.complete(request())
        withTimeout(5_000) { firstJob.join() }
        assertThat(replies(first).first()).isEqualTo(Reply.Accepted)
        assertThat(replies(first)[1]).isInstanceOf(Reply.ChannelBody::class.java)
        assertThat(zipFile.exists()).isFalse()
        assertThat(streams.closed).contains(first)

        val third = channel()
        streams.inputs[third] = CompletableDeferred(request())
        withTimeout(5_000) { checkNotNull(sut.onChannelOpened(third)).join() }
        assertThat(replies(third).first()).isEqualTo(Reply.Accepted)
    }
}
