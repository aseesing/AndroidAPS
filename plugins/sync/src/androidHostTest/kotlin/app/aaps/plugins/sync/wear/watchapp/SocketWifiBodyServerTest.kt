package app.aaps.plugins.sync.wear.watchapp

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** The Wi-Fi server of the phone, on the loopback address of the test machine */
class SocketWifiBodyServerTest {

    private val body = ByteArray(100_000) { (it * 3).toByte() }
    private val server = SocketWifiBodyServer(listOf("127.0.0.1"), InetAddress.getLoopbackAddress())

    /** Runs [SocketWifiBodyServer.serve] in its own thread; the result is null, or what it threw */
    private fun serveInBackground(): Pair<Thread, AtomicReference<Throwable?>> {
        val error = AtomicReference<Throwable?>()
        val serving = thread(isDaemon = true) {
            try {
                server.serve("right", { ByteArrayInputStream(body) }, body.size.toLong()) {}
            } catch (e: Throwable) {
                error.set(e)
            }
        }
        return serving to error
    }

    private fun fetch(token: String): ByteArray =
        Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
            WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), token)
            socket.getInputStream().readBytes()
        }

    @Test
    fun `a wrong token gets nothing, the right one gets the body`() {
        val (serving, error) = serveInBackground()

        assertThat(fetch("wrong")).isEmpty()
        assertThat(fetch("right")).isEqualTo(body)

        serving.join(5_000)
        assertThat(serving.isAlive).isFalse()
        assertThat(error.get()).isNull()
        server.close()
    }

    @Test
    fun `something that is not the watch gets nothing`() {
        val (serving, _) = serveInBackground()

        val answer = Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
            socket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            // The server closes with bytes unread, which may reset the connection
            try {
                socket.getInputStream().readBytes()
            } catch (_: IOException) {
                ByteArray(0)
            }
        }

        assertThat(answer).isEmpty()
        server.close()
        serving.join(5_000)
    }

    @Test
    fun `too many wrong tries end the server`() {
        val (serving, error) = serveInBackground()

        repeat(5) { fetch("wrong") }

        serving.join(5_000)
        assertThat(error.get()).isInstanceOf(IOException::class.java)
        server.close()
    }

    @Test
    fun `closing ends a server that waits`() {
        val (serving, error) = serveInBackground()

        server.close()

        serving.join(5_000)
        assertThat(serving.isAlive).isFalse()
        assertThat(error.get()).isInstanceOf(IOException::class.java)
        assertThrows<IOException> { fetch("right") }
    }

    /** The watch log download: the server only checks the token, then the watch writes */
    @Test
    fun `accept gives the socket of the watch with the right token, so the phone can read from it`() {
        val received = AtomicReference<ByteArray?>()
        val accepting = thread(isDaemon = true) {
            server.accept("right").use { socket -> received.set(socket.getInputStream().readBytes()) }
        }

        assertThat(fetch("wrong")).isEmpty()
        Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
            WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), "right")
            socket.getOutputStream().write(body)
        }

        accepting.join(5_000)
        assertThat(accepting.isAlive).isFalse()
        assertThat(received.get()).isEqualTo(body)
        server.close()
    }
}
