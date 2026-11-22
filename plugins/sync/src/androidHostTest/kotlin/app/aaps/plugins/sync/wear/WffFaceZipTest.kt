package app.aaps.plugins.sync.wear

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal class WffFaceZipTest {

    private val full = zip(
        "face.json", "data.zip",
        "wff4/$APP.apk", "wff4/$APP.token", "wff2/$APP.apk",
        "wff4/$OTHER.apk", "wff4/$OTHER.token", "wff2/$OTHER.apk"
    )

    @Test
    fun `a zip with face json is a face zip, an old design zip is not`() {
        assertThat(WffFaceZip.isFaceZip(full)).isTrue()
        assertThat(WffFaceZip.isFaceZip(zip("CustomWatchface.json", "CustomWatchface.png"))).isFalse()
        assertThat(WffFaceZip.isFaceZip(ByteArray(10))).isFalse()
    }

    @Test
    fun `a Wear OS 6 watch gets version 4 with its token, for Watch Face Push`() {
        val result = WffFaceZip.forWatch(full, APP, 36) as WffFaceZip.Result.ForWatch

        assertThat(result.usesPush).isTrue()
        assertThat(entries(result.zip)).containsExactly(
            "face.json", "face.json", "data.zip", "data.zip", "face.apk", "wff4/$APP.apk", "face.token", "wff4/$APP.token"
        )
    }

    @Test
    fun `a Wear OS 5 watch gets version 2 without a token`() {
        val result = WffFaceZip.forWatch(full, APP, 34) as WffFaceZip.Result.ForWatch

        assertThat(result.usesPush).isFalse()
        assertThat(entries(result.zip)).containsExactly("face.json", "face.json", "data.zip", "data.zip", "face.apk", "wff2/$APP.apk")
    }

    @Test
    fun `version 4 without its token is not offered`() {
        val result = WffFaceZip.forWatch(zip("face.json", "data.zip", "wff4/$APP.apk", "wff2/$APP.apk"), APP, 36) as WffFaceZip.Result.ForWatch

        assertThat(entries(result.zip)["face.apk"]).isEqualTo("wff2/$APP.apk")
    }

    @Test
    fun `a watch older than every face is told the lowest version needed`() {
        val result = WffFaceZip.forWatch(full, APP, 33)

        assertThat((result as WffFaceZip.Result.WatchTooOld).minApiLevel).isEqualTo(34)
    }

    @Test
    fun `a zip without a face for this app says so`() {
        assertThat(WffFaceZip.forWatch(full, "info.nightscout.aapsclient2", 36)).isEqualTo(WffFaceZip.Result.OtherApp)
    }

    @Test
    fun `a zip without data zip is not valid`() {
        assertThat(WffFaceZip.forWatch(zip("face.json", "wff2/$APP.apk"), APP, 36)).isEqualTo(WffFaceZip.Result.Invalid)
    }

    /** A zip whose every file holds its own name, so the tests can tell where a file came from */
    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The files of [zip]: name to content */
    private fun entries(zip: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            generateSequence { input.nextEntry }.forEach { result[it.name] = String(input.readBytes()) }
        }
        return result
    }

    private companion object {

        const val APP = "info.nightscout.androidaps"
        const val OTHER = "info.nightscout.aapsclient"
    }
}
