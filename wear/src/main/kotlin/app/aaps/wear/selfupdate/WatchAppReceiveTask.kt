package app.aaps.wear.selfupdate

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol.Reply
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

    fun create(): WatchAppInstallSession
}

/**
 * The watch side of [WatchAppUpdateProtocol]: reads one update from the phone and gives the APK
 * to the package installer.
 *
 * The APK is unpacked while it arrives, so the watch never holds the compressed copy in a file.
 */
class WatchAppReceiveTask(private val sessionFactory: WatchAppInstallSessionFactory) {

    sealed class Result {

        /** The APK went to the installer. The result of the install comes later, from Android. */
        data class Committed(val label: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * @param input from the phone
     * @param output to the phone
     * @param onProgress percent of the update received so far
     */
    fun receive(input: InputStream, output: OutputStream, onProgress: (Int) -> Unit = {}): Result {
        val header = try {
            WatchAppUpdateProtocol.readHeader(input)
        } catch (e: IOException) {
            return fail(output, "Wrong update: ${e.message}")
        }
        val session = try {
            sessionFactory.create()
        } catch (e: Exception) {
            return fail(output, "Cannot start the install: ${e.message ?: e.javaClass.simpleName}")
        }
        try {
            WatchAppUpdateProtocol.writeReply(output, Reply.Ready)
            val body = CountingInputStream(WatchAppUpdateProtocol.limited(input, header.bodySize))
            var percent = -1
            val buffer = ByteArray(64 * 1024)
            GZIPInputStream(body, buffer.size).use { apk ->
                while (true) {
                    val read = apk.read(buffer)
                    if (read < 0) break
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
            // Answer before the commit: when the install replaces this app, Android stops it soon after
            WatchAppUpdateProtocol.writeReply(output, Reply.Committed)
            session.commit()
            return Result.Committed(header.label)
        } catch (e: Exception) {
            runCatching { session.abandon() }
            return fail(output, e.message ?: e.javaClass.simpleName)
        }
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
