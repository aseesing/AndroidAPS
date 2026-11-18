package app.aaps.wear.watchlog

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
import app.aaps.shared.impl.weardata.WatchLogCrypto
import app.aaps.shared.impl.weardata.WatchLogProtocol
import app.aaps.shared.impl.weardata.WatchLogProtocol.Reply
import app.aaps.shared.impl.weardata.WatchLogProtocol.Request
import app.aaps.wear.selfupdate.WifiBody
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class WatchLogSendTaskTest {

    @get:Rule val folder = TemporaryFolder()

    private val offer = WifiOffer(listOf("192.168.1.20"), 45_123, "token")
    private val secret = WatchLogCrypto.newSecret()

    /** Makes a "zip" whose content tells how many files were asked for */
    private inner class FakeZipper : LogZipSource {

        val asked = mutableListOf<Int>()

        override fun zip(maxFiles: Int): LogZip {
            asked += maxFiles
            val bytes = ByteArray(1000 + maxFiles) { (it + maxFiles).toByte() }
            val file = folder.newFile().apply { writeBytes(bytes) }
            return LogZip(file, WatchLogProtocol.Zip(bytes.size.toLong(), WatchAppUpdateProtocol.sha256(ByteArrayInputStream(bytes))), maxFiles)
        }
    }

    private class FakeWifiBody : WifiBody {

        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val output = ByteArrayOutputStream()
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private fun request(request: Request): InputStream =
        ByteArrayInputStream(ByteArrayOutputStream().also { WatchLogProtocol.writeRequest(it, request) }.toByteArray())

    @Test
    fun `without a Wi-Fi offer the channel zip goes over the channel`() {
        val zipper = FakeZipper()
        val output = ByteArrayOutputStream()

        val result = WatchLogSendTask(zipper, wifi = { throw IOException("must not be used") }).send(request(Request(0, 10)), output)

        assertThat(result).isEqualTo(WatchLogSendTask.Result.Sent(10, 1010, overWifi = false))
        assertThat(zipper.asked).containsExactly(10)
        val input = ByteArrayInputStream(output.toByteArray())
        assertThat(WatchLogProtocol.readReply(input)).isEqualTo(Reply.Accepted)
        val reply = WatchLogProtocol.readReply(input) as Reply.ChannelBody
        assertThat(input.readBytes().size.toLong()).isEqualTo(reply.zip.size)
    }

    @Test
    fun `with a Wi-Fi offer all files go over Wi-Fi, encrypted with the secret from the channel`() {
        val zipper = FakeZipper()
        val wifi = FakeWifiBody()
        val output = ByteArrayOutputStream()

        val result = WatchLogSendTask(zipper, wifi = { wifi }).send(request(Request(WatchLogProtocol.ALL_FILES, 10, offer, secret)), output)

        assertThat(result).isEqualTo(WatchLogSendTask.Result.Sent(0, 1000, overWifi = true))
        assertThat(zipper.asked).containsExactly(WatchLogProtocol.ALL_FILES)
        val input = ByteArrayInputStream(output.toByteArray())
        assertThat(WatchLogProtocol.readReply(input)).isEqualTo(Reply.Accepted)
        val started = WatchLogProtocol.readReply(input) as Reply.WifiZip
        assertThat(WatchLogProtocol.readReply(input)).isEqualTo(Reply.WifiDone)
        val sent = wifi.output.toByteArray()
        assertThat(sent.size.toLong()).isEqualTo(started.size)
        val zip = ByteArray(1000) { it.toByte() }
        // Over Wi-Fi only the cipher text: not the zip itself, but the zip once decrypted with the secret
        assertThat(sent.copyOf(zip.size)).isNotEqualTo(zip)
        assertThat(WatchLogCrypto.decrypt(sent, secret, WatchLogProtocol.MAX_ZIP_SIZE)).isEqualTo(zip)
        assertThat(wifi.closed).isTrue()
    }

    @Test
    fun `when Wi-Fi does not connect only the smaller zip is made, and it goes over the channel`() {
        val zipper = FakeZipper()
        val output = ByteArrayOutputStream()
        val logged = mutableListOf<String>()

        val result = WatchLogSendTask(zipper, wifi = { throw IOException("No Wi-Fi network") }, log = { logged += it })
            .send(request(Request(WatchLogProtocol.ALL_FILES, 10, offer, secret)), output)

        assertThat(result).isEqualTo(WatchLogSendTask.Result.Sent(10, 1010, overWifi = false))
        assertThat(zipper.asked).containsExactly(10)
        assertThat(logged.single()).contains("No Wi-Fi network")
        val input = ByteArrayInputStream(output.toByteArray())
        assertThat(WatchLogProtocol.readReply(input)).isEqualTo(Reply.Accepted)
        val body = WatchLogProtocol.readReply(input) as Reply.ChannelBody
        assertThat(input.readBytes().size.toLong()).isEqualTo(body.zip.size)
    }

    /** A Wi-Fi connection whose writes hang, like a socket to a phone that is gone, until it is closed */
    private class HangingWifiBody : WifiBody {

        private val closed = CountDownLatch(1)
        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                closed.await()
                throw IOException("Socket closed")
            }
        }

        override fun close() {
            closed.countDown()
        }
    }

    @Test(timeout = 10_000)
    fun `a Wi-Fi write that hangs is ended, and the log goes over the channel`() {
        val zipper = FakeZipper()
        val output = ByteArrayOutputStream()

        val result = WatchLogSendTask(zipper, wifi = { HangingWifiBody() }, stallTimeoutMs = 300)
            .send(request(Request(WatchLogProtocol.ALL_FILES, 10, offer, secret)), output)

        assertThat(result).isEqualTo(WatchLogSendTask.Result.Sent(10, 1010, overWifi = false))
        assertThat(zipper.asked).containsExactly(WatchLogProtocol.ALL_FILES, 10).inOrder()
        val input = ByteArrayInputStream(output.toByteArray())
        assertThat(WatchLogProtocol.readReply(input)).isEqualTo(Reply.Accepted)
        assertThat(WatchLogProtocol.readReply(input)).isInstanceOf(Reply.WifiZip::class.java)
        assertThat(WatchLogProtocol.readReply(input)).isInstanceOf(Reply.ChannelBody::class.java)
    }

    @Test
    fun `no log files are reported to the phone`() {
        val output = ByteArrayOutputStream()

        val result = WatchLogSendTask({ throw IOException("No log files") }).send(request(Request(0, 10)), output)

        assertThat(result).isEqualTo(WatchLogSendTask.Result.Failed("No log files"))
        val input = ByteArrayInputStream(output.toByteArray())
        assertThat(WatchLogProtocol.readReply(input)).isEqualTo(Reply.Accepted)
        assertThat(WatchLogProtocol.readReply(input)).isEqualTo(Reply.Failed("No log files"))
    }

    @Test
    fun `a wrong request is refused`() {
        val output = ByteArrayOutputStream()

        val result = WatchLogSendTask(FakeZipper()).send(ByteArrayInputStream(ByteArray(20)), output)

        assertThat(result).isInstanceOf(WatchLogSendTask.Result.Failed::class.java)
        assertThat(WatchLogProtocol.readReply(ByteArrayInputStream(output.toByteArray()))).isInstanceOf(Reply.Failed::class.java)
    }

    @Test
    fun `the zipper takes the newest log files and leaves other files out`() {
        val logs = folder.newFolder("logs")
        fun log(name: String, time: Long) = File(logs, name).apply { writeText(name); setLastModified(time) }
        log("AndroidAPS._2026-11-15_10-00-00_.0.zip", 1_000_000)
        log("AndroidAPS._2026-11-16_10-00-00_.0.zip", 2_000_000)
        log("AndroidAPS.log", 3_000_000)
        log("other.txt", 4_000_000)
        val target = File(folder.root, "out.zip")

        val two = WatchLogZipper({ logs }, target).zip(2)

        assertThat(two.fileCount).isEqualTo(2)
        assertThat(entries(target)).containsExactly("AndroidAPS.log", "AndroidAPS._2026-11-16_10-00-00_.0.zip").inOrder()
        assertThat(two.zip.size).isEqualTo(target.length())
        assertThat(two.zip.sha256).isEqualTo(target.inputStream().use { WatchAppUpdateProtocol.sha256(it) })

        // The old part is a zip already and is not compressed again; the current log is
        ZipFile(target).use { zip ->
            val part = zip.getEntry("AndroidAPS._2026-11-16_10-00-00_.0.zip")
            assertThat(part.compressedSize).isAtLeast(part.size)
        }

        val all = WatchLogZipper({ logs }, target).zip(WatchLogProtocol.ALL_FILES)
        assertThat(all.fileCount).isEqualTo(3)
        assertThat(entries(target)).doesNotContain("other.txt")
    }

    @Test
    fun `the zipper takes only the app's own files in the folder itself, with flat names`() {
        val logs = folder.newFolder("logs")
        File(logs, "AndroidAPS.log").writeText("current")
        File(logs, "AndroidAPS.log.txt").writeText("not a log")
        File(logs, "other.log").writeText("another app")
        File(logs, "AndroidAPS_sub.log").mkdir()
        File(File(logs, "AndroidAPS_sub.log"), "AndroidAPS.log").writeText("in a sub folder")
        val target = File(folder.root, "out.zip")

        WatchLogZipper({ logs }, target).zip(WatchLogProtocol.ALL_FILES)

        assertThat(entries(target)).containsExactly("AndroidAPS.log")
    }

    @Test
    fun `the newest log files are taken up to the size limit, the older ones stay on the watch`() {
        val logs = folder.newFolder("logs")
        fun log(name: String, size: Int, time: Long) = File(logs, name).apply { writeBytes(ByteArray(size)); setLastModified(time) }
        log("AndroidAPS.log", 400, 4_000_000)
        log("AndroidAPS._2026-11-17_10-00-00_.0.zip", 400, 3_000_000)
        log("AndroidAPS._2026-11-16_10-00-00_.0.zip", 400, 2_000_000)
        log("AndroidAPS._2026-11-15_10-00-00_.0.zip", 100, 1_000_000)

        val files = WatchLogZipper.logFiles(logs, WatchLogProtocol.ALL_FILES, maxBytes = 1_000)

        // 400 + 400 fit, the third does not; the small old one after it is not taken either
        assertThat(files.map { it.name }).containsExactly("AndroidAPS.log", "AndroidAPS._2026-11-17_10-00-00_.0.zip").inOrder()
        assertThat(WatchLogZipper.logFiles(logs, 1, maxBytes = 1_000).map { it.name }).containsExactly("AndroidAPS.log")
    }

    @Test(expected = IOException::class)
    fun `the zipper fails without a log folder`() {
        WatchLogZipper({ null }, File(folder.root, "out.zip")).zip(1)
    }

    @Test(expected = IOException::class)
    fun `the zipper fails without log files`() {
        WatchLogZipper({ folder.newFolder("empty") }, File(folder.root, "out.zip")).zip(1)
    }

    private fun entries(zip: File): List<String> =
        ZipInputStream(zip.inputStream()).use { input -> generateSequence { input.nextEntry?.name }.toList() }
}
