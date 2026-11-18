package app.aaps.wear.data

import android.content.Context
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.rx.weardata.EventData
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

/**
 * The night face slot of the watch's store (see `CwfNight`): it never touches the main face, and it
 * has its own file, which the data writes leave alone.
 */
class NightWatchfaceSlotTest {

    @TempDir lateinit var tempDir: File

    private lateinit var repository: ComplicationDataRepository
    private val sp = mock<SP>()

    @BeforeEach
    fun setUp() {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(tempDir)
        whenever(context.noBackupFilesDir).thenReturn(File(tempDir, "no_backup"))
        repository = ComplicationDataRepository(context, AAPSLoggerTest(), sp)
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
        // Kept in its own file, so a new start reads it back
        val stored = ProtoBuf.decodeFromByteArray(NightWatchfaceData.serializer(), nightFile().readBytes())
        assertThat(stored.customWatchfaceNight.name()).isEqualTo("night")
    }

    private fun nightFile() = File(tempDir, "no_backup/" + ComplicationDataRepository.NIGHT_WATCHFACE_FILE)

    @Test
    fun dataWritesDoNotWriteTheNightFaceAgain() = runBlocking<Unit> {
        repository.storeNightWatchface(cwf("night"))
        val before = nightFile().readBytes()
        val modified = nightFile().lastModified()
        Thread.sleep(20)

        repository.updateBgData(EventData.SingleBg(dataset = 0, timeStamp = 1L, sgv = 100.0, high = 180.0, low = 70.0))

        assertThat(nightFile().lastModified()).isEqualTo(modified)
        assertThat(nightFile().readBytes()).isEqualTo(before)
        assertThat(repository.getNightWatchface().name()).isEqualTo("night")
    }

    @Test
    fun aNewMainFaceKeepsTheNightFace() = runBlocking<Unit> {
        repository.storeNightWatchface(cwf("night"))
        repository.storeCustomWatchface(cwf("other main"))

        assertThat(repository.getNightWatchface().name()).isEqualTo("night")
    }

    @Test
    fun clearingForgetsOnlyTheNightFaceAndItsRule() = runBlocking<Unit> {
        repository.storeCustomWatchface(cwf("main"))
        repository.storeNightWatchface(cwf("night"))
        repository.storeDayNightRule(DayNightRule.Place(52.4, 4.9))

        repository.clearNightWatchface()

        assertThat(repository.getNightWatchface()).isNull()
        assertThat(repository.getNightWatchfaceData().dayNightRule).isNull()
        assertThat(repository.getCustomWatchface().name()).isEqualTo("main")
    }

    @Test
    fun aRuleIsKeptOnlyWithANightFace() = runBlocking<Unit> {
        val rule = DayNightRule.FixedTimes(dayStartMinute = 6 * 60, nightStartMinute = 22 * 60)

        assertThat(repository.storeDayNightRule(rule)).isFalse()
        assertThat(repository.getNightWatchfaceData().dayNightRule).isNull()

        repository.storeNightWatchface(cwf("night"))
        assertThat(repository.storeDayNightRule(rule)).isTrue()
        assertThat(repository.getNightWatchfaceData().dayNightRule).isEqualTo(rule)

        // Another night face keeps the rule
        repository.storeNightWatchface(cwf("other night"))
        assertThat(repository.getNightWatchfaceData().dayNightRule).isEqualTo(rule)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun thePlaceIsStoredInTheNoBackupFolder() = runBlocking<Unit> {
        repository.storeNightWatchface(cwf("night"))
        repository.storeDayNightRule(DayNightRule.Place(52.4, 4.9))

        val stored = ProtoBuf.decodeFromByteArray(NightWatchfaceData.serializer(), nightFile().readBytes())
        assertThat(stored.dayNightRule).isEqualTo(DayNightRule.Place(52.4, 4.9))
        assertThat(nightFile().parentFile?.name).isEqualTo("no_backup")
    }

    @Test
    fun anUnreadableFileMeansNoNightFace() = runBlocking<Unit> {
        nightFile().parentFile?.mkdirs()
        nightFile().writeBytes(byteArrayOf(-1, -1, -1, -1, 7, 3))

        assertThat(repository.getNightWatchface()).isNull()
        // The file is replaced, so a night face can be stored again
        repository.storeNightWatchface(cwf("night"))
        assertThat(repository.getNightWatchface().name()).isEqualTo("night")
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
}
