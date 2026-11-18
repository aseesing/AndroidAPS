package app.aaps.wear.watchlog

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import app.aaps.shared.impl.weardata.WatchLogProtocol
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A zip of log files, ready to send */
class LogZip(val file: File, val zip: WatchLogProtocol.Zip, val fileCount: Int)

/** Makes the zip of the log files; a fake in tests */
fun interface LogZipSource {

    /**
     * @param maxFiles the newest this many log files; [WatchLogProtocol.ALL_FILES] for all. Never more
     *   than [WatchLogProtocol.MAX_LOG_BYTES] of files together.
     * @throws IOException when there is no log, or the zip cannot be written
     */
    fun zip(maxFiles: Int): LogZip
}

/**
 * Zips the log files of the watch app into [target].
 *
 * logback writes `AndroidAPS.log` and rolls older parts into `AndroidAPS._<date>_<time>_.<n>.zip`
 * (see `wear/src/main/assets/logback.xml`). The zip holds them as they are, so an old part is a zip
 * inside the zip, like in the log export of the phone. Only these files, directly in the log folder,
 * with their plain names as entries. Newest first, up to [WatchLogProtocol.MAX_LOG_BYTES] together:
 * older files stay on the watch.
 *
 * @param logDirectory where logback writes; null when it is not known
 */
class WatchLogZipper(private val logDirectory: () -> File?, private val target: File) : LogZipSource {

    override fun zip(maxFiles: Int): LogZip {
        val directory = logDirectory() ?: throw IOException("The log folder is not known")
        val files = logFiles(directory, maxFiles, WatchLogProtocol.MAX_LOG_BYTES)
        if (files.isEmpty()) throw IOException("No log files in ${directory.absolutePath}")
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        ZipOutputStream(BufferedOutputStream(DigestOutputStream(FileOutputStream(target), digest))).use { zip ->
            for (file in files) {
                // The old parts are zips already: compressing them again costs battery and gains nothing
                zip.setLevel(if (file.name.endsWith(".zip")) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(file.name).apply { time = file.lastModified() })
                // The current log grows while it is read: take what is there now
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return LogZip(target, WatchLogProtocol.Zip(target.length(), WatchAppUpdateProtocol.hex(digest.digest())), files.size)
    }

    companion object {

        /**
         * The log files in [directory], newest first: at most [maxFiles] of them (all for
         * [WatchLogProtocol.ALL_FILES]), and only as many as fit in [maxBytes] together. Stops at the
         * first file that does not fit, so the zip always holds the newest part of the log.
         */
        fun logFiles(directory: File, maxFiles: Int, maxBytes: Long): List<File> {
            val all = directory.listFiles { file -> file.isFile && isLogFile(file.name) }.orEmpty()
                .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            val counted = if (maxFiles == WatchLogProtocol.ALL_FILES) all else all.take(maxFiles)
            var total = 0L
            return counted.takeWhile { file ->
                total += file.length()
                total <= maxBytes
            }
        }

        private fun isLogFile(name: String): Boolean =
            name.startsWith("AndroidAPS") && (name.endsWith(".log") || name.endsWith(".zip"))
    }
}
