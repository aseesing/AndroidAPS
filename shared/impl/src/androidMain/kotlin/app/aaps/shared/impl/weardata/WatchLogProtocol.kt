// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.shared.impl.weardata

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.WifiOffer
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
 * 1. phone: [Request] - a magic number, the protocol version, how many log files it wants, and a
 *    [WifiOffer] when the phone has a Wi-Fi address
 * 2. watch: [Reply.Accepted] at once, before the slow work. A watch app older than this feature
 *    does not know the path and never answers, so the phone can stop after a short timeout.
 * 3. watch, with a Wi-Fi offer: it turns on its Wi-Fi and connects to the phone (see
 *    [WatchAppUpdateProtocol.writeWifiRequest]). Only when that works, it zips the log files and
 *    answers [Reply.WifiZip] with the size and SHA-256 of the zip, writes exactly that many bytes on
 *    the Wi-Fi connection, and answers [Reply.WifiDone]. When Wi-Fi did not work, it goes on with
 *    step 4 instead.
 * 4. watch, over the channel: [Reply.ChannelBody] with the size and SHA-256 of the zip, then the zip
 *    itself, exactly that many bytes. This zip may hold fewer files than the one for Wi-Fi, because
 *    Bluetooth is slow.
 * 5. Instead of any reply the watch may send [Reply.Failed].
 *
 * The size and the hash come over the channel, which only the paired phone can open, so the phone
 * can trust them also for a zip that came over Wi-Fi. The Wi-Fi server of the phone only takes a
 * connection that has the token from the offer.
 */
object WatchLogProtocol {

    /** The channel path. Both apps must use the same value. */
    const val PATH = "/aaps/watch_logs"

    /** "AAPL": a random stream is not taken as a log request */
    const val MAGIC = 0x4141504C

    /** Raise this when the format changes; the watch refuses a version it does not know */
    const val VERSION = 1

    /** In [Request]: all log files the watch has */
    const val ALL_FILES = 0

    /** Larger than any real log zip, so a broken reply cannot make the phone wait for gigabytes */
    const val MAX_ZIP_SIZE = 500L * 1024 * 1024

    /**
     * @param filesOverWifi how many log files, newest first, the watch puts in the zip for Wi-Fi; [ALL_FILES] for all
     * @param filesOverChannel the same for the zip over the channel, which is much slower
     * @param wifi where the watch can send the zip over Wi-Fi; null for the channel only
     */
    data class Request(
        val filesOverWifi: Int,
        val filesOverChannel: Int,
        val wifi: WifiOffer? = null
    )

    /**
     * @param size of the zip in bytes
     * @param sha256 of the zip, lower case hex
     */
    data class Zip(val size: Long, val sha256: String)

    sealed class Reply {

        /** The watch got the request and starts; the next reply may take a while */
        data object Accepted : Reply()

        /** The watch is connected over Wi-Fi and writes [zip] there now; [WifiDone] follows */
        data class WifiZip(val zip: Zip) : Reply()

        /** All of the zip went over Wi-Fi */
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
        data.flush()
    }

    /** @throws IOException when the stream is not a log request of a version this app understands */
    fun readRequest(input: InputStream): Request {
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) throw IOException("Not a watch log request")
        val version = data.readInt()
        if (version != VERSION) throw IOException("Unknown log request format $version, expected $VERSION")
        val filesOverWifi = data.readInt()
        val filesOverChannel = data.readInt()
        if (filesOverWifi < 0 || filesOverChannel < 0) throw IOException("Wrong file count")
        val wifi = WatchAppUpdateProtocol.readWifiOffer(data)
        return Request(filesOverWifi, filesOverChannel, wifi)
    }

    fun writeReply(output: OutputStream, reply: Reply) {
        val data = DataOutputStream(output)
        when (reply) {
            Reply.Accepted       -> data.writeInt(REPLY_ACCEPTED)

            is Reply.WifiZip     -> {
                data.writeInt(REPLY_WIFI_ZIP)
                writeZip(data, reply.zip)
            }

            Reply.WifiDone       -> data.writeInt(REPLY_WIFI_DONE)

            is Reply.ChannelBody -> {
                data.writeInt(REPLY_CHANNEL_BODY)
                writeZip(data, reply.zip)
            }

            is Reply.Failed      -> {
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
            REPLY_ACCEPTED     -> Reply.Accepted
            REPLY_WIFI_ZIP     -> Reply.WifiZip(readZip(data))
            REPLY_WIFI_DONE    -> Reply.WifiDone
            REPLY_CHANNEL_BODY -> Reply.ChannelBody(readZip(data))
            REPLY_FAILED       -> Reply.Failed(data.readUTF())
            else               -> throw IOException("Unknown reply $code")
        }
    }

    private fun writeZip(data: DataOutputStream, zip: Zip) {
        data.writeLong(zip.size)
        data.writeUTF(zip.sha256)
    }

    private fun readZip(data: DataInputStream): Zip {
        val size = data.readLong()
        if (size !in 1..MAX_ZIP_SIZE) throw IOException("Wrong log size $size")
        return Zip(size, data.readUTF())
    }
}
