package app.aaps.plugins.sync.wear

import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.alerts.EatReminder
import app.aaps.core.interfaces.alerts.EatReminderState
import app.aaps.core.interfaces.scenes.SceneAutomationApi
import app.aaps.plugins.sync.tidepool.utils.RateLimit
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobileHelper
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WearPluginTest : TestBaseWithProfile() {

    @Mock lateinit var dataHandlerMobile: DataHandlerMobile
    @Mock lateinit var dataLayerListenerServiceMobileHelper: DataLayerListenerServiceMobileHelper
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var scenes: SceneAutomationApi
    @Mock lateinit var eatReminder: EatReminder

    private lateinit var wearPlugin: WearPlugin
    private lateinit var rateLimit: RateLimit

    private val temporaryTargets = MutableSharedFlow<List<TT>>()
    private val runningModes = MutableSharedFlow<List<RM>>()
    private val eatReminderState = MutableStateFlow<EatReminderState?>(null)

    @BeforeEach fun prepare() {
        rateLimit = RateLimit(dateUtil)
        whenever(eatReminder.state).thenReturn(eatReminderState)
        wearPlugin = WearPlugin(aapsLogger, rh, preferences, rxBus, context, dataHandlerMobile, dataLayerListenerServiceMobileHelper, config, BolusProgressData(ch, CoroutineScope(Dispatchers.Unconfined)), persistenceLayer, scenes, eatReminder, mock())
        whenever(persistenceLayer.observeChanges(TT::class)).thenReturn(temporaryTargets)
        whenever(persistenceLayer.observeChanges(RM::class)).thenReturn(runningModes)
    }

    /** Collects [WearPlugin.resendRequests] in virtual time; the database flows send their first value as on a phone. */
    private suspend fun TestScope.collectResends(): MutableList<String> {
        val reasons = mutableListOf<String>()
        val flow = wearPlugin.resendRequests(backgroundScope) { testScheduler.currentTime }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { reasons.add(it) } }
        runCurrent()
        // observeChanges sends the current state when it is collected; that is not a change
        temporaryTargets.emit(emptyList())
        runningModes.emit(emptyList())
        runCurrent()
        return reasons
    }

    /**
     * An event sent right after the start, before anything reads the flow, is not lost: the sources are
     * subscribed when `resendRequests` is called. With `merge` they were subscribed only when read.
     */
    @Test
    fun `an event right after the start is not lost`() = runTest {
        val reasons = mutableListOf<String>()
        val flow = wearPlugin.resendRequests(backgroundScope)
        loopGui()

        backgroundScope.launch { flow.collect { reasons.add(it) } }
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(reasons).containsExactly("EventLoopUpdateGui")
    }

    /** Sends [send] at [atSecond] seconds after the previous call's time. */
    private fun TestScope.at(atSecond: Double, send: () -> Unit) {
        advanceTimeBy((atSecond * 1000).toLong().milliseconds - testScheduler.currentTime.milliseconds)
        runCurrent()
        send()
    }

    private val autosens = { rxBus.send(EventAutosensCalculationFinished(triggeredByNewBG = true)) }
    private val loopGui = { rxBus.send(EventLoopUpdateGui()) }
    private val nsStatus = { rxBus.send(EventNsClientStatusUpdated()) }

    /**
     * The events of the BG cycle measured on a Pixel at 19:15 (seconds after 19:15:00). Each event
     * used to start a full resend: 10 events, 9 resends seen in the log. A 2 s debounce made that 5,
     * one per burst. Now the first burst goes out fast with the new BG, and everything after it in
     * the same cycle waits for [WearPlugin.RESEND_SETTLE_DEBOUNCE] of quiet: 2 resends.
     */
    @Test
    fun `a BG cycle of events gives two resends`() = runTest {
        val reasons = collectResends()

        at(10.0, autosens); at(11.0, autosens)                   // calculation, twice
        at(24.0, loopGui)                                         // loop result
        at(29.0, nsStatus)                                        // NS
        at(49.0, loopGui); at(50.0, loopGui); at(50.2, autosens)  // a new TBR: history change, calculation, loop
        at(51.0, autosens); at(51.3, loopGui)
        at(55.0, nsStatus)
        advanceTimeBy(WearPlugin.RESEND_SETTLE_DEBOUNCE - 100.milliseconds)
        runCurrent()
        assertThat(reasons).containsExactly("EventAutosensCalculationFinished")

        advanceTimeBy(200.milliseconds)
        runCurrent()
        // The reason of the last event of each burst
        assertThat(reasons).containsExactly("EventAutosensCalculationFinished", "EventNsClientStatusUpdated").inOrder()
    }

    /** A change the user made does not wait for the BG cycle to settle. */
    @Test
    fun `a temp target set while the cycle settles goes out fast`() = runTest {
        val reasons = collectResends()

        at(10.0, autosens)                                        // new BG: resent at 12 s
        at(20.0, loopGui)                                         // loop result: would wait 30 s
        advanceTimeBy(5.seconds)                                  // 25 s: the user sets a temp target
        runCurrent()
        temporaryTargets.emit(emptyList())
        advanceTimeBy(WearPlugin.RESEND_DEBOUNCE + 100.milliseconds)
        runCurrent()

        assertThat(reasons).containsExactly("EventAutosensCalculationFinished", "TempTargetChange").inOrder()
    }

    /** The next BG cycle, minutes later, is fast again. */
    @Test
    fun `the first event of the next cycle is resent fast again`() = runTest {
        val reasons = collectResends()

        at(10.0, autosens)
        at(300.0, autosens)
        advanceTimeBy(WearPlugin.RESEND_DEBOUNCE + 100.milliseconds)
        runCurrent()

        assertThat(reasons).containsExactly("EventAutosensCalculationFinished", "EventAutosensCalculationFinished")
    }

    /** A single event waits the debounce time, not longer. */
    @Test
    fun `one event is resent after the debounce time`() = runTest {
        val reasons = collectResends()

        loopGui()
        advanceTimeBy(WearPlugin.RESEND_DEBOUNCE - 100.milliseconds)
        runCurrent()
        assertThat(reasons).isEmpty()
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertThat(reasons).containsExactly("EventLoopUpdateGui")
    }

    /**
     * The temporary target and running mode changes go through the same debounce, so a TT that also
     * makes the loop run gives one resend, and the first value of the database flows gives none.
     */
    @Test
    fun `database changes share the debounce and their first value is not a change`() = runTest {
        val reasons = collectResends()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertThat(reasons).isEmpty()

        temporaryTargets.emit(emptyList())
        advanceTimeBy(500.milliseconds)
        runningModes.emit(emptyList())
        advanceTimeBy(500.milliseconds)
        loopGui()
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(reasons).containsExactly("EventLoopUpdateGui")
    }

    @Test
    fun `a change of the eat reminder is resent at once, its first value is not a change`() = runTest {
        val reasons = collectResends()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertThat(reasons).isEmpty()

        eatReminderState.value = EatReminderState(startedAt = 1_000L, endsAt = 901_000L, durationMs = 900_000L)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(reasons).containsExactly("EatReminderChange")
    }
}
