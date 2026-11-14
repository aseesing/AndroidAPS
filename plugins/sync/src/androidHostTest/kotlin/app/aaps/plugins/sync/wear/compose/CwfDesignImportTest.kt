// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.keys.WearDesignImportKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File

internal class CwfDesignImportTest {

    private val preferences: Preferences = mock()

    /** Every file is a design, unless its bytes say "no". */
    private val sut = CwfDesignImport(
        fileListProvider = mock<FileListProvider>(),
        preferences = preferences,
        rh = mock<ResourceHelper>(),
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
    }

    private fun picked(name: String, content: String?) = PickedDesign(name) { content?.toByteArray() }

    @Test
    fun `copies the picked designs and replaces the old ones with the same name`() {
        val folder = FakeFolder(mutableMapOf("Cyberpunk_Tech.zip" to "old", "Mine.zip" to "mine"))

        val result = sut.import(listOf(picked("Cyberpunk_Tech.zip", "new"), picked("Wilds_Weather.zip", "wilds")), folder)

        assertThat(result.imported).containsExactly("Cyberpunk_Tech.zip", "Wilds_Weather.zip")
        assertThat(result.notImported).isEmpty()
        assertThat(folder.files).containsExactly("Cyberpunk_Tech.zip", "new", "Wilds_Weather.zip", "wilds", "Mine.zip", "mine")
    }

    @Test
    fun `a name without zip gets it, because only zips are listed`() {
        val folder = FakeFolder()

        sut.import(listOf(picked("Cyberpunk_Tech", "new")), folder)

        assertThat(folder.files.keys).containsExactly("Cyberpunk_Tech.zip")
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
    fun `a real design zip is a watch face, other bytes are not`() {
        val zip = File(repoRoot(), "wear/cwf-designs/Cyberpunk_Tech.zip").readBytes()

        assertThat(CwfDesignImport.isWatchfaceZip(zip, "Cyberpunk_Tech.zip")).isTrue()
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
        assertThat(CwfDesignImport.zipName("Wilds_Weather.zip")).isEqualTo("Wilds_Weather.zip")
        assertThat(CwfDesignImport.zipName("Wilds_Weather")).isEqualTo("Wilds_Weather.zip")
    }

    /** The test runs in the module folder; the designs live at the top of the repo. */
    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "wear/cwf-designs").isDirectory }
}
