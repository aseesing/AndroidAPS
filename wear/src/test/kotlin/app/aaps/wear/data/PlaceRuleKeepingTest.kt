package app.aaps.wear.data

import android.content.Context
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.sharedPreferences.SP
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
 * The watch keeps the rule (which can hold the place) only while something on it needs it: a night
 * face, or a main design that needs the place. See [PlaceRuleKeeping].
 */
class PlaceRuleKeepingTest {

    @TempDir lateinit var tempDir: File

    private lateinit var repository: ComplicationDataRepository
    private val sp = mock<SP>()

    private val place = DayNightRule.Place(52.4, 4.9)

    @BeforeEach
    fun setUp() {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(tempDir)
        whenever(context.noBackupFilesDir).thenReturn(File(tempDir, "no_backup"))
        repository = ComplicationDataRepository(context, AAPSLoggerTest(), sp)
    }

    private fun cwf(name: String, json: String) = CwfData(
        json = json,
        metadata = mutableMapOf(CwfMetadataKey.CWF_NAME to name, CwfMetadataKey.CWF_AUTHOR_VERSION to "1"),
        resData = mutableMapOf()
    )

    private val skyDesign = cwf("D2", """{"metadata": {"name": "D2"}, "dynData": {"sunTime": {"valueKey": "sun_next_time"}}}""")
    private val weatherDesign = cwf("Wilds", """{"weatherLooks": {"rain": {}}}""")
    private val askingDesign = cwf("Mine", """{"needsPlace": true}""")
    private val temperatureDesign = cwf("Warm", """{"dynData": {"temperature": {"valueKey": "weather_temperature"}}}""")
    private val plainDesign = cwf("AAPS V2", """{"metadata": {"name": "AAPS V2"}, "dynData": {"bg": {"valueKey": "sgv"}}}""")

    private suspend fun rule() = repository.getNightWatchfaceData().dayNightRule

    private fun nightFile() = File(tempDir, "no_backup/" + ComplicationDataRepository.NIGHT_WATCHFACE_FILE)

    @Test
    fun aDesignThatNeedsThePlaceKeepsTheRuleWithoutANightFace() = runBlocking<Unit> {
        repository.storeCustomWatchface(skyDesign)

        assertThat(repository.storeDayNightRule(place)).isTrue()
        assertThat(rule()).isEqualTo(place)
        assertThat(repository.getNightWatchface()).isNull()
    }

    @Test
    fun theWeatherAndTheExplicitKeyKeepTheRuleToo() = runBlocking<Unit> {
        repository.storeCustomWatchface(weatherDesign)
        assertThat(repository.storeDayNightRule(place)).isTrue()

        repository.storeCustomWatchface(askingDesign)
        assertThat(rule()).isEqualTo(place)
    }

    @Test
    fun aDesignThatOnlyShowsTheTemperatureKeepsNoRule() = runBlocking<Unit> {
        // The phone looks up the weather and sends only the weather: the watch does not need the place
        repository.storeCustomWatchface(temperatureDesign)

        assertThat(repository.storeDayNightRule(place)).isFalse()
        assertThat(rule()).isNull()
    }

    @Test
    fun aDesignThatNeedsNothingKeepsNoRule() = runBlocking<Unit> {
        repository.storeCustomWatchface(plainDesign)

        assertThat(repository.storeDayNightRule(place)).isFalse()
        assertThat(rule()).isNull()
    }

    @Test
    fun aNewDesignThatNeedsNothingDropsTheRule() = runBlocking<Unit> {
        repository.storeCustomWatchface(skyDesign)
        repository.storeDayNightRule(place)

        repository.storeCustomWatchface(plainDesign)

        assertThat(rule()).isNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theDroppedPlaceIsGoneFromTheFile() = runBlocking<Unit> {
        repository.storeCustomWatchface(skyDesign)
        repository.storeDayNightRule(place)

        repository.storeCustomWatchface(plainDesign)

        val stored = ProtoBuf.decodeFromByteArray(NightWatchfaceData.serializer(), nightFile().readBytes())
        assertThat(stored.dayNightRule).isNull()
    }

    @Test
    fun theDefaultDesignDropsTheRule() = runBlocking<Unit> {
        repository.storeCustomWatchface(plainDesign, isDefault = true)
        repository.storeCustomWatchface(skyDesign)
        repository.storeDayNightRule(place)

        repository.setDefaultWatchface()

        assertThat(rule()).isNull()
    }

    @Test
    fun aNightFaceKeepsTheRuleWhenTheMainDesignNeedsNothing() = runBlocking<Unit> {
        repository.storeCustomWatchface(skyDesign)
        repository.storeNightWatchface(plainDesign)
        repository.storeDayNightRule(place)

        repository.storeCustomWatchface(plainDesign)

        assertThat(rule()).isEqualTo(place)
    }

    @Test
    fun removingTheNightFaceKeepsTheRuleForADesignThatNeedsThePlace() = runBlocking<Unit> {
        repository.storeCustomWatchface(skyDesign)
        repository.storeNightWatchface(plainDesign)
        repository.storeDayNightRule(place)

        repository.clearNightWatchface()

        assertThat(repository.getNightWatchface()).isNull()
        assertThat(rule()).isEqualTo(place)
    }

    @Test
    fun removingTheNightFaceDropsTheRuleWhenTheDesignNeedsNothing() = runBlocking<Unit> {
        repository.storeCustomWatchface(plainDesign)
        repository.storeNightWatchface(plainDesign)
        repository.storeDayNightRule(place)

        repository.clearNightWatchface()

        assertThat(rule()).isNull()
    }

    @Test
    fun noRuleFromThePhoneDropsIt() = runBlocking<Unit> {
        repository.storeCustomWatchface(skyDesign)
        repository.storeDayNightRule(place)

        assertThat(repository.clearDayNightRule()).isTrue()

        assertThat(rule()).isNull()
    }

    @Test
    fun noRuleFromThePhoneIsRefusedWhileTheWatchHasANightFace() = runBlocking<Unit> {
        repository.storeNightWatchface(plainDesign)
        repository.storeDayNightRule(place)

        assertThat(repository.clearDayNightRule()).isFalse()

        assertThat(rule()).isEqualTo(place)
    }

    @Test
    fun theAnswerForTheSameDesignIsKept() {
        val keeping = PlaceRuleKeeping()

        assertThat(keeping.mainNeedsRule(skyDesign)).isTrue()
        assertThat(keeping.mainNeedsRule(skyDesign)).isTrue()
        assertThat(keeping.mainNeedsRule(plainDesign)).isFalse()
        assertThat(keeping.mainNeedsRule(null)).isFalse()
        assertThat(keeping.keepsRule(nightFace = plainDesign, mainDesign = null)).isTrue()
        assertThat(keeping.keepsRule(nightFace = null, mainDesign = plainDesign)).isFalse()
    }
}
