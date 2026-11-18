// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.watchlog

import app.aaps.plugins.sync.wear.watchapp.SocketWifiBodyServer
import app.aaps.plugins.sync.wear.watchapp.WatchChannel
import app.aaps.plugins.sync.wear.watchapp.WatchChannelOpener
import app.aaps.plugins.sync.wear.watchapp.WifiBodyServerFactory
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
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
 * Real time: the receiver works on blocking streams and a watchdog, so the timeouts are short.
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
        WatchLogReceiver(opener, wifiServers, answerTimeoutMs = 500, prepareTimeoutMs = 2_000, stallTimeoutMs = 1_000)

    /** What the watch does first: read the request and accept it */
    private fun accept(input: InputStream, output: OutputStream): WatchLogProtocol.Request =
        WatchLogProtocol.readRequest(input).also { WatchLogProtocol.writeReply(output, Reply.Accepted) }

    /** The watch connects to the Wi-Fi server of the phone and writes [bytes] there */
    private fun sendOverWifi(offer: WifiOffer, output: OutputStream, zip: WatchLogProtocol.Zip, bytes: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(offer.addresses.first(), offer.port))
            WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), offer.token)
            WatchLogProtocol.writeReply(output, Reply.WifiZip(zip))
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
        WatchLogProtocol.writeReply(output, Reply.WifiDone)
    }

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
    fun `with Wi-Fi all files come over Wi-Fi`() = runBlocking {
        val result = receiver(channel { input, output ->
            val offer = requireNotNull(accept(input, output).wifi)
            sendOverWifi(offer, output, info(wifiZip), wifiZip)
        }, loopbackServer).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Received("Test Watch", overWifi = true))
        assertThat(target.readBytes()).isEqualTo(wifiZip)
        assertThat(states.filterIsInstance<WatchLogState.Receiving>().all { it.overWifi }).isTrue()
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
    fun `a damaged zip over Wi-Fi is refused`() = runBlocking {
        val damaged = wifiZip.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val result = receiver(channel { input, output ->
            val offer = requireNotNull(accept(input, output).wifi)
            sendOverWifi(offer, output, info(wifiZip), damaged)
        }, loopbackServer).receive(target) { states += it }

        assertThat(result).isInstanceOf(WatchLogState.Failed::class.java)
        assertThat((result as WatchLogState.Failed).reason).isEqualTo(WatchLogState.Reason.ERROR)
        assertThat(result.detail).contains("SHA-256")
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
        val result = receiver(channel { _, _ -> Thread.sleep(5_000) }).receive(target) { states += it }

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
            Thread.sleep(5_000)
        }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Failed(WatchLogState.Reason.TIMEOUT))
    }

    @Test
    fun `a watch that accepted may take longer than the answer timeout to zip`() = runBlocking {
        val result = receiver(channel { input, output ->
            accept(input, output)
            // Longer than answerTimeoutMs, shorter than prepareTimeoutMs
            Thread.sleep(1_200)
            WatchLogProtocol.writeReply(output, Reply.ChannelBody(info(channelZip)))
            output.write(channelZip)
            output.flush()
        }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Received("Test Watch", overWifi = false))
    }

    @Test
    fun `a busy watch says so at once`() = runBlocking {
        val result = receiver(channel { _, output ->
            WatchLogProtocol.writeReply(output, Reply.Failed("The watch is still sending a log"))
        }).receive(target) { states += it }

        assertThat(result).isEqualTo(WatchLogState.Failed(WatchLogState.Reason.WATCH_ERROR, "The watch is still sending a log"))
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
}
