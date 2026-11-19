package app.aaps.wear.comm

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearDataToMobile
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.impl.rx.bus.RxBusImpl
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.WearTestBase
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
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList

/** "No rule" from the phone, see `PlaceRuleKeeping`: the watch forgets the rule, or with a night face says it has one. */
internal class DataHandlerWearPlaceRuleTest : WearTestBase() {

    @Mock lateinit var preferences: Preferences
    @Mock lateinit var complicationDataRepository: ComplicationDataRepository
    @Mock lateinit var watchFacePushHelper: WatchFacePushHelper
    @Mock lateinit var eatReminderWatch: EatReminderWatch
    @Mock lateinit var phoneAlarmWatch: PhoneAlarmWatch

    private val logger = AAPSLoggerTest()
    private lateinit var rxBus: RxBus

    private val sent = CopyOnWriteArrayList<EventData>()
    private lateinit var collector: Job

    @BeforeEach
    fun setupHandler() {
        rxBus = RxBusImpl(logger)
        collector = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            rxBus.toFlow(EventWearDataToMobile::class).collect { sent.add(it.payload) }
        }
        DataHandlerWear(context, rxBus, sp, preferences, logger, complicationDataRepository, watchFacePushHelper, eatReminderWatch, phoneAlarmWatch)
    }

    @AfterEach
    fun stopCollector() {
        collector.cancel()
    }

    private fun sentToPhone(count: Int): List<EventData> {
        val end = System.currentTimeMillis() + HANDLER_TIMEOUT_MS
        while (sent.size < count && System.currentTimeMillis() < end) Thread.sleep(10)
        Thread.sleep(100)
        return sent.toList()
    }

    @Test
    fun `no rule is forgotten without an answer`() = runBlocking<Unit> {
        whenever(complicationDataRepository.clearDayNightRule()).thenReturn(true)

        rxBus.send(EventData.ActionClearDayNightRule(1_000L))

        verifyBlocking(complicationDataRepository, timeout(HANDLER_TIMEOUT_MS)) { clearDayNightRule() }
        assertThat(sentToPhone(0)).isEmpty()
    }

    @Test
    fun `with a night face the rule stays and the phone learns of the night face`() = runBlocking<Unit> {
        val night = CwfData("{}", mutableMapOf(CwfMetadataKey.CWF_NAME to "night"), mutableMapOf())
        whenever(complicationDataRepository.clearDayNightRule()).thenReturn(false)
        whenever(complicationDataRepository.getSimplifiedNightWatchface()).thenReturn(night)

        rxBus.send(EventData.ActionClearDayNightRule(1_000L))

        assertThat(sentToPhone(1)).containsExactly(EventData.ActionUpdateCustomWatchface(night, night = true))
    }

    private companion object {

        const val HANDLER_TIMEOUT_MS = 5_000L
    }
}
