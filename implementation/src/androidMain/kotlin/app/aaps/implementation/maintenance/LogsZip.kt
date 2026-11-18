package app.aaps.implementation.maintenance

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes [files] as one zip into [output], with one entry per file, named like the file.
 *
 * [output] is closed on every path. When a file cannot be read, this throws instead of
 * returning, so the caller never takes a half written zip for a good one.
 */
internal fun writeLogsZip(files: List<File>, output: OutputStream) {
    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
        for (file in files) {
            // Open the file first: if that fails, no empty entry is left in the zip
            FileInputStream(file).use { input ->
                zip.putNextEntry(ZipEntry(file.name))
                input.copyTo(zip)
                zip.closeEntry()
            }
        }
    }
}
