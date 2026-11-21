// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.cwfsettings

import android.app.Application
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.wear.WearPlugin
import app.aaps.plugins.sync.wear.preview.PreviewSlot
import app.aaps.shared.impl.rx.bus.RxBusImpl
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSetting
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone side of the Custom watch face settings: the phone store, the rule against the watch's
 * messages, and the names and values read from the watch's own resources inside the phone app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CwfSettingsSyncPhoneTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val logger: AAPSLogger = mock()
    private var now = 1_000_000L
    private val dateUtil: DateUtil = mock { on { now() } doAnswer { now } }
    private val rxBus = RxBusImpl(logger)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /** What the phone sent to the watch. */
    private val sent = CopyOnWriteArrayList<EventData.CwfSettingsSync>()
    private lateinit var collector: Job
    private lateinit var store: CwfWatchSettingsStore
    private lateinit var sut: CwfSettingsSyncPhone

    @Before
    fun setUp() {
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            rxBus.toFlow(EventMobileToWear::class).collect { (it.payload as? EventData.CwfSettingsSync)?.let(sent::add) }
        }
        store = CwfWatchSettingsStore(app)
        sut = CwfSettingsSyncPhone(store, rxBus, dateUtil, logger, app)
    }

    @After
    fun tearDown() {
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun theNamesOnThePhoneAreTheNamesOnTheWatch() {
        // From the watch's resources, built into the phone app: a phone module with the same
        // resource name would silently give another text here
        assertThat(sut.names.settings.keys).containsAtLeast("show_iob", "showBG", "showExternalStatus", "show_second", "dark", "wake_animation_length")
        assertThat(sut.names.settings).hasSize(CwfSyncedSettings.all.size)
        assertThat(sut.names.isAllowed("wake_animation_length", "3000")).isTrue()
        assertThat(sut.names.isAllowed("wake_animation_length", "1234")).isFalse()
        assertThat(sut.names.isAllowed("show_iob", "false")).isTrue()
        assertThat(sut.names.isAllowed("show_iob", "maybe")).isFalse()
        assertThat(sut.names.isAllowed("units_mgdl", "true")).isFalse()
    }

    @Test
    fun aChangeOnThePhoneIsStoredWithItsTimeAndSentToTheWatch() {
        sut.setByUser("show_iob", "false")

        assertThat(store.values.value).containsExactly("show_iob", CwfSettingValue("false", now))
        assertThat(sent.single()).isEqualTo(EventData.CwfSettingsSync(mapOf("show_iob" to CwfSettingValue("false", now))))
    }

    @Test
    fun aValueTheWatchCannotTakeIsNotStoredOrSent() {
        sut.setByUser("wake_animation_length", "1234")
        sut.setByUser("not_a_setting", "true")

        assertThat(store.values.value).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test
    fun aNewerValueFromTheWatchIsTakenAndNotSentBack() {
        sut.setByUser("show_iob", "false")
        sent.clear()

        sut.fromWatch(EventData.CwfSettingsSync(mapOf("show_iob" to CwfSettingValue("true", now + 5_000))))

        assertThat(store.values.value["show_iob"]).isEqualTo(CwfSettingValue("true", now + 5_000))
        assertThat(sent).isEmpty()
    }

    @Test
    fun aWatchThatIsBehindGetsOneAnswerAndAnAnswerIsNeverAnswered() {
        sut.setByUser("show_iob", "false")
        sent.clear()
        val older = EventData.CwfSettingsSync(mapOf("show_iob" to CwfSettingValue("true", 5L)))

        sut.fromWatch(older)
        assertThat(store.values.value["show_iob"]).isEqualTo(CwfSettingValue("false", now))
        assertThat(sent.single()).isEqualTo(EventData.CwfSettingsSync(mapOf("show_iob" to CwfSettingValue("false", now)), isAnswer = true))

        sent.clear()
        sut.fromWatch(older.copy(isAnswer = true))
        assertThat(sent).isEmpty()
    }

    @Test
    fun withBothTimesUnknownThePhoneTakesTheValueOfTheWatch() {
        // Learned from another watch, time not known
        sut.fromWatch(EventData.CwfSettingsSync(mapOf("show_date" to CwfSettingValue("true", 0L), "wake_animation_length" to CwfSettingValue("3000", 0L))))
        sent.clear()

        // A new watch, or the watch app installed again: its own values, time not known either
        sut.fromWatch(EventData.CwfSettingsSync(mapOf("show_date" to CwfSettingValue("false", 0L), "wake_animation_length" to CwfSettingValue("design", 0L))))

        assertThat(store.values.value["show_date"]).isEqualTo(CwfSettingValue("false", 0L))
        assertThat(store.values.value["wake_animation_length"]).isEqualTo(CwfSettingValue("design", 0L))
        assertThat(sent).isEmpty()
    }

    @Test
    fun aChangeOnThePhoneIsNotAnAnswerOfTheWatch() {
        // An older watch app never sends its settings: the screen must keep saying so
        sut.setByUser("show_iob", "false")
        assertThat(store.heardFromWatch.value).isFalse()

        sut.fromWatch(EventData.CwfSettingsSync(emptyMap()))
        assertThat(store.heardFromWatch.value).isTrue()
        assertThat(CwfWatchSettingsStore(app).heardFromWatch.value).isTrue()
    }

    @Test
    fun whenTheWatchAsksForEverythingThePhoneSendsItsSettings() {
        sut.fromWatch(EventData.CwfSettingsSync(mapOf("dark" to CwfSettingValue("false", 0L))))
        sut.start(scope)
        sent.clear()

        rxBus.send(EventData.ActionResendData("test"))

        assertThat(sent.single()).isEqualTo(EventData.CwfSettingsSync(mapOf("dark" to CwfSettingValue("false", 0L))))
    }

    @Test
    fun messagesFromTheWatchArriveThroughTheBus() {
        sut.start(scope)
        rxBus.send(EventData.CwfSettingsSync(mapOf("show_cob" to CwfSettingValue("false", 7L))))
        assertThat(store.values.value["show_cob"]).isEqualTo(CwfSettingValue("false", 7L))
    }

    @Test
    fun theStoreKeepsItsSettingsOverARestart() {
        sut.setByUser("show_second", "false")
        sut.fromWatch(EventData.CwfSettingsSync(mapOf("wake_animation_length" to CwfSettingValue("6000", 3L))))

        assertThat(CwfWatchSettingsStore(app).values.value).containsExactly(
            "show_second", CwfSettingValue("false", now),
            "wake_animation_length", CwfSettingValue("6000", 3L)
        )
    }

    @Test
    fun theScreenShowsWhatTheDesignUsesAndWhatTheDesignSets() {
        val design = CwfData(
            "{}",
            mutableMapOf(CwfMetadataKey.CWF_AUTHORIZATION to "true", CwfMetadataKey.CWF_PREF_WATCH_SHOW_IOB to "false"),
            mutableMapOf()
        )
        val state = CwfSettingsViewModel.uiStateOf(sut.names, mapOf("show_cob" to CwfSettingValue("false", 9L)), design, fromWatch = true)

        // A design without views uses none of the look settings, except the one about the template
        assertThat(state.look.map { it.name }).containsExactly("include_external")
        assertThat(state.shownValues.map { it.name }).containsExactlyElementsIn(CwfSyncedSettings.shownValues.map(sut.names::nameOf)).inOrder()
        assertThat(state.shownValues.single { it.name == "show_cob" }.value).isEqualTo("false")
        assertThat(state.shownValues.single { it.name == "showBGI" }.value).isEqualTo("false") // the default
        assertThat(state.shownValues.filter { it.setByDesign }.map { it.name }).containsExactly("show_iob")
        assertThat(state.fromWatch).isTrue()

        // Without the authorization the design sets nothing; without values nothing came from the watch yet
        val notAuthorized = design.copy(metadata = mutableMapOf(CwfMetadataKey.CWF_PREF_WATCH_SHOW_IOB to "false"))
        val stateWithout = CwfSettingsViewModel.uiStateOf(sut.names, emptyMap(), notAuthorized, fromWatch = false)
        assertThat(stateWithout.shownValues.none { it.setByDesign }).isTrue()
        assertThat(stateWithout.fromWatch).isFalse()
        // No design known: every look setting, as the watch menu does
        assertThat(CwfSettingsViewModel.uiStateOf(sut.names, emptyMap(), null, fromWatch = false).look.map { it.setting })
            .containsExactlyElementsIn(CwfSyncedSettings.faceLook).inOrder()
    }

    @Test
    fun anOptionOfTheDesignHasTheTextsOfTheDesign() {
        val json = """{"options": [{"name": "santa", "title": "Santa", "choices": ["At Christmas", "Always", "Never"], "default": 2}]}"""
        val state = CwfSettingsViewModel.uiStateOf(sut.names, emptyMap(), CwfData(json, mutableMapOf(), mutableMapOf()), fromWatch = true)

        val row = state.look.single { it.name == "cwf_option_1" }
        val setting = row.setting as CwfSyncedSetting.Choice
        assertThat(setting.labels?.title).isEqualTo("Santa")
        assertThat(setting.labels?.entries).containsExactly("At Christmas", "Always", "Never").inOrder()
        // While nothing is chosen the row shows the default of the design
        assertThat(row.value).isEqualTo("2")
        // The watch takes the number of a choice, and "as designed"
        assertThat(sut.names.isAllowed("cwf_option_1", "1")).isTrue()
        assertThat(sut.names.isAllowed("cwf_option_1", "design")).isTrue()
        assertThat(sut.names.isAllowed("cwf_option_1", "santa")).isFalse()
        // All eight places are known to the phone, so it can send each of them
        assertThat(sut.names.isAllowed("cwf_option_8", "7")).isTrue()
        assertThat(sut.names.isAllowed("cwf_option_9", "1")).isFalse()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theNightFaceHasItsOwnSettingsScreenWithTheSameSettings() {
        Dispatchers.setMain(UnconfinedTestDispatcher()) // the view model scope
        try {
            val day = CwfData("{}", mutableMapOf(), mutableMapOf())
            // Only the night design sets a value
            val night = CwfData(
                "{}",
                mutableMapOf(CwfMetadataKey.CWF_AUTHORIZATION to "true", CwfMetadataKey.CWF_PREF_WATCH_SHOW_IOB to "false"),
                mutableMapOf()
            )
            val nightFlow = MutableStateFlow<CwfData?>(night)
            val wearPlugin: WearPlugin = mock {
                on { savedCustomWatchface } doReturn MutableStateFlow(day)
                on { savedNightWatchface } doReturn nightFlow
            }
            val viewModel = CwfSettingsViewModel(sut, store, wearPlugin)
            fun stateOf(slot: PreviewSlot, shared: Boolean) =
                runBlocking { withTimeout(5_000) { viewModel.uiState(slot).first { it.shownValues.isNotEmpty() && it.sharedWithOtherFace == shared } } }

            assertThat(stateOf(PreviewSlot.DAY, shared = true).shownValues.none { it.setByDesign }).isTrue()
            assertThat(stateOf(PreviewSlot.NIGHT, shared = true).shownValues.filter { it.setByDesign }.map { it.name }).containsExactly("show_iob")

            // Without a night face the main face shares its settings with nothing
            nightFlow.value = null
            assertThat(stateOf(PreviewSlot.DAY, shared = false).shownValues).isNotEmpty()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
