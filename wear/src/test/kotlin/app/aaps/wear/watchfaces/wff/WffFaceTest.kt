package app.aaps.wear.watchfaces.wff

import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class WffFaceTest {

    private val face = WffFace(RuntimeEnvironment.getApplication(), AAPSLoggerTest())

    private val dataZip = zip(
        "CustomWatchface.json" to """{"metadata":{"name":"Data of Night Sky","cwf_authorization":"true"}}""".toByteArray(),
        "CustomWatchface.png" to byteArrayOf(1),
        "ArrowFlat.png" to byteArrayOf(2)
    )

    @Test
    fun `without a stored face everything is null`() {
        assertThat(face.name).isNull()
        assertThat(face.token).isNull()
        assertThat(face.apk()).isNull()
        assertThat(face.dataDesign()).isNull()
    }

    @Test
    fun `a stored face gives its name, token, apk and data design`() {
        face.store(
            zip(
                WffFace.FACE_JSON to """{"format":1,"name":"Night Sky"}""".toByteArray(),
                WffFace.DATA_ZIP to dataZip,
                WffFace.APK to byteArrayOf(7, 8),
                WffFace.TOKEN to "token-1\n".toByteArray()
            )
        )

        assertThat(face.name).isEqualTo("Night Sky")
        assertThat(face.token).isEqualTo("token-1")
        assertThat(face.apk()).isEqualTo(byteArrayOf(7, 8))
        val design = face.dataDesign()!!
        assertThat(design.metadata[CwfMetadataKey.CWF_NAME]).isEqualTo("Data of Night Sky")
        assertThat(design.resData.keys).contains(ResFileMap.ARROW_FLAT.fileName)
    }

    @Test
    fun `the data design never changes the watch settings`() {
        face.store(zip(WffFace.FACE_JSON to "{}".toByteArray(), WffFace.DATA_ZIP to dataZip))

        assertThat(face.dataDesign()!!.metadata[CwfMetadataKey.CWF_AUTHORIZATION]).isEqualTo("false")
    }

    @Test
    fun `a face for an older watch has no token`() {
        face.store(zip(WffFace.FACE_JSON to "{}".toByteArray(), WffFace.APK to byteArrayOf(1)))

        assertThat(face.token).isNull()
        assertThat(face.name).isNull()
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
