package app.aaps.shared.impl.weardata

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
import app.aaps.shared.impl.weardata.WatchLogProtocol.Reply
import app.aaps.shared.impl.weardata.WatchLogProtocol.Request
import app.aaps.shared.impl.weardata.WatchLogProtocol.Zip
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class WatchLogProtocolTest {

    private fun roundTrip(request: Request): Request {
        val bytes = ByteArrayOutputStream().also { WatchLogProtocol.writeRequest(it, request) }.toByteArray()
        return WatchLogProtocol.readRequest(ByteArrayInputStream(bytes))
    }

    private fun roundTrip(reply: Reply): Reply {
        val bytes = ByteArrayOutputStream().also { WatchLogProtocol.writeReply(it, reply) }.toByteArray()
        return WatchLogProtocol.readReply(ByteArrayInputStream(bytes))
    }

    @Test
    fun `a request without Wi-Fi survives the trip`() {
        val request = Request(WatchLogProtocol.ALL_FILES, 10)
        assertThat(roundTrip(request)).isEqualTo(request)
    }

    @Test
    fun `a request with Wi-Fi and its secret survives the trip`() {
        val request = Request(0, 3, WifiOffer(listOf("192.168.1.20", "10.0.0.5"), 45_123, "token"), WatchLogCrypto.newSecret())
        assertThat(roundTrip(request)).isEqualTo(request)
    }

    @Test
    fun `a Wi-Fi offer without a secret cannot be made`() {
        assertThrows<IllegalArgumentException> { Request(0, 3, WifiOffer(listOf("192.168.1.20"), 45_123, "token")) }
    }

    @Test
    fun `the secret and the token never show in the text of a request`() {
        val secret = WatchLogCrypto.newSecret()
        val token = "00112233445566778899aabbccddeeff"
        val text = Request(0, 3, WifiOffer(listOf("192.168.1.20"), 45_123, token), secret).toString()
        assertThat(text).doesNotContain(secret.key.joinToString())
        assertThat(text).doesNotContain(WatchAppUpdateProtocol.hex(secret.key))
        assertThat(text).doesNotContain(token)
    }

    @Test
    fun `every reply survives the trip`() {
        val zip = Zip(123_456, "ab".repeat(32))
        listOf(Reply.Accepted, Reply.WifiZip(123_472), Reply.WifiDone, Reply.ChannelBody(zip), Reply.Failed("No log files")).forEach {
            assertThat(roundTrip(it)).isEqualTo(it)
        }
    }

    @Test
    fun `a watch app update header is not taken as a log request`() {
        val bytes = ByteArrayOutputStream().also { WatchAppUpdateProtocol.writeHeader(it, WatchAppUpdateProtocol.Header("x", 10, 30, "ab".repeat(32))) }.toByteArray()
        assertThrows<IOException> { WatchLogProtocol.readRequest(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `an unknown version is refused`() {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(WatchLogProtocol.MAGIC); writeInt(WatchLogProtocol.VERSION + 1) } }.toByteArray()
        val error = assertThrows<IOException> { WatchLogProtocol.readRequest(ByteArrayInputStream(bytes)) }
        assertThat(error.message).startsWith(WatchLogProtocol.UNKNOWN_FORMAT)
    }

    @Test
    fun `a zip size over the limit is refused before the body is read`() {
        val overChannel = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(4); writeLong(WatchLogProtocol.MAX_ZIP_SIZE + 1); writeUTF("x") }
        }.toByteArray()
        assertThrows<IOException> { WatchLogProtocol.readReply(ByteArrayInputStream(overChannel)) }
        val overWifi = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(2); writeLong(WatchLogProtocol.MAX_ZIP_SIZE + WatchLogCrypto.TAG_BYTES + 1) }
        }.toByteArray()
        assertThrows<IOException> { WatchLogProtocol.readReply(ByteArrayInputStream(overWifi)) }
    }
}
