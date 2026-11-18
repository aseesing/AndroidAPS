// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.data

import android.content.Context
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/** The night face of the personal build moves once from the design file to its own file, see [NightFaceFromDesignFile]. */
@OptIn(ExperimentalSerializationApi::class)
class NightFaceFromDesignFileTest {

    @TempDir lateinit var tempDir: File

    private fun repository(): ComplicationDataRepository {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(tempDir)
        whenever(context.noBackupFilesDir).thenReturn(File(tempDir, "no_backup"))
        return ComplicationDataRepository(context, AAPSLoggerTest(), mock<SP>())
    }

    private fun cwf(name: String) = CwfData("""{"name": "$name"}""", mutableMapOf(CwfMetadataKey.CWF_NAME to name), mutableMapOf())

    private fun CwfData?.name() = this?.metadata?.get(CwfMetadataKey.CWF_NAME)

    private val designFile get() = File(tempDir, "datastore/complication_data.pb")
    private val nightFile get() = File(tempDir, "no_backup/" + ComplicationDataRepository.NIGHT_WATCHFACE_FILE)

    /** The design file as the personal build wrote it: the night face beside the main face. */
    private fun writeOldDesignFile(night: CwfData?) {
        designFile.parentFile?.mkdirs()
        designFile.writeBytes(ProtoBuf.encodeToByteArray(ComplicationData.serializer(), ComplicationData(customWatchface = cwf("main"), customWatchfaceNight = night)))
    }

    private fun storedDesigns() = ProtoBuf.decodeFromByteArray(ComplicationData.serializer(), designFile.readBytes())

    @Test
    fun theNightFaceOfThePersonalBuildMovesToItsOwnFile() = runBlocking<Unit> {
        writeOldDesignFile(cwf("night"))

        val repository = repository()

        assertThat(repository.getNightWatchface().name()).isEqualTo("night")
        assertThat(repository.getNightWatchfaceData().dayNightRule).isNull()
        assertThat(repository.getCustomWatchface().name()).isEqualTo("main")
        assertThat(nightFile.exists()).isTrue()
        // The design file forgets its copy, after the night face file was written
        val deadline = System.currentTimeMillis() + 5_000
        while (storedDesigns().customWatchfaceNight != null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(storedDesigns().customWatchfaceNight).isNull()
        assertThat(storedDesigns().customWatchface.name()).isEqualTo("main")
    }

    @Test
    fun aNightFaceFileThatExistsIsNotFilledAgain() = runBlocking<Unit> {
        // The night face was removed on this build already: the old copy must not come back
        nightFile.parentFile?.mkdirs()
        nightFile.writeBytes(ProtoBuf.encodeToByteArray(NightWatchfaceData.serializer(), NightWatchfaceData()))
        writeOldDesignFile(cwf("old night"))

        assertThat(repository().getNightWatchface()).isNull()
    }

    @Test
    fun withoutAnOldNightFaceThereIsNone() = runBlocking<Unit> {
        writeOldDesignFile(null)

        val repository = repository()

        assertThat(repository.getNightWatchface()).isNull()
        assertThat(repository.getCustomWatchface().name()).isEqualTo("main")
        // A night face stored now is kept as usual
        repository.storeNightWatchface(cwf("night"))
        assertThat(repository.getNightWatchface().name()).isEqualTo("night")
    }
}
