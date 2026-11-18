package app.aaps.wear.comm

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearDataToMobile
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.impl.rx.bus.RxBusImpl
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.WearTestBase
import app.aaps.wear.alarm.CgmAlarmWatch
import app.aaps.wear.alarm.PhoneAlarmWatch
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.eatreminder.EatReminderWatch
import app.aaps.wear.watchfaces.WatchFacePushHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The night face (see `CwfNight`) through [DataHandlerWear] and a real [RxBusImpl], like
 * [DataHandlerWearTest].
 */
internal class DataHandlerWearNightFaceTest : WearTestBase() {

    @Mock lateinit var preferences: Preferences
    @Mock lateinit var complicationDataRepository: ComplicationDataRepository
    @Mock lateinit var watchFacePushHelper: WatchFacePushHelper
    @Mock lateinit var eatReminderWatch: EatReminderWatch
    @Mock lateinit var phoneAlarmWatch: PhoneAlarmWatch
    @Mock lateinit var cgmAlarmWatch: CgmAlarmWatch // PERSONAL PATCH: CGM alarms on the watch

    private val logger = AAPSLoggerTest()
    private lateinit var rxBus: RxBus
    private lateinit var sut: DataHandlerWear

    /** What the watch sent to the phone. */
    private val sent = CopyOnWriteArrayList<EventData>()
    private lateinit var collector: Job

    private val main = cwf("main")
    private val night = cwf("night")

    private fun cwf(name: String) = CwfData("{}", mutableMapOf(CwfMetadataKey.CWF_NAME to name), mutableMapOf())

    @BeforeEach
    fun setupHandler() {
        rxBus = RxBusImpl(logger)
        // UNDISPATCHED so the collector is subscribed before anything is sent; RxBus has no replay.
        collector = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            rxBus.toFlow(EventWearDataToMobile::class).collect { sent.add(it.payload) }
        }
        sut = DataHandlerWear(context, rxBus, sp, preferences, logger, complicationDataRepository, watchFacePushHelper, eatReminderWatch, phoneAlarmWatch, cgmAlarmWatch) // PERSONAL PATCH: cgmAlarmWatch
    }

    @AfterEach
    fun stopCollector() {
        collector.cancel()
    }

    /** Waits until the watch sent [count] messages to the phone, then gives them. */
    private fun sentToPhone(count: Int): List<EventData> {
        val end = System.currentTimeMillis() + HANDLER_TIMEOUT_MS
        while (sent.size < count && System.currentTimeMillis() < end) Thread.sleep(10)
        // A little longer, so a message too many would show up as well
        Thread.sleep(100)
        return sent.toList()
    }

    @Test
    fun `a night face is stored in the night slot and reported as night`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedNightWatchface()).thenReturn(night)

        rxBus.send(EventData.ActionSetCustomWatchface(night, night = true))

        verifyBlocking(complicationDataRepository, timeout(HANDLER_TIMEOUT_MS)) { storeNightWatchface(night) }
        assertThat(sentToPhone(1)).containsExactly(EventData.ActionUpdateCustomWatchface(night, night = true))
        verifyBlocking(complicationDataRepository, never()) { storeCustomWatchface(any(), any(), any()) }
    }

    @Test
    fun `a main face is stored and reported as before`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(false)).thenReturn(main)

        rxBus.send(EventData.ActionSetCustomWatchface(main))

        verifyBlocking(complicationDataRepository, timeout(HANDLER_TIMEOUT_MS)) { storeCustomWatchface(main, null, false) }
        assertThat(sentToPhone(1)).containsExactly(EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(main), false))
        verifyBlocking(complicationDataRepository, never()) { storeNightWatchface(any()) }
    }

    @Test
    fun `clearing the night face is answered with the main face and no night face`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(false)).thenReturn(main)

        rxBus.send(EventData.ActionClearNightWatchface(1_000L))

        verifyBlocking(complicationDataRepository, timeout(HANDLER_TIMEOUT_MS)) { clearNightWatchface() }
        val answer = sentToPhone(2)
        assertThat(answer).hasSize(2)
        assertThat(answer[0]).isEqualTo(EventData.ActionUpdateCustomWatchface(main, night = false))
        assertThat(answer[1]).isInstanceOf(EventData.ActionClearNightWatchface::class.java)
    }

    @Test
    fun `a rule is stored with the night face`() = runBlocking<Unit> {
        val rule = DayNightRule.Place(52.4, 4.9)
        whenever(complicationDataRepository.storeDayNightRule(rule)).thenReturn(true)

        rxBus.send(EventData.ActionSetDayNightRule(rule))

        verifyBlocking(complicationDataRepository, timeout(HANDLER_TIMEOUT_MS)) { storeDayNightRule(rule) }
        assertThat(sentToPhone(0)).isEmpty()
    }

    @Test
    fun `a rule without a night face is answered with no night face`() = runBlocking<Unit> {
        val rule = DayNightRule.FixedTimes(420, 1140)
        whenever(complicationDataRepository.storeDayNightRule(rule)).thenReturn(false)

        rxBus.send(EventData.ActionSetDayNightRule(rule))

        val answer = sentToPhone(1)
        assertThat(answer).hasSize(1)
        assertThat(answer[0]).isInstanceOf(EventData.ActionClearNightWatchface::class.java)
    }

    @Test
    fun `face messages are handled one after the other`() = runBlocking<Unit> {
        // The night face as the mock store holds it
        var stored: CwfData? = null
        whenever(complicationDataRepository.storeNightWatchface(any())).thenAnswer { stored = it.arguments[0] as CwfData; Unit }
        whenever(complicationDataRepository.getSimplifiedNightWatchface()).thenAnswer { stored }
        whenever(complicationDataRepository.getNightWatchface()).thenAnswer { stored }
        // A slow read of the main face for the request that comes first
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(false)).thenAnswer { Thread.sleep(300); main }

        rxBus.send(EventData.ActionrequestCustomWatchface(false, night = true))
        rxBus.send(EventData.ActionSetCustomWatchface(night, night = true))

        // The request was first, so its "no night face" comes before the report of the new night face
        val answer = sentToPhone(3)
        assertThat(answer).hasSize(3)
        assertThat(answer[0]).isEqualTo(EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(main), false))
        assertThat(answer[1]).isInstanceOf(EventData.ActionClearNightWatchface::class.java)
        assertThat(answer[2]).isEqualTo(EventData.ActionUpdateCustomWatchface(night, night = true))
    }

    @Test
    fun `a metadata update for the night face goes to the night slot`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedNightWatchface()).thenReturn(night)

        rxBus.send(EventData.ActionUpdateCustomWatchface(night, night = true))

        verifyBlocking(complicationDataRepository, timeout(HANDLER_TIMEOUT_MS)) { updateNightWatchfaceMetadata(night.metadata) }
        assertThat(sentToPhone(1)).containsExactly(EventData.ActionUpdateCustomWatchface(night, night = true))
        verifyBlocking(complicationDataRepository, never()) { updateCustomWatchfaceMetadata(any()) }
    }

    @Test
    fun `asked for its face the watch sends the main face and the night face`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(false)).thenReturn(main)
        whenever(complicationDataRepository.getNightWatchface()).thenReturn(night)
        whenever(complicationDataRepository.getSimplifiedNightWatchface()).thenReturn(night)

        // Also when the phone does not ask about a night face: it may have forgotten it
        rxBus.send(EventData.ActionrequestCustomWatchface(false))

        assertThat(sentToPhone(2)).containsExactly(
            EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(main), false),
            EventData.ActionUpdateCustomWatchface(night, night = true)
        ).inOrder()
    }

    @Test
    fun `asked for its face without a night face the watch sends only the main face, as before`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(false)).thenReturn(main)

        rxBus.send(EventData.ActionrequestCustomWatchface(false))

        assertThat(sentToPhone(1)).containsExactly(EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(main), false))
    }

    @Test
    fun `asked about a night face it does not have the watch says it has none`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(false)).thenReturn(main)

        rxBus.send(EventData.ActionrequestCustomWatchface(false, night = true))

        val answer = sentToPhone(2)
        assertThat(answer).hasSize(2)
        assertThat(answer[0]).isEqualTo(EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(main), false))
        assertThat(answer[1]).isInstanceOf(EventData.ActionClearNightWatchface::class.java)
    }

    @Test
    fun `a metadata update for a night face the watch no longer has is answered with no night face`() = runBlocking<Unit> {
        rxBus.send(EventData.ActionUpdateCustomWatchface(night, night = true))

        val answer = sentToPhone(1)
        assertThat(answer).hasSize(1)
        assertThat(answer[0]).isInstanceOf(EventData.ActionClearNightWatchface::class.java)
    }

    @Test
    fun `an export sends only the default face`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(true)).thenReturn(main)
        whenever(complicationDataRepository.getNightWatchface()).thenReturn(night)
        whenever(complicationDataRepository.getSimplifiedNightWatchface()).thenReturn(night)

        rxBus.send(EventData.ActionrequestCustomWatchface(true, night = true))

        assertThat(sentToPhone(1)).containsExactly(EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(main), true))
    }

    companion object {

        private const val HANDLER_TIMEOUT_MS = 5_000L
    }
}
