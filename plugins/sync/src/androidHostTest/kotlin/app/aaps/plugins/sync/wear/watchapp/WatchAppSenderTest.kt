package app.aaps.plugins.sync.wear.watchapp

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The phone side of the watch app update, against a fake watch on the other end of two pipes.
 * Real time: the sender works on blocking streams and a watchdog, so the timeouts are short.
 */
class WatchAppSenderTest {

    private val body = ByteArray(300_000) { (it * 7).toByte() }
    private val app = EmbeddedWatchApp("4.0.1 (wear.apk)", body.size.toLong())

    private val source = object : EmbeddedWatchAppSource {
        override fun info() = app
        override fun open(): InputStream = ByteArrayInputStream(body)
    }

    private val states = CopyOnWriteArrayList<WatchAppUpdateState>()

    /** A channel whose far end runs [watch] in its own thread, like the watch app would */
    private class FakeChannel(pipeSize: Int, watch: (input: InputStream, output: OutputStream) -> Unit) : WatchChannel {

        private val phoneOut = PipedOutputStream()
        private val watchIn = PipedInputStream(phoneOut, pipeSize)
        private val watchOut = PipedOutputStream()
        private val phoneIn = PipedInputStream(watchOut, pipeSize)
        override val watchName = "Test Watch"
        override val input: InputStream = phoneIn
        override val output: OutputStream = phoneOut

        init {
            thread(isDaemon = true) {
                try {
                    watch(watchIn, watchOut)
                } catch (_: IOException) {
                    // The phone closed the channel
                }
            }
        }

        override fun close() {
            // Closing the writing ends wakes both readers and both writers
            runCatching { phoneOut.close() }
            runCatching { watchOut.close() }
        }
    }

    private fun sender(source: EmbeddedWatchAppSource = this.source, opener: WatchChannelOpener) =
        WatchAppSender(source, opener, answerTimeoutMs = 500, stallTimeoutMs = 500, installTimeoutMs = 500)

    private fun channel(pipeSize: Int = 1024 * 1024, watch: (InputStream, OutputStream) -> Unit) =
        WatchChannelOpener { path ->
            assertThat(path).isEqualTo(WatchAppUpdateProtocol.PATH)
            FakeChannel(pipeSize, watch)
        }

    @Test
    fun `a watch that takes the update gets the whole body and the phone reports it sent`() = runBlocking {
        val received = ByteArrayOutputStream()
        var label = ""

        val result = sender(opener = channel { input, output ->
            val header = WatchAppUpdateProtocol.readHeader(input)
            label = header.label
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, received, header.bodySize)
            WatchAppUpdateProtocol.writeReply(output, Reply.Committed)
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Sent("Test Watch"))
        assertThat(received.toByteArray()).isEqualTo(body)
        assertThat(label).isEqualTo(app.label)
        assertThat(states.first()).isEqualTo(WatchAppUpdateState.Connecting)
        assertThat(states).contains(WatchAppUpdateState.Sending("Test Watch", 100))
        assertThat(states).contains(WatchAppUpdateState.Installing("Test Watch"))
        assertThat(states.last()).isEqualTo(result)
        val percents = states.filterIsInstance<WatchAppUpdateState.Sending>().map { it.percent }
        assertThat(percents).isInStrictOrder()
    }

    @Test
    fun `a watch app without the update feature never answers`() = runBlocking {
        val result = sender(opener = channel { _, _ -> Thread.sleep(5_000) }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_ANSWER))
        assertThat(states.last()).isEqualTo(result)
    }

    @Test
    fun `a watch that refuses at the start sends no body`() = runBlocking {
        var bodyBytes = 0
        val result = sender(opener = channel { input, output ->
            WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.Failed("Cannot start the install"))
            bodyBytes = input.available()
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.WATCH_ERROR, "Cannot start the install"))
        assertThat(bodyBytes).isEqualTo(0)
        assertThat(states.none { it is WatchAppUpdateState.Sending }).isTrue()
    }

    @Test
    fun `a watch that refuses after the body reports its reason`() = runBlocking {
        val result = sender(opener = channel { input, output ->
            val header = WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, OutputStream.nullOutputStream(), header.bodySize)
            WatchAppUpdateProtocol.writeReply(output, Reply.Failed("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.WATCH_ERROR, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
    }

    @Test
    fun `a watch that stops reading in the middle times out`() = runBlocking {
        val result = sender(opener = channel(pipeSize = 16 * 1024) { input, output ->
            WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            input.read(ByteArray(1000))
            Thread.sleep(5_000)
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.TIMEOUT))
    }

    @Test
    fun `a watch that never finishes the install times out`() = runBlocking {
        val result = sender(opener = channel { input, output ->
            val header = WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, OutputStream.nullOutputStream(), header.bodySize)
            Thread.sleep(5_000)
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.TIMEOUT))
        assertThat(states).contains(WatchAppUpdateState.Installing("Test Watch"))
    }

    @Test
    fun `no connected watch`() = runBlocking {
        val result = sender(opener = { null }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_WATCH))
    }

    @Test
    fun `an error while connecting counts as no watch`() = runBlocking {
        val result = sender(opener = { throw IllegalStateException("Wearable API unavailable") }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_WATCH, "Wearable API unavailable"))
    }

    @Test
    fun `a build without a watch app does not connect`() = runBlocking {
        var opened = false
        val empty = object : EmbeddedWatchAppSource {
            override fun info(): EmbeddedWatchApp? = null
            override fun open(): InputStream = throw AssertionError("must not be opened")
        }

        val result = sender(source = empty, opener = { opened = true; null }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NOT_EMBEDDED))
        assertThat(opened).isFalse()
    }
}
