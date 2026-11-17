// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfFile
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.keys.WearDesignImportKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class CwfDesignImportTest {

    private val preferences: Preferences = mock()
    private val rh: ResourceHelper = mock()

    /** Every file is a design, unless its bytes say "no". */
    private val sut = CwfDesignImport(
        fileListProvider = mock<FileListProvider>(),
        preferences = preferences,
        rh = rh,
        aapsLogger = mock<AAPSLogger>(),
        isDesign = { bytes, _ -> String(bytes) != "no" }
    )

    /** AAPS/export in memory. Writing a name in [broken] fails. */
    private class FakeFolder(val files: MutableMap<String, String> = mutableMapOf(), val broken: Set<String> = emptySet()) : DesignFolder {

        override fun write(fileName: String, bytes: ByteArray): Boolean {
            if (fileName in broken) return false
            files[fileName] = String(bytes)
            return true
        }

        override fun delete(fileName: String): Boolean {
            if (fileName in broken) return false
            return files.remove(fileName) != null
        }
    }

    private fun picked(name: String, content: String?) = PickedDesign(name) { content?.toByteArray() }

    @Test
    fun `copies the picked designs and replaces the old ones with the same name`() {
        val folder = FakeFolder(mutableMapOf("Design_One.zip" to "old", "Mine.zip" to "mine"))

        val result = sut.import(listOf(picked("Design_One.zip", "new"), picked("Design_Two.zip", "two")), folder)

        assertThat(result.imported).containsExactly("Design_One.zip", "Design_Two.zip")
        assertThat(result.notImported).isEmpty()
        assertThat(folder.files).containsExactly("Design_One.zip", "new", "Design_Two.zip", "two", "Mine.zip", "mine")
    }

    @Test
    fun `a name without zip gets it, because only zips are listed`() {
        val folder = FakeFolder()

        sut.import(listOf(picked("Design_One", "new")), folder)

        assertThat(folder.files.keys).containsExactly("Design_One.zip")
    }

    @Test
    fun `leaves out what is not a design, cannot be read or cannot be written`() {
        val folder = FakeFolder(broken = setOf("Locked.zip"))
        val throwing = PickedDesign("Throws.zip") { error("gone") }

        val result = sut.import(
            listOf(picked("notes.txt", "no"), picked("Empty.zip", null), throwing, picked("Locked.zip", "design"), picked("Good.zip", "design")),
            folder
        )

        assertThat(result.imported).containsExactly("Good.zip")
        assertThat(result.notImported).containsExactly("notes.txt", "Empty.zip", "Throws.zip", "Locked.zip").inOrder()
        assertThat(folder.files.keys).containsExactly("Good.zip")
    }

    @Test
    fun `a design zip is a watch face, other bytes are not`() {
        val json = "CustomWatchface.json" to "{\"metadata\":{\"name\":\"Test\"}}"

        assertThat(CwfDesignImport.isWatchfaceZip(zipOf(json, "CustomWatchface.png" to "picture"), "Test.zip")).isTrue()
        // Without its preview picture it is not a design
        assertThat(CwfDesignImport.isWatchfaceZip(zipOf(json), "Test.zip")).isFalse()
        assertThat(CwfDesignImport.isWatchfaceZip("not a zip".toByteArray(), "notes.zip")).isFalse()
    }

    @Test
    fun `reads a stream up to the limit and refuses a longer one`() {
        val bytes = ByteArray(20_000) { it.toByte() }

        assertThat(CwfDesignImport.readAtMost(ByteArrayInputStream(bytes), 20_000)).isEqualTo(bytes)
        assertThat(CwfDesignImport.readAtMost(ByteArrayInputStream(bytes), 19_999)).isNull()
    }

    @Test
    fun `the last picked file is null until something was picked`() {
        whenever(preferences.get(WearDesignImportKey.LastPickedDesign)).thenReturn("")
        assertThat(sut.lastPicked()).isNull()

        whenever(preferences.get(WearDesignImportKey.LastPickedDesign)).thenReturn("content://drive/doc1")
        assertThat(sut.lastPicked()).isEqualTo("content://drive/doc1")
    }

    @Test
    fun `zip names are kept and other names get zip`() {
        assertThat(CwfDesignImport.zipName("Design_Two.zip")).isEqualTo("Design_Two.zip")
        assertThat(CwfDesignImport.zipName("Design_Two")).isEqualTo("Design_Two.zip")
    }

    @Test
    fun `deletes the zip of a design and leaves the others`() {
        val folder = FakeFolder(mutableMapOf("Design_One.zip" to "one", "Mine.zip" to "mine"))
        whenever(rh.gs(SyncStrings.wear_design_delete_done, "Design_One.zip")).thenReturn("Deleted: Design_One.zip")

        val message = sut.delete(design(fileName = "Design_One", name = "One"), folder)

        assertThat(message).isEqualTo("Deleted: Design_One.zip")
        assertThat(folder.files.keys).containsExactly("Mine.zip")
    }

    @Test
    fun `says so when the zip is gone or cannot be deleted`() {
        val folder = FakeFolder(mutableMapOf("Locked.zip" to "locked"), broken = setOf("Locked.zip"))
        whenever(rh.gs(SyncStrings.wear_design_delete_failed, "Gone.zip")).thenReturn("Could not delete Gone.zip")
        whenever(rh.gs(SyncStrings.wear_design_delete_failed, "Locked.zip")).thenReturn("Could not delete Locked.zip")

        assertThat(sut.delete(design(fileName = "Gone", name = "Gone"), folder)).isEqualTo("Could not delete Gone.zip")
        assertThat(sut.delete(design(fileName = "Locked", name = "Locked"), folder)).isEqualTo("Could not delete Locked.zip")
        assertThat(folder.files.keys).containsExactly("Locked.zip")
    }

    @Test
    fun `a design without a file name is not deleted and is named by its title`() {
        val folder = FakeFolder(mutableMapOf("Mine.zip" to "mine"))
        whenever(rh.gs(SyncStrings.wear_design_delete_failed, "Nameless")).thenReturn("Could not delete Nameless")

        assertThat(sut.delete(design(fileName = null, name = "Nameless"), folder)).isEqualTo("Could not delete Nameless")
        assertThat(folder.files.keys).containsExactly("Mine.zip")
    }

    /** A design as the list holds it: [fileName] is the zip name without ".zip", as AAPS stores it. */
    private fun design(fileName: String?, name: String): CwfFile {
        val metadata = mutableMapOf(CwfMetadataKey.CWF_NAME to name)
        if (fileName != null) metadata[CwfMetadataKey.CWF_FILENAME] = fileName
        return CwfFile(CwfData("{}", metadata, mutableMapOf()), ByteArray(0))
    }

    /** A zip of [entries]: file name and text. */
    private fun zipOf(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
