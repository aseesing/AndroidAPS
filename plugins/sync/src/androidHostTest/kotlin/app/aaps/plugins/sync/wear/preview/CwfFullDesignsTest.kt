// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.ResFileMap
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The watch sends a short copy of its design; the preview needs the whole zip from the phone. */
class CwfFullDesignsTest {

    private val preview = ResFileMap.CUSTOM_WATCHFACE.fileName
    private fun res() = ResData(byteArrayOf(1, 2, 3), ResFormat.PNG)

    private fun design(name: String?, version: String?, json: String, vararg files: String) = CwfData(
        json,
        buildMap {
            name?.let { put(CwfMetadataKey.CWF_NAME, it) }
            version?.let { put(CwfMetadataKey.CWF_AUTHOR_VERSION, it) }
        }.toMutableMap(),
        files.associateWith { res() }.toMutableMap()
    )

    /** What the watch sends: the json, the metadata and the preview picture only. */
    private val shortCopy = design("Cyberpunk Tech", "1.2", "{json}", preview)

    @Test
    fun theZipWithTheSameJsonGivesItsPicturesAndFonts() {
        val zip = design("Cyberpunk Tech", "9.9", "{json}", preview, "background", "font")
        val whole = CwfFullDesigns.completeFrom(shortCopy, listOf(design("Other", "1.2", "{json}", "x"), zip))!!
        assertThat(whole.resData.keys).containsExactly(preview, "background", "font")
        // The json and the metadata are the watch's
        assertThat(whole.json).isEqualTo("{json}")
        assertThat(whole.metadata[CwfMetadataKey.CWF_AUTHOR_VERSION]).isEqualTo("1.2")
    }

    @Test
    fun otherwiseTheZipWithTheSameNameAndVersion() {
        // The phone may change the json when it sends the zip, so the version is enough
        val zip = design("Cyberpunk Tech", "1.2", "{json changed}", preview, "background")
        assertThat(CwfFullDesigns.completeFrom(shortCopy, listOf(zip))!!.resData.keys).containsExactly(preview, "background")
    }

    @Test
    fun anotherVersionIsNotUsed() {
        // Its pictures could be other ones: better the static picture than a wrong face
        val zip = design("Cyberpunk Tech", "1.1", "{old json}", preview, "background")
        assertThat(CwfFullDesigns.completeFrom(shortCopy, listOf(zip))).isNull()
    }

    @Test
    fun noZipOrNoNameGivesNothing() {
        assertThat(CwfFullDesigns.completeFrom(shortCopy, emptyList())).isNull()
        assertThat(CwfFullDesigns.completeFrom(design(null, "1.2", "{json}", preview), listOf(design(null, "1.2", "{json}", "background")))).isNull()
    }

    @Test
    fun aDesignWithMoreThanThePreviewPictureIsWholeAlready() {
        assertThat(CwfFullDesigns.isWhole(shortCopy)).isFalse()
        assertThat(CwfFullDesigns.isWhole(design("A", "1", "{}"))).isFalse()
        assertThat(CwfFullDesigns.isWhole(design("A", "1", "{}", preview, "background"))).isTrue()
    }
}
