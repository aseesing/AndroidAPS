// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.CwfFile
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** The zips of a folder, read by name: what [WffFaceDesigns] needs of AAPS/export. */
internal interface FaceFolder {

    /** The names of the files that end in `.zip` */
    fun zipNames(): List<String>

    /** The content of the file [name], or null when there is no such file. The caller closes it. */
    fun open(name: String): InputStream?
}

/** The zips in [dir], through the Storage Access Framework like the rest of the AAPS directory. */
internal class SafFaceFolder(private val dir: DocumentFile, private val contentResolver: ContentResolver) : FaceFolder {

    override fun zipNames(): List<String> =
        dir.listFiles().filter { it.isFile }.mapNotNull { it.name }.filter { it.endsWith(".${ZipWatchfaceFormat.CWF_EXTENSION}") }

    override fun open(name: String): InputStream? =
        dir.findFile(name)?.takeIf { it.isFile }?.let { contentResolver.openInputStream(it.uri) }
}

/**
 * Watch Face Format face zips in AAPS/export, for the list of watchfaces (see `_docs/WFF_FACE_ZIP.md`).
 *
 * "Import designs" copies a face zip into AAPS/export like a design. The list then shows it next to
 * the designs, and only when the wearer picks it, it goes to the watch.
 *
 * A face zip holds `data.zip`, a design zip with the name, author and preview picture of the face.
 * The list shows the face as that design, but named by the file name of the face zip itself, so a
 * delete removes the face zip and not some other file. Only `face.json` and `data.zip` are read into
 * memory here: the face APKs in the zip can be many MB.
 */
internal class WffFaceDesigns(private val aapsLogger: AAPSLogger) {

    /** The face zips in [folder] as designs for the list. A zip that is not a face zip is left out. */
    fun list(folder: FaceFolder): List<CwfFile> =
        folder.zipNames().mapNotNull { name ->
            try {
                folder.open(name)?.use { faceDesign(it, name) }
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "Cannot read face zip $name", e)
                null
            }
        }

    /** The whole face zip of [cwfFile], a face from [list], to send to the watch. Null when it cannot be read. */
    fun read(folder: FaceFolder, cwfFile: CwfFile): ByteArray? {
        val name = cwfFile.cwfData.metadata[CwfMetadataKey.CWF_FILENAME]?.let { CwfDesignImport.zipName(it) } ?: return null
        return try {
            folder.open(name)?.use { CwfDesignImport.readAtMost(it, CwfDesignImport.MAX_FACE_ZIP_BYTES) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Cannot read face zip $name", e)
            null
        }
    }

    companion object {

        private const val FACE_JSON = "face.json"
        private const val DATA_ZIP = "data.zip"

        /**
         * Checks and writes one face zip [bytes] as [fileName], as `CwfDesignImport.import` does for a
         * design. A face zip may be bigger than a design, because it holds a face for each AAPS app
         * and Wear OS version. Returns null when it was written, or why it was not.
         */
        internal fun refusalOf(bytes: ByteArray, fileName: String, folder: DesignFolder): DesignRefusal? {
            if (bytes.size > CwfDesignImport.MAX_FACE_ZIP_BYTES) return DesignRefusal.TOO_BIG
            // The list shows the face by its design, so a face zip without one is no use there
            val design = try {
                faceDesign(ByteArrayInputStream(bytes), fileName)
            } catch (_: DesignTooBigException) {
                return DesignRefusal.TOO_BIG
            } catch (_: Exception) {
                null
            }
            return when {
                design == null                 -> DesignRefusal.NOT_A_DESIGN
                !folder.write(fileName, bytes) -> DesignRefusal.NOT_WRITTEN
                else                           -> null
            }
        }

        /**
         * The design in the `data.zip` of the face zip [input], named [fileName] (the file name of
         * the face zip). Null when [input] is not a face zip, or its `data.zip` is not a design.
         * Throws [DesignTooBigException] when `data.zip` is bigger than a design may be.
         */
        internal fun faceDesign(input: InputStream, fileName: String): CwfFile? {
            var hasFaceJson = false
            var dataZip: ByteArray? = null
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    FACE_JSON -> hasFaceJson = true
                    DATA_ZIP  -> dataZip = CwfDesignImport.readAtMost(zip, CwfDesignImport.MAX_ZIP_BYTES)
                }
            }
            val data = dataZip ?: return null
            if (!hasFaceJson || !CwfDesignImport.unzipsToAtMost(data, CwfDesignImport.MAX_UNZIPPED_BYTES)) return null
            // The watch loads data.zip without the right to change watch settings
            val design = ZipWatchfaceFormat.loadCustomWatchface(data, fileName, authorization = false) ?: return null
            // So the list does not show the settings of the design either
            design.cwfData.metadata.keys.removeAll { it.isPref }
            return design
        }
    }
}
