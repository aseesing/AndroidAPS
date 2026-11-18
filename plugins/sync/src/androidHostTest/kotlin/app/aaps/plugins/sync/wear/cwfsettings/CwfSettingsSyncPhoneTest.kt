package app.aaps.plugins.sync.wear.cwfsettings

import android.app.Application
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.CwfWatchSettings
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.shared.impl.rx.bus.RxBusImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
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
        sut = CwfSettingsSyncPhone(store, rxBus, dateUtil, logger)
    }

    @After
    fun tearDown() {
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun onlyTheSharedSettingsWithOnOffValuesAreAllowed() {
        assertThat(CwfWatchSettings.isAllowed("show_iob", "false")).isTrue()
        assertThat(CwfWatchSettings.isAllowed("show_iob", "maybe")).isFalse()
        assertThat(CwfWatchSettings.isAllowed("units_mgdl", "true")).isFalse()
        assertThat(CwfWatchSettings.all.map { it.key }).containsAtLeast("show_iob", "showBG", "showExternalStatus", "show_second", "dark", "include_external")
    }

    @Test
    fun aChangeOnThePhoneIsStoredWithItsTimeAndSentToTheWatch() {
        sut.setByUser("show_iob", "false")

        assertThat(store.values.value).containsExactly("show_iob", CwfSettingValue("false", now))
        assertThat(sent.single()).isEqualTo(EventData.CwfSettingsSync(mapOf("show_iob" to CwfSettingValue("false", now))))
    }

    @Test
    fun aValueTheWatchCannotTakeIsNotStoredOrSent() {
        sut.setByUser("dark", "maybe")
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
        sut.fromWatch(EventData.CwfSettingsSync(mapOf("show_date" to CwfSettingValue("true", 0L), "dark" to CwfSettingValue("false", 0L))))
        sent.clear()

        // A new watch, or the watch app installed again: its own values, time not known either
        sut.fromWatch(EventData.CwfSettingsSync(mapOf("show_date" to CwfSettingValue("false", 0L), "dark" to CwfSettingValue("true", 0L))))

        assertThat(store.values.value["show_date"]).isEqualTo(CwfSettingValue("false", 0L))
        assertThat(store.values.value["dark"]).isEqualTo(CwfSettingValue("true", 0L))
        assertThat(sent).isEmpty()
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
    fun aChangeOnThePhoneIsNotAnAnswerOfTheWatch() {
        // An older watch app never sends its settings: the screen must keep saying so
        sut.setByUser("show_iob", "false")
        assertThat(store.heardFromWatch.value).isFalse()

        sut.fromWatch(EventData.CwfSettingsSync(emptyMap()))
        assertThat(store.heardFromWatch.value).isTrue()
        assertThat(CwfWatchSettingsStore(app).heardFromWatch.value).isTrue()
    }

    @Test
    fun theStoreKeepsItsSettingsOverARestart() {
        sut.setByUser("show_second", "false")
        sut.fromWatch(EventData.CwfSettingsSync(mapOf("dark" to CwfSettingValue("false", 3L))))

        assertThat(CwfWatchSettingsStore(app).values.value).containsExactly(
            "show_second", CwfSettingValue("false", now),
            "dark", CwfSettingValue("false", 3L)
        )
    }

    @Test
    fun theScreenShowsTheValuesAndWhatTheDesignSets() {
        val design = CwfData(
            "{}",
            mutableMapOf(CwfMetadataKey.CWF_AUTHORIZATION to "true", CwfMetadataKey.CWF_PREF_WATCH_SHOW_IOB to "false"),
            mutableMapOf()
        )
        val state = CwfSettingsViewModel.uiStateOf(mapOf("show_cob" to CwfSettingValue("false", 9L)), design, fromWatch = true)

        assertThat(state.look.map { it.name }).containsExactlyElementsIn(CwfWatchSettings.look.map { it.key }).inOrder()
        assertThat(state.shownValues.map { it.name }).containsExactlyElementsIn(CwfWatchSettings.shownValues.map { it.key }).inOrder()
        assertThat(state.shownValues.single { it.name == "show_cob" }.value).isFalse()
        assertThat(state.shownValues.single { it.name == "showBGI" }.value).isFalse() // the default
        assertThat(state.shownValues.single { it.name == "show_iob" }.value).isTrue() // the default
        assertThat(state.shownValues.filter { it.setByDesign }.map { it.name }).containsExactly("show_iob")
        assertThat(state.fromWatch).isTrue()

        // Without the authorization the design sets nothing; without values nothing came from the watch yet
        val notAuthorized = design.copy(metadata = mutableMapOf(CwfMetadataKey.CWF_PREF_WATCH_SHOW_IOB to "false"))
        val stateWithout = CwfSettingsViewModel.uiStateOf(emptyMap(), notAuthorized, fromWatch = false)
        assertThat(stateWithout.shownValues.none { it.setByDesign }).isTrue()
        assertThat(stateWithout.fromWatch).isFalse()
    }
}
