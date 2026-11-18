package app.aaps.wear.selfupdate

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Header
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/** The watch side of the watch app update: what reaches the installer, and what the phone is told */
internal class WatchAppReceiveTaskTest {

    private val apk = ByteArray(500_000) { (it % 251).toByte() }
    private val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(apk) } }.toByteArray()

    private class FakeSession : WatchAppInstallSession {

        override val output = ByteArrayOutputStream()
        var committed = false
        var abandoned = false

        override fun commit() {
            committed = true
        }

        override fun abandon() {
            abandoned = true
        }
    }

    private val session = FakeSession()
    private val toPhone = ByteArrayOutputStream()
    private val progress = mutableListOf<Int>()

    /** What the phone sends: header, body, and the bytes that follow while the channel stays open */
    private fun fromPhone(body: ByteArray = gzipped, size: Long = body.size.toLong(), sha256: String = "", wifi: WifiOffer? = null): InputStream {
        val out = ByteArrayOutputStream()
        WatchAppUpdateProtocol.writeHeader(out, Header("4.0.1 (wear.apk)", size, sha256, wifi))
        out.write(body)
        return ByteArrayInputStream(out.toByteArray())
    }

    private val offer = WifiOffer(listOf("192.168.1.20"), 45_123, "token")
    private val sha256 = WatchAppUpdateProtocol.sha256(ByteArrayInputStream(gzipped))

    /** A Wi-Fi connection that gives [body], and remembers whether it was closed */
    private class FakeWifiBody(body: ByteArray) : WifiBody {

        override val input: InputStream = ByteArrayInputStream(body)
        override val output: OutputStream = ByteArrayOutputStream()
        var closed = false

        override fun close() {
            closed = true
        }
    }

    /** Hands out a new session for every install, as the package installer does */
    private val sessions = mutableListOf<FakeSession>()
    private val newSession = WatchAppInstallSessionFactory { FakeSession().also { sessions += it } }

    private fun replies(): List<Reply> {
        val input = ByteArrayInputStream(toPhone.toByteArray())
        return buildList { while (input.available() > 0) add(WatchAppUpdateProtocol.readReply(input)) }
    }

    @Test
    fun `the unpacked APK goes to the installer and the phone is told`() {
        val result = WatchAppReceiveTask { session }.receive(fromPhone(), toPhone) { progress += it }

        assertThat(result).isEqualTo(WatchAppReceiveTask.Result.Committed("4.0.1 (wear.apk)"))
        assertThat(session.output.toByteArray()).isEqualTo(apk)
        assertThat(session.committed).isTrue()
        assertThat(session.abandoned).isFalse()
        assertThat(replies()).containsExactly(Reply.Ready, Reply.Committed).inOrder()
        assertThat(progress.last()).isEqualTo(100)
        assertThat(progress).isInStrictOrder()
    }

    @Test
    fun `a stream that is not an update starts no install`() {
        var created = false

        val result = WatchAppReceiveTask { created = true; session }.receive(ByteArrayInputStream("hello watch".toByteArray()), toPhone)

        assertThat(result).isInstanceOf(WatchAppReceiveTask.Result.Failed::class.java)
        assertThat(created).isFalse()
        assertThat(replies().single()).isInstanceOf(Reply.Failed::class.java)
    }

    @Test
    fun `an installer that refuses a session is reported before the body`() {
        val result = WatchAppReceiveTask { throw SecurityException("not allowed") }.receive(fromPhone(), toPhone)

        assertThat(result).isEqualTo(WatchAppReceiveTask.Result.Failed("Cannot start the install: not allowed"))
        assertThat(replies()).containsExactly(Reply.Failed("Cannot start the install: not allowed"))
    }

    @Test
    fun `a damaged body is not installed`() {
        val damaged = gzipped.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }

        val result = WatchAppReceiveTask { session }.receive(fromPhone(damaged), toPhone)

        assertThat(result).isInstanceOf(WatchAppReceiveTask.Result.Failed::class.java)
        assertThat(session.committed).isFalse()
        assertThat(session.abandoned).isTrue()
        assertThat(replies().first()).isEqualTo(Reply.Ready)
        assertThat(replies().last()).isInstanceOf(Reply.Failed::class.java)
    }

    @Test
    fun `a body that ends early is not installed`() {
        val result = WatchAppReceiveTask { session }.receive(fromPhone(gzipped.copyOf(gzipped.size - 100), size = gzipped.size.toLong()), toPhone)

        assertThat(result).isInstanceOf(WatchAppReceiveTask.Result.Failed::class.java)
        assertThat(session.committed).isFalse()
        assertThat(session.abandoned).isTrue()
    }

    @Test
    fun `with a Wi-Fi offer the body comes over Wi-Fi`() {
        val wifiBody = FakeWifiBody(gzipped)
        var askedOffer: WifiOffer? = null
        val channel = fromPhone(ByteArray(0), size = gzipped.size.toLong(), sha256 = sha256, wifi = offer)

        val result = WatchAppReceiveTask(wifi = { askedOffer = it; wifiBody }, sessionFactory = newSession).receive(channel, toPhone) { progress += it }

        assertThat(result).isEqualTo(WatchAppReceiveTask.Result.Committed("4.0.1 (wear.apk)", overWifi = true))
        assertThat(askedOffer).isEqualTo(offer)
        assertThat(sessions.single().output.toByteArray()).isEqualTo(apk)
        assertThat(sessions.single().committed).isTrue()
        assertThat(wifiBody.closed).isTrue()
        assertThat(replies()).containsExactly(Reply.WifiStarted, Reply.Committed).inOrder()
        assertThat(progress.last()).isEqualTo(100)
    }

    @Test
    fun `when Wi-Fi does not work the body comes over the channel in a new session`() {
        val logged = mutableListOf<String>()

        val result = WatchAppReceiveTask(wifi = { throw IOException("No Wi-Fi network") }, log = { logged += it }, sessionFactory = newSession)
            .receive(fromPhone(sha256 = sha256, wifi = offer), toPhone)

        assertThat(result).isEqualTo(WatchAppReceiveTask.Result.Committed("4.0.1 (wear.apk)", overWifi = false))
        assertThat(replies()).containsExactly(Reply.WifiStarted, Reply.Ready, Reply.Committed).inOrder()
        assertThat(sessions).hasSize(2)
        assertThat(sessions[0].abandoned).isTrue()
        assertThat(sessions[1].committed).isTrue()
        assertThat(sessions[1].output.toByteArray()).isEqualTo(apk)
        assertThat(logged.single()).contains("No Wi-Fi network")
    }

    @Test
    fun `a wrong body over Wi-Fi is not installed, the channel gives the right one`() {
        val other = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(apk.size)) } }.toByteArray()
        // Same size, so only the hash can tell
        val wrong = other.copyOf(gzipped.size)

        val result = WatchAppReceiveTask(wifi = { FakeWifiBody(wrong) }, sessionFactory = newSession)
            .receive(fromPhone(sha256 = sha256, wifi = offer), toPhone)

        assertThat(result).isEqualTo(WatchAppReceiveTask.Result.Committed("4.0.1 (wear.apk)", overWifi = false))
        assertThat(sessions[0].committed).isFalse()
        assertThat(sessions[1].output.toByteArray()).isEqualTo(apk)
    }

    @Test
    fun `a body with a wrong hash is not installed`() {
        val result = WatchAppReceiveTask { session }.receive(fromPhone(sha256 = "00".repeat(32)), toPhone)

        assertThat(result).isEqualTo(WatchAppReceiveTask.Result.Failed("Update has a wrong SHA-256"))
        assertThat(session.committed).isFalse()
        assertThat(session.abandoned).isTrue()
    }

    @Test
    fun `without a Wi-Fi fetcher an offer is ignored`() {
        val result = WatchAppReceiveTask { session }.receive(fromPhone(sha256 = sha256, wifi = offer), toPhone)

        assertThat(result).isEqualTo(WatchAppReceiveTask.Result.Committed("4.0.1 (wear.apk)"))
        assertThat(replies()).containsExactly(Reply.Ready, Reply.Committed).inOrder()
    }

    @Test
    fun `bytes after the body are not read`() {
        val input = fromPhone(gzipped + byteArrayOf(9, 9, 9), size = gzipped.size.toLong())

        WatchAppReceiveTask { session }.receive(input, toPhone)

        assertThat(session.committed).isTrue()
        assertThat(input.available()).isEqualTo(3)
    }
}
