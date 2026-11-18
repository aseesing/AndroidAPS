package app.aaps.wear.watchlog

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
import app.aaps.shared.impl.weardata.WatchLogCrypto
import app.aaps.shared.impl.weardata.WatchLogCrypto.Secret
import app.aaps.shared.impl.weardata.WatchLogProtocol
import app.aaps.shared.impl.weardata.WatchLogProtocol.Reply
import app.aaps.wear.selfupdate.WifiBodyFetcher
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * The watch side of [WatchLogProtocol]: reads the request of the phone and sends the log zip.
 *
 * When the phone offers Wi-Fi, the watch connects first and only then makes the zip with all log
 * files, so it does not zip twice when Wi-Fi is off. Over Wi-Fi the zip is encrypted with the secret
 * from the request ([WatchLogCrypto]). When Wi-Fi does not work, a zip with fewer files goes over the
 * channel, as it is.
 *
 * @param zipper makes the zip of the log files
 * @param wifi connects to the phone over Wi-Fi; null for the channel only
 * @param log gets why Wi-Fi did not work
 * @param stallTimeoutMs a write on Wi-Fi that does not move this long is ended
 */
class WatchLogSendTask(
    private val zipper: LogZipSource,
    private val wifi: WifiBodyFetcher? = null,
    private val log: (String) -> Unit = {},
    private val stallTimeoutMs: Long = 60_000
) {

    sealed class Result {

        data class Sent(val fileCount: Int, val size: Long, val overWifi: Boolean) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * @param input from the phone
     * @param output to the phone
     */
    fun send(input: InputStream, output: OutputStream): Result {
        val request = try {
            WatchLogProtocol.readRequest(input)
        } catch (e: IOException) {
            return fail(output, "Wrong log request: ${e.message}")
        }
        try {
            // At once, so the phone knows this watch app can do it; zipping can take a while
            WatchLogProtocol.writeReply(output, Reply.Accepted)
            val offer = request.wifi
            val secret = request.secret
            val wifiZip = if (offer != null && secret != null && wifi != null) sendOverWifi(wifi, offer, secret, request, output) else null
            if (wifiZip is WifiResult.Sent) return Result.Sent(wifiZip.zip.fileCount, wifiZip.zip.zip.size, overWifi = true)

            // The same zip, when it has the files the channel may carry; otherwise a smaller one
            val zip = (wifiZip as? WifiResult.Broken)?.zip?.takeIf { request.filesOverWifi == request.filesOverChannel } ?: zipper.zip(request.filesOverChannel)
            WatchLogProtocol.writeReply(output, Reply.ChannelBody(zip.zip))
            zip.file.inputStream().use { body -> WatchAppUpdateProtocol.copyBody(body, output, zip.zip.size) }
            return Result.Sent(zip.fileCount, zip.zip.size, overWifi = false)
        } catch (e: Exception) {
            return fail(output, e.message ?: e.javaClass.simpleName)
        }
    }

    private sealed class WifiResult {

        class Sent(val zip: LogZip) : WifiResult()

        /** Wi-Fi broke off during the transfer; [zip] is already made */
        class Broken(val zip: LogZip) : WifiResult()
    }

    /** @return null when the watch could not connect; a failing zip throws */
    private fun sendOverWifi(fetcher: WifiBodyFetcher, offer: WifiOffer, secret: Secret, request: WatchLogProtocol.Request, output: OutputStream): WifiResult? {
        val connection = try {
            fetcher.open(offer)
        } catch (e: Exception) {
            log("Wi-Fi did not work, the log goes over the channel: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
        connection.use {
            val zip = zipper.zip(request.filesOverWifi)
            // Only the phone that sent the secret over the channel can read it; the zip is at most
            // MAX_ZIP_SIZE, so it fits in memory once
            val body = WatchLogCrypto.encrypt(zip.file.readBytes(), secret)
            WatchLogProtocol.writeReply(output, Reply.WifiZip(body.size.toLong()))
            try {
                // A socket write has no timeout of its own: when the phone is gone, it could wait for minutes
                StallGuard(stallTimeoutMs) { runCatching { connection.close() } }.use { guard ->
                    WatchAppUpdateProtocol.copyBody(ByteArrayInputStream(body), connection.output, body.size.toLong()) { guard.touch() }
                }
            } catch (e: Exception) {
                log("Wi-Fi broke off, the log goes over the channel: ${e.message ?: e.javaClass.simpleName}")
                return WifiResult.Broken(zip)
            }
            WatchLogProtocol.writeReply(output, Reply.WifiDone)
            return WifiResult.Sent(zip)
        }
    }

    private fun fail(output: OutputStream, message: String): Result.Failed {
        // The phone may be gone already
        runCatching { WatchLogProtocol.writeReply(output, Reply.Failed(message)) }
        return Result.Failed(message)
    }
}

/** Calls [onStall] when [touch] was not called for [timeoutMs]. [close] stops it. */
internal class StallGuard(private val timeoutMs: Long, private val onStall: () -> Unit) : Closeable {

    @Volatile private var last = System.nanoTime()
    @Volatile private var done = false
    private val thread = thread(isDaemon = true, name = "WatchLogStallGuard") {
        try {
            while (!done) {
                Thread.sleep(CHECK_INTERVAL_MS)
                if (!done && (System.nanoTime() - last) / 1_000_000 > timeoutMs) {
                    onStall()
                    return@thread
                }
            }
        } catch (_: InterruptedException) {
            // Closed
        }
    }

    fun touch() {
        last = System.nanoTime()
    }

    override fun close() {
        done = true
        thread.interrupt()
    }

    companion object {

        private const val CHECK_INTERVAL_MS = 200L
    }
}
