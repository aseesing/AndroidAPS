// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.bolus.WizardExecutor
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.pump.PumpStatusProvider
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.PushedWatchfaceId
import app.aaps.core.keys.StringKey
import app.aaps.core.objects.runningMode.RunningModeGuard
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.plugins.sync.wear.daynight.WearPlaceUse
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [DataHandlerMobile.previewStartValues]: a live preview that opens before the phone sent anything to
 * the watch does the first resend itself, so it has the units of the phone and the status at once.
 * Before, it drew the glucose in mg/dl for a wearer who uses mmol/l until the first resend came.
 * The rule itself is tested in [PreviewStartValuesTest].
 */
class DataHandlerMobilePreviewStartTest : TestBaseWithProfile() {

    @Mock private lateinit var loop: Loop
    @Mock private lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock private lateinit var receiverStatusStore: ReceiverStatusStore
    @Mock private lateinit var quickWizard: QuickWizard
    @Mock private lateinit var trendCalculator: TrendCalculator
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var uiInteraction: UiInteraction
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var importExportPrefs: ImportExportPrefs
    @Mock private lateinit var pumpStatusProvider: PumpStatusProvider
    @Mock private lateinit var runningModeGuard: RunningModeGuard
    @Mock private lateinit var masterActionExecutor: MasterActionExecutor
    @Mock private lateinit var batchExecutor: BatchExecutor
    @Mock private lateinit var wizardExecutor: WizardExecutor

    private lateinit var sut: DataHandlerMobile

    /** What the handler sends to the watch */
    private val toWatch = mutableListOf<EventData>()
    private lateinit var listening: Job

    @BeforeEach fun prepare() {
        sut = DataHandlerMobile(
            context, rxBus, aapsLogger, rh, preferences, config,
            iobCobCalculator, processedTbrEbData, smbGlucoseStatusProvider, profileFunction, profileUtil,
            loop, processedDeviceStatusData, receiverStatusStore, quickWizard, trendCalculator, dateUtil,
            constraintsChecker, activePlugin, commandQueue, fabricPrivacy, uiInteraction,
            persistenceLayer, importExportPrefs, decimalFormatter, pumpStatusProvider,
            ch, runningModeGuard, masterActionExecutor, batchExecutor, wizardExecutor, UnchangedToWearFilter()
        )
        sut.wearWeather = mock()
        sut.placeUse = mock<WearPlaceUse>()
        // A phone without readings yet, set to mmol/l
        val ads = mock<AutosensDataStore>()
        whenever(ads.getBucketedDataTableCopy()).thenReturn(null)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MMOL)
        whenever(preferences.get(StringKey.WearPushedWatchface)).thenReturn(PushedWatchfaceId.CWF)
        // UNDISPATCHED because RxBus has no replay
        listening = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            rxBus.toFlow(EventMobileToWear::class).collect { toWatch += it.payload }
        }
    }

    @AfterEach fun done() {
        listening.cancel()
    }

    // The resend of this test stops after the preferences: the rest of it needs the whole app. That is
    // enough here, and it shows that a resend that fails does not stop the preview.
    @Test fun `a preview that opens before anything was sent has the units of the phone at once`() = runTest {
        whenever(config.appInitialized).thenReturn(true)

        val values = sut.previewStartValues()

        // From the first resend, done for the preview
        assertThat(values.filterIsInstance<EventData.Preferences>().single().unitsMgdl).isFalse()
        assertThat(toWatch.filterIsInstance<EventData.Preferences>()).hasSize(1)

        // Opened again: the resend is not done twice
        assertThat(sut.previewStartValues().filterIsInstance<EventData.Preferences>()).hasSize(1)
        assertThat(toWatch.filterIsInstance<EventData.Preferences>()).hasSize(1)
    }

    @Test fun `no resend while the app is not ready`() = runTest {
        whenever(config.appInitialized).thenReturn(false)

        assertThat(sut.previewStartValues()).isEmpty()
        assertThat(toWatch).isEmpty()
    }
}
