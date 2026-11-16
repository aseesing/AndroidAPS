package app.aaps.wear.selfupdate

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Fetches the update body from the phone over Wi-Fi.
 *
 * A watch that is connected to the phone over Bluetooth keeps its Wi-Fi off. Asking for a Wi-Fi
 * network makes Wear OS turn it on, when the user allows Wi-Fi and the watch knows a network. The
 * connection is bound to that network, so it never goes through the Bluetooth proxy of the phone.
 */
internal class NetworkWifiBodyFetcher(private val context: Context) : WifiBodyFetcher {

    override fun open(offer: WifiOffer): WifiBody {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: throw IOException("No connectivity service")
        val available = CompletableFuture<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.complete(network)
            }

            override fun onUnavailable() {
                available.completeExceptionally(IOException("No Wi-Fi network"))
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.requestNetwork(request, callback, NETWORK_TIMEOUT_MS)
        try {
            val network = awaitNetwork(available)
            val errors = mutableListOf<String>()
            for (address in offer.addresses) {
                val socket = network.socketFactory.createSocket()
                try {
                    socket.connect(InetSocketAddress(address, offer.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    WatchAppUpdateProtocol.writeWifiRequest(socket.getOutputStream(), offer.token)
                    val input = socket.getInputStream()
                    return object : WifiBody {
                        override val input: InputStream = input

                        override fun close() {
                            runCatching { socket.close() }
                            runCatching { connectivity.unregisterNetworkCallback(callback) }
                        }
                    }
                } catch (e: IOException) {
                    runCatching { socket.close() }
                    errors += "$address: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            throw IOException("Cannot reach the phone: ${errors.joinToString()}")
        } catch (e: Exception) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            throw e
        }
    }

    private fun awaitNetwork(available: CompletableFuture<Network>): Network =
        try {
            // A little longer than the request itself, which then reports onUnavailable
            available.get(NETWORK_TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause)
        } catch (_: TimeoutException) {
            throw IOException("No Wi-Fi network in time")
        }

    companion object {

        /** Turning on Wi-Fi and joining a network takes a few seconds, sometimes more */
        private const val NETWORK_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** The phone sends without a break; this long without data means the connection is gone */
        private const val READ_TIMEOUT_MS = 30_000
    }
}
