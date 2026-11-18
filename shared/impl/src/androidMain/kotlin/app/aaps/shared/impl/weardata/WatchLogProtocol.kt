package app.aaps.shared.impl.weardata

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
import app.aaps.shared.impl.weardata.WatchLogCrypto.Secret
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * How the phone downloads the log of the watch app, over one Wear OS channel.
 *
 * The phone opens a channel on [PATH] and the two sides talk like this:
 *
 * 1. phone: [Request] - a magic number, the protocol version, how many log files it wants, and when
 *    the phone has a Wi-Fi address a [WifiOffer] and a [Secret] (a new AES key and nonce)
 * 2. watch: [Reply.Accepted] at once, before the slow work. A watch app older than this feature
 *    does not know the path and never answers, so the phone can stop after a short timeout.
 * 3. watch, with a Wi-Fi offer: it turns on its Wi-Fi and connects to the phone (see
 *    [WatchAppUpdateProtocol.writeWifiRequest]). Only when that works, it zips the log files,
 *    encrypts the zip with the secret ([WatchLogCrypto]), answers [Reply.WifiZip] with the size of
 *    the encrypted body, writes exactly that many bytes on the Wi-Fi connection, and answers
 *    [Reply.WifiDone]. When Wi-Fi did not work, it goes on with step 4 instead.
 * 4. watch, over the channel: [Reply.ChannelBody] with the size and SHA-256 of the zip, then the zip
 *    itself, exactly that many bytes. This zip may hold fewer files than the one for Wi-Fi, because
 *    Bluetooth is slow.
 * 5. Instead of any reply the watch may send [Reply.Failed].
 *
 * The secret, the size and the hash come over the channel, which only the paired apps can use. Over
 * Wi-Fi the log is encrypted, and the AES-GCM tag proves that it came from the watch and is whole. Over
 * the channel the SHA-256 does that. Both zips hold at most [MAX_LOG_BYTES] of log files.
 */
object WatchLogProtocol {

    /** The channel path. Both apps must use the same value. */
    const val PATH = "/aaps/watch_logs"

    /** "AAPL": a random stream is not taken as a log request */
    const val MAGIC = 0x4141504C

    /** Raise this when the format changes; the watch refuses a version it does not know */
    const val VERSION = 2

    /**
     * The start of the [Reply.Failed] message for a request version the watch does not know.
     * Watch apps of every version send this text, so it must never change.
     */
    const val UNKNOWN_FORMAT = "Unknown log request format"

    /** The [Reply.Failed] message when the watch is still sending a log */
    const val BUSY = "Busy with another log download"

    /** In [Request]: all log files the watch has, up to [MAX_LOG_BYTES] */
    const val ALL_FILES = 0

    /** The watch takes the newest log files up to this many bytes on its storage, together */
    const val MAX_LOG_BYTES = 8L * 1024 * 1024

    /** A zip of [MAX_LOG_BYTES] of files, with room for the zip headers */
    const val MAX_ZIP_SIZE = MAX_LOG_BYTES + 1024 * 1024

    /**
     * @param filesOverWifi how many log files, newest first, the watch puts in the zip for Wi-Fi; [ALL_FILES] for all
     * @param filesOverChannel the same for the zip over the channel, which is much slower
     * @param wifi where the watch can send the zip over Wi-Fi; null for the channel only
     * @param secret the key and nonce for the zip over Wi-Fi; given exactly when [wifi] is
     */
    data class Request(
        val filesOverWifi: Int,
        val filesOverChannel: Int,
        val wifi: WifiOffer? = null,
        val secret: Secret? = null
    ) {

        init {
            require((wifi == null) == (secret == null)) { "A Wi-Fi offer needs a secret" }
        }
    }

    /**
     * @param size of the zip in bytes
     * @param sha256 of the zip, lower case hex
     */
    data class Zip(val size: Long, val sha256: String)

    sealed class Reply {

        /** The watch got the request and starts; the next reply may take a while */
        data object Accepted : Reply()

        /** The watch is connected over Wi-Fi and writes the encrypted zip there now, [size] bytes; [WifiDone] follows */
        data class WifiZip(val size: Long) : Reply()

        /** All of the encrypted zip went over Wi-Fi */
        data object WifiDone : Reply()

        /** [zip] comes over the channel, right after this reply */
        data class ChannelBody(val zip: Zip) : Reply()

        /** The watch cannot send its log. [message] is for the user and the log. */
        data class Failed(val message: String) : Reply()
    }

    private const val REPLY_ACCEPTED = 1
    private const val REPLY_WIFI_ZIP = 2
    private const val REPLY_WIFI_DONE = 3
    private const val REPLY_CHANNEL_BODY = 4
    private const val REPLY_FAILED = 5

    fun writeRequest(output: OutputStream, request: Request) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeInt(request.filesOverWifi)
        data.writeInt(request.filesOverChannel)
        WatchAppUpdateProtocol.writeWifiOffer(data, request.wifi)
        request.secret?.let { secret ->
            data.write(secret.key)
            data.write(secret.nonce)
        }
        data.flush()
    }

    /** @throws IOException when the stream is not a log request of a version this app understands */
    fun readRequest(input: InputStream): Request {
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) throw IOException("Not a watch log request")
        val version = data.readInt()
        if (version != VERSION) throw IOException("$UNKNOWN_FORMAT $version, expected $VERSION")
        val filesOverWifi = data.readInt()
        val filesOverChannel = data.readInt()
        if (filesOverWifi < 0 || filesOverChannel < 0) throw IOException("Wrong file count")
        val wifi = WatchAppUpdateProtocol.readWifiOffer(data)
        val secret = wifi?.let {
            val key = ByteArray(WatchLogCrypto.KEY_BYTES).also { bytes -> data.readFully(bytes) }
            val nonce = ByteArray(WatchLogCrypto.NONCE_BYTES).also { bytes -> data.readFully(bytes) }
            Secret(key, nonce)
        }
        return Request(filesOverWifi, filesOverChannel, wifi, secret)
    }

    fun writeReply(output: OutputStream, reply: Reply) {
        val data = DataOutputStream(output)
        when (reply) {
            Reply.Accepted       -> data.writeInt(REPLY_ACCEPTED)

            is Reply.WifiZip     -> {
                data.writeInt(REPLY_WIFI_ZIP)
                data.writeLong(reply.size)
            }

            Reply.WifiDone       -> data.writeInt(REPLY_WIFI_DONE)

            is Reply.ChannelBody -> {
                data.writeInt(REPLY_CHANNEL_BODY)
                data.writeLong(reply.zip.size)
                data.writeUTF(reply.zip.sha256)
            }

            is Reply.Failed      -> {
                data.writeInt(REPLY_FAILED)
                data.writeUTF(reply.message.take(1000))
            }
        }
        data.flush()
    }

    /** @throws IOException when the stream ends, holds something else than a reply, or a size over the limit */
    fun readReply(input: InputStream): Reply {
        val data = DataInputStream(input)
        return when (val code = data.readInt()) {
            REPLY_ACCEPTED     -> Reply.Accepted

            REPLY_WIFI_ZIP     -> {
                val size = data.readLong()
                if (size !in WatchLogCrypto.TAG_BYTES + 1..MAX_ZIP_SIZE + WatchLogCrypto.TAG_BYTES) throw IOException("Wrong log size $size")
                Reply.WifiZip(size)
            }

            REPLY_WIFI_DONE    -> Reply.WifiDone

            REPLY_CHANNEL_BODY -> {
                val size = data.readLong()
                if (size !in 1..MAX_ZIP_SIZE) throw IOException("Wrong log size $size")
                Reply.ChannelBody(Zip(size, data.readUTF()))
            }

            REPLY_FAILED       -> Reply.Failed(data.readUTF())
            else               -> throw IOException("Unknown reply $code")
        }
    }
}
