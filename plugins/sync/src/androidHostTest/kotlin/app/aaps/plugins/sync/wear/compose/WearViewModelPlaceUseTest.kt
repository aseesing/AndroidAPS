// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearUpdateGui
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.versionChecker.VersionCheckerUtils
import app.aaps.core.keys.PushedWatchfaceId
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.WearPlugin
import app.aaps.plugins.sync.wear.wearintegration.WearNightFace
import app.aaps.shared.impl.weardata.CwfPlaceUse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

/**
 * The entry to the "Day and night" screen for a design that needs the place, see `WearPlaceUseUi.kt`:
 * on the main face card while there is no night face (whose card has the button), and gone with a
 * design that needs nothing, as upstream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WearViewModelPlaceUseTest {

    @Mock private lateinit var wearPlugin: WearPlugin
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var versionCheckerUtils: VersionCheckerUtils
    @Mock private lateinit var fileListProvider: FileListProvider
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var designImport: CwfDesignImport
    @Mock private lateinit var wearNightFace: WearNightFace

    private val mainFlow = MutableStateFlow<CwfData?>(null)
    private val nightFlow = MutableStateFlow<CwfData?>(null)

    private lateinit var sut: WearViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        whenever(wearPlugin.connectedDevice).thenReturn(MutableStateFlow("Galaxy Watch 5"))
        whenever(wearPlugin.savedCustomWatchface).thenReturn(mainFlow)
        whenever(wearNightFace.saved).thenReturn(nightFlow)
        whenever(wearPlugin.watchFacePushStatus).thenReturn(MutableStateFlow(null))
        whenever(rxBus.toFlow(EventWearUpdateGui::class)).thenReturn(MutableSharedFlow())
        whenever(preferences.observe(StringKey.WearPushedWatchface)).thenReturn(MutableStateFlow(PushedWatchfaceId.CWF))
        whenever(rh.gs(SyncStrings.no_watch_connected)).thenReturn("No watch connected")
        sut = WearViewModel(wearPlugin, rxBus, rh, dateUtil, preferences, versionCheckerUtils, fileListProvider, aapsLogger, designImport, wearNightFace)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun design(name: String, json: String) = CwfData(json, mutableMapOf(CwfMetadataKey.CWF_NAME to name), mutableMapOf())

    private val sky = design("D2", """{"dynData": {"sunTime": {"valueKey": "sun_next_time"}}, "weatherLooks": {"rain": {}}}""")
    private val plain = design("AAPS V2", """{"dynData": {"bg": {"valueKey": "sgv"}}}""")

    @Test
    fun `no design that needs the place - no entry and no screen, as upstream`() {
        mainFlow.value = plain

        assertThat(sut.uiState.value.showsPlaceEntry()).isFalse()
        sut.showDayNight()
        assertThat(sut.uiState.value.showDayNight).isFalse()
    }

    @Test
    fun `a design that needs the place shows the entry and opens the screen`() {
        mainFlow.value = sky

        assertThat(sut.uiState.value.placeUse.main).containsExactly(CwfPlaceUse.SUN_AND_MOON, CwfPlaceUse.WEATHER)
        assertThat(sut.uiState.value.showsPlaceEntry()).isTrue()
        sut.showDayNight()
        assertThat(sut.uiState.value.showDayNight).isTrue()
        assertThat(sut.uiState.value.placeUseLine()).isEqualTo(WearPlaceUseLine(dayAndNight = false, sunAndMoon = true, weather = true))
    }

    @Test
    fun `the entry goes, and the screen closes, when the design needs nothing any more`() {
        mainFlow.value = sky
        sut.showDayNight()

        mainFlow.value = plain

        assertThat(sut.uiState.value.showsPlaceEntry()).isFalse()
        assertThat(sut.uiState.value.showDayNight).isFalse()
    }

    @Test
    fun `with a night face its card has the button, so the main card shows none`() {
        mainFlow.value = sky
        nightFlow.value = plain

        assertThat(sut.uiState.value.showsPlaceEntry()).isFalse()
        assertThat(sut.uiState.value.dayNightReachable()).isTrue()
        assertThat(sut.uiState.value.placeUseLine()).isEqualTo(WearPlaceUseLine(dayAndNight = true, sunAndMoon = true, weather = true))
    }

    @Test
    fun `the screen stays open when the night face goes but the design needs the place`() {
        mainFlow.value = sky
        nightFlow.value = plain
        sut.showDayNight()

        nightFlow.value = null

        assertThat(sut.uiState.value.showDayNight).isTrue()
        assertThat(sut.uiState.value.showsPlaceEntry()).isTrue()
    }

    @Test
    fun `the night design counts only with a night face`() {
        mainFlow.value = plain
        nightFlow.value = design("Night", """{"needsPlace": true}""")

        assertThat(sut.uiState.value.placeUseLine()).isEqualTo(WearPlaceUseLine(dayAndNight = true, asked = true))

        nightFlow.value = null
        assertThat(sut.uiState.value.placeUseLine()).isEqualTo(WearPlaceUseLine())
        sut.showDayNight()
        assertThat(sut.uiState.value.showDayNight).isFalse()
    }
}
