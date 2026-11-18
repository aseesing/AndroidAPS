package app.aaps.plugins.sync.wear.wearintegration

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
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventWearUpdateGui
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.StringNonKey
import app.aaps.core.objects.runningMode.RunningModeGuard
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.plugins.sync.wear.daynight.WearPlaceUse // PERSONAL PATCH
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever // PERSONAL PATCH
import kotlin.reflect.KClass

/**
 * What the phone does with the watch's word on its faces: a night face goes to [WearNightFace],
 * the main face stays where it always was. The watch may answer with
 * ActionGetCustomWatchface (as it always did) or with ActionUpdateCustomWatchface; both count.
 */
class DataHandlerMobileNightFaceTest : TestBaseWithProfile() {

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

    private val nightFace = CwfData("{}", mutableMapOf(CwfMetadataKey.CWF_NAME to "Night"), mutableMapOf())
    private val mainFace = CwfData("{}", mutableMapOf(CwfMetadataKey.CWF_NAME to "AAPS V2"), mutableMapOf())

    @Mock private lateinit var wearNightFace: WearNightFace
    @Mock private lateinit var placeUse: WearPlaceUse // PERSONAL PATCH

    @BeforeEach
    fun prepare() {
        // Constructed for its subscriptions on rxBus
        DataHandlerMobile(
            context, rxBus, aapsLogger, rh, preferences, config,
            iobCobCalculator, processedTbrEbData, smbGlucoseStatusProvider, profileFunction, profileUtil,
            loop, processedDeviceStatusData, receiverStatusStore, quickWizard, trendCalculator, dateUtil,
            constraintsChecker, activePlugin, commandQueue, fabricPrivacy, uiInteraction,
            persistenceLayer, importExportPrefs, decimalFormatter, pumpStatusProvider,
            ch, runningModeGuard, masterActionExecutor, batchExecutor, wizardExecutor, UnchangedToWearFilter()
        ).also {
            it.wearNightFace = wearNightFace
            it.placeUse = placeUse // PERSONAL PATCH
        }
    }

    /** Sends [fromWatch] and waits for the first [T] the phone sends after it. */
    private fun <T : Event> answerTo(fromWatch: EventData, type: KClass<T>): T = runBlocking {
        val answer = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { rxBus.toFlow(type).first() } }
        rxBus.send(fromWatch)
        answer.await()
    }

    @Test
    fun `a night face from the watch goes to the night face, not to the main face keys`() {
        rxBus.send(EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(nightFace, night = true)))

        verify(wearNightFace, timeout(5_000)).reportedByWatch(nightFace)
        verify(preferences, never()).put(eq(StringNonKey.WearCwfWatchfaceName), any<String>())
    }

    @Test
    fun `a night face sent as ActionUpdateCustomWatchface goes to the night face`() {
        rxBus.send(EventData.ActionUpdateCustomWatchface(nightFace, night = true))

        verify(wearNightFace, timeout(5_000)).reportedByWatch(nightFace)
    }

    @Test
    fun `the main face sent as ActionUpdateCustomWatchface stays the main face`() {
        val event = answerTo(EventData.ActionUpdateCustomWatchface(mainFace), EventWearUpdateGui::class)

        assertThat(event.customWatchfaceData).isEqualTo(mainFace)
        assertThat(event.exportFile).isFalse()
        verify(wearNightFace, never()).reportedByWatch(any())
    }

    @Test
    fun `the watch saying it has no night face goes to the night face`() {
        rxBus.send(EventData.ActionClearNightWatchface(1L))

        verify(wearNightFace, timeout(5_000)).noneOnWatch()
    }

    // PERSONAL PATCH: "no night face" while the phone knew of none is the watch refusing the rule, see WearPlaceUse.ruleRefused

    @Test
    fun `no night face while the phone knew of none - the phone forgets the need of the design`() {
        whenever(wearNightFace.isKnown()).thenReturn(false)

        rxBus.send(EventData.ActionClearNightWatchface(1L))

        verify(placeUse, timeout(5_000)).ruleRefused()
        verify(wearNightFace, timeout(5_000)).noneOnWatch()
    }

    @Test
    fun `no night face while the phone knew of one - only the night face is forgotten`() {
        whenever(wearNightFace.isKnown()).thenReturn(true)

        rxBus.send(EventData.ActionClearNightWatchface(1L))

        verify(wearNightFace, timeout(5_000)).noneOnWatch()
        verify(placeUse, never()).ruleRefused()
    }

    @Test
    fun `the main face from the watch stays the main face`() {
        val event = answerTo(EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(mainFace)), EventWearUpdateGui::class)

        assertThat(event.customWatchfaceData).isEqualTo(mainFace)
        verify(wearNightFace, never()).reportedByWatch(any())
    }
}
