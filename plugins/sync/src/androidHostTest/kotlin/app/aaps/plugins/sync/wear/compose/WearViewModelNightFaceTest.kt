// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventMobileToWearWatchface
import app.aaps.core.interfaces.rx.events.EventWearUpdateGui
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfFile
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.versionChecker.VersionCheckerUtils
import app.aaps.core.keys.PushedWatchfaceId
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.WearPlugin
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The night face on the Wear screen: its own state, its own load and its own remove. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WearViewModelNightFaceTest {

    @Mock private lateinit var wearPlugin: WearPlugin
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var versionCheckerUtils: VersionCheckerUtils
    @Mock private lateinit var fileListProvider: FileListProvider
    @Mock private lateinit var aapsLogger: AAPSLogger

    private val savedNightWatchfaceFlow = MutableStateFlow<CwfData?>(null)

    private lateinit var sut: WearViewModel

    private val zip = CwfFile(
        cwfData = CwfData(
            json = "{}",
            metadata = mutableMapOf(CwfMetadataKey.CWF_NAME to "Cyberpunk", CwfMetadataKey.CWF_FILENAME to "Cyberpunk"),
            resData = mutableMapOf()
        ),
        zipByteArray = ByteArray(3)
    )

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        whenever(wearPlugin.connectedDevice).thenReturn(MutableStateFlow("Galaxy Watch 5"))
        whenever(wearPlugin.savedCustomWatchface).thenReturn(MutableStateFlow(null))
        whenever(wearPlugin.savedNightWatchface).thenReturn(savedNightWatchfaceFlow)
        whenever(wearPlugin.watchFacePushStatus).thenReturn(MutableStateFlow(null))
        whenever(rxBus.toFlow(EventWearUpdateGui::class)).thenReturn(MutableSharedFlow())
        whenever(preferences.observe(StringKey.WearPushedWatchface)).thenReturn(MutableStateFlow(PushedWatchfaceId.CWF))
        whenever(fileListProvider.listCustomWatchfaceFiles()).thenReturn(mutableListOf())
        sut = WearViewModel(wearPlugin, rxBus, rh, dateUtil, preferences, versionCheckerUtils, fileListProvider, aapsLogger)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The zip events the screen sent */
    private fun sentZips(): List<EventMobileToWearWatchface> {
        val captor = argumentCaptor<Event>()
        verify(rxBus, atLeast(0)).send(captor.capture())
        return captor.allValues.filterIsInstance<EventMobileToWearWatchface>()
    }

    /**
     * The file list is read on Dispatchers.IO. In the app the wearer can only pick a file once the
     * list shows; the test must wait for that too, or a late load opens the list again after the pick.
     */
    private fun awaitImportList() {
        val until = System.currentTimeMillis() + 5_000
        while (!sut.uiState.value.showImportList && System.currentTimeMillis() < until) Thread.sleep(10)
        assertThat(sut.uiState.value.showImportList).isTrue()
    }

    @Test
    fun `the night face the watch reports is shown apart from the main face`() {
        assertThat(sut.uiState.value.nightFace.hasNightWatchface).isFalse()

        savedNightWatchfaceFlow.value = zip.cwfData
        assertThat(sut.uiState.value.nightFace.hasNightWatchface).isTrue()
        assertThat(sut.uiState.value.nightFace.nightWatchfaceName).isEqualTo("Cyberpunk")
        assertThat(sut.uiState.value.hasCustomWatchface).isFalse()

        savedNightWatchfaceFlow.value = null
        assertThat(sut.uiState.value.nightFace.hasNightWatchface).isFalse()
        assertThat(sut.uiState.value.nightFace.nightWatchfaceName).isEmpty()
    }

    @Test
    fun `a zip chosen after load night face goes to the watch as the night face`() {
        sut.loadNightWatchfaceFiles()
        awaitImportList()
        assertThat(sut.uiState.value.nightFace.importForNight).isTrue()

        sut.selectWatchface(zip)

        assertThat(sentZips().single().night).isTrue()
        verify(preferences).put(WearNightFaceKey.NightWatchfaceName, "Cyberpunk")
        verify(preferences, never()).put(eq(StringNonKey.WearCwfWatchfaceName), any<String>())
        assertThat(sut.uiState.value.nightFace.importForNight).isFalse()
        assertThat(sut.uiState.value.showImportList).isFalse()
    }

    @Test
    fun `a zip chosen after the normal load stays the main face`() {
        sut.loadWatchfaceFiles()
        awaitImportList()
        sut.selectWatchface(zip)

        assertThat(sentZips().single().night).isFalse()
        verify(preferences).put(StringNonKey.WearCwfWatchfaceName, "Cyberpunk")
        verify(preferences, never()).put(eq(WearNightFaceKey.NightWatchfaceName), any<String>())
    }

    @Test
    fun `leaving the import list ends the night load`() {
        sut.loadNightWatchfaceFiles()
        awaitImportList()
        sut.hideImportList()
        assertThat(sut.uiState.value.nightFace.importForNight).isFalse()

        // The next zip from the normal load is the main face again
        sut.selectWatchface(zip)
        assertThat(sentZips().single().night).isFalse()
    }

    @Test
    fun `remove night face asks the plugin to clear it`() {
        whenever(dateUtil.now()).thenReturn(12345L)

        sut.clearNightWatchface()

        verify(wearPlugin).clearNightWatchface(12345L)
    }
}
