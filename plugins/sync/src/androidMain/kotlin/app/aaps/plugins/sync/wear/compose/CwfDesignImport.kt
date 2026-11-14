// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.keys.WearDesignImportKey
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/*
 * Import of Custom watch face designs straight from Google Drive (or any other place the Android
 * file picker shows).
 *
 * Without this the wearer had to delete the old zips in AAPS/export, download the new ones from
 * Drive, and move them into AAPS/export by hand. Now the import list has a button that opens the
 * file picker. The picked zips are copied into AAPS/export, a zip with the same file name is
 * replaced, and the list is shown again with the new designs.
 */

/** One zip the wearer picked: its file name and how to read it. [read] gives null when it cannot be read. */
internal class PickedDesign(val fileName: String, val read: () -> ByteArray?)

/** The folder the designs are copied to: AAPS/export. */
internal interface DesignFolder {

    /** Writes [bytes] as [fileName] and replaces a file with that name. Returns false when it could not write. */
    fun write(fileName: String, bytes: ByteArray): Boolean
}

/**
 * What an import did.
 *
 * @param imported the file names that are now in AAPS/export
 * @param notImported the picked file names that were left out: not a watch face zip, unreadable, or not written
 */
internal data class DesignImportResult(val imported: List<String>, val notImported: List<String>)

/**
 * Copies watch face zips into AAPS/export. A file that is not a watch face zip is left out.
 *
 * @param fileListProvider gives the AAPS/export folder
 * @param preferences where the last picked file is remembered
 * @param isDesign true when the bytes are a watch face zip, see [isWatchfaceZip]
 */
internal class CwfDesignImport(
    private val fileListProvider: FileListProvider,
    private val preferences: Preferences,
    private val rh: ResourceHelper,
    private val aapsLogger: AAPSLogger,
    private val isDesign: (bytes: ByteArray, fileName: String) -> Boolean = ::isWatchfaceZip
) {

    /** The last picked file, so the file picker opens in its folder again. Null the first time. */
    fun lastPicked(): String? = preferences.get(WearDesignImportKey.LastPickedDesign).ifEmpty { null }

    /**
     * Copies the zips at [uris] into AAPS/export and remembers where they came from.
     *
     * @return the message for the wearer: how many designs were imported, and which files were not
     */
    suspend fun importFrom(contentResolver: ContentResolver, uris: List<Uri>): String = withContext(Dispatchers.IO) {
        preferences.put(WearDesignImportKey.LastPickedDesign, uris.first().toString())
        val dir = fileListProvider.ensureExportDirExists() ?: return@withContext rh.gs(SyncStrings.wear_design_import_no_folder)
        val result = import(contentResolver.pickedDesigns(uris), ExportDesignFolder(dir, contentResolver, aapsLogger))
        if (result.notImported.isEmpty()) rh.gs(SyncStrings.wear_design_import_done, result.imported.size)
        else rh.gs(SyncStrings.wear_design_import_partly, result.imported.size, result.notImported.joinToString(", "))
    }

    fun import(picked: List<PickedDesign>, folder: DesignFolder): DesignImportResult {
        val imported = mutableListOf<String>()
        val notImported = mutableListOf<String>()
        for (design in picked) {
            val fileName = zipName(design.fileName)
            val bytes = try {
                design.read()
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "Cannot read picked design ${design.fileName}", e)
                null
            }
            val written = bytes != null && isDesign(bytes, fileName) && folder.write(fileName, bytes)
            if (written) imported += fileName else notImported += design.fileName
        }
        aapsLogger.debug(LTag.WEAR, "Design import: imported $imported, not imported $notImported")
        return DesignImportResult(imported, notImported)
    }

    companion object {

        /** A bigger file is not read at all. The watch only takes zips of about 320 KB anyway. */
        const val MAX_ZIP_BYTES = 5 * 1024 * 1024

        /** The MIME types the file picker offers. Drive does not always call a zip `application/zip`. */
        val ZIP_TYPES = arrayOf("application/zip", "application/x-zip", "application/x-zip-compressed", "application/octet-stream")

        /** The name in AAPS/export. It must end in `.zip`, because only those files are listed. */
        fun zipName(fileName: String): String {
            val extension = ".${ZipWatchfaceFormat.CWF_EXTENSION}"
            return if (fileName.endsWith(extension)) fileName else fileName + extension
        }

        /** True when [bytes] is a zip that AAPS can load as a Custom watch face. */
        fun isWatchfaceZip(bytes: ByteArray, fileName: String): Boolean =
            try {
                ZipWatchfaceFormat.loadCustomWatchface(bytes, fileName, authorization = false) != null
            } catch (_: Exception) {
                false
            }

        /** Reads the whole stream, or gives null when it is longer than [limit] bytes. */
        fun readAtMost(input: InputStream, limit: Int): ByteArray? {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                if (total > limit) return null
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

/** AAPS/export, written through the Storage Access Framework like the rest of the AAPS directory. */
internal class ExportDesignFolder(
    private val dir: DocumentFile,
    private val contentResolver: ContentResolver,
    private val aapsLogger: AAPSLogger
) : DesignFolder {

    override fun write(fileName: String, bytes: ByteArray): Boolean =
        try {
            val baseName = fileName.removeSuffix(".${ZipWatchfaceFormat.CWF_EXTENSION}")
            val file = dir.findFile(fileName)
                ?: dir.createFile("application/${ZipWatchfaceFormat.CWF_EXTENSION}", baseName)
            when {
                file == null           -> false
                // The folder gave the new file another name: it would not replace the old design
                file.name != fileName  -> file.delete().let { false }
                // "wt" cuts the old file off, so a shorter new zip leaves no old bytes at the end
                else                   -> contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes); true } ?: false
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Cannot write design $fileName", e)
            false
        }
}

/** The zips at [uris], named as the place they come from (Drive, Downloads, ...) names them. */
internal fun ContentResolver.pickedDesigns(uris: List<Uri>): List<PickedDesign> =
    uris.map { uri ->
        PickedDesign(fileName = displayName(uri) ?: uri.lastPathSegment ?: "design") {
            openInputStream(uri)?.use { CwfDesignImport.readAtMost(it, CwfDesignImport.MAX_ZIP_BYTES) }
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

/**
 * Opens the Android file picker for one or more zips. The input is the last picked file, so the
 * picker opens in the same folder again; null the first time.
 */
internal class PickDesignZips : ActivityResultContract<String?, List<Uri>>() {

    private val openMany = ActivityResultContracts.OpenMultipleDocuments()

    override fun createIntent(context: Context, input: String?): Intent =
        openMany.createIntent(context, CwfDesignImport.ZIP_TYPES).apply {
            if (!input.isNullOrEmpty()) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input.toUri())
        }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> = openMany.parseResult(resultCode, intent)
}

/** The toolbar button of the import list that opens the file picker. */
@Composable
internal fun DesignImportAction(viewModel: WearViewModel) {
    val contentResolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(PickDesignZips()) { uris -> viewModel.importDesigns(contentResolver, uris) }
    IconButton(onClick = { picker.launch(viewModel.lastPickedDesign()) }) {
        Icon(imageVector = Icons.Default.CloudDownload, contentDescription = stringResource(SyncStrings.wear_design_import))
    }
}
