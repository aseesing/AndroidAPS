// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.comm

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearDataToMobile
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.impl.rx.bus.RxBusImpl
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.R
import app.aaps.wear.WearTestBase
import app.aaps.wear.alarm.PhoneAlarmWatch
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.eatreminder.EatReminderWatch
import app.aaps.wear.watchfaces.WatchFacePushHelper
import app.aaps.wear.watchfaces.daynight.DayNightChoice
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The night face (see `CwfNight`) and the day / night settings from the phone, through
 * [DataHandlerWear] and a real [RxBusImpl], like [DataHandlerWearTest].
 */
internal class DataHandlerWearNightFaceTest : WearTestBase() {

    @Mock lateinit var preferences: Preferences
    @Mock lateinit var complicationDataRepository: ComplicationDataRepository
    @Mock lateinit var watchFacePushHelper: WatchFacePushHelper
    @Mock lateinit var eatReminderWatch: EatReminderWatch
    @Mock lateinit var phoneAlarmWatch: PhoneAlarmWatch

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
        sut = DataHandlerWear(context, rxBus, sp, preferences, logger, complicationDataRepository, watchFacePushHelper, eatReminderWatch, phoneAlarmWatch)
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
    fun `clearing the night face is answered with the main face`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(false)).thenReturn(main)

        rxBus.send(EventData.ActionClearNightWatchface(1_000L))

        verifyBlocking(complicationDataRepository, timeout(HANDLER_TIMEOUT_MS)) { clearNightWatchface() }
        assertThat(sentToPhone(1)).containsExactly(EventData.ActionUpdateCustomWatchface(main, night = false))
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
        whenever(complicationDataRepository.getSimplifiedNightWatchface()).thenReturn(night)

        rxBus.send(EventData.ActionrequestCustomWatchface(false))

        assertThat(sentToPhone(2)).containsExactly(
            EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(main), false),
            EventData.ActionUpdateCustomWatchface(night, night = true)
        ).inOrder()
    }

    @Test
    fun `asked for its face without a night face the watch sends only the main face`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(false)).thenReturn(main)

        rxBus.send(EventData.ActionrequestCustomWatchface(false))

        assertThat(sentToPhone(1)).containsExactly(EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(main), false))
    }

    @Test
    fun `an export sends only the default face`() = runBlocking<Unit> {
        whenever(complicationDataRepository.getSimplifiedCustomWatchface(true)).thenReturn(main)
        whenever(complicationDataRepository.getSimplifiedNightWatchface()).thenReturn(night)

        rxBus.send(EventData.ActionrequestCustomWatchface(true))

        assertThat(sentToPhone(1)).containsExactly(EventData.ActionGetCustomWatchface(EventData.ActionSetCustomWatchface(main), true))
    }

    @Test
    fun `day and night settings from the phone are stored when the phone changed them`() {
        whenever(sp.getString(R.string.key_day_night_look_phone, "")).thenReturn(DayNightChoice.AUTO)
        whenever(sp.getString(R.string.key_day_night_always_on_phone, "")).thenReturn("")

        rxBus.send(preferences(dayNightLook = DayNightChoice.NIGHT, alwaysOnLook = DayNightChoice.AS_ACTIVE))

        verify(sp, timeout(HANDLER_TIMEOUT_MS)).putString(R.string.key_day_night_look, DayNightChoice.NIGHT)
        verify(sp, timeout(HANDLER_TIMEOUT_MS)).putString(R.string.key_day_night_look_phone, DayNightChoice.NIGHT)
        verify(sp, timeout(HANDLER_TIMEOUT_MS)).putString(R.string.key_day_night_always_on, DayNightChoice.AS_ACTIVE)
        verify(sp, timeout(HANDLER_TIMEOUT_MS)).putString(R.string.key_day_night_always_on_phone, DayNightChoice.AS_ACTIVE)
    }

    @Test
    fun `day and night settings the phone sent before do not undo a change made on the watch`() {
        whenever(sp.getString(R.string.key_day_night_look_phone, "")).thenReturn(DayNightChoice.NIGHT)
        whenever(sp.getString(R.string.key_day_night_always_on_phone, "")).thenReturn(DayNightChoice.DAY)

        rxBus.send(preferences(dayNightLook = DayNightChoice.NIGHT, alwaysOnLook = DayNightChoice.DAY))

        // The face is chosen after these settings, so once it is, they would have been written
        verify(watchFacePushHelper, timeout(HANDLER_TIMEOUT_MS)).selectFace(any())
        verify(sp, never()).putString(eq(R.string.key_day_night_look), any())
        verify(sp, never()).putString(eq(R.string.key_day_night_always_on), any())
    }

    @Test
    fun `an older phone without day and night settings changes nothing`() {
        rxBus.send(preferences(dayNightLook = null, alwaysOnLook = null))

        verify(watchFacePushHelper, timeout(HANDLER_TIMEOUT_MS)).selectFace(any())
        verify(sp, never()).putString(eq(R.string.key_day_night_look), any())
        verify(sp, never()).putString(eq(R.string.key_day_night_always_on), any())
        verify(sp, never()).putString(eq(R.string.key_day_night_look_phone), any())
    }

    private fun preferences(dayNightLook: String?, alwaysOnLook: String?) =
        EventData.Preferences(0L, false, true, 50, 80, 25.0, 0.5, 1.0, 5, 10, dayNightLook = dayNightLook, alwaysOnLook = alwaysOnLook)

    companion object {

        private const val HANDLER_TIMEOUT_MS = 5_000L
    }
}
