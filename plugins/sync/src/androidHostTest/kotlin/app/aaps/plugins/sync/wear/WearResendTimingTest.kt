// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.PS
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.alerts.EatReminder
import app.aaps.core.interfaces.alerts.EatReminderState
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.FAST_DELAY_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.MAX_RETRIES
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.REASON_NEW_BG
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.RETRY_DELAY_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Companion.SETTLE_QUIET_MS
import app.aaps.plugins.sync.wear.WearResendSchedule.Outcome
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/** The sources of [WearResendTiming] and how each one counts, in virtual time. */
class WearResendTimingTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var eatReminder: EatReminder
    @Mock lateinit var dataHandlerMobile: DataHandlerMobile
    @Mock lateinit var autosensDataStore: AutosensDataStore

    private lateinit var sut: WearResendTiming

    // The database flows send changes only, never a first value, as on a phone
    private val temporaryTargets = MutableSharedFlow<List<TT>>()
    private val runningModes = MutableSharedFlow<List<RM>>()
    private val carbs = MutableSharedFlow<List<CA>>()
    private val boluses = MutableSharedFlow<List<BS>>()
    private val profileSwitches = MutableSharedFlow<List<PS>>()
    private val effectiveProfileSwitches = MutableSharedFlow<List<EPS>>()
    private val eatReminderState = MutableStateFlow<EatReminderState?>(null)
    private var lastBg: InMemoryGlucoseValue? = null

    @BeforeEach fun prepare() {
        whenever(eatReminder.state).thenReturn(eatReminderState)
        whenever(persistenceLayer.observeChanges(TT::class)).thenReturn(temporaryTargets)
        whenever(persistenceLayer.observeChanges(RM::class)).thenReturn(runningModes)
        whenever(persistenceLayer.observeChanges(CA::class)).thenReturn(carbs)
        whenever(persistenceLayer.observeChanges(BS::class)).thenReturn(boluses)
        whenever(persistenceLayer.observeChanges(PS::class)).thenReturn(profileSwitches)
        whenever(persistenceLayer.observeChanges(EPS::class)).thenReturn(effectiveProfileSwitches)
        whenever(iobCobCalculator.ads).thenReturn(autosensDataStore)
        whenever(autosensDataStore.lastBg()).thenAnswer { lastBg }
        whenever(config.appInitialized).thenReturn(true)
        sut = WearResendTiming(aapsLogger, rxBus, persistenceLayer, eatReminder, iobCobCalculator, config, dataHandlerMobile)
    }

    /** Starts the timing with a resend that only notes when it ran and why. */
    private fun TestScope.collectResends(): MutableList<Pair<Long, List<String>>> {
        val resends = mutableListOf<Pair<Long, List<String>>>()
        sut.start(backgroundScope, { testScheduler.currentTime }) { reasons ->
            resends += testScheduler.currentTime to reasons
            Outcome.SENT
        }
        runCurrent()
        return resends
    }

    private fun TestScope.at(ms: Long) {
        val step = ms - testScheduler.currentTime
        if (step > 0) advanceTimeBy(step)
        runCurrent()
    }

    private fun bolus(type: BS.Type) = BS(timestamp = 1_000L, amount = 1.0, type = type, iCfg = someICfg)

    /** A resend at 2 s that starts the settle window, so a slow reason after it would wait. */
    private fun TestScope.firstResend() {
        rxBus.send(EventLoopUpdateGui())
        at(FAST_DELAY_MS)
    }

    /** An event sent right after the start is not lost: the sources are subscribed in start. */
    @Test
    fun `an event right after the start is not lost`() = runTest {
        val resends = collectResends()
        rxBus.send(EventLoopUpdateGui())
        at(5_000)
        assertThat(resends).containsExactly(FAST_DELAY_MS to listOf("EventLoopUpdateGui"))
    }

    /** f: a database flow has no first value. Its first change after the start is a change, not dropped. */
    @Test
    fun `the first change of a database flow after the start gives a resend`() = runTest {
        val resends = collectResends()
        temporaryTargets.emit(emptyList())
        at(5_000)
        assertThat(resends).containsExactly(FAST_DELAY_MS to listOf("TempTargetChange"))
    }

    /** The eat timer is a StateFlow: its first value is the current state, not a change. */
    @Test
    fun `the current state of the eat timer is not a change, a new one is`() = runTest {
        val resends = collectResends()
        at(10_000)
        assertThat(resends).isEmpty()
        eatReminderState.value = EatReminderState(startedAt = 1_000L, endsAt = 901_000L, durationMs = 900_000L)
        at(10_000 + FAST_DELAY_MS)
        assertThat(resends).containsExactly(10_000 + FAST_DELAY_MS to listOf("EatReminderChange"))
    }

    /** b: carbs, a bolus, a profile switch and the running mode go out within 2 s, also while the cycle settles. */
    @Test
    fun `user changes in the database are fast while the cycle settles`() = runTest {
        val resends = collectResends()
        firstResend()
        at(20_000); carbs.emit(emptyList())
        at(30_000); boluses.emit(listOf(bolus(BS.Type.NORMAL)))
        at(40_000); profileSwitches.emit(emptyList())
        at(50_000); effectiveProfileSwitches.emit(emptyList())
        at(60_000); runningModes.emit(emptyList())
        at(70_000)
        assertThat(resends.map { it.first }).containsExactly(2_000L, 22_000L, 32_000L, 42_000L, 52_000L, 62_000L).inOrder()
        assertThat(resends.map { it.second.single() }).containsExactly(
            "EventLoopUpdateGui", "CarbsChange", "BolusChange", "ProfileSwitchChange", "EffectiveProfileSwitchChange", "RunningModeChange"
        ).inOrder()
    }

    /** An SMB is the loop's own: it waits while the cycle settles. */
    @Test
    fun `an SMB takes the slow path`() = runTest {
        val resends = collectResends()
        firstResend()
        at(20_000); boluses.emit(listOf(bolus(BS.Type.SMB)))
        at(20_000 + FAST_DELAY_MS)
        assertThat(resends).hasSize(1)
        at(20_000 + SETTLE_QUIET_MS)
        assertThat(resends.last()).isEqualTo(20_000 + SETTLE_QUIET_MS to listOf("SmbChange"))
    }

    /** e: the flag of the calculation, or a newer BG, makes it a new BG; neither makes it a recalculation. */
    @Test
    fun `a calculation is a new BG by its flag or by a newer BG`() = runTest {
        lastBg = InMemoryGlucoseValue(timestamp = 1_000L, value = 100.0)
        val resends = collectResends()
        rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = true))
        // In recalculated mode the newest bucket keeps its time, but the calculation was for a new BG
        at(20_000); lastBg = InMemoryGlucoseValue(timestamp = 1_000L, value = 104.0)
        rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = true))
        // No flag, but a newer BG
        at(40_000); lastBg = InMemoryGlucoseValue(timestamp = 39_000L, value = 108.0)
        rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = false))
        // Neither: a recalculation after the follow window, it waits
        at(60_000); rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = false))
        at(60_000 + FAST_DELAY_MS)
        assertThat(resends.map { it.first }).containsExactly(2_000L, 22_000L, 42_000L).inOrder()
        assertThat(resends.map { it.second }).containsExactly(listOf(REASON_NEW_BG), listOf(REASON_NEW_BG), listOf(REASON_NEW_BG)).inOrder()
        at(60_000 + SETTLE_QUIET_MS)
        assertThat(resends.map { it.first }).containsExactly(2_000L, 22_000L, 42_000L, 90_000L).inOrder()
    }

    /** g: before the app is initialized `resendData` sends nothing, so it is not called and the resend waits. */
    @Test
    fun `a resend waits until the app is initialized`() = runTest {
        whenever(config.appInitialized).thenReturn(false)
        sut.start(backgroundScope, { testScheduler.currentTime })
        runCurrent()
        rxBus.send(EventLoopUpdateGui())
        at(FAST_DELAY_MS + 1)
        verifyBlocking(dataHandlerMobile, never()) { resendData(any()) }

        whenever(config.appInitialized).thenReturn(true)
        at(FAST_DELAY_MS + RETRY_DELAY_MS + 1)
        verifyBlocking(dataHandlerMobile, times(1)) { resendData("EventLoopUpdateGui") }
    }

    /** g: a resend that threw is tried again. */
    @Test
    fun `a resend that threw is tried again`() = runTest {
        var calls = 0
        dataHandlerMobile.stub {
            onBlocking { resendData(any()) } doAnswer {
                calls++
                if (calls == 1) throw IllegalStateException("test")
            }
        }
        sut.start(backgroundScope, { testScheduler.currentTime })
        runCurrent()
        temporaryTargets.emit(emptyList())
        at(FAST_DELAY_MS + RETRY_DELAY_MS + 1)
        assertThat(calls).isEqualTo(2)
        at(60_000)
        assertThat(calls).isEqualTo(2)
    }

    /** A resend that keeps throwing is tried two more times, then given up: it does not wake the watch for ever. */
    @Test
    fun `a resend that keeps throwing is given up`() = runTest {
        var calls = 0
        dataHandlerMobile.stub {
            onBlocking { resendData(any()) } doAnswer {
                calls++
                throw IllegalStateException("test")
            }
        }
        sut.start(backgroundScope, { testScheduler.currentTime })
        runCurrent()
        temporaryTargets.emit(emptyList())
        at(10 * 60_000L)
        assertThat(calls).isEqualTo(1 + MAX_RETRIES)
        // A new reason gives new tries
        carbs.emit(emptyList())
        at(20 * 60_000L)
        assertThat(calls).isEqualTo(2 * (1 + MAX_RETRIES))
    }

    /** AAPSCLIENT: the predictions, IOB and COB from NS count as a loop run, so after a new BG they go out within 2 s. */
    @Test
    fun `new predictions from NS shortly after a new BG go out within 2 s`() = runTest {
        val resends = collectResends()
        rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = true))
        at(10_000)
        rxBus.send(EventNsClientStatusUpdated())
        at(10_000 + FAST_DELAY_MS)
        assertThat(resends).containsExactly(
            FAST_DELAY_MS to listOf(REASON_NEW_BG),
            10_000 + FAST_DELAY_MS to listOf("EventNsClientStatusUpdated")
        ).inOrder()
    }
}
