package app.aaps.wear.selfupdate

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import app.aaps.wear.AAPSLoggerTest
import com.google.android.gms.wearable.ChannelClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** One update at a time on the watch: a second one is refused with a reply, and the lock is released */
internal class WatchAppSelfUpdateTest {

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
    private val sut = WatchAppSelfUpdate(
        aapsLogger = AAPSLoggerTest(),
        streams = streams,
        sessions = { throw AssertionError("no install in this test") },
        notifier = mock(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        closeDelayMs = 0
    )

    private fun channel(path: String = WatchAppUpdateProtocol.PATH): ChannelClient.Channel = mock { on { this.path } doReturn path }

    private fun reply(channel: ChannelClient.Channel): Reply =
        WatchAppUpdateProtocol.readReply(ByteArrayInputStream(streams.outputs.getValue(channel).toByteArray()))

    @Test
    fun `another channel is not taken`() {
        assertThat(sut.onChannelOpened(channel("/some/other/path"))).isNull()
    }

    @Test
    fun `a second update while one runs gets a busy reply, and the next one runs again`() = runBlocking {
        val first = channel()
        val firstJob = checkNotNull(sut.onChannelOpened(first))

        val second = channel()
        withTimeout(5_000) { checkNotNull(sut.onChannelOpened(second)).join() }
        assertThat(reply(second)).isEqualTo(Reply.Failed(WatchAppUpdateProtocol.BUSY))
        assertThat(streams.closed).contains(second)

        // The first one ends (here with a stream that is not an update): the lock is released
        streams.inputs.getOrPut(first) { CompletableDeferred() }.complete(ByteArrayInputStream("hello watch".toByteArray()))
        withTimeout(5_000) { firstJob.join() }
        assertThat(reply(first)).isInstanceOf(Reply.Failed::class.java)
        assertThat(streams.closed).contains(first)

        val third = channel()
        streams.inputs[third] = CompletableDeferred(ByteArrayInputStream("hello again".toByteArray()))
        withTimeout(5_000) { checkNotNull(sut.onChannelOpened(third)).join() }
        assertThat(reply(third)).isNotEqualTo(Reply.Failed(WatchAppUpdateProtocol.BUSY))
    }

    @Test
    fun `the lock is released before the channel waits to close`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // A close delay much longer than the test: the next update must not wait for it
        val slow = WatchAppSelfUpdate(AAPSLoggerTest(), streams, { throw AssertionError("no install in this test") }, mock(), scope, 60_000)
        try {
            val first = channel()
            streams.inputs[first] = CompletableDeferred(ByteArrayInputStream("hello watch".toByteArray()))
            slow.onChannelOpened(first)
            assertThat(awaitReply(first)).isInstanceOf(Reply.Failed::class.java)

            // The first one may still be between its answer and the release: try again for a while
            var next: Reply
            val end = System.nanoTime() + 5_000_000_000L
            do {
                val channel = channel()
                streams.inputs[channel] = CompletableDeferred(ByteArrayInputStream("hello again".toByteArray()))
                slow.onChannelOpened(channel)
                next = awaitReply(channel)
            } while (next == Reply.Failed(WatchAppUpdateProtocol.BUSY) && System.nanoTime() < end)
            assertThat(next).isNotEqualTo(Reply.Failed(WatchAppUpdateProtocol.BUSY))
            assertThat(streams.closed).doesNotContain(first)
        } finally {
            scope.cancel()
        }
    }

    /** The first whole reply on [channel]; it may be written while the test reads */
    private suspend fun awaitReply(channel: ChannelClient.Channel): Reply =
        withTimeout(5_000) {
            var found: Reply? = null
            while (found == null) {
                found = streams.outputs[channel]?.let { runCatching { reply(channel) }.getOrNull() }
                if (found == null) delay(10)
            }
            found
        }
}
