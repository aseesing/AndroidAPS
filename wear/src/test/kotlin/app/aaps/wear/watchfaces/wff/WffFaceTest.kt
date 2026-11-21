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
    fun `the face is shown while the system uses one of its complications`() {
        // The system never said: not known
        assertThat(face.shown).isNull()
        face.slotActivated("WffGlucoseComplication#7")
        face.slotActivated("WffDataComplication#9")
        assertThat(face.shown).isTrue()
        // Kept on disk: the app's process comes and goes
        assertThat(WffFace(RuntimeEnvironment.getApplication(), AAPSLoggerTest()).shown).isTrue()
        face.slotDeactivated("WffGlucoseComplication#7")
        assertThat(face.shown).isTrue()
        // The wearer picked another face: the system let go of the last one
        face.slotDeactivated("WffDataComplication#9")
        assertThat(face.shown).isFalse()
        face.slotDeactivated("WffDataComplication#9")
        assertThat(face.shown).isFalse()
    }

    @Test
    fun `a code-based face on the screen means the face is not shown`() {
        val slots = WffShownSlots(RuntimeEnvironment.getApplication())
        // The face was on the screen before the app was updated: the system never said
        assertThat(face.shown).isNull()
        slots.otherFaceShown()
        assertThat(face.shown).isFalse()

        // The system never said that it let go of the complications: the Custom watchface says it
        face.slotActivated("WffGlucoseComplication#7")
        assertThat(face.shown).isTrue()
        slots.otherFaceShown()
        assertThat(face.shown).isFalse()

        // The wearer picks the face from the zip again
        face.slotActivated("WffGlucoseComplication#8")
        assertThat(face.shown).isTrue()
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
