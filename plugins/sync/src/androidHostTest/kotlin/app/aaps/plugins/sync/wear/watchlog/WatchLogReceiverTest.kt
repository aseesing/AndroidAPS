package app.aaps.plugins.sync.wear.watchlog

import app.aaps.plugins.sync.wear.watchapp.SocketWifiBodyServer
import app.aaps.plugins.sync.wear.watchapp.WatchChannel
import app.aaps.plugins.sync.wear.watchapp.WatchChannelOpener
import app.aaps.plugins.sync.wear.watchapp.WifiBodyServerFactory
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
import app.aaps.shared.impl.weardata.WatchLogCrypto
import app.aaps.shared.impl.weardata.WatchLogProtocol
import app.aaps.shared.impl.weardata.WatchLogProtocol.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The phone side of the watch log download, against a fake watch on the other end of two pipes.
 * Real time: the receiver works on blocking streams and a watchdog. The timeouts are short, but long
 * enough that a slow machine does not hit them where the fake watch answers at once.
 */
class WatchLogReceiverTest {

    @TempDir lateinit var folder: File

    private val wifiZip = ByteArray(400_000) { (it * 7).toByte() }
    private val channelZip = ByteArray(50_000) { (it * 3).toByte() }
    private val states = CopyOnWriteArrayList<WatchLogState>()

    private fun info(bytes: ByteArray) = WatchLogProtocol.Zip(bytes.size.toLong(), WatchAppUpdateProtocol.sha256(ByteArrayInputStream(bytes)))

    /** A channel whose far end runs [watch] in its own thread, like the watch app would */
    private class FakeChannel(watch: (input: InputStream, output: OutputStream) -> Unit) : WatchChannel {

        private val phoneOut = PipedOutputStream()
        private val watchIn = PipedInputStream(phoneOut, 1024 * 1024)
        private val watchOut = PipedOutputStream()
        private val phoneIn = PipedInputStream(watchOut, 1024 * 1024)
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
            runCatching { phoneOut.close() }
            runCatching { watchOut.close() }
        }
    }

    private fun channel(watch: (InputStream, OutputStream) -> Unit) =
        WatchChannelOpener { path ->
            assertThat(path).isEqualTo(WatchLogProtocol.PATH)
            FakeChannel(watch)
        }

    /** A real server, on the loopback address of the test machine */
    private val loopbackServer = WifiBodyServerFactory { SocketWifiBodyServer(listOf("127.0.0.1"), InetAddress.getLoopbackAddress()) }

    private fun receiver(opener: WatchChannelOpener, wifiServers: WifiBodyServerFactory? = null) =
        WatchLogReceiver(
            opener, wifiServers,
            answerTimeoutMs = ANSWER_TIMEOUT_MS, prepareTimeoutMs = PREPARE_TIMEOUT_MS, stallTimeoutMs = STALL_TIMEOUT_MS, fallbackTimeoutMs = FALLBACK_TIMEOUT_MS
        )

    /** What the watch does first: read the request and accept it */
    private fun accept(input: InputStream, output: OutputStream): WatchLogProtocol.Request =
        WatchLogProtocol.readRequest(input).also { WatchLogProtocol.writeReply(output, Reply.Accepted) }

    /** The watch connects to the Wi-Fi server of the phone and writes [body] there, already encrypted */
    private fun sendOverWifi(offer: WifiOffer, output: OutputStream, body: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(offer.addresses.first(), offer.port))
            WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), offer.token)
            WatchLogProtocol.writeReply(output, Reply.WifiZip(body.size.toLong()))
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
        }
        WatchLogProtocol.writeReply(output, Reply.WifiDone)
    }

    /** The zip encrypted with the secret of [request], as the watch sends it over Wi-Fi */
    private fun encrypted(request: WatchLogProtocol.Request, zip: ByteArray): ByteArray = WatchLogCrypto.encrypt(zip, requireNotNull(request.secret))

    private val target get() = File(folder, "log.zip")

    @Test
    fun `without Wi-Fi the newest files come over the channel`() = runBlocking {
        var request: WatchLogProtocol.Request? = null

        val result = receiver(channel { input, output ->
            request = accept(input, output)
            WatchLogProtocol.writeReply(output, Reply.ChannelBody(info(channelZip)))
            output.write(channelZip)
            output.flush()
        }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Received("Test Watch", overWifi = false))
        assertThat(target.readBytes()).isEqualTo(channelZip)
        assertThat(request).isEqualTo(WatchLogProtocol.Request(WatchLogProtocol.ALL_FILES, WatchLogReceiver.FILES_OVER_CHANNEL, null))
        assertThat(states.first()).isEqualTo(WatchLogState.Connecting)
        assertThat(states).contains(WatchLogState.Preparing("Test Watch"))
        assertThat(states.filterIsInstance<WatchLogState.Receiving>().last().percent).isEqualTo(100)
        assertThat(states.last()).isEqualTo(result)
    }

    @Test
    fun `with Wi-Fi all files come over Wi-Fi, encrypted with a new secret`() = runBlocking {
        var request: WatchLogProtocol.Request? = null
        val result = receiver(channel { input, output ->
            val accepted = accept(input, output).also { request = it }
            sendOverWifi(requireNotNull(accepted.wifi), output, encrypted(accepted, wifiZip))
        }, loopbackServer).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Received("Test Watch", overWifi = true))
        assertThat(target.readBytes()).isEqualTo(wifiZip)
        assertThat(request?.secret).isNotNull()
        assertThat(states.filterIsInstance<WatchLogState.Receiving>().all { it.overWifi }).isTrue()
    }

    @Test
    fun `a zip over Wi-Fi from somebody without the secret is refused, nothing is saved`() = runBlocking {
        val result = receiver(channel { input, output ->
            val offer = requireNotNull(accept(input, output).wifi)
            // Encrypted with another key, as somebody else would have to
            sendOverWifi(offer, output, WatchLogCrypto.encrypt(wifiZip, WatchLogCrypto.newSecret()))
        }, loopbackServer).receive(target) { states += it }

        assertThat((result as WatchLogState.Failed).reason).isEqualTo(WatchLogState.Reason.ERROR)
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `when Wi-Fi breaks in the middle, the phone waits until the watch sends over the channel`() = runBlocking {
        // A real clock with short times: the transfer runs on blocking sockets in other threads
        val sut = WatchLogReceiver(
            channel { input, output ->
                val accepted = accept(input, output)
                val offer = requireNotNull(accepted.wifi)
                val body = encrypted(accepted, wifiZip)
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(offer.addresses.first(), offer.port))
                    WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), offer.token)
                    WatchLogProtocol.writeReply(output, Reply.WifiZip(body.size.toLong()))
                    socket.getOutputStream().write(body, 0, 1000)
                    socket.getOutputStream().flush()
                    // Wi-Fi is gone: the watch notices only after its own stall time, as long as the phone's
                    Thread.sleep(STALL_TIMEOUT_MS + STALL_TIMEOUT_MS / 2)
                }
                WatchLogProtocol.writeReply(output, Reply.ChannelBody(info(channelZip)))
                output.write(channelZip)
                output.flush()
            },
            loopbackServer,
            answerTimeoutMs = ANSWER_TIMEOUT_MS, prepareTimeoutMs = PREPARE_TIMEOUT_MS, stallTimeoutMs = STALL_TIMEOUT_MS, fallbackTimeoutMs = FALLBACK_TIMEOUT_MS
        )

        val result = sut.receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Received("Test Watch", overWifi = false))
        assertThat(target.readBytes()).isEqualTo(channelZip)
    }

    @Test
    fun `when Wi-Fi does not work on the watch the smaller zip comes over the channel`() = runBlocking {
        val result = receiver(channel { input, output ->
            accept(input, output)
            // The watch cannot reach the phone and gives up on Wi-Fi
            WatchLogProtocol.writeReply(output, Reply.ChannelBody(info(channelZip)))
            output.write(channelZip)
            output.flush()
        }, loopbackServer).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Received("Test Watch", overWifi = false))
        assertThat(target.readBytes()).isEqualTo(channelZip)
    }

    @Test
    fun `when somebody else blocks the Wi-Fi server, the zip comes over the channel`() = runBlocking {
        var refused = false
        val result = receiver(channel { input, output ->
            val offer = requireNotNull(accept(input, output).wifi)
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
            // The watch cannot connect any more, and sends over the channel
            // (the server closes right after the last wrong request; allow it a moment)
            refused = (1..30).any {
                runCatching { Socket(offer.addresses.first(), offer.port).close() }.isFailure.also { closed -> if (!closed) Thread.sleep(100) }
            }
            WatchLogProtocol.writeReply(output, Reply.ChannelBody(info(channelZip)))
            output.write(channelZip)
            output.flush()
        }, loopbackServer).receive(target) { states += it }

        assertThat(refused).isTrue()
        assertThat(result).isEqualTo(WatchLogState.Received("Test Watch", overWifi = false))
        assertThat(target.readBytes()).isEqualTo(channelZip)
    }

    @Test
    fun `a damaged zip over Wi-Fi is refused`() = runBlocking {
        val result = receiver(channel { input, output ->
            val accepted = accept(input, output)
            val damaged = encrypted(accepted, wifiZip).also { it[1000] = (it[1000] + 1).toByte() }
            sendOverWifi(requireNotNull(accepted.wifi), output, damaged)
        }, loopbackServer).receive(target) { states += it }

        assertThat(result).isInstanceOf(WatchLogState.Failed::class.java)
        assertThat((result as WatchLogState.Failed).reason).isEqualTo(WatchLogState.Reason.ERROR)
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `a damaged zip over the channel is refused`() = runBlocking {
        val result = receiver(channel { input, output ->
            accept(input, output)
            WatchLogProtocol.writeReply(output, Reply.ChannelBody(info(channelZip)))
            output.write(ByteArray(channelZip.size))
            output.flush()
        }).receive(target) { states += it }

        assertThat((result as WatchLogState.Failed).reason).isEqualTo(WatchLogState.Reason.ERROR)
    }

    @Test
    fun `a watch that cannot send its log tells why`() = runBlocking {
        val result = receiver(channel { input, output ->
            accept(input, output)
            WatchLogProtocol.writeReply(output, Reply.Failed("No log files"))
        }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Failed(WatchLogState.Reason.WATCH_ERROR, "No log files"))
    }

    @Test
    fun `a watch app without the feature never answers`() = runBlocking {
        val result = receiver(channel { _, _ -> Thread.sleep(SLOW_WATCH_MS) }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Failed(WatchLogState.Reason.NO_ANSWER))
        assertThat(states.last()).isEqualTo(result)
    }

    @Test
    fun `a watch that stops in the middle times out`() = runBlocking {
        val result = receiver(channel { input, output ->
            accept(input, output)
            WatchLogProtocol.writeReply(output, Reply.ChannelBody(info(channelZip)))
            output.write(channelZip, 0, 1000)
            output.flush()
            Thread.sleep(SLOW_WATCH_MS)
        }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Failed(WatchLogState.Reason.TIMEOUT))
    }

    @Test
    fun `a watch that accepted may take longer than the answer timeout to zip`() = runBlocking {
        val result = receiver(channel { input, output ->
            accept(input, output)
            // Longer than answerTimeoutMs, much shorter than prepareTimeoutMs
            Thread.sleep(ANSWER_TIMEOUT_MS + ANSWER_TIMEOUT_MS / 4)
            WatchLogProtocol.writeReply(output, Reply.ChannelBody(info(channelZip)))
            output.write(channelZip)
            output.flush()
        }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Received("Test Watch", overWifi = false))
    }

    @Test
    fun `a busy watch says so at once`() = runBlocking {
        val result = receiver(channel { _, output ->
            WatchLogProtocol.writeReply(output, Reply.Failed(WatchLogProtocol.BUSY))
        }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Failed(WatchLogState.Reason.BUSY))
    }

    @Test
    fun `a watch app with an older request format is told to be updated`() = runBlocking {
        val result = receiver(channel { _, output ->
            WatchLogProtocol.writeReply(output, Reply.Failed("Wrong log request: ${WatchLogProtocol.UNKNOWN_FORMAT} 2, expected 1"))
        }).receive(target) { states += it }

        assertThat((result as WatchLogState.Failed).reason).isEqualTo(WatchLogState.Reason.OLD_WATCH_APP)
    }

    @Test
    fun `no watch connected`() = runBlocking {
        val result = receiver(WatchChannelOpener { null }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Failed(WatchLogState.Reason.NO_WATCH))
    }

    @Test
    fun `the file name has a readable time`() {
        assertThat(WatchLogDownloader.fileName(0)).matches("AndroidAPS_WEAR_LOG_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.zip")
    }

    companion object {

        /** The timeouts of the receiver in these tests. A fake watch that must be too slow sleeps [SLOW_WATCH_MS]. */
        private const val ANSWER_TIMEOUT_MS = 2_000L
        private const val PREPARE_TIMEOUT_MS = 8_000L
        private const val STALL_TIMEOUT_MS = 2_000L
        private const val FALLBACK_TIMEOUT_MS = 8_000L
        private const val SLOW_WATCH_MS = 3 * ANSWER_TIMEOUT_MS
    }
}
