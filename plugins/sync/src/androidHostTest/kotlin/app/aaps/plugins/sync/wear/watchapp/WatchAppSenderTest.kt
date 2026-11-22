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
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The phone side of the watch app update, against a fake watch on the other end of two pipes.
 * Real time: the sender works on blocking streams and a watchdog. The timeouts are short, but long
 * enough that a slow machine does not hit them where the fake watch answers at once.
 */
class WatchAppSenderTest {

    private val body = ByteArray(300_000) { (it * 7).toByte() }
    private val app = EmbeddedWatchApp("4.0.1 (wear.apk)", body.size.toLong(), body.size * 3L)

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

    private fun sender(source: EmbeddedWatchAppSource = this.source, opener: WatchChannelOpener, wifiServers: WifiBodyServerFactory? = null) =
        WatchAppSender(
            source, opener, wifiServers,
            answerTimeoutMs = TIMEOUT_MS, stallTimeoutMs = TIMEOUT_MS, installTimeoutMs = TIMEOUT_MS, wifiTimeoutMs = WIFI_TIMEOUT_MS
        )

    /** A real server, on the loopback address of the test machine */
    private val loopbackServer = WifiBodyServerFactory { SocketWifiBodyServer(listOf("127.0.0.1"), InetAddress.getLoopbackAddress()) }

    private fun channel(pipeSize: Int = 1024 * 1024, watch: (InputStream, OutputStream) -> Unit) =
        WatchChannelOpener { path ->
            assertThat(path).isEqualTo(WatchAppUpdateProtocol.PATH)
            FakeChannel(pipeSize, watch)
        }

    /**
     * Reads until the phone closes the channel, and returns how many bytes came.
     *
     * A fake watch that answers before it has read all the phone writes must wait here, and not end
     * its thread: a pipe fails the next write ("Read end dead") once the thread that read from it has
     * ended. The phone would then see an error instead of the answer.
     */
    private fun readUntilClosed(input: InputStream): Long {
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return count
            count += read
        }
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
        // Sending shows at once, before the first block is through
        assertThat(states[1]).isEqualTo(WatchAppUpdateState.Sending("Test Watch", 0))
        assertThat(states.filterIsInstance<WatchAppUpdateState.Sending>().last().percent).isEqualTo(100)
        assertThat(states).contains(WatchAppUpdateState.Installing("Test Watch"))
        assertThat(states.last()).isEqualTo(result)
        val percents = states.filterIsInstance<WatchAppUpdateState.Sending>().map { it.percent }
        assertThat(percents).isInStrictOrder()
    }

    @Test
    fun `a watch app without the update feature never answers`() = runBlocking {
        val result = sender(opener = channel { _, _ -> Thread.sleep(SLOW_WATCH_MS) }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.NO_ANSWER))
        assertThat(states.last()).isEqualTo(result)
    }

    @Test
    fun `a watch that refuses at the start sends no body`() = runBlocking {
        val bodyBytes = AtomicLong(-1)
        val watchDone = CountDownLatch(1)
        val result = sender(opener = channel { input, output ->
            try {
                WatchAppUpdateProtocol.readHeader(input)
                WatchAppUpdateProtocol.writeReply(output, Reply.Failed("Cannot start the install"))
                // All that the phone sends after the refusal, until it closes the channel
                bodyBytes.set(readUntilClosed(input))
            } finally {
                watchDone.countDown()
            }
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.WATCH_ERROR, "Cannot start the install"))
        assertThat(watchDone.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(bodyBytes.get()).isEqualTo(0)
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
            Thread.sleep(SLOW_WATCH_MS)
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.TIMEOUT))
    }

    @Test
    fun `a watch that never says whether the install started asks the user to look on the watch`() = runBlocking {
        val result = sender(opener = channel { input, output ->
            val header = WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, OutputStream.nullOutputStream(), header.bodySize)
            Thread.sleep(SLOW_WATCH_MS)
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.CHECK_WATCH))
        assertThat(states).contains(WatchAppUpdateState.Installing("Test Watch"))
    }

    @Test
    fun `a channel that closes after the body is not taken as success`() = runBlocking {
        val result = sender(opener = channel { input, output ->
            val header = WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, OutputStream.nullOutputStream(), header.bodySize)
            output.close()
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.CHECK_WATCH))
    }

    @Test
    fun `a watch that is busy with another update says so`() = runBlocking {
        val result = sender(opener = channel { _, output ->
            WatchAppUpdateProtocol.writeReply(output, Reply.Failed(WatchAppUpdateProtocol.BUSY))
        }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.BUSY))
    }

    @Test
    fun `a watch app with an older update format is told to be installed with adb once`() = runBlocking {
        val result = sender(opener = channel { input, output ->
            // What a watch app with an older version answers to this header
            WatchAppUpdateProtocol.writeReply(output, Reply.Failed("Wrong update: ${WatchAppUpdateProtocol.UNKNOWN_FORMAT} 3, expected 2"))
            readUntilClosed(input)
        }).send { states += it }

        assertThat((result as WatchAppUpdateState.Failed).reason).isEqualTo(WatchAppUpdateState.Reason.OLD_WATCH_APP)
    }

    @Test
    fun `a watch app with a newer update format asks for a newer phone app`() = runBlocking {
        val result = sender(opener = channel { input, output ->
            // What a watch app with a newer version answers to this header
            WatchAppUpdateProtocol.writeReply(output, Reply.Failed("Wrong update: ${WatchAppUpdateProtocol.OLD_FORMAT} 3, expected 4"))
            readUntilClosed(input)
        }).send { states += it }

        assertThat((result as WatchAppUpdateState.Failed).reason).isEqualTo(WatchAppUpdateState.Reason.OLD_PHONE_APP)
    }

    @Test
    fun `the header carries the size of the APK`() = runBlocking {
        var apkSize = 0L
        sender(opener = channel { input, output ->
            apkSize = WatchAppUpdateProtocol.readHeader(input).apkSize
            WatchAppUpdateProtocol.writeReply(output, Reply.Failed("stop"))
        }).send { states += it }

        assertThat(apkSize).isEqualTo(app.apkSize)
    }

    @Test
    fun `the time left comes from the speed so far`() = runBlocking {
        // A slow link: every reading of the clock is one second later
        val clock = AtomicLong(0)
        val result = WatchAppSender(source, channel { input, output ->
            val header = WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, OutputStream.nullOutputStream(), header.bodySize)
            WatchAppUpdateProtocol.writeReply(output, Reply.Committed)
        }, answerTimeoutMs = 60_000_000, stallTimeoutMs = 60_000_000, installTimeoutMs = 60_000_000, nowMs = { clock.getAndAdd(1000) }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Sent("Test Watch"))
        val sending = states.filterIsInstance<WatchAppUpdateState.Sending>()
        assertThat(sending.first().secondsLeft).isNull()
        val estimates = sending.mapNotNull { it.secondsLeft }
        assertThat(estimates).isNotEmpty()
        assertThat(estimates.last()).isEqualTo(0)
    }

    @Test
    fun `a watch on Wi-Fi fetches the body from the phone's server`() = runBlocking {
        val received = ByteArrayOutputStream()
        var header: WatchAppUpdateProtocol.Header? = null

        val result = sender(opener = channel { input, output ->
            val read = WatchAppUpdateProtocol.readHeader(input)
            header = read
            WatchAppUpdateProtocol.writeReply(output, Reply.WifiStarted)
            val offer = checkNotNull(read.wifi)
            Socket(offer.addresses.first(), offer.port).use { socket ->
                WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), offer.token)
                WatchAppUpdateProtocol.copyBody(socket.getInputStream(), received, read.bodySize)
            }
            WatchAppUpdateProtocol.writeReply(output, Reply.Committed)
        }, wifiServers = loopbackServer).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Sent("Test Watch"))
        assertThat(received.toByteArray()).isEqualTo(body)
        assertThat(header?.sha256).isEqualTo(WatchAppUpdateProtocol.sha256(ByteArrayInputStream(body)))
        assertThat(header?.wifi?.token).hasLength(32)
        val sending = states.filterIsInstance<WatchAppUpdateState.Sending>()
        assertThat(sending.all { it.overWifi }).isTrue()
        assertThat(sending.last().percent).isEqualTo(100)
        assertThat(states).contains(WatchAppUpdateState.Installing("Test Watch"))
        assertThat(states.last()).isEqualTo(result)
    }

    @Test
    fun `when Wi-Fi does not work on the watch the body comes over the channel`() = runBlocking {
        val received = ByteArrayOutputStream()

        val result = sender(opener = channel { input, output ->
            val header = WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.WifiStarted)
            // No Wi-Fi on the watch: it never connects, and asks for the channel
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, received, header.bodySize)
            WatchAppUpdateProtocol.writeReply(output, Reply.Committed)
        }, wifiServers = loopbackServer).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Sent("Test Watch"))
        assertThat(received.toByteArray()).isEqualTo(body)
        val sending = states.filterIsInstance<WatchAppUpdateState.Sending>()
        assertThat(sending.first().overWifi).isTrue()
        assertThat(sending.last()).isEqualTo(sending.last().copy(percent = 100, overWifi = false))
    }

    @Test
    fun `when somebody else blocks the Wi-Fi server, the body comes over the channel`() = runBlocking {
        val received = ByteArrayOutputStream()

        val result = sender(opener = channel { input, output ->
            val header = WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.WifiStarted)
            val offer = checkNotNull(header.wifi)
            // Another device knocks with wrong tokens until the server gives up
            repeat(SocketWifiBodyServer.MAX_WRONG_REQUESTS) {
                Socket(offer.addresses.first(), offer.port).use { socket ->
                    // Also the write may fail: the server may have closed this connection already
                    runCatching {
                        WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), "wrong")
                        socket.getInputStream().read()
                    }
                }
            }
            // The watch cannot connect any more, and asks for the channel
            // (the server closes right after the last wrong request; allow it a moment)
            val refused = (1..30).any {
                runCatching { Socket(offer.addresses.first(), offer.port).close() }.isFailure.also { closed -> if (!closed) Thread.sleep(100) }
            }
            check(refused) { "the server is still open" }
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, received, header.bodySize)
            WatchAppUpdateProtocol.writeReply(output, Reply.Committed)
        }, wifiServers = loopbackServer).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Sent("Test Watch"))
        assertThat(received.toByteArray()).isEqualTo(body)
    }

    @Test
    fun `a phone without Wi-Fi offers none`() = runBlocking {
        var offer: WatchAppUpdateProtocol.WifiOffer? = WatchAppUpdateProtocol.WifiOffer(emptyList(), 1, "")

        val result = sender(opener = channel { input, output ->
            val header = WatchAppUpdateProtocol.readHeader(input)
            offer = header.wifi
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, OutputStream.nullOutputStream(), header.bodySize)
            WatchAppUpdateProtocol.writeReply(output, Reply.Committed)
        }, wifiServers = { null }).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Sent("Test Watch"))
        assertThat(offer).isNull()
    }

    @Test
    fun `a watch that stops on Wi-Fi and never answers times out`() = runBlocking {
        val result = sender(opener = channel { input, output ->
            WatchAppUpdateProtocol.readHeader(input)
            WatchAppUpdateProtocol.writeReply(output, Reply.WifiStarted)
            Thread.sleep(SLOW_WATCH_MS)
        }, wifiServers = loopbackServer).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.TIMEOUT))
    }

    @Test
    fun `no estimate at the very start`() {
        // Under 3 percent sent
        assertThat(WatchAppSender.secondsLeft(copied = 2, size = 100, elapsedMs = 60_000)).isNull()
        // Under 3 seconds gone
        assertThat(WatchAppSender.secondsLeft(copied = 50, size = 100, elapsedMs = 2_000)).isNull()
        assertThat(WatchAppSender.secondsLeft(copied = 0, size = 100, elapsedMs = 60_000)).isNull()
    }

    @Test
    fun `half sent after a minute leaves a minute`() {
        assertThat(WatchAppSender.secondsLeft(copied = 50, size = 100, elapsedMs = 60_000)).isEqualTo(60)
        assertThat(WatchAppSender.secondsLeft(copied = 25, size = 100, elapsedMs = 60_000)).isEqualTo(180)
        assertThat(WatchAppSender.secondsLeft(copied = 100, size = 100, elapsedMs = 60_000)).isEqualTo(0)
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

    companion object {

        /** The timeouts of the sender in these tests. A fake watch that must be too slow sleeps [SLOW_WATCH_MS]. */
        private const val TIMEOUT_MS = 2_000L
        private const val WIFI_TIMEOUT_MS = 4_000L
        private const val SLOW_WATCH_MS = 3 * WIFI_TIMEOUT_MS
    }
}
