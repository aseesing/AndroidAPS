package app.aaps.wear.selfupdate

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Header
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** One install of an APK. On Android a `PackageInstaller.Session`, a fake in tests. */
interface WatchAppInstallSession {

    /** Where the APK goes */
    val output: OutputStream

    /** All of the APK is written: install it */
    fun commit()

    /** Something went wrong: drop what was written */
    fun abandon()
}

/** Starts an install session; throws when the package installer refuses */
fun interface WatchAppInstallSessionFactory {

    /** @param apkSize the exact size of the APK that will be written */
    fun create(apkSize: Long): WatchAppInstallSession
}

/**
 * A Wi-Fi connection to the phone. The update reads the body from [input]; the watch log download
 * writes the log to [output]. [close] ends the connection and frees the network.
 */
interface WifiBody : Closeable {

    val input: InputStream
    val output: OutputStream
}

/** Connects to the Wi-Fi server of the phone, see [WifiOffer] */
fun interface WifiBodyFetcher {

    /** @throws IOException when the watch has no Wi-Fi, or cannot reach the phone */
    fun open(offer: WifiOffer): WifiBody
}

/**
 * The watch side of [WatchAppUpdateProtocol]: reads one update from the phone and gives the APK
 * to the package installer.
 *
 * When the phone offers Wi-Fi, the body comes from there, which is much faster. When that does not
 * work, the body comes over the channel as before. The APK is unpacked while it arrives, so the
 * watch never holds the compressed copy in a file.
 *
 * @param wifi connects to the phone over Wi-Fi; null for the channel only
 * @param log gets why Wi-Fi did not work
 */
class WatchAppReceiveTask(
    private val wifi: WifiBodyFetcher? = null,
    private val log: (String) -> Unit = {},
    private val sessionFactory: WatchAppInstallSessionFactory
) {

    sealed class Result {

        /** The APK went to the installer. The result of the install comes later, from Android. */
        data class Committed(val label: String, val overWifi: Boolean = false) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * @param input from the phone
     * @param output to the phone
     * @param onProgress percent of the update received so far; starts again at 0 when Wi-Fi fails
     */
    fun receive(input: InputStream, output: OutputStream, onProgress: (Int) -> Unit = {}): Result {
        val header = try {
            WatchAppUpdateProtocol.readHeader(input)
        } catch (e: IOException) {
            return fail(output, "Wrong update: ${e.message}")
        }
        var session = try {
            sessionFactory.create(header.apkSize)
        } catch (e: Exception) {
            return fail(output, "Cannot start the install: ${e.message ?: e.javaClass.simpleName}")
        }
        try {
            val offer = header.wifi
            if (offer != null && wifi != null) {
                WatchAppUpdateProtocol.writeReply(output, Reply.WifiStarted)
                val overWifi = try {
                    wifi.open(offer).use { body -> unpack(body.input, header, session, onProgress) }
                    true
                } catch (e: Exception) {
                    log("Wi-Fi did not work, the update comes over the channel: ${e.message ?: e.javaClass.simpleName}")
                    runCatching { session.abandon() }
                    session = sessionFactory.create(header.apkSize)
                    false
                }
                // Outside the Wi-Fi try: a failing commit is a failure, not a reason to send again
                if (overWifi) return commit(output, header, session, overWifi = true)
            }
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            unpack(input, header, session, onProgress)
            return commit(output, header, session, overWifi = false)
        } catch (e: Exception) {
            runCatching { session.abandon() }
            return fail(output, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Unpacks exactly [Header.bodySize] bytes of [input] into [session], and checks them. Stops at
     * once when the APK gets larger than [Header.apkSize]: the hash is only known at the end, and a
     * broken body must not fill the storage of the watch before that.
     */
    private fun unpack(input: InputStream, header: Header, session: WatchAppInstallSession, onProgress: (Int) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val body = CountingInputStream(DigestInputStream(WatchAppUpdateProtocol.limited(input, header.bodySize), digest))
        var percent = -1
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        GZIPInputStream(body, buffer.size).use { apk ->
            while (true) {
                val read = apk.read(buffer)
                if (read < 0) break
                written += read
                if (written > header.apkSize) throw IOException("The APK is larger than ${header.apkSize} bytes")
                session.output.write(buffer, 0, read)
                val now = (body.count * 100 / header.bodySize).toInt()
                if (now != percent) {
                    percent = now
                    onProgress(now)
                }
            }
        }
        // The body must be one gzip member and nothing else: a shorter body means a broken sender
        if (body.count != header.bodySize) throw IOException("Update has ${body.count} of ${header.bodySize} bytes")
        if (written != header.apkSize) throw IOException("The APK has $written of ${header.apkSize} bytes")
        if (WatchAppUpdateProtocol.hex(digest.digest()) != header.sha256) throw IOException("Update has a wrong SHA-256")
    }

    private fun commit(output: OutputStream, header: Header, session: WatchAppInstallSession, overWifi: Boolean): Result {
        // Commit first, so the phone never hears "committed" for an install that did not start. The
        // commit only hands the APK over; Android installs it later, and then stops this app.
        session.commit()
        // From here the install is in the hands of the installer: never abandon it. The phone may be
        // gone already; it then tells the user to look on the watch.
        runCatching { WatchAppUpdateProtocol.writeReply(output, Reply.Committed) }
        return Result.Committed(header.label, overWifi)
    }

    private fun fail(output: OutputStream, message: String): Result.Failed {
        // The phone may be gone already; the watch still shows the reason
        runCatching { WatchAppUpdateProtocol.writeReply(output, Reply.Failed(message)) }
        return Result.Failed(message)
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {

        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
