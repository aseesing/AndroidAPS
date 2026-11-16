package app.aaps.shared.impl.weardata

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Header
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class WatchAppUpdateProtocolTest {

    @Test
    fun `a header is read back as written`() {
        val buffer = ByteArrayOutputStream()
        WatchAppUpdateProtocol.writeHeader(buffer, Header("4.0.1 (aaps-wear.apk)", 20_000_000))

        val header = WatchAppUpdateProtocol.readHeader(ByteArrayInputStream(buffer.toByteArray()))

        assertThat(header).isEqualTo(Header("4.0.1 (aaps-wear.apk)", 20_000_000))
    }

    @Test
    fun `a stream that is not an update is refused`() {
        assertThrows<IOException> { WatchAppUpdateProtocol.readHeader(ByteArrayInputStream("PK\u0003\u0004 a zip".toByteArray())) }
    }

    @Test
    fun `a newer protocol version is refused`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).apply {
            writeInt(WatchAppUpdateProtocol.MAGIC)
            writeInt(WatchAppUpdateProtocol.VERSION + 1)
            writeUTF("x")
            writeLong(10)
        }

        val e = assertThrows<IOException> { WatchAppUpdateProtocol.readHeader(ByteArrayInputStream(buffer.toByteArray())) }
        assertThat(e.message).contains("format")
    }

    @Test
    fun `an empty or huge body size is refused`() {
        for (size in listOf(0L, -1L, WatchAppUpdateProtocol.MAX_BODY_SIZE + 1)) {
            val buffer = ByteArrayOutputStream()
            WatchAppUpdateProtocol.writeHeader(buffer, Header("x", size))
            assertThrows<IOException> { WatchAppUpdateProtocol.readHeader(ByteArrayInputStream(buffer.toByteArray())) }
        }
    }

    @Test
    fun `every reply is read back as written`() {
        val replies = listOf(Reply.Ready, Reply.Committed, Reply.Failed("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        val buffer = ByteArrayOutputStream()
        replies.forEach { WatchAppUpdateProtocol.writeReply(buffer, it) }

        val input = ByteArrayInputStream(buffer.toByteArray())

        assertThat(List(replies.size) { WatchAppUpdateProtocol.readReply(input) }).isEqualTo(replies)
    }

    @Test
    fun `a stream that ends before a reply throws`() {
        assertThrows<IOException> { WatchAppUpdateProtocol.readReply(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test
    fun `copyBody copies exactly the size and reports progress`() {
        val data = ByteArray(200_000) { it.toByte() }
        val input = ByteArrayInputStream(data + byteArrayOf(1, 2, 3))
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()

        WatchAppUpdateProtocol.copyBody(input, output, data.size.toLong()) { progress += it }

        assertThat(output.toByteArray()).isEqualTo(data)
        assertThat(progress.last()).isEqualTo(data.size.toLong())
        assertThat(progress).isInOrder()
        // The bytes after the body stay for the next reader
        assertThat(input.available()).isEqualTo(3)
    }

    @Test
    fun `copyBody throws when the input ends early`() {
        assertThrows<IOException> {
            WatchAppUpdateProtocol.copyBody(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 11)
        }
    }

    @Test
    fun `the limited view ends after its size and does not close the input`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val limited = WatchAppUpdateProtocol.limited(input, 3)

        assertThat(limited.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(limited.read()).isEqualTo(-1)
        limited.close()
        assertThat(input.read()).isEqualTo(4)
    }
}
