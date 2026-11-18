package app.aaps.shared.impl.weardata

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * How the phone sends a new version of the watch app to the watch, over one Wear OS channel.
 *
 * The phone app can carry the watch APK inside its assets (built with `-PembedWearApk=<file>`).
 * The phone opens a channel on [PATH] and the two sides talk like this:
 *
 * 1. phone: [Header] - a magic number, the protocol version, a label, the size and the SHA-256 of
 *    the body, the size of the unpacked APK, and a [WifiOffer] when the phone has a Wi-Fi address
 * 2. watch, with a Wi-Fi offer: [Reply.WifiStarted]. The watch turns on its Wi-Fi, connects to the
 *    phone (see [writeWifiRequest]) and fetches the body there, which is much faster than the
 *    channel over Bluetooth. Then it answers [Reply.Committed] or [Reply.Failed] as in step 4, or
 *    [Reply.Ready] when Wi-Fi did not work: then the body comes over the channel as in step 3.
 * 2. watch, without a Wi-Fi offer: [Reply.Ready] - the watch can install. A watch app older than
 *    this feature does not know the path and never answers, so the phone stops after a timeout.
 * 3. phone: the body - the watch APK, gzip compressed, exactly [Header.bodySize] bytes
 * 4. watch: [Reply.Committed] when the APK went to the package installer, or [Reply.Failed]
 *
 * gzip has its own CRC check, and the watch checks the SHA-256 from the header, so a damaged or
 * wrong body is never installed. The header comes over the channel, which only the paired phone
 * can open, so the hash is a trusted one also for a body that came over Wi-Fi. The watch stops
 * unpacking at the APK size from the header, so a broken body cannot fill its storage. The
 * installer itself also refuses an APK that is not signed with the same key as the installed watch
 * app.
 *
 * Versions 1 and 2 were early formats in test builds; version 3 had no [WifiOffer]. When the
 * versions of the two apps differ, the watch answers [Reply.Failed]. For a header that is newer than
 * the watch app, the message holds [UNKNOWN_FORMAT], and the phone asks the user to install the
 * watch app once with adb. For a header that is older than the watch app, the message holds
 * [OLD_FORMAT], and the phone asks for a newer phone app.
 */
object WatchAppUpdateProtocol {

    /** The channel path. Both apps must use the same value. */
    const val PATH = "/aaps/watch_app_update"

    /** "AAPU": a random stream is not taken as an update */
    const val MAGIC = 0x41415055

    /** Raise this when the format changes; the watch refuses a version it does not know */
    const val VERSION = 4

    /**
     * The start of the [Reply.Failed] message for a header version the watch does not know.
     * Watch apps of every version send this text, so it must never change.
     */
    const val UNKNOWN_FORMAT = "Unknown update format"

    /**
     * The start of the [Reply.Failed] message for a header version that is older than the watch app:
     * the phone app is older than the watch app. This text must never change either.
     */
    const val OLD_FORMAT = "Update format older than the watch app"

    /** The [Reply.Failed] message when the watch is still busy with another update */
    const val BUSY = "Busy with another update"

    /** At most this many phone addresses in a [WifiOffer] */
    const val MAX_ADDRESSES = 8

    /** Larger than any real watch APK, so a broken header cannot make the watch wait for gigabytes */
    const val MAX_BODY_SIZE = 500L * 1024 * 1024

    /** The same for the unpacked APK */
    const val MAX_APK_SIZE = 500L * 1024 * 1024

    /** A SHA-256 as [sha256] writes it: the watch never installs a body without one */
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /**
     * @param label shown to the user, for example the version and file name of the watch APK
     * @param bodySize number of bytes of the gzip compressed APK
     * @param apkSize number of bytes of the APK after unpacking
     * @param sha256 of the gzip compressed APK, lower case hex
     * @param wifi where the watch can fetch the body over Wi-Fi; null for the channel only
     */
    data class Header(
        val label: String,
        val bodySize: Long,
        val apkSize: Long,
        val sha256: String,
        val wifi: WifiOffer? = null
    )

    /**
     * A one-time server on the phone, reachable over Wi-Fi, that sends the body.
     *
     * @param addresses the phone's IP addresses, best first; the watch tries them in this order
     * @param port the TCP port of the server
     * @param token a random secret for this one update; the server sends nothing without it.
     *   [toString] never shows it, so it cannot end up in a log.
     */
    data class WifiOffer(val addresses: List<String>, val port: Int, val token: String) {

        override fun toString(): String = "WifiOffer(addresses=$addresses, port=$port, token=hidden)"
    }

    sealed class Reply {

        /** The watch is ready to receive the body over the channel */
        data object Ready : Reply()

        /** The watch fetches the body over Wi-Fi now; the next reply comes when it is done */
        data object WifiStarted : Reply()

        /** The watch gave the APK to the package installer. The watch may still ask the user to confirm. */
        data object Committed : Reply()

        /** The watch could not take the update. [message] is for the user and the log. */
        data class Failed(val message: String) : Reply()
    }

    private const val REPLY_READY = 1
    private const val REPLY_COMMITTED = 2
    private const val REPLY_FAILED = 3
    private const val REPLY_WIFI_STARTED = 4

    fun writeHeader(output: OutputStream, header: Header) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeUTF(header.label)
        data.writeLong(header.bodySize)
        data.writeLong(header.apkSize)
        data.writeUTF(header.sha256)
        writeWifiOffer(data, header.wifi)
        data.flush()
    }

    /** @throws IOException when the stream is not an update of a version this app understands */
    fun readHeader(input: InputStream): Header {
        val data = DataInputStream(input)
        val magic = data.readInt()
        if (magic != MAGIC) throw IOException("Not a watch app update")
        val version = data.readInt()
        if (version < VERSION) throw IOException("$OLD_FORMAT $version, expected $VERSION")
        if (version != VERSION) throw IOException("$UNKNOWN_FORMAT $version, expected $VERSION")
        val label = data.readUTF()
        val bodySize = data.readLong()
        if (bodySize !in 1..MAX_BODY_SIZE) throw IOException("Wrong update size $bodySize")
        val apkSize = data.readLong()
        if (apkSize !in 1..MAX_APK_SIZE) throw IOException("Wrong APK size $apkSize")
        val sha256 = data.readUTF()
        if (!SHA256_HEX.matches(sha256)) throw IOException("Wrong update hash")
        return Header(label, bodySize, apkSize, sha256, readWifiOffer(data))
    }

    /** Writes an optional [WifiOffer] */
    internal fun writeWifiOffer(data: DataOutputStream, wifi: WifiOffer?) {
        data.writeBoolean(wifi != null)
        if (wifi != null) {
            data.writeInt(wifi.port)
            data.writeUTF(wifi.token)
            val addresses = wifi.addresses.take(MAX_ADDRESSES)
            data.writeInt(addresses.size)
            addresses.forEach { data.writeUTF(it) }
        }
    }

    /** Reads what [writeWifiOffer] wrote. @throws IOException when the values are out of range */
    internal fun readWifiOffer(data: DataInputStream): WifiOffer? {
        if (!data.readBoolean()) return null
        val port = data.readInt()
        if (port !in 1..65535) throw IOException("Wrong port $port")
        val token = data.readUTF()
        val count = data.readInt()
        if (count !in 0..MAX_ADDRESSES) throw IOException("Wrong address count $count")
        return WifiOffer(List(count) { data.readUTF() }, port, token)
    }

    /** The first thing the watch sends on the Wi-Fi connection: who it is, and the secret from the offer */
    fun writeWifiRequest(output: OutputStream, token: String) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeUTF(token)
        data.flush()
    }

    /**
     * Reads what [writeWifiRequest] wrote. After it, the phone sends exactly [Header.bodySize] bytes.
     *
     * @return the token
     * @throws IOException when the stream is something else
     */
    fun readWifiRequest(input: InputStream): String {
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) throw IOException("Not a watch app update request")
        return data.readUTF()
    }

    /** Compares two tokens in a time that does not tell how much of them matched */
    fun sameToken(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** The SHA-256 of all of [input] as lower case hex */
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    /** Lower case hex, as [sha256] returns it */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun writeReply(output: OutputStream, reply: Reply) {
        val data = DataOutputStream(output)
        when (reply) {
            Reply.Ready       -> data.writeInt(REPLY_READY)
            Reply.Committed   -> data.writeInt(REPLY_COMMITTED)
            Reply.WifiStarted -> data.writeInt(REPLY_WIFI_STARTED)
            is Reply.Failed   -> {
                data.writeInt(REPLY_FAILED)
                data.writeUTF(reply.message.take(1000))
            }
        }
        data.flush()
    }

    /** @throws IOException when the stream ends or holds something else than a reply */
    fun readReply(input: InputStream): Reply {
        val data = DataInputStream(input)
        return when (val code = data.readInt()) {
            REPLY_READY        -> Reply.Ready
            REPLY_COMMITTED    -> Reply.Committed
            REPLY_WIFI_STARTED -> Reply.WifiStarted
            REPLY_FAILED       -> Reply.Failed(data.readUTF())
            else               -> throw IOException("Unknown reply $code")
        }
    }

    /**
     * Copies exactly [size] bytes from [input] to [output].
     *
     * @param onProgress called with the number of bytes copied so far, after each block
     * @throws IOException when [input] ends early
     */
    fun copyBody(input: InputStream, output: OutputStream, size: Long, onProgress: (Long) -> Unit = {}) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (copied < size) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt())
            if (read < 0) throw IOException("Update ended after $copied of $size bytes")
            output.write(buffer, 0, read)
            copied += read
            onProgress(copied)
        }
        output.flush()
    }

    /**
     * A view of [input] that ends after [size] bytes and never reads further.
     *
     * The channel stays open after the body for the reply, so a reader that reads "until the end"
     * (a gzip stream checks for a next member) would wait for ever. Closing the view does not close [input].
     */
    fun limited(input: InputStream, size: Long): InputStream = LimitedInputStream(input, size)

    private class LimitedInputStream(private val input: InputStream, private var left: Long) : InputStream() {

        override fun read(): Int {
            if (left <= 0) return -1
            val value = input.read()
            if (value >= 0) left--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val read = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }

        override fun available(): Int = minOf(input.available().toLong(), left).toInt()

        override fun close() {
            // The channel belongs to the caller
        }
    }
}
