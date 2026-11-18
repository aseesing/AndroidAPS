// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
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
    fun `a request with Wi-Fi survives the trip`() {
        val request = Request(0, 3, WifiOffer(listOf("192.168.1.20", "10.0.0.5"), 45_123, "secret"))
        assertThat(roundTrip(request)).isEqualTo(request)
    }

    @Test
    fun `every reply survives the trip`() {
        val zip = Zip(123_456, "ab".repeat(32))
        listOf(Reply.Accepted, Reply.WifiZip(zip), Reply.WifiDone, Reply.ChannelBody(zip), Reply.Failed("No log files")).forEach {
            assertThat(roundTrip(it)).isEqualTo(it)
        }
    }

    @Test
    fun `a watch app update header is not taken as a log request`() {
        val bytes = ByteArrayOutputStream().also { WatchAppUpdateProtocol.writeHeader(it, WatchAppUpdateProtocol.Header("x", 10)) }.toByteArray()
        assertThrows<IOException> { WatchLogProtocol.readRequest(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `an unknown version is refused`() {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(WatchLogProtocol.MAGIC); writeInt(WatchLogProtocol.VERSION + 1) } }.toByteArray()
        val error = assertThrows<IOException> { WatchLogProtocol.readRequest(ByteArrayInputStream(bytes)) }
        assertThat(error.message).contains("Unknown")
    }

    @Test
    fun `a zip size out of range is refused`() {
        val bytes = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(4); writeLong(WatchLogProtocol.MAX_ZIP_SIZE + 1); writeUTF("x") }
        }.toByteArray()
        assertThrows<IOException> { WatchLogProtocol.readReply(ByteArrayInputStream(bytes)) }
    }
}
