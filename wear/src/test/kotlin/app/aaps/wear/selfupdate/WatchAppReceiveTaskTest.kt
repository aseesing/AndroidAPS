package app.aaps.wear.selfupdate

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Header
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
    private fun fromPhone(body: ByteArray = gzipped, size: Long = body.size.toLong()): InputStream {
        val out = ByteArrayOutputStream()
        WatchAppUpdateProtocol.writeHeader(out, Header("4.0.1 (wear.apk)", size))
        out.write(body)
        return ByteArrayInputStream(out.toByteArray())
    }

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
    fun `bytes after the body are not read`() {
        val input = fromPhone(gzipped + byteArrayOf(9, 9, 9), size = gzipped.size.toLong())

        WatchAppReceiveTask { session }.receive(input, toPhone)

        assertThat(session.committed).isTrue()
        assertThat(input.available()).isEqualTo(3)
    }
}
