package app.aaps.shared.impl.weardata

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Header
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
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
        // The phone looks for this text to try an older version
        assertThat(e.message).contains(WatchAppUpdateProtocol.UNKNOWN_FORMAT)
    }

    @Test
    fun `a header with a hash and a Wi-Fi offer is read back as written`() {
        val written = Header(
            label = "4.0.1 (aaps-wear.apk)",
            bodySize = 20_000_000,
            sha256 = "ab".repeat(32),
            wifi = WifiOffer(listOf("192.168.1.20", "10.0.0.5"), 45_123, "00112233445566778899aabbccddeeff")
        )
        val buffer = ByteArrayOutputStream()
        WatchAppUpdateProtocol.writeHeader(buffer, written)

        val header = WatchAppUpdateProtocol.readHeader(ByteArrayInputStream(buffer.toByteArray()))

        assertThat(header).isEqualTo(written)
        assertThat(header.version).isEqualTo(WatchAppUpdateProtocol.VERSION)
    }

    @Test
    fun `a version 1 header is still read, without hash and Wi-Fi`() {
        val buffer = ByteArrayOutputStream()
        // What a phone app with version 1 wrote
        DataOutputStream(buffer).apply {
            writeInt(WatchAppUpdateProtocol.MAGIC)
            writeInt(1)
            writeUTF("old")
            writeLong(10)
        }

        val header = WatchAppUpdateProtocol.readHeader(ByteArrayInputStream(buffer.toByteArray()))

        assertThat(header).isEqualTo(Header("old", 10, version = WatchAppUpdateProtocol.VERSION_CHANNEL_ONLY))
    }

    @Test
    fun `a version 1 header is written the way version 1 reads it`() {
        val buffer = ByteArrayOutputStream()
        WatchAppUpdateProtocol.writeHeader(buffer, Header("old", 10, sha256 = "ignored", version = WatchAppUpdateProtocol.VERSION_CHANNEL_ONLY))

        // magic, version, label (2 + 3 bytes), size, and nothing more
        assertThat(buffer.size()).isEqualTo(4 + 4 + 5 + 8)
    }

    @Test
    fun `a Wi-Fi request is read back as written`() {
        val buffer = ByteArrayOutputStream()
        WatchAppUpdateProtocol.writeWifiRequest(buffer, "secret")

        assertThat(WatchAppUpdateProtocol.readWifiRequest(ByteArrayInputStream(buffer.toByteArray()))).isEqualTo("secret")
        assertThrows<IOException> { WatchAppUpdateProtocol.readWifiRequest(ByteArrayInputStream("GET / HTTP/1.1".toByteArray())) }
    }

    @Test
    fun `sha256 is the standard one`() {
        assertThat(WatchAppUpdateProtocol.sha256(ByteArrayInputStream("abc".toByteArray())))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `tokens match only when equal`() {
        assertThat(WatchAppUpdateProtocol.sameToken("abc", "abc")).isTrue()
        assertThat(WatchAppUpdateProtocol.sameToken("abc", "abd")).isFalse()
        assertThat(WatchAppUpdateProtocol.sameToken("abc", "abcd")).isFalse()
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
        val replies = listOf(Reply.Ready, Reply.WifiStarted, Reply.Committed, Reply.Failed("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
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
