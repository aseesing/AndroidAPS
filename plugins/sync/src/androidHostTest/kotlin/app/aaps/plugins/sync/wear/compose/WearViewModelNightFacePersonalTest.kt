// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import android.net.Uri
import app.aaps.core.interfaces.configuration.Config
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
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.WearPlugin
import app.aaps.plugins.sync.wear.keys.WearDesignImportKey
import app.aaps.plugins.sync.wear.wearintegration.WearNightFace
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The personal extras of the night face on the Wear screen: its loading indicator, its import list after an import, the last import place. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WearViewModelNightFacePersonalTest {

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
    @Mock private lateinit var config: Config

    private val savedNightWatchfaceFlow = MutableStateFlow<CwfData?>(null)
    private val savedCustomWatchfaceFlow = MutableStateFlow<CwfData?>(null)
    private val connectedDeviceFlow = MutableStateFlow<String?>("Galaxy Watch 5")

    private lateinit var sut: WearViewModel

    private val zip = CwfFile(
        cwfData = CwfData(
            json = "{}",
            metadata = mutableMapOf(CwfMetadataKey.CWF_NAME to "Night", CwfMetadataKey.CWF_FILENAME to "Night"),
            resData = mutableMapOf()
        ),
        zipByteArray = ByteArray(3)
    )

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        whenever(wearPlugin.connectedDevice).thenReturn(connectedDeviceFlow)
        whenever(wearPlugin.savedCustomWatchface).thenReturn(savedCustomWatchfaceFlow)
        whenever(wearNightFace.saved).thenReturn(savedNightWatchfaceFlow)
        whenever(wearPlugin.watchFacePushStatus).thenReturn(MutableStateFlow(null))
        whenever(rxBus.toFlow(EventWearUpdateGui::class)).thenReturn(MutableSharedFlow())
        whenever(preferences.observe(StringKey.WearPushedWatchface)).thenReturn(MutableStateFlow(PushedWatchfaceId.CWF))
        whenever(fileListProvider.listCustomWatchfaceFiles()).thenReturn(mutableListOf())
        whenever(rh.gs(SyncStrings.no_watch_connected)).thenReturn("No watch connected")
        sut = WearViewModel(wearPlugin, rxBus, rh, dateUtil, preferences, versionCheckerUtils, fileListProvider, aapsLogger, designImport, wearNightFace, config)
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
    fun `a chosen night face shows as loading until the watch reports it`() {
        sut.loadNightWatchfaceFiles()
        awaitImportList()
        sut.selectWatchface(zip)
        assertThat(sut.uiState.value.nightFace.loadingNightWatchface).isEqualTo("Night")
        assertThat(sut.uiState.value.loadingWatchface).isNull()

        // A report of the main face does not end the night load
        savedCustomWatchfaceFlow.value = zip.cwfData.copy()
        assertThat(sut.uiState.value.nightFace.loadingNightWatchface).isEqualTo("Night")

        savedNightWatchfaceFlow.value = zip.cwfData.copy()
        assertThat(sut.uiState.value.nightFace.loadingNightWatchface).isNull()
    }

    @Test
    fun `a chosen main face does not show the night face as loading`() {
        sut.loadWatchfaceFiles()
        awaitImportList()
        sut.selectWatchface(zip)

        assertThat(sut.uiState.value.loadingWatchface).isEqualTo("Night")
        assertThat(sut.uiState.value.nightFace.loadingNightWatchface).isNull()
    }

    @Test
    fun `the night face loading ends when the watch disconnects`() {
        sut.loadNightWatchfaceFiles()
        awaitImportList()
        sut.selectWatchface(zip)
        assertThat(sut.uiState.value.nightFace.loadingNightWatchface).isEqualTo("Night")

        connectedDeviceFlow.value = null

        assertThat(sut.uiState.value.nightFace.loadingNightWatchface).isNull()
    }

    @Test
    fun `without a watch a chosen night face does not show as loading`() {
        sut.loadNightWatchfaceFiles()
        awaitImportList()
        connectedDeviceFlow.value = null

        sut.selectWatchface(zip)

        assertThat(sentZips().single().night).isTrue()
        assertThat(sut.uiState.value.nightFace.loadingNightWatchface).isNull()
    }

    @Test
    fun `after an import in the night list the next pick is still the night face`() = runTest(UnconfinedTestDispatcher()) {
        val uri: Uri = mock()
        whenever(designImport.importFrom(listOf(uri))).thenReturn(DesignImport("Designs imported: 1", emptyList()))
        sut.loadNightWatchfaceFiles()
        awaitImportList()

        sut.importDesigns(listOf(uri))
        // The list is read again on the IO dispatcher: wait for it, as the wearer does
        verify(fileListProvider, timeout(2_000).times(2)).listCustomWatchfaceFiles()
        assertThat(sut.uiState.value.nightFace.importForNight).isTrue()
        sut.selectWatchface(zip)

        assertThat(sentZips().single().night).isTrue()
        verify(preferences, never()).put(eq(StringNonKey.WearCwfWatchfaceName), any<String>())
    }

    @Test
    fun `after an import in the main list the next pick is still the main face`() = runTest(UnconfinedTestDispatcher()) {
        val uri: Uri = mock()
        whenever(designImport.importFrom(listOf(uri))).thenReturn(DesignImport("Designs imported: 1", emptyList()))
        sut.loadWatchfaceFiles()
        awaitImportList()

        sut.importDesigns(listOf(uri))
        verify(fileListProvider, timeout(2_000).times(2)).listCustomWatchfaceFiles()
        sut.selectWatchface(zip)

        assertThat(sentZips().single().night).isFalse()
    }

    @Test
    fun `a design picked while the list reloads after an import - the reload does not open the list again`() = runTest(UnconfinedTestDispatcher()) {
        val uri: Uri = mock()
        whenever(designImport.importFrom(listOf(uri))).thenReturn(DesignImport("Designs imported: 1", emptyList()))
        val reloadStarted = CountDownLatch(1)
        val picked = CountDownLatch(1)
        // The first read shows the list; the second one, after the import, waits until the wearer picked
        whenever(fileListProvider.listCustomWatchfaceFiles()).thenReturn(mutableListOf()).thenAnswer {
            reloadStarted.countDown()
            picked.await(5, TimeUnit.SECONDS)
            mutableListOf<CwfFile>()
        }
        sut.loadWatchfaceFiles()
        awaitImportList()

        sut.importDesigns(listOf(uri))
        assertThat(reloadStarted.await(5, TimeUnit.SECONDS)).isTrue()
        sut.selectWatchface(zip)
        picked.countDown()

        // The reload ends on the IO thread; the list must stay closed
        val until = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < until) {
            assertThat(sut.uiState.value.showImportList).isFalse()
            Thread.sleep(10)
        }
        assertThat(sentZips()).hasSize(1)
    }

    @Test
    fun `an import remembers the place of the pick for the next file picker`() = runTest(UnconfinedTestDispatcher()) {
        val uri: Uri = mock()
        whenever(uri.toString()).thenReturn("content://drive/doc1")
        whenever(designImport.importFrom(listOf(uri))).thenReturn(DesignImport("Designs imported: 1", emptyList()))
        // The import starts from the open list, as in the app
        sut.loadWatchfaceFiles()
        awaitImportList()

        sut.importDesigns(listOf(uri))
        // After the import the list is read again on Dispatchers.IO, and that reads the preferences
        // first. Wait until the list was read: stubbing a mock while another thread calls it can put
        // the answer on the other call.
        verify(fileListProvider, timeout(2_000).times(2)).listCustomWatchfaceFiles()

        verify(preferences).put(WearDesignImportKey.LastPickedDesign, "content://drive/doc1")
        whenever(preferences.get(WearDesignImportKey.LastPickedDesign)).thenReturn("content://drive/doc1")
        assertThat(sut.lastImportPlace()).isEqualTo("content://drive/doc1")
    }
}
