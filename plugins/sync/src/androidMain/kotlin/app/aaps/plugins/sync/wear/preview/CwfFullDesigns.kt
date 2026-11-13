// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.shared.impl.weardata.ResFileMap
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds the whole design of a watch face, with its pictures and fonts.
 *
 * When the phone asks, the watch sends a short copy of its design: the json, the metadata and the
 * preview picture only, to keep the message small. Drawn from that, the preview misses the
 * background, the fonts and the other pictures. The whole design is usually on the phone: it is the
 * zip that was loaded and sent to the watch, in the AAPS export folder.
 */
@SingleIn(AppScope::class)
@Inject
class CwfFullDesigns(private val fileListProvider: FileListProvider) {

    /** Found designs, by the short copy they were found for. */
    private val found = HashMap<CwfData, CwfData>()

    /**
     * [design] with the pictures and fonts of its zip on the phone, or null when there is no such zip.
     * Reads the zips, so it is not run on the main thread.
     */
    suspend fun complete(design: CwfData): CwfData? {
        if (isWhole(design)) return design
        synchronized(found) { found[design] }?.let { return it }
        val zips = withContext(Dispatchers.IO) { fileListProvider.listCustomWatchfaceFiles().map { it.cwfData } }
        return completeFrom(design, zips)?.also { synchronized(found) { found[design] = it } }
    }

    companion object {

        /** True when [design] has more than the preview picture, so it is not a short copy. */
        fun isWhole(design: CwfData): Boolean =
            design.resData.keys.any { it != ResFileMap.CUSTOM_WATCHFACE.fileName }

        /**
         * [design] with the pictures and fonts of its zip among [zips]: the zip with the same json, else
         * the one with the same name and author version. Null when none matches: then a zip of another
         * version could have other pictures, and the face would be drawn wrong.
         *
         * The json and the metadata stay the ones of [design]: that is what the watch has.
         */
        fun completeFrom(design: CwfData, zips: List<CwfData>): CwfData? {
            val name = design.metadata[CwfMetadataKey.CWF_NAME] ?: return null
            val sameName = zips.filter { it.metadata[CwfMetadataKey.CWF_NAME] == name }
            val zip = sameName.firstOrNull { it.json == design.json }
                ?: sameName.firstOrNull { it.metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] == design.metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] }
                ?: return null
            return CwfData(design.json, design.metadata, (zip.resData + design.resData).toMutableMap())
        }
    }
}
