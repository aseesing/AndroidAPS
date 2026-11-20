package app.aaps.plugins.sync.wear.compose

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfFile
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.plugins.sync.SyncStrings
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class CwfDesignImportTest {

    private val rh: ResourceHelper = mock()

    private val sut = CwfDesignImport(
        context = mock<Context>(),
        fileListProvider = mock<FileListProvider>(),
        rh = rh,
        aapsLogger = mock<AAPSLogger>()
    )

    /** AAPS/export in memory. Writing a name in [broken] fails. */
    private class FakeFolder(val files: MutableMap<String, ByteArray> = mutableMapOf(), val broken: Set<String> = emptySet()) : DesignFolder {

        override fun write(fileName: String, bytes: ByteArray): Boolean {
            if (fileName in broken) return false
            files[fileName] = bytes
            return true
        }

        override fun delete(fileName: String): Boolean {
            if (fileName in broken) return false
            return files.remove(fileName) != null
        }
    }

    /** The files of a folder in memory, where single steps can fail, for [SafeReplaceFolder]. */
    private class FakeFiles(
        val files: MutableMap<String, ByteArray> = mutableMapOf(),
        val failCreate: Set<String> = emptySet(),
        val failWrite: Set<String> = emptySet(),
        val failRename: Set<Pair<String, String>> = emptySet()
    ) : FolderFiles {

        override fun exists(name: String) = name in files

        override fun create(name: String): Boolean {
            if (name in failCreate) return false
            files[name] = ByteArray(0)
            return true
        }

        override fun write(name: String, bytes: ByteArray): Boolean {
            if (name !in files) return false
            // A write that breaks half way leaves half a file
            files[name] = if (name in failWrite) bytes.copyOf(bytes.size / 2) else bytes
            return name !in failWrite
        }

        override fun rename(from: String, to: String): Boolean {
            if (from !in files || to in files || (from to to) in failRename) return false
            files[to] = files.remove(from)!!
            return true
        }

        override fun delete(name: String) = files.remove(name) != null
    }

    private fun picked(name: String, content: ByteArray?) = PickedDesign(name) { content }

    @Test
    fun `copies the picked designs and replaces the old ones with the same name`() {
        val old = design("Old")
        val mine = design("Mine")
        val one = design("One")
        val two = design("Two")
        val folder = FakeFolder(mutableMapOf("Design_One.zip" to old, "Mine.zip" to mine))

        val result = sut.import(listOf(picked("Design_One.zip", one), picked("Design_Two.zip", two)), folder)

        assertThat(result.imported).containsExactly("Design_One.zip", "Design_Two.zip")
        assertThat(result.refused).isEmpty()
        assertThat(folder.files).containsExactly("Design_One.zip", one, "Design_Two.zip", two, "Mine.zip", mine)
    }

    @Test
    fun `a name without zip gets it, because only zips are listed`() {
        val folder = FakeFolder()

        sut.import(listOf(picked("Design_One", design("One")), picked("Design_Two.ZIP", design("Two"))), folder)

        assertThat(folder.files.keys).containsExactly("Design_One.zip", "Design_Two.zip")
    }

    @Test
    fun `two picks with the same name count once, the last one stays`() {
        val first = design("First")
        val last = design("Last")
        val folder = FakeFolder()

        val result = sut.import(listOf(picked("Same.zip", first), picked("same.ZIP", last)), folder)

        assertThat(result.imported).containsExactly("same.zip")
        assertThat(folder.files).containsExactly("same.zip", last)
    }

    @Test
    fun `says why a file was left out`() {
        val folder = FakeFolder(broken = setOf("Locked.zip"))
        val throwing = PickedDesign("Throws.zip") { error("gone") }
        val tooBig = PickedDesign("Big.zip") { throw DesignTooBigException() }

        val result = sut.import(
            listOf(
                picked("notes.txt", "not a zip".toByteArray()),
                picked("Empty.zip", null),
                throwing,
                tooBig,
                picked("Locked.zip", design("Locked")),
                picked("Good.zip", design("Good"))
            ),
            folder
        )

        assertThat(result.imported).containsExactly("Good.zip")
        assertThat(result.refused).containsExactly(
            "notes.txt" to DesignRefusal.NOT_A_DESIGN,
            "Empty.zip" to DesignRefusal.NOT_READ,
            "Throws.zip" to DesignRefusal.NOT_READ,
            "Big.zip" to DesignRefusal.TOO_BIG,
            "Locked.zip" to DesignRefusal.NOT_WRITTEN
        ).inOrder()
        assertThat(folder.files.keys).containsExactly("Good.zip")
    }

    @Test
    fun `the message has one line for the count and one for each file left out`() {
        whenever(rh.gs(SyncStrings.wear_design_import_done, 1)).thenReturn("Designs imported: 1")
        whenever(rh.gs(SyncStrings.wear_design_import_too_big, "Big.zip")).thenReturn("Big.zip is too big")
        whenever(rh.gs(SyncStrings.wear_design_import_not_a_design, "notes.txt")).thenReturn("notes.txt is not a design")

        val message = sut.message(
            DesignImportResult(listOf("Good.zip"), listOf("Big.zip" to DesignRefusal.TOO_BIG, "notes.txt" to DesignRefusal.NOT_A_DESIGN))
        )

        assertThat(message.lines()).containsExactly("Designs imported: 1", "Big.zip is too big", "notes.txt is not a design").inOrder()
    }

    @Test
    fun `a face zip is set aside for the watch and not copied`() {
        val folder = FakeFolder()
        val face = faceZip()

        val result = sut.import(listOf(picked("D2-wff.zip", face), picked("Design_One.zip", design("One"))), folder)

        assertThat(result.faces).hasSize(1)
        assertThat(result.faces[0]).isEqualTo(face)
        assertThat(result.imported).containsExactly("Design_One.zip")
        assertThat(result.refused).isEmpty()
        assertThat(folder.files.keys).containsExactly("Design_One.zip")
    }

    @Test
    fun `a face zip may be bigger than a design`() {
        val folder = FakeFolder()
        // A design of this size is refused, a face zip of this size is fine
        val big = faceZip(padding = CwfDesignImport.MAX_ZIP_BYTES + 1)

        val result = sut.import(listOf(picked("Big-wff.zip", big)), folder)

        assertThat(result.faces).hasSize(1)
        assertThat(result.refused).isEmpty()
    }

    @Test
    fun `only face zips picked gives no count line`() {
        val message = sut.message(DesignImportResult(emptyList(), emptyList(), listOf(faceZip())))

        assertThat(message).isEmpty()
    }

    /** A face zip as the design tool makes it: a `face.json` among other files */
    private fun faceZip(padding: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(0)
            for (name in listOf("face.json", "data.zip", "wff2/app.apk")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (name == "wff2/app.apk") ByteArray(padding) else name.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a design zip is a watch face, other bytes are not`() {
        assertThat(CwfDesignImport.isWatchfaceZip(design("Test"), "Test.zip")).isTrue()
        // Without its preview picture it is not a design
        assertThat(CwfDesignImport.isWatchfaceZip(zipOf("CustomWatchface.json" to json("Test").toByteArray()), "Test.zip")).isFalse()
        assertThat(CwfDesignImport.isWatchfaceZip("not a zip".toByteArray(), "notes.zip")).isFalse()
    }

    @Test
    fun `a zip that unpacks to too much is refused as too big`() {
        // About 25 MB of zeros pack to a few kB: a small "zip bomb"
        val bomb = zipOf(
            "CustomWatchface.json" to json("Bomb").toByteArray(),
            "CustomWatchface.png" to ByteArray(1),
            "filler.png" to ByteArray(25 * 1024 * 1024)
        )
        assertThat(bomb.size).isLessThan(CwfDesignImport.MAX_ZIP_BYTES)
        val folder = FakeFolder()

        assertThat(CwfDesignImport.unzipsToAtMost(bomb, CwfDesignImport.MAX_UNZIPPED_BYTES)).isFalse()
        assertThat(CwfDesignImport.isWatchfaceZip(bomb, "Bomb.zip")).isFalse()
        val result = sut.import(listOf(picked("Bomb.zip", bomb)), folder)

        assertThat(result.refused).containsExactly("Bomb.zip" to DesignRefusal.TOO_BIG)
        assertThat(folder.files).isEmpty()
    }

    @Test
    fun `counts the unpacked size of all files together`() {
        val zip = zipOf("a.png" to ByteArray(600), "b.png" to ByteArray(600))

        assertThat(CwfDesignImport.unzipsToAtMost(zip, 1_200)).isTrue()
        assertThat(CwfDesignImport.unzipsToAtMost(zip, 1_199)).isFalse()
    }

    @Test
    fun `reads a stream up to the limit and refuses a longer one`() {
        val bytes = ByteArray(20_000) { it.toByte() }

        assertThat(CwfDesignImport.readAtMost(ByteArrayInputStream(bytes), 20_000)).isEqualTo(bytes)
        assertThrows<DesignTooBigException> { CwfDesignImport.readAtMost(ByteArrayInputStream(bytes), 19_999) }
    }

    @Test
    fun `zip names end in a lower case zip`() {
        assertThat(CwfDesignImport.zipName("Design_Two.zip")).isEqualTo("Design_Two.zip")
        assertThat(CwfDesignImport.zipName("Design_Two.ZIP")).isEqualTo("Design_Two.zip")
        assertThat(CwfDesignImport.zipName("Design_Two")).isEqualTo("Design_Two.zip")
    }

    @Test
    fun `a safe replace puts the new design in place of the old one and leaves nothing else`() {
        val files = FakeFiles(mutableMapOf("Design.zip" to design("Old")))
        val new = design("New")

        assertThat(SafeReplaceFolder(files).write("Design.zip", new)).isTrue()

        assertThat(files.files).containsExactly("Design.zip", new)
    }

    @Test
    fun `a write that breaks keeps the old design and leaves no broken file`() {
        val old = design("Old")
        val files = FakeFiles(mutableMapOf("Design.zip" to old), failWrite = setOf("Design.zip.new"))

        assertThat(SafeReplaceFolder(files).write("Design.zip", design("New"))).isFalse()

        assertThat(files.files).containsExactly("Design.zip", old)
    }

    @Test
    fun `a new design that cannot be written leaves no empty file`() {
        val files = FakeFiles(failWrite = setOf("Design.zip.new"))

        assertThat(SafeReplaceFolder(files).write("Design.zip", design("New"))).isFalse()

        assertThat(files.files).isEmpty()
    }

    @Test
    fun `a rename that fails puts the old design back`() {
        val old = design("Old")
        // The old one is moved away, but the new one cannot take its name
        val files = FakeFiles(mutableMapOf("Design.zip" to old), failRename = setOf("Design.zip.new" to "Design.zip"))

        assertThat(SafeReplaceFolder(files).write("Design.zip", design("New"))).isFalse()

        assertThat(files.files).containsExactly("Design.zip", old)
    }

    @Test
    fun `when the old design cannot be moved away it stays and the new one is dropped`() {
        val old = design("Old")
        val files = FakeFiles(mutableMapOf("Design.zip" to old), failRename = setOf("Design.zip" to "Design.zip.old"))

        assertThat(SafeReplaceFolder(files).write("Design.zip", design("New"))).isFalse()

        assertThat(files.files).containsExactly("Design.zip", old)
    }

    @Test
    fun `left overs of an earlier failure are cleaned up`() {
        val new = design("New")
        val files = FakeFiles(mutableMapOf("Design.zip.new" to ByteArray(3), "Design.zip.old" to ByteArray(3)))

        assertThat(SafeReplaceFolder(files).write("Design.zip", new)).isTrue()

        assertThat(files.files).containsExactly("Design.zip", new)
    }

    @Test
    fun `deletes the zip of a design and leaves the others`() {
        val folder = FakeFolder(mutableMapOf("Design_One.zip" to design("One"), "Mine.zip" to design("Mine")))
        whenever(rh.gs(SyncStrings.wear_design_delete_done, "Design_One.zip")).thenReturn("Deleted: Design_One.zip")

        val message = sut.delete(listed(fileName = "Design_One", name = "One"), folder)

        assertThat(message).isEqualTo("Deleted: Design_One.zip")
        assertThat(folder.files.keys).containsExactly("Mine.zip")
    }

    @Test
    fun `says so when the zip is gone or cannot be deleted`() {
        val folder = FakeFolder(mutableMapOf("Locked.zip" to design("Locked")), broken = setOf("Locked.zip"))
        whenever(rh.gs(SyncStrings.wear_design_delete_failed, "Gone.zip")).thenReturn("Could not delete Gone.zip")
        whenever(rh.gs(SyncStrings.wear_design_delete_failed, "Locked.zip")).thenReturn("Could not delete Locked.zip")

        assertThat(sut.delete(listed(fileName = "Gone", name = "Gone"), folder)).isEqualTo("Could not delete Gone.zip")
        assertThat(sut.delete(listed(fileName = "Locked", name = "Locked"), folder)).isEqualTo("Could not delete Locked.zip")
        assertThat(folder.files.keys).containsExactly("Locked.zip")
    }

    @Test
    fun `a design without a file name is not deleted and is named by its title`() {
        val folder = FakeFolder(mutableMapOf("Mine.zip" to design("Mine")))
        whenever(rh.gs(SyncStrings.wear_design_delete_failed, "Nameless")).thenReturn("Could not delete Nameless")

        assertThat(sut.delete(listed(fileName = null, name = "Nameless"), folder)).isEqualTo("Could not delete Nameless")
        assertThat(folder.files.keys).containsExactly("Mine.zip")
    }

    @Test
    fun `a safe delete removes only the named file`() {
        val mine = design("Mine")
        val files = FakeFiles(mutableMapOf("Design.zip" to design("Old"), "Mine.zip" to mine))

        assertThat(SafeReplaceFolder(files).delete("Design.zip")).isTrue()
        assertThat(SafeReplaceFolder(files).delete("Gone.zip")).isFalse()

        assertThat(files.files).containsExactly("Mine.zip", mine)
    }

    /** A design as the list holds it: [fileName] is the zip name without ".zip", as AAPS stores it. */
    private fun listed(fileName: String?, name: String): CwfFile {
        val metadata = mutableMapOf(CwfMetadataKey.CWF_NAME to name)
        if (fileName != null) metadata[CwfMetadataKey.CWF_FILENAME] = fileName
        return CwfFile(CwfData("{}", metadata, mutableMapOf()), ByteArray(0))
    }

    private fun json(name: String) = "{\"metadata\":{\"name\":\"$name\"}}"

    /** The smallest zip AAPS loads as a design: the json with a name, and the preview picture. */
    private fun design(name: String): ByteArray =
        zipOf("CustomWatchface.json" to json(name).toByteArray(), "CustomWatchface.png" to name.toByteArray())

    /** A zip of [entries]: file name and content. */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
