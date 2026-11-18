package app.aaps.plugins.sync.wear.watchapp

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
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
    fun `too many wrong tries end the server and close it, so the watch fails at once`() {
        val (serving, error) = serveInBackground()

        repeat(SocketWifiBodyServer.MAX_WRONG_REQUESTS) { fetch("wrong") }

        serving.join(5_000)
        assertThat(error.get()).isInstanceOf(IOException::class.java)
        // Not even a connection: the watch does not wait for its read timeout
        assertThrows<IOException> { fetch("right") }
    }

    @Test
    fun `idle connections are closed after a short time and count as wrong`() {
        val (serving, error) = serveInBackground()
        val started = System.nanoTime()

        repeat(SocketWifiBodyServer.MAX_WRONG_REQUESTS) {
            Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
                // Sends nothing; the server closes it
                assertThat(socket.getInputStream().read()).isEqualTo(-1)
            }
        }

        serving.join(5_000)
        assertThat(error.get()).isInstanceOf(IOException::class.java)
        val seconds = (System.nanoTime() - started) / 1_000_000_000
        assertThat(seconds).isAtMost(SocketWifiBodyServer.MAX_WRONG_REQUESTS * SocketWifiBodyServer.REQUEST_TIMEOUT_MS / 1000L + 2)
    }

    @Test
    fun `a client that sends its request very slowly is closed after the time for the whole request`() {
        val (serving, _) = serveInBackground()
        val started = System.nanoTime()

        Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
            // A regression would keep the socket open for the whole trickle below: fail instead of hang
            socket.soTimeout = 10 * SocketWifiBodyServer.REQUEST_TIMEOUT_MS
            // One byte at a time, each well within the time of one read, for much longer than the request may take
            val trickle = thread(isDaemon = true) {
                runCatching {
                    val request = ByteArrayOutputStream().also { WatchAppUpdateProtocol.writeWifiRequest(it, "r".repeat(1000)) }.toByteArray()
                    for (byte in request) {
                        socket.getOutputStream().write(byte.toInt())
                        socket.getOutputStream().flush()
                        Thread.sleep(SocketWifiBodyServer.REQUEST_TIMEOUT_MS / 10L)
                    }
                }
            }
            // The server closes it: end of stream, or a reset because bytes were left unread
            val end = try {
                socket.getInputStream().read()
            } catch (e: SocketTimeoutException) {
                throw AssertionError("The server kept the slow client", e)
            } catch (_: IOException) {
                -1
            }
            assertThat(end).isEqualTo(-1)
            trickle.interrupt()
        }

        val ms = (System.nanoTime() - started) / 1_000_000
        assertThat(ms).isLessThan(5L * SocketWifiBodyServer.REQUEST_TIMEOUT_MS)
        server.close()
        serving.join(5_000)
    }

    @Test
    fun `a connection on an address that was not offered gets nothing`() {
        // Offers another address than the one the test connects to
        val other = SocketWifiBodyServer(listOf("192.0.2.1"), InetAddress.getLoopbackAddress())
        val error = AtomicReference<Throwable?>()
        val serving = thread(isDaemon = true) {
            try {
                other.serve("right", { ByteArrayInputStream(body) }, body.size.toLong()) {}
            } catch (e: Throwable) {
                error.set(e)
            }
        }

        val answer = Socket(InetAddress.getLoopbackAddress(), other.port).use { socket ->
            WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), "right")
            try {
                socket.getInputStream().readBytes()
            } catch (_: IOException) {
                ByteArray(0)
            }
        }

        assertThat(answer).isEmpty()
        other.close()
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

    /** accept only checks the token; then the caller may also read what the watch writes */
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
