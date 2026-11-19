package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.wear.PreferencesInMemory
import app.aaps.plugins.sync.wear.daynight.DayNightMode
import app.aaps.plugins.sync.wear.daynight.PhoneRoughLocation
import app.aaps.plugins.sync.wear.daynight.TimeZoneCities
import app.aaps.plugins.sync.wear.daynight.WearDayNightSettings
import app.aaps.plugins.sync.wear.daynight.WearPlaceUse
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightStringKey
import app.aaps.plugins.sync.wear.keys.WearPlaceUseKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The weather follows the one switch too: only when a design shows it and a place is allowed, see [WearWeather]. */
class WearWeatherTest {

    private lateinit var store: PreferencesInMemory
    private lateinit var location: PhoneRoughLocation
    private lateinit var placeUse: WearPlaceUse
    private lateinit var sut: WearWeather
    private lateinit var rxBus: RxBus
    private lateinit var dateUtil: DateUtil

    private val sent = mutableListOf<EventData>()
    private val lookedUp = mutableListOf<Pair<Double, Double>>()
    private var now = 1_800_000_000_000L

    /** Runs the look-up at once */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val phonePlace = DayNightRule.Place(52.4, 4.9)

    @BeforeEach
    fun setUp() {
        store = PreferencesInMemory()
        whenever(store.preferences.getIfExists(WearDayNightBooleanKey.UsePhoneLocation)).thenAnswer { store.values[WearDayNightBooleanKey.UsePhoneLocation] as Boolean? }
        WearPlaceUseKey.entries.forEach { key -> whenever(store.preferences.getIfExists(key)).thenAnswer { store.values[key] as String? } }
        rxBus = mock()
        doAnswer { (it.arguments[0] as? EventMobileToWear)?.let { event -> sent.add(event.payload) }; Unit }.whenever(rxBus).send(any<Event>())
        dateUtil = mock()
        whenever(dateUtil.now()).thenAnswer { now }
        location = mock()
        whenever(location.hasPermission()).thenReturn(true)
        whenever(location.read()).thenReturn(phonePlace)
        val settings = WearDayNightSettings(store.preferences, location, TimeZoneCities())
        placeUse = WearPlaceUse(store.preferences, settings, location, rxBus, dateUtil, mock<AAPSLogger>())
        sut = WearWeather(placeUse, rxBus, dateUtil, mock<AAPSLogger>())
        sut.lookUp = { latitude, longitude ->
            lookedUp += latitude to longitude
            OpenMeteoWeather.Now(EventData.Weather.RAIN, 12.5)
        }
        // The switch as the user set it; the tests change it
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true
    }

    private fun designOnWatch(json: String) {
        placeUse.mainDesignReported(CwfData(json, mutableMapOf(), mutableMapOf()), nightFaceOnWatch = false)
        sent.clear()
        clearInvocations(location)
    }

    @Test
    fun `shown and allowed - looked up at the rough place, at most every half hour`() {
        designOnWatch(WEATHER)

        sut.refresh(scope)
        sut.refresh(scope)

        assertThat(lookedUp).containsExactly(52.4 to 4.9)
        assertThat(sent).containsExactly(EventData.Weather(now, EventData.Weather.RAIN, 12.5))
        assertThat(sut.last()).isEqualTo(EventData.Weather(now, EventData.Weather.RAIN, 12.5))

        now += OpenMeteoWeather.REFRESH_MS
        sut.refresh(scope)
        assertThat(lookedUp).hasSize(2)
    }

    @Test
    fun `no design shows the weather - nothing read, nothing asked, the watch told once there is none`() {
        designOnWatch(SKY)

        sut.refresh(scope)
        sut.refresh(scope)

        assertThat(lookedUp).isEmpty()
        assertThat(sent).containsExactly(EventData.Weather(now, WearWeather.NO_WEATHER))
        verify(location, never()).read()
    }

    @Test
    fun `the switch off - nothing read, nothing asked, the watch told once there is none`() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false
        designOnWatch(WEATHER)

        sut.refresh(scope)
        sut.refresh(scope)

        assertThat(lookedUp).isEmpty()
        assertThat(sent).containsExactly(EventData.Weather(now, WearWeather.NO_WEATHER))
        verify(location, never()).read()
    }

    @Test
    fun `after a restart without the weather allowed the watch forgets its old weather`() {
        // The weather went to the watch, then the user revoked the permission, which stops the app
        designOnWatch(WEATHER)
        sut.refresh(scope)
        whenever(location.hasPermission()).thenReturn(false)
        val afterRestart = WearWeather(placeUse, rxBus, dateUtil, mock<AAPSLogger>())
        sent.clear()

        afterRestart.refresh(scope)
        afterRestart.refresh(scope)

        // The new process has no weather of its own, but the watch is told: once
        assertThat(sent).containsExactly(EventData.Weather(now, WearWeather.NO_WEATHER))
        assertThat(afterRestart.last()).isNull()
    }

    @Test
    fun `no weather is told again after a new weather stopped`() {
        designOnWatch(WEATHER)
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false
        sut.refresh(scope)
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true
        sut.refresh(scope)
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false
        sut.refresh(scope)

        assertThat(sent).containsExactly(
            EventData.Weather(now, WearWeather.NO_WEATHER),
            EventData.Weather(now, EventData.Weather.RAIN, 12.5),
            EventData.Weather(now, WearWeather.NO_WEATHER)
        ).inOrder()
    }

    @Test
    fun `a failure never comes out of refresh, so the status still goes to the watch`() {
        designOnWatch(WEATHER)
        whenever(location.hasPermission()).thenThrow(IllegalStateException("broken"))

        sut.refresh(scope) // does not throw

        assertThat(sent).isEmpty()
    }

    @Test
    fun `a failing look-up is caught too`() {
        designOnWatch(WEATHER)
        sut.lookUp = { _, _ -> throw IllegalStateException("broken") }

        sut.refresh(scope) // does not throw, also not from the launched look-up

        assertThat(sent).isEmpty()
        assertThat(sut.last()).isNull()
    }

    @Test
    fun `the switch off and a city chosen - the weather of the city, the location not read`() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false
        store.values[WearDayNightStringKey.Mode] = DayNightMode.CITY.value
        store.values[WearDayNightStringKey.City] = "Europe/Amsterdam"
        designOnWatch(WEATHER)

        sut.refresh(scope)

        val amsterdam = TimeZoneCities().byZoneId("Europe/Amsterdam")!!
        assertThat(lookedUp).containsExactly(amsterdam.latitude to amsterdam.longitude)
        verify(location, never()).read()
    }

    @Test
    fun `when the weather is no longer allowed the watch forgets it, once`() {
        designOnWatch(WEATHER)
        sut.refresh(scope)
        sent.clear()

        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false
        sut.refresh(scope)
        sut.refresh(scope)

        assertThat(sent).containsExactly(EventData.Weather(now, WearWeather.NO_WEATHER))
        assertThat(sut.last()).isNull()
    }

    @Test
    fun `when the design no longer shows the weather the watch forgets it`() {
        designOnWatch(WEATHER)
        sut.refresh(scope)
        designOnWatch(SKY)

        sut.refresh(scope)

        assertThat(sent).containsExactly(EventData.Weather(now, WearWeather.NO_WEATHER))
        assertThat(lookedUp).hasSize(1)
    }

    private companion object {

        const val WEATHER = """{"dynData": {"temperature": {"valueKey": "weather_temperature"}}}"""
        const val SKY = """{"dynData": {"sunTime": {"valueKey": "sun_next_time"}}}"""
    }
}
