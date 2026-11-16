package app.aaps.shared.impl.weardata

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * How the phone sends a new version of the watch app to the watch, over one Wear OS channel.
 *
 * The phone app can carry the watch APK inside its assets (built with `-PembedWearApk=<file>`).
 * The phone opens a channel on [PATH] and the two sides talk like this:
 *
 * 1. phone: [Header] - a magic number, the protocol version, a label and the size of the body
 * 2. watch: [Reply.Ready] - the watch can install. An older watch app does not know the path and
 *    never answers, so the phone stops after a timeout.
 * 3. phone: the body - the watch APK, gzip compressed, exactly [Header.bodySize] bytes
 * 4. watch: [Reply.Committed] when the APK went to the package installer, or [Reply.Failed]
 *
 * gzip has its own CRC check, so a damaged transfer fails on the watch before anything is installed.
 * The installer itself also refuses an APK that is not signed with the same key as the installed
 * watch app.
 */
object WatchAppUpdateProtocol {

    /** The channel path. Both apps must use the same value. */
    const val PATH = "/aaps/watch_app_update"

    /** "AAPU": a random stream is not taken as an update */
    const val MAGIC = 0x41415055

    /** Raise this when the format changes; the watch refuses a version it does not know */
    const val VERSION = 1

    /** Larger than any real watch APK, so a broken header cannot make the watch wait for gigabytes */
    const val MAX_BODY_SIZE = 500L * 1024 * 1024

    /**
     * @param label shown to the user, for example the version and file name of the watch APK
     * @param bodySize number of bytes of the gzip compressed APK that follow the header
     */
    data class Header(val label: String, val bodySize: Long)

    sealed class Reply {

        /** The watch is ready to receive the body */
        data object Ready : Reply()

        /** The watch gave the APK to the package installer. The watch may still ask the user to confirm. */
        data object Committed : Reply()

        /** The watch could not take the update. [message] is for the user and the log. */
        data class Failed(val message: String) : Reply()
    }

    private const val REPLY_READY = 1
    private const val REPLY_COMMITTED = 2
    private const val REPLY_FAILED = 3

    fun writeHeader(output: OutputStream, header: Header) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeUTF(header.label)
        data.writeLong(header.bodySize)
        data.flush()
    }

    /** @throws IOException when the stream is not an update of a version this app understands */
    fun readHeader(input: InputStream): Header {
        val data = DataInputStream(input)
        val magic = data.readInt()
        if (magic != MAGIC) throw IOException("Not a watch app update")
        val version = data.readInt()
        if (version != VERSION) throw IOException("Unknown update format $version, expected $VERSION")
        val label = data.readUTF()
        val bodySize = data.readLong()
        if (bodySize !in 1..MAX_BODY_SIZE) throw IOException("Wrong update size $bodySize")
        return Header(label, bodySize)
    }

    fun writeReply(output: OutputStream, reply: Reply) {
        val data = DataOutputStream(output)
        when (reply) {
            Reply.Ready      -> data.writeInt(REPLY_READY)
            Reply.Committed  -> data.writeInt(REPLY_COMMITTED)
            is Reply.Failed  -> {
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
            REPLY_READY     -> Reply.Ready
            REPLY_COMMITTED -> Reply.Committed
            REPLY_FAILED    -> Reply.Failed(data.readUTF())
            else            -> throw IOException("Unknown reply $code")
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
