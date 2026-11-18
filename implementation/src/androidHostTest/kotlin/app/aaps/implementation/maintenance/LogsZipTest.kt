package app.aaps.implementation.maintenance

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

internal class LogsZipTest {

    @TempDir lateinit var dir: File

    /** Remembers whether it was closed, so a test can see that the zip let go of it. */
    private class ClosingOutput : ByteArrayOutputStream() {

        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun entriesOf(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
            }
        }
        return entries
    }

    @Test
    fun `good files give a readable zip with one entry per file`() {
        val log = File(dir, "AndroidAPS.log").apply { writeText("newest lines") }
        val older = File(dir, "AndroidAPS.2018-01-03_01-01-00.1.zip").apply { writeText("older lines") }
        val output = ClosingOutput()

        writeLogsZip(listOf(log, older), output)

        assertThat(output.closed).isTrue()
        assertThat(entriesOf(output.toByteArray())).containsExactly(
            "AndroidAPS.log", "newest lines",
            "AndroidAPS.2018-01-03_01-01-00.1.zip", "older lines"
        )
    }

    @Test
    fun `a file that cannot be opened makes it fail instead of returning a half zip`() {
        val log = File(dir, "AndroidAPS.log").apply { writeText("newest lines") }
        // Rolled over and gone between listing the files and reading them
        val gone = File(dir, "AndroidAPS.2018-01-03_01-01-00.1.zip")
        val output = ClosingOutput()

        assertThrows<IOException> { writeLogsZip(listOf(log, gone), output) }

        // The stream is still closed, so the caller can delete the file it belongs to
        assertThat(output.closed).isTrue()
    }
}
