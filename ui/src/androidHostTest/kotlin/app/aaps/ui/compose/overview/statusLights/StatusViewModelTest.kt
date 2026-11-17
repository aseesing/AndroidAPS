package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TDD
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventConfigBuilderChange
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TimeDiff
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.shared.tests.generatedTextResolver
import app.aaps.ui.UiStringsValues
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
internal class StatusViewModelTest {

    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var config: Config
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var tddCalculator: TddCalculator
    @Mock private lateinit var decimalFormatter: DecimalFormatter
    @Mock private lateinit var processedDeviceStatusData: ProcessedDeviceStatusData

    private val teChanges = MutableSharedFlow<List<TE>>()
    private val reservoir = MutableStateFlow(PumpInsulin(10.0))

    /** Refreshes that have started: each one reads the cannula change once. */
    private val started = AtomicInteger(0)

    /** Refreshes that have written the cannula usage, which is the last thing a refresh does. */
    private val finished = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(rxBus.toFlow(EventInitializationChanged::class)).thenReturn(emptyFlow())
        whenever(rxBus.toFlow(EventConfigBuilderChange::class)).thenReturn(emptyFlow())
        whenever(rxBus.toFlow(EventNsClientStatusUpdated::class)).thenReturn(emptyFlow())
        whenever(persistenceLayer.observeChanges(TE::class)).thenReturn(teChanges)
        whenever(persistenceLayer.databaseClearedFlow).thenReturn(emptyFlow())
        whenever(preferences.observe(BooleanKey.OverviewStatusLightsCountdown)).thenReturn(countdownSwitch)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** The countdown switch, as the preference store publishes it. */
    private val countdownSwitch = MutableStateFlow(false)

    private fun newViewModel(textResolver: TextResolver = rh) = StatusViewModel(
        textResolver, activePlugin, profileFunction, config, persistenceLayer, dateUtil, rxBus, preferences,
        tddCalculator, decimalFormatter, processedDeviceStatusData, StatusTimeLeft(textResolver, dateUtil)
    )

    @Test
    fun `default uiState has no status items and hidden actions`() {
        val state = newViewModel().uiState.value
        assertThat(state.sensorStatus).isNull()
        assertThat(state.insulinStatus).isNull()
        assertThat(state.showFill).isFalse()
        assertThat(state.showPumpBatteryChange).isFalse()
        assertThat(state.isPatchPump).isFalse()
    }

    /**
     * Every screen collects the state with collectAsStateWithLifecycle, so with the app in the background
     * nobody collects. Then nothing may run: no database reads, no cannula usage, no minute ticker (#5246).
     */
    @Test
    fun `nothing is refreshed while no screen shows the status lights`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenStatusData()
        newViewModel()

        teChanges.emit(emptyList())
        reservoir.value = PumpInsulin(20.0)
        delay(QUIET_MS)

        assertThat(started.get()).isEqualTo(0)
    }

    @Test
    fun `a screen that starts showing the status lights gets one refresh`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenStatusData()
        val viewModel = newViewModel()

        val screen = show(viewModel)
        awaitValue(finished, 1)
        delay(QUIET_MS)

        assertThat(started.get()).isEqualTo(1)
        assertThat(viewModel.uiState.value.cannulaStatus?.level).isEqualTo(USAGE_TEXT)
        screen.cancelAndJoin()
    }

    /**
     * Reservoir and battery are the only pump values the status lights show. A new value refreshes, the
     * same value does not. Pump status events are not used any more: they came many times per
     * connection with nothing changed.
     */
    @Test
    fun `a new reservoir level refreshes, the same level does not`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenStatusData()
        val viewModel = newViewModel()
        val screen = show(viewModel)
        awaitValue(finished, 1)

        reservoir.value = PumpInsulin(10.0)
        delay(QUIET_MS)
        assertThat(started.get()).isEqualTo(1)

        reservoir.value = PumpInsulin(9.5)
        assertThat(awaitValue(finished, 2)).isEqualTo(2)
        screen.cancelAndJoin()
    }

    /**
     * Triggers can come close together. Each refresh used to start its own cannula usage calculation and
     * none was cancelled, so they piled up on the database. Only one may run at a time: a newer refresh
     * cancels the older one.
     */
    @Test
    fun `a new refresh cancels the cannula usage calculation still running`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenStatusData()
        val running = AtomicInteger(0)
        val tddDone = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        whenever(tddCalculator.calculateIntervalWithCachedDays(any(), any(), any())).doSuspendableAnswer {
            running.incrementAndGet()
            try {
                gate.await()
                // Before `running` drops: the test reads this as soon as `running` is 0. Seen on CI as 0 when it came after.
                tddDone.incrementAndGet()
            } finally {
                running.decrementAndGet()
            }
            TDD(timestamp = 0, totalAmount = 12.0)
        }
        val viewModel = newViewModel()
        val screen = show(viewModel)
        assertThat(awaitValue(running, 1)).isEqualTo(1)

        // Three therapy event changes while the calculation is still running. Each must end the one before.
        repeat(3) {
            val before = started.get()
            teChanges.emit(emptyList())
            awaitMore(started, before)
            assertThat(awaitValue(running, 1)).isEqualTo(1)
        }

        gate.complete(Unit)
        assertThat(awaitValue(running, 0)).isEqualTo(0)
        assertThat(tddDone.get()).isEqualTo(1)

        // Wait until the last refresh is back on Main and has written the usage. `running` drops inside
        // the IO hop, so without this the test could return while that coroutine is still on its way
        // back to Main. tearDown() then resets Main, the resume crashes, and the next runTest in this
        // JVM fails with UncaughtExceptionsBeforeTest (seen on CI in an unrelated test class).
        awaitValue(finished, 1)
        screen.cancelAndJoin()
    }

    /**
     * The countdown is a switch, off by default. Off, every row shows the age exactly as before, from the
     * user's thresholds, and the newest BG reading is not even read.
     */
    @Test
    fun `with the countdown off, a pod and a G6 show their age as before`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenDevices(countdown = false)

        val state = refreshed(newViewModel(texts()))

        assertThat(state.sensorStatus?.age).isEqualTo("2d0h")
        assertThat(state.sensorStatus?.ageStatus).isEqualTo(StatusLevel.NORMAL)
        assertThat(state.sensorStatus?.agePercent).isWithin(0.001f).of(48f / 240f)
        assertThat(state.cannulaStatus?.age).isEqualTo("2d12h")
        assertThat(state.cannulaStatus?.ageStatus).isEqualTo(StatusLevel.WARNING)
        assertThat(state.cannulaStatus?.agePercent).isWithin(0.001f).of(60f / 72f)
        assertThat(state.insulinStatus?.age).isEqualTo("2d12h")
        assertThat(state.insulinStatus?.ageStatus).isEqualTo(StatusLevel.NORMAL)
        assertThat(state.insulinStatus?.agePercent).isWithin(0.001f).of(60f / 144f)
        verify(persistenceLayer, never()).getLastGlucoseValue()
    }

    @Test
    fun `with the countdown on, a pod and a G6 show the time left until their real end`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenDevices(countdown = true)

        val state = refreshed(newViewModel(texts()))

        // A G6 stops 10 days after the start, 2 days are gone
        assertThat(state.sensorStatus?.age).isEqualTo("8d0h left")
        assertThat(state.sensorStatus?.ageStatus).isEqualTo(StatusLevel.NORMAL)
        assertThat(state.sensorStatus?.agePercent).isWithin(0.001f).of(48f / 240f)
        // A pod stops 80 hours after the start, 60 are gone: less than one day left is yellow
        assertThat(state.cannulaStatus?.age).isEqualTo("20h left")
        assertThat(state.cannulaStatus?.ageStatus).isEqualTo(StatusLevel.WARNING)
        // The insulin is in the pod, so it ends with the pod
        assertThat(state.insulinStatus?.age).isEqualTo("20h left")
        assertThat(state.insulinStatus?.ageStatus).isEqualTo(StatusLevel.WARNING)
    }

    /** A 10 day and a 15 day G7 send the same label, so a G7 shows its age and never a wrong countdown. */
    @Test
    fun `with the countdown on, a G7 shows its age`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenDevices(countdown = true, sensor = SourceSensor.DEXCOM_G7)

        val state = refreshed(newViewModel(texts()))

        assertThat(state.sensorStatus?.age).isEqualTo("2d0h")
        assertThat(state.sensorStatus?.ageStatus).isEqualTo(StatusLevel.NORMAL)
        assertThat(state.sensorStatus?.agePercent).isWithin(0.001f).of(48f / 240f)
    }

    /** No start event: the same "-" as the age, and the BG reading is not read for nothing. */
    @Test
    fun `with the countdown on and no sensor change, the sensor row shows a dash`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenDevices(countdown = true)
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(null)

        val state = refreshed(newViewModel(texts()))

        assertThat(state.sensorStatus?.age).isEqualTo("-")
        assertThat(state.sensorStatus?.ageStatus).isEqualTo(StatusLevel.UNSPECIFIED)
        assertThat(state.sensorStatus?.agePercent).isEqualTo(0f)
        verify(persistenceLayer, never()).getLastGlucoseValue()
    }

    /** The rows follow the switch at once, not only at the next minute tick. */
    @Test
    fun `flipping the switch refreshes the rows`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenDevices(countdown = false)
        val viewModel = newViewModel(texts())
        val screen = show(viewModel)
        awaitValue(finished, 1)
        assertThat(viewModel.uiState.value.sensorStatus?.age).isEqualTo("2d0h")

        whenever(preferences.get(BooleanKey.OverviewStatusLightsCountdown)).thenReturn(true)
        countdownSwitch.value = true
        assertThat(awaitValue(finished, 2)).isEqualTo(2)
        delay(QUIET_MS)

        assertThat(viewModel.uiState.value.sensorStatus?.age).isEqualTo("8d0h left")
        assertThat(viewModel.uiState.value.cannulaStatus?.age).isEqualTo("20h left")
        screen.cancelAndJoin()
    }

    /** No wrong countdown: a part whose end is not known keeps showing its age, also with the switch on. */
    @Test
    fun `with the countdown on, a sensor and pump with no known end show their age`() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        givenDevices(countdown = true, sensor = SourceSensor.LIBRE_2, pumpType = PumpType.MEDTRUM_NANO)

        val state = refreshed(newViewModel(texts()))

        assertThat(state.sensorStatus?.age).isEqualTo("2d0h")
        assertThat(state.sensorStatus?.ageStatus).isEqualTo(StatusLevel.NORMAL)
        assertThat(state.cannulaStatus?.age).isEqualTo("2d12h")
        assertThat(state.cannulaStatus?.ageStatus).isEqualTo(StatusLevel.WARNING)
        assertThat(state.insulinStatus?.age).isEqualTo("2d12h")
    }

    /** The real texts of this module and of core/ui, so the tests see what the user sees. */
    private fun texts(): TextResolver = generatedTextResolver("ui" to UiStringsValues::textOf)

    /** Refreshes once, the way a screen does when it shows the status lights. */
    private suspend fun CoroutineScope.refreshed(viewModel: StatusViewModel): StatusUiState {
        val screen = show(viewModel)
        awaitValue(finished, 1)
        delay(QUIET_MS)
        screen.cancelAndJoin()
        return viewModel.uiState.value
    }

    /**
     * A sensor changed 2 days ago and a cannula (or pod) and insulin changed 60 hours ago, with the
     * default thresholds. The newest BG reading comes from [sensor], the active pump is [pumpType].
     */
    private suspend fun givenDevices(countdown: Boolean, sensor: SourceSensor = SourceSensor.DEXCOM_G6, pumpType: PumpType = PumpType.OMNIPOD_DASH) {
        givenStatusData()
        whenever(dateUtil.now()).thenReturn(NOW)
        whenever(dateUtil.computeDiff(any(), any())).thenAnswer {
            val diff = it.getArgument<Long>(1) - it.getArgument<Long>(0)
            TimeDiff(diff / DAY, diff % DAY / HOUR, 0, 0, 0, 0, 0)
        }
        whenever(preferences.get(BooleanKey.OverviewStatusLightsCountdown)).thenReturn(countdown)
        whenever(preferences.get(IntKey.OverviewSageWarning)).thenReturn(216)
        whenever(preferences.get(IntKey.OverviewSageCritical)).thenReturn(240)
        whenever(preferences.get(IntKey.OverviewCageWarning)).thenReturn(48)
        whenever(preferences.get(IntKey.OverviewCageCritical)).thenReturn(72)
        whenever(preferences.get(IntKey.OverviewIageWarning)).thenReturn(72)
        whenever(preferences.get(IntKey.OverviewIageCritical)).thenReturn(144)
        whenever(activePlugin.activePump.model()).thenReturn(pumpType)
        val sensorChange = TE(timestamp = NOW - 2 * DAY, type = TE.Type.SENSOR_CHANGE, glucoseUnit = GlucoseUnit.MGDL)
        val insulinChange = TE(timestamp = NOW - 60 * HOUR, type = TE.Type.INSULIN_CHANGE, glucoseUnit = GlucoseUnit.MGDL)
        val cannulaChange = TE(timestamp = NOW - 60 * HOUR, type = TE.Type.CANNULA_CHANGE, glucoseUnit = GlucoseUnit.MGDL)
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(sensorChange)
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.INSULIN_CHANGE)).thenReturn(insulinChange)
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)).doSuspendableAnswer {
            started.incrementAndGet()
            cannulaChange
        }
        val reading = mock<GV>()
        whenever(reading.sourceSensor).thenReturn(sensor)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(reading)
    }

    /** Collects the state the way a screen does. */
    private fun CoroutineScope.show(viewModel: StatusViewModel): Job =
        launch(Dispatchers.Unconfined) { viewModel.uiState.collect { } }

    private suspend fun awaitMore(counter: AtomicInteger, than: Int) =
        withTimeout(5_000) { while (counter.get() <= than) delay(10) }

    /**
     * Waits for [counter] to reach [value] and returns what it has then.
     *
     * These waits are real time, not virtual: the test polls a counter that a coroutine on another
     * dispatcher increments. The timeout is only a ceiling, so a generous one costs nothing when the
     * machine is idle and stops the test failing on a busy one - at 2 s it began failing inside the
     * full module suite while still passing on its own, which says nothing about the behaviour under
     * test and everything about how much else was running.
     */
    private suspend fun awaitValue(counter: AtomicInteger, value: Int): Int =
        withTimeoutOrNull(20_000) { while (counter.get() != value) delay(10); counter.get() } ?: counter.get()

    /** Enough for a whole refresh: a cannula change to count from, no profile, no pump battery. */
    private suspend fun givenStatusData() {
        val pump = mock<PumpWithConcentration>()
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.pumpDescription).thenReturn(PumpDescription())
        whenever(pump.batteryLevel).thenReturn(MutableStateFlow<Int?>(null))
        whenever(pump.reservoirLevel).thenReturn(reservoir)
        val bgSource = mock<BgSource>()
        whenever(activePlugin.activeBgSource).thenReturn(bgSource)
        whenever(bgSource.sensorBatteryLevel).thenReturn(-1)
        whenever(rh.gs(any<TextRef>())).thenReturn("text")
        // The age is a text with arguments. An unstubbed mock gives null for it, which a real resolver never does.
        whenever(rh.gs(any<TextRef>(), anyVararg<Any>())).thenReturn("text")
        whenever(dateUtil.computeDiff(any(), any())).thenReturn(TimeDiff(1, 2, 0, 0, 0, 0, 0))
        val cannulaChange = mock<TE>()
        whenever(cannulaChange.timestamp).thenReturn(1_000L)
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(any())).thenReturn(null)
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)).doSuspendableAnswer {
            started.incrementAndGet()
            cannulaChange
        }
        whenever(tddCalculator.calculateIntervalWithCachedDays(any(), any(), any())).thenReturn(TDD(timestamp = 0, totalAmount = 12.0))
        // Only the cannula usage is formatted here (no profile, so no reservoir level), and it is the last
        // step of a refresh, so this marks the moment a refresh has finished.
        whenever(decimalFormatter.to0Decimal(any<Double>())).thenAnswer {
            finished.incrementAndGet()
            "12"
        }
        whenever(rh.gs(eq(CoreUiStrings.value_with_unit), any(), any())).thenReturn(USAGE_TEXT)
    }

    private companion object {

        const val USAGE_TEXT = "12 U"

        /** How long a test waits to be sure that something did NOT happen. */
        const val QUIET_MS = 300L

        const val NOW = 1_800_000_000_000L
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
