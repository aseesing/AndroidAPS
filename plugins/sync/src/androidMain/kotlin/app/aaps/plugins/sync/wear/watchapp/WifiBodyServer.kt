package app.aaps.plugins.sync.wear.watchapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A one-time server on the phone's Wi-Fi, from which the watch fetches the watch app body. Much
 * faster than the Wear OS channel, which goes over Bluetooth. See [WatchAppUpdateProtocol.WifiOffer].
 */
interface WifiBodyServer : Closeable {

    /** The phone's addresses that the watch can try, best first */
    val addresses: List<String>
    val port: Int

    /**
     * Waits for the watch, checks its token, then sends [size] bytes from [body]. Blocks until all is
     * sent. [close] ends a waiting or sending call with an [IOException].
     *
     * @param onProgress the number of bytes sent so far
     */
    fun serve(token: String, body: () -> InputStream, size: Long, onProgress: (Long) -> Unit)

    /**
     * Waits for the watch and checks its token. The caller then reads or writes on the socket and
     * closes it. [serve] uses this to send. [close] ends a waiting call with an [IOException].
     */
    fun accept(token: String): Socket
}

/** Starts a [WifiBodyServer] */
fun interface WifiBodyServerFactory {

    /** @return null when the phone has no Wi-Fi address that the watch could reach */
    fun start(): WifiBodyServer?
}

/**
 * A [WifiBodyServer] on a plain TCP socket.
 *
 * It sends nothing before the watch gave the token from the offer, which the phone sent over the
 * Wear OS channel. The watch also checks the SHA-256 from that channel, so another device on the
 * network can neither fetch the APK nor give the watch a different one.
 *
 * It takes only connections that reached the phone on one of [addresses], so nothing comes in over
 * the mobile network or a VPN. A connection that has not sent its whole request within
 * [REQUEST_TIMEOUT_MS] after it was taken, or sends a wrong one, is closed. After [MAX_WRONG_REQUESTS] wrong or idle connections the server
 * closes, so the watch fails at once and the transfer goes over the channel.
 *
 * @param bindAddress where to listen; null for every interface
 */
class SocketWifiBodyServer(override val addresses: List<String>, bindAddress: InetAddress? = null) : WifiBodyServer {

    private val server = ServerSocket(0, BACKLOG, bindAddress)
    @Volatile private var client: Socket? = null

    override val port: Int get() = server.localPort

    override fun serve(token: String, body: () -> InputStream, size: Long, onProgress: (Long) -> Unit) {
        accept(token).use { socket ->
            body().use { input -> WatchAppUpdateProtocol.copyBody(input, socket.getOutputStream(), size, onProgress) }
        }
    }

    override fun accept(token: String): Socket {
        var wrongRequests = 0
        while (true) {
            val socket = server.accept()
            client = socket
            if (socket.localAddress?.hostAddress in addresses && isWatch(socket, token)) return socket
            runCatching { socket.close() }
            if (++wrongRequests >= MAX_WRONG_REQUESTS) {
                // Close at once: the watch then fails fast instead of waiting for its read timeout
                close()
                throw IOException("Too many wrong requests on the Wi-Fi server")
            }
        }
    }

    private fun isWatch(socket: Socket, token: String): Boolean =
        try {
            val deadlineNs = System.nanoTime() + REQUEST_TIMEOUT_MS * 1_000_000L
            val request = WatchAppUpdateProtocol.readWifiRequest(DeadlineInputStream(socket, deadlineNs))
            // After the request the watch only reads; a stalled read on its side is its own timeout
            socket.soTimeout = 0
            WatchAppUpdateProtocol.sameToken(request, token)
        } catch (_: IOException) {
            false
        }

    override fun close() {
        runCatching { server.close() }
        runCatching { client?.close() }
    }

    /**
     * Reads from [socket] until [deadlineNs] (of [System.nanoTime]). Each read waits at most the time
     * that is left, so a client that sends a byte now and then cannot keep the connection longer.
     */
    private class DeadlineInputStream(private val socket: Socket, private val deadlineNs: Long) : InputStream() {

        private val input = socket.getInputStream()

        override fun read(): Int {
            waitAtMostTheTimeLeft()
            return input.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            waitAtMostTheTimeLeft()
            return input.read(b, off, len)
        }

        private fun waitAtMostTheTimeLeft() {
            val leftMs = (deadlineNs - System.nanoTime()) / 1_000_000
            if (leftMs <= 0) throw SocketTimeoutException("No whole request within $REQUEST_TIMEOUT_MS ms")
            // Never 0: that would wait for ever
            socket.soTimeout = leftMs.toInt()
        }
    }

    companion object {

        private const val BACKLOG = 2

        /**
         * The watch sends its whole request right after it connects; anything slower is not the watch.
         * This is the time for the whole request, not for each read.
         */
        const val REQUEST_TIMEOUT_MS = 2_000

        /** Something else on the network that knocks this often does not get more tries */
        const val MAX_WRONG_REQUESTS = 5
    }
}

/** Serves on the phone's Wi-Fi, or on its hotspot, where the watch may be connected too */
@ContributesBinding(AppScope::class)
@Inject
class AndroidWifiBodyServerFactory(private val context: Context) : WifiBodyServerFactory {

    override fun start(): WifiBodyServer? {
        val addresses = wifiAddresses()
        return if (addresses.isEmpty()) null else SocketWifiBodyServer(addresses)
    }

    private fun wifiAddresses(): List<String> {
        val result = LinkedHashSet<String>()
        // The Wi-Fi the phone uses now
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                connectivity.getLinkProperties(network)?.linkAddresses?.forEach { link ->
                    (link.address as? Inet4Address)?.hostAddress?.let { result += it }
                }
        }
        // Other Wi-Fi interfaces, such as the hotspot of the phone; never the mobile network
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && WIFI_INTERFACES.any { prefix -> it.name.startsWith(prefix) } }
                .flatMap { it.inetAddresses.toList() }
                .forEach { address -> (address as? Inet4Address)?.hostAddress?.let { result += it } }
        }
        return result.take(WatchAppUpdateProtocol.MAX_ADDRESSES)
    }

    companion object {

        /** Interface names of Wi-Fi and hotspot on Android phones */
        private val WIFI_INTERFACES = listOf("wlan", "swlan", "ap", "softap")
    }
}
