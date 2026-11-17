package app.aaps.plugins.sync.wear.compose

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.sync.SyncStrings
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * One zip the wearer picked: its file name and how to read it. [read] gives null when it cannot be
 * read, and throws [DesignTooBigException] when the file is too big.
 */
internal class PickedDesign(val fileName: String, val read: () -> ByteArray?)

/** A picked file is bigger than [CwfDesignImport.MAX_ZIP_BYTES]: it is not read to the end. */
internal class DesignTooBigException : IOException()

/** The folder the designs are copied to: AAPS/export. */
internal interface DesignFolder {

    /** Writes [bytes] as [fileName] and replaces a file with that name. Returns false when it could not write. */
    fun write(fileName: String, bytes: ByteArray): Boolean
}

/** Why a picked file was not imported. */
internal enum class DesignRefusal { TOO_BIG, NOT_A_DESIGN, NOT_READ, NOT_WRITTEN }

/**
 * What an import did.
 *
 * @param imported the file names that are now in AAPS/export
 * @param refused the picked file names that were left out, with the reason
 */
internal data class DesignImportResult(val imported: List<String>, val refused: List<Pair<String, DesignRefusal>>)

/**
 * Copies Custom watch face zips into AAPS/export, from Google Drive, the phone storage, or any other
 * place the Android file picker shows.
 *
 * Without this the wearer had to put the zips into AAPS/export with a file manager, and delete the
 * old ones first. A zip with the same file name is replaced. A file that is not a watch face zip is
 * left out.
 *
 * The picked files come from anywhere, so they are checked before they are written: at most
 * [MAX_ZIP_BYTES] are read, the zip may unpack to at most [MAX_UNZIPPED_BYTES], and it must load as
 * a Custom watch face. The file name is only used inside AAPS/export, see [SafFolderFiles], and a
 * failed write never breaks the old design, see [SafeReplaceFolder].
 *
 * @param fileListProvider gives the AAPS/export folder
 */
@Inject
class CwfDesignImport(
    private val context: Context,
    private val fileListProvider: FileListProvider,
    private val rh: ResourceHelper,
    private val aapsLogger: AAPSLogger
) {

    /**
     * Copies the zips at [uris] into AAPS/export.
     *
     * @return the message for the wearer: how many designs were imported, then one line for each
     *   file that was not, with the reason
     */
    suspend fun importFrom(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val dir = fileListProvider.ensureExportDirExists() ?: return@withContext rh.gs(SyncStrings.wear_design_import_no_folder)
        val result = import(contentResolver.pickedDesigns(uris), SafeReplaceFolder(SafFolderFiles(dir, contentResolver, aapsLogger)))
        message(result)
    }

    internal fun message(result: DesignImportResult): String {
        val lines = listOf(rh.gs(SyncStrings.wear_design_import_done, result.imported.size)) +
            result.refused.map { (fileName, refusal) ->
                val reason = when (refusal) {
                    DesignRefusal.TOO_BIG      -> SyncStrings.wear_design_import_too_big
                    DesignRefusal.NOT_A_DESIGN -> SyncStrings.wear_design_import_not_a_design
                    DesignRefusal.NOT_READ     -> SyncStrings.wear_design_import_not_read
                    DesignRefusal.NOT_WRITTEN  -> SyncStrings.wear_design_import_not_written
                }
                rh.gs(reason, fileName)
            }
        // One file per line: each line is a whole sentence from its own string resource
        return lines.joinToString("\n")
    }

    internal fun import(picked: List<PickedDesign>, folder: DesignFolder): DesignImportResult {
        val imported = mutableListOf<String>()
        val refused = mutableListOf<Pair<String, DesignRefusal>>()
        // Two picks with the same name would end as one file: only the last one counts
        val unique = picked.associateBy { zipName(it.fileName).lowercase() }.values
        for (design in unique) {
            val fileName = zipName(design.fileName)
            val refusal = refusalOf(design, fileName, folder)
            if (refusal == null) imported += fileName else refused += design.fileName to refusal
        }
        aapsLogger.debug(LTag.WEAR, "Design import: imported $imported, refused $refused")
        return DesignImportResult(imported, refused)
    }

    /** Checks and writes one design. Returns null when it was imported, or why it was not. */
    private fun refusalOf(design: PickedDesign, fileName: String, folder: DesignFolder): DesignRefusal? {
        val bytes = try {
            design.read() ?: return DesignRefusal.NOT_READ
        } catch (_: DesignTooBigException) {
            return DesignRefusal.TOO_BIG
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Cannot read picked design ${design.fileName}", e)
            return DesignRefusal.NOT_READ
        }
        return when {
            !unzipsToAtMost(bytes, MAX_UNZIPPED_BYTES) -> DesignRefusal.TOO_BIG
            !isWatchfaceZip(bytes, fileName)           -> DesignRefusal.NOT_A_DESIGN
            !folder.write(fileName, bytes)             -> DesignRefusal.NOT_WRITTEN
            else                                       -> null
        }
    }

    /** The zips at [uris], named as the place they come from (Drive, Downloads, ...) names them. */
    private fun ContentResolver.pickedDesigns(uris: List<Uri>): List<PickedDesign> =
        uris.map { uri ->
            PickedDesign(fileName = displayName(uri) ?: uri.lastPathSegment ?: "design") {
                openInputStream(uri)?.use { readAtMost(it, MAX_ZIP_BYTES) }
            }
        }

    private fun ContentResolver.displayName(uri: Uri): String? =
        try {
            query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }

    companion object {

        /** A bigger file is not read at all. The watch only takes zips of about 320 KB anyway. */
        const val MAX_ZIP_BYTES = 5 * 1024 * 1024

        /**
         * A zip that unpacks to more is refused before it is loaded. A small zip can unpack to
         * gigabytes, and the loader keeps every file of the zip in memory.
         */
        const val MAX_UNZIPPED_BYTES = 20L * 1024 * 1024

        /** The MIME types the file picker offers. Drive does not always call a zip `application/zip`. */
        val ZIP_TYPES = arrayOf("application/zip", "application/x-zip", "application/x-zip-compressed", "application/octet-stream")

        private const val ZIP_EXTENSION = ".${ZipWatchfaceFormat.CWF_EXTENSION}"

        /**
         * The name in AAPS/export. It must end in a lower case `.zip`, because only those files are
         * listed: `Foo.ZIP` becomes `Foo.zip`, and `Foo` becomes `Foo.zip`.
         */
        fun zipName(fileName: String): String =
            if (fileName.endsWith(ZIP_EXTENSION, ignoreCase = true)) fileName.dropLast(ZIP_EXTENSION.length) + ZIP_EXTENSION
            else fileName + ZIP_EXTENSION

        /** True when [bytes] is a zip of a sane size that AAPS can load as a Custom watch face. */
        fun isWatchfaceZip(bytes: ByteArray, fileName: String): Boolean =
            try {
                unzipsToAtMost(bytes, MAX_UNZIPPED_BYTES) &&
                    ZipWatchfaceFormat.loadCustomWatchface(bytes, fileName, authorization = false) != null
            } catch (_: Exception) {
                false
            }

        /**
         * True when all files in the zip [bytes] together unpack to at most [limit] bytes. Nothing is
         * kept. Bytes that are not a zip unpack to nothing, so they pass here and fail as a design.
         */
        fun unzipsToAtMost(bytes: ByteArray, limit: Long): Boolean =
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (zip.nextEntry != null) {
                        while (true) {
                            val count = zip.read(buffer)
                            if (count == -1) break
                            total += count
                            if (total > limit) return false
                        }
                    }
                }
                true
            } catch (_: Exception) {
                true
            }

        /** Reads the whole stream. Throws [DesignTooBigException] when it is longer than [limit] bytes. */
        internal fun readAtMost(input: InputStream, limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                if (total > limit) throw DesignTooBigException()
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

/** The few file operations by name in one folder that [SafeReplaceFolder] needs. */
internal interface FolderFiles {

    fun exists(name: String): Boolean

    /** Makes an empty file [name]. False when it could not, or when the folder gave it another name (it is then deleted). */
    fun create(name: String): Boolean

    /** Writes the whole [bytes] into the existing file [name]. */
    fun write(name: String, bytes: ByteArray): Boolean

    /** Renames the file [from] to [to]. False when it could not, or when the result got another name (it is then named [from] again). */
    fun rename(from: String, to: String): Boolean

    /** Deletes the file [name]. False when there is no such file, or it could not be deleted. */
    fun delete(name: String): Boolean
}

/**
 * Writes a design so that a failure never leaves a broken or empty zip, and never loses the old
 * design of the same name.
 *
 * The new zip is first written under a temporary name. Only when that worked, the old zip is
 * renamed out of the way, the new one gets its name, and then the old one is deleted. When a step
 * fails, the steps before it are undone. The temporary names do not end in `zip`, so the list
 * never shows them.
 */
internal class SafeReplaceFolder(private val files: FolderFiles) : DesignFolder {

    override fun write(fileName: String, bytes: ByteArray): Boolean {
        val temp = "$fileName.new"
        val backup = "$fileName.old"
        // Left over from an earlier failure
        if (files.exists(temp)) files.delete(temp)
        if (!files.create(temp)) return false
        if (!files.write(temp, bytes)) {
            files.delete(temp)
            return false
        }
        if (files.exists(backup)) files.delete(backup)
        val hadOld = files.exists(fileName)
        if (hadOld && !files.rename(fileName, backup)) {
            files.delete(temp)
            return false
        }
        if (!files.rename(temp, fileName)) {
            if (hadOld) files.rename(backup, fileName)
            files.delete(temp)
            return false
        }
        if (hadOld) files.delete(backup)
        return true
    }
}

/**
 * The files of [dir], through the Storage Access Framework like the rest of the AAPS directory.
 *
 * A file is only looked up by name among the files of [dir], and a new file is made by the folder
 * itself, so a name like `../x.zip` cannot reach another folder: the folder cleans the name, and
 * [create] then deletes the file again.
 */
internal class SafFolderFiles(
    private val dir: DocumentFile,
    private val contentResolver: ContentResolver,
    private val aapsLogger: AAPSLogger
) : FolderFiles {

    private fun find(name: String): DocumentFile? = dir.findFile(name)?.takeIf { it.isFile }

    override fun exists(name: String): Boolean = safely(name) { find(name) != null }

    override fun create(name: String): Boolean = safely(name) {
        // octet-stream, so the folder adds no extension of its own
        val file = dir.createFile("application/octet-stream", name)
        when {
            file == null       -> false
            file.name == name  -> true
            else               -> file.delete().let { false }
        }
    }

    override fun write(name: String, bytes: ByteArray): Boolean = safely(name) {
        val file = find(name)
        file != null && contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes); true } == true
    }

    override fun rename(from: String, to: String): Boolean = safely(from) {
        val file = find(from)
        when {
            file == null         -> false
            !file.renameTo(to)   -> false
            file.name == to      -> true
            else                 -> file.renameTo(from).let { false }
        }
    }

    override fun delete(name: String): Boolean = safely(name) { find(name)?.delete() == true }

    private fun safely(name: String, action: () -> Boolean): Boolean =
        try {
            action()
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "File operation on design $name failed", e)
            false
        }
}
