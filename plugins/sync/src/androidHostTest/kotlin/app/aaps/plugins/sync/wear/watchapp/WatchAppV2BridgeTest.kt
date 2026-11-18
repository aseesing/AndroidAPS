// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.watchapp

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The bridge for the personal watch app that knows only update format version 2, against fake
 * watches on the other end of two pipes, as in `WatchAppSenderTest`.
 *
 * The tests through the whole [WatchAppSender] wait the real [WatchAppV2Bridge.RETRY_DELAY_MS]
 * of 12 seconds; the others call the bridge with a short wait.
 */
class WatchAppV2BridgeTest {

    private val body = ByteArray(300_000) { (it * 7).toByte() }
    private val app = EmbeddedWatchApp("4.0.1 (wear.apk)", body.size.toLong(), body.size * 3L)
    private val sha256 = WatchAppUpdateProtocol.sha256(ByteArrayInputStream(body))

    private val source = object : EmbeddedWatchAppSource {
        override fun info() = app
        override fun open(): InputStream = ByteArrayInputStream(body)
    }

    private val states = CopyOnWriteArrayList<WatchAppUpdateState>()

    /** The versions of all headers the fake watches got, in order */
    private val versions = CopyOnWriteArrayList<Int>()
    private val opened = AtomicInteger(0)

    /** A version 2 header, as the personal watch app reads it */
    private data class Version2Header(val label: String, val bodySize: Long, val sha256: String, val wifi: WatchAppUpdateProtocol.WifiOffer?)

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

    /**
     * A watch app that reads the magic and the version first, as all versions do. [knows] says which
     * versions it takes; it answers every other one as the personal watch app does.
     */
    private fun watch(knows: Set<Int>, received: ByteArrayOutputStream = ByteArrayOutputStream(), headers: MutableList<Version2Header> = mutableListOf()) =
        WatchChannelOpener { path ->
            assertThat(path).isEqualTo(WatchAppUpdateProtocol.PATH)
            opened.incrementAndGet()
            FakeChannel { input, output ->
                val data = DataInputStream(input)
                check(data.readInt() == WatchAppUpdateProtocol.MAGIC)
                val version = data.readInt()
                versions += version
                if (version !in knows) {
                    WatchAppUpdateProtocol.writeReply(output, Reply.Failed("Wrong update: ${WatchAppUpdateProtocol.UNKNOWN_FORMAT} $version, expected ${knows.max()}"))
                    input.read()
                    return@FakeChannel
                }
                if (version == WatchAppV2Bridge.VERSION_2) {
                    val header = readVersion2Header(data)
                    headers += header
                    receive(header.bodySize, header.wifi, input, output, received)
                } else {
                    // The current format: the rest of what the upstream reader reads
                    data.readUTF()
                    val bodySize = data.readLong()
                    data.readLong()
                    data.readUTF()
                    val wifi = if (data.readBoolean()) readOffer(data) else null
                    receive(bodySize, wifi, input, output, received)
                }
            }
        }

    private fun readVersion2Header(data: DataInputStream): Version2Header {
        val label = data.readUTF()
        val bodySize = data.readLong()
        val sha = data.readUTF()
        val wifi = if (data.readBoolean()) readOffer(data) else null
        return Version2Header(label, bodySize, sha, wifi)
    }

    private fun readOffer(data: DataInputStream): WatchAppUpdateProtocol.WifiOffer {
        val port = data.readInt()
        val token = data.readUTF()
        return WatchAppUpdateProtocol.WifiOffer(List(data.readInt()) { data.readUTF() }, port, token)
    }

    /** Takes the body over Wi-Fi when there is an offer, as the personal watch app does, else over the channel */
    private fun receive(bodySize: Long, wifi: WatchAppUpdateProtocol.WifiOffer?, input: InputStream, output: OutputStream, received: ByteArrayOutputStream) {
        if (wifi != null) {
            WatchAppUpdateProtocol.writeReply(output, Reply.WifiStarted)
            Socket(wifi.addresses.first(), wifi.port).use { socket ->
                WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), wifi.token)
                WatchAppUpdateProtocol.copyBody(socket.getInputStream(), received, bodySize)
            }
        } else {
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            WatchAppUpdateProtocol.copyBody(input, received, bodySize)
        }
        WatchAppUpdateProtocol.writeReply(output, Reply.Committed)
    }

    private val loopbackServer = WifiBodyServerFactory { SocketWifiBodyServer(listOf("127.0.0.1"), InetAddress.getLoopbackAddress()) }

    private fun sender(opener: WatchChannelOpener, wifiServers: WifiBodyServerFactory? = null) =
        WatchAppSender(source, opener, wifiServers, answerTimeoutMs = TIMEOUT_MS, stallTimeoutMs = TIMEOUT_MS, installTimeoutMs = TIMEOUT_MS, wifiTimeoutMs = TIMEOUT_MS)

    private fun bridge(opener: WatchChannelOpener, wifiServers: WifiBodyServerFactory? = null) =
        WatchAppV2Bridge(source, opener, wifiServers, Dispatchers.IO, TIMEOUT_MS, TIMEOUT_MS, TIMEOUT_MS, TIMEOUT_MS, { System.nanoTime() / 1_000_000 }, retryDelayMs = 50)

    private val oldWatchApp = WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.OLD_WATCH_APP, "Wrong update: ${WatchAppUpdateProtocol.UNKNOWN_FORMAT} 4, expected 2")

    @Test
    fun `a watch that knows only version 2 gets the update through the bridge`() = runBlocking<Unit> {
        val received = ByteArrayOutputStream()
        val headers = mutableListOf<Version2Header>()

        val result = sender(watch(setOf(2), received, headers)).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Sent("Test Watch"))
        assertThat(received.toByteArray()).isEqualTo(body)
        assertThat(versions).containsExactly(WatchAppUpdateProtocol.VERSION, 2).inOrder()
        assertThat(headers.single()).isEqualTo(Version2Header(app.label, app.bodySize, sha256, null))
        // The "install once with adb" of the first try never shows
        assertThat(states.none { it is WatchAppUpdateState.Failed }).isTrue()
        assertThat(states.last()).isEqualTo(result)
    }

    @Test
    fun `a watch that knows neither version is told to install once with adb, after one try with version 2`() = runBlocking<Unit> {
        val result = sender(watch(setOf(3))).send { states += it }

        assertThat((result as WatchAppUpdateState.Failed).reason).isEqualTo(WatchAppUpdateState.Reason.OLD_WATCH_APP)
        // At most one try with the bridge: two channels in all
        assertThat(opened.get()).isEqualTo(2)
        assertThat(versions).containsExactly(WatchAppUpdateProtocol.VERSION, 2).inOrder()
        assertThat(states.filterIsInstance<WatchAppUpdateState.Failed>()).containsExactly(result)
    }

    @Test
    fun `a watch that knows the current version never sees version 2`() = runBlocking<Unit> {
        val received = ByteArrayOutputStream()

        val result = sender(watch(setOf(WatchAppUpdateProtocol.VERSION), received)).send { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Sent("Test Watch"))
        assertThat(received.toByteArray()).isEqualTo(body)
        assertThat(versions).containsExactly(WatchAppUpdateProtocol.VERSION)
        assertThat(opened.get()).isEqualTo(1)
    }

    @Test
    fun `a watch that refuses for another reason gets no second try`() = runBlocking<Unit> {
        val first = WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.BUSY)

        val result = bridge(watch(setOf(2))).orRetry(first, {}) { states += it }

        assertThat(result).isEqualTo(first)
        assertThat(opened.get()).isEqualTo(0)
    }

    @Test
    fun `a version 2 watch on Wi-Fi fetches the body from the phone's server`() = runBlocking<Unit> {
        val received = ByteArrayOutputStream()
        val headers = mutableListOf<Version2Header>()
        var closedFirst = false

        val result = bridge(watch(setOf(2), received, headers), loopbackServer).orRetry(oldWatchApp, { closedFirst = true }) { states += it }

        assertThat(result).isEqualTo(WatchAppUpdateState.Sent("Test Watch"))
        assertThat(received.toByteArray()).isEqualTo(body)
        assertThat(closedFirst).isTrue()
        assertThat(headers.single().sha256).isEqualTo(sha256)
        assertThat(headers.single().wifi?.token).hasLength(32)
        assertThat(states.filterIsInstance<WatchAppUpdateState.Sending>().all { it.overWifi }).isTrue()
        // The end is for the caller to report
        assertThat(states.none { it is WatchAppUpdateState.Sent }).isTrue()
    }

    @Test
    fun `an upstream watch app of version 3 keeps the first answer`() = runBlocking<Unit> {
        // Version 3 reads version 2 as older than itself and asks for a newer phone app: wrong advice here
        val opener = WatchChannelOpener {
            opened.incrementAndGet()
            FakeChannel { input, output ->
                WatchAppUpdateProtocol.writeReply(output, Reply.Failed("Wrong update: ${WatchAppUpdateProtocol.OLD_FORMAT} 2, expected 3"))
                input.read()
            }
        }

        val result = bridge(opener).orRetry(oldWatchApp, {}) { states += it }

        assertThat(result).isEqualTo(oldWatchApp)
        assertThat(opened.get()).isEqualTo(1)
    }

    @Test
    fun `the version 2 header has the fields of the personal watch app in its order`() {
        val offer = WatchAppUpdateProtocol.WifiOffer(listOf("192.168.1.2", "10.0.0.3"), 4242, "secret")
        val bytes = WatchAppV2Bridge.version2Header(WatchAppUpdateProtocol.Header("label", 1234, 5678, sha256, offer))

        val data = DataInputStream(ByteArrayInputStream(bytes))
        assertThat(data.readInt()).isEqualTo(WatchAppUpdateProtocol.MAGIC)
        assertThat(data.readInt()).isEqualTo(2)
        assertThat(readVersion2Header(data)).isEqualTo(Version2Header("label", 1234, sha256, offer))
        assertThat(data.available()).isEqualTo(0)
    }

    @Test
    fun `the body after the header goes out as it is`() {
        val out = ByteArrayOutputStream()
        val sut = Version2HeaderOutput(out)
        val header = WatchAppUpdateProtocol.Header("label", 3, 9, sha256)

        WatchAppUpdateProtocol.writeHeader(sut, header)
        sut.write(byteArrayOf(1, 2, 3))
        sut.flush()

        assertThat(out.toByteArray()).isEqualTo(WatchAppV2Bridge.version2Header(header) + byteArrayOf(1, 2, 3))
    }

    companion object {

        private const val TIMEOUT_MS = 4_000L
    }
}
