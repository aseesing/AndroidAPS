// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.data

import android.content.Context
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/** The night face slot of the watch's store (see `CwfNight`): it never touches the main face. */
class NightWatchfaceSlotTest {

    @TempDir lateinit var tempDir: File

    private lateinit var repository: ComplicationDataRepository

    @BeforeEach
    fun setUp() {
        repository = newRepository()
    }

    private fun newRepository(): ComplicationDataRepository {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(tempDir)
        return ComplicationDataRepository(context, AAPSLoggerTest(), mock<SP>())
    }

    private fun cwf(name: String, version: String = "1") = CwfData(
        json = """{"name": "$name"}""",
        metadata = mutableMapOf(CwfMetadataKey.CWF_NAME to name, CwfMetadataKey.CWF_AUTHOR_VERSION to version),
        resData = mutableMapOf(
            ResFileMap.CUSTOM_WATCHFACE.fileName to ResData(byteArrayOf(1, 2, 3), ResFormat.PNG),
            "background" to ResData(byteArrayOf(4, 5, 6), ResFormat.PNG)
        )
    )

    private fun CwfData?.name() = this?.metadata?.get(CwfMetadataKey.CWF_NAME)

    @Test
    fun noNightFaceAtFirst() = runBlocking<Unit> {
        assertThat(repository.getNightWatchface()).isNull()
        assertThat(repository.getSimplifiedNightWatchface()).isNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theNightFaceIsStoredBesideTheMainFace() = runBlocking<Unit> {
        repository.storeCustomWatchface(cwf("main"))
        repository.storeNightWatchface(cwf("night"))

        assertThat(repository.getCustomWatchface().name()).isEqualTo("main")
        assertThat(repository.getNightWatchface().name()).isEqualTo("night")
        // Kept in the file, so a new start reads it back
        val stored = ProtoBuf.decodeFromByteArray(ComplicationData.serializer(), File(tempDir, "datastore/complication_data.pb").readBytes())
        assertThat(stored.customWatchfaceNight.name()).isEqualTo("night")
    }

    @Test
    fun aNewMainFaceKeepsTheNightFace() = runBlocking<Unit> {
        repository.storeNightWatchface(cwf("night"))
        repository.storeCustomWatchface(cwf("other main"))

        assertThat(repository.getNightWatchface().name()).isEqualTo("night")
    }

    @Test
    fun clearingForgetsOnlyTheNightFace() = runBlocking<Unit> {
        repository.storeCustomWatchface(cwf("main"))
        repository.storeNightWatchface(cwf("night"))

        repository.clearNightWatchface()

        assertThat(repository.getNightWatchface()).isNull()
        assertThat(repository.getCustomWatchface().name()).isEqualTo("main")
    }

    @Test
    fun theSimplifiedNightFaceHasOnlyItsMainPicture() = runBlocking<Unit> {
        repository.storeNightWatchface(cwf("night"))

        val simplified = repository.getSimplifiedNightWatchface()

        assertThat(simplified.name()).isEqualTo("night")
        assertThat(simplified?.resData?.keys).containsExactly(ResFileMap.CUSTOM_WATCHFACE.fileName)
    }

    @Test
    fun metadataOfTheNightFaceIsUpdatedOnlyForTheSameDesign() = runBlocking<Unit> {
        repository.storeCustomWatchface(cwf("main"))
        repository.storeNightWatchface(cwf("night"))

        repository.updateNightWatchfaceMetadata(mapOf(CwfMetadataKey.CWF_NAME to "night", CwfMetadataKey.CWF_AUTHOR_VERSION to "1", CwfMetadataKey.CWF_FILENAME to "night.zip"))
        repository.updateNightWatchfaceMetadata(mapOf(CwfMetadataKey.CWF_NAME to "other", CwfMetadataKey.CWF_AUTHOR_VERSION to "1", CwfMetadataKey.CWF_FILENAME to "other.zip"))

        assertThat(repository.getNightWatchface()?.metadata?.get(CwfMetadataKey.CWF_FILENAME)).isEqualTo("night.zip")
        assertThat(repository.getCustomWatchface()?.metadata?.get(CwfMetadataKey.CWF_FILENAME)).isNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun aFileWrittenBeforeTheNightSlotStillReads() = runBlocking<Unit> {
        // The night field is the last one, so the main face of an older file keeps its place
        val bytes = ProtoBuf.encodeToByteArray(ComplicationData.serializer(), ComplicationData(customWatchface = cwf("main")))
        File(tempDir, "datastore").mkdirs()
        File(tempDir, "datastore/complication_data.pb").writeBytes(bytes)

        val updated = newRepository()

        assertThat(updated.getCustomWatchface().name()).isEqualTo("main")
        assertThat(updated.getNightWatchface()).isNull()
    }
}
