package app.aaps.plugins.sync.wear.daynight

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.wear.PreferencesInMemory
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightStringKey
import app.aaps.plugins.sync.wear.keys.WearPlaceUseKey
import app.aaps.plugins.sync.wear.wearintegration.isForOwnWatchOnly
import app.aaps.shared.impl.weardata.CwfPlaceUse
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * One switch for the place: read only when needed AND allowed, see [WearPlaceUse]. NEEDED is a night
 * face or a design that needs the place; ALLOWED is the switch on with the permission.
 */
class WearPlaceUseTest {

    private lateinit var store: PreferencesInMemory
    private lateinit var location: PhoneRoughLocation
    private lateinit var sut: WearPlaceUse

    /** What the phone sent to the watch */
    private val sent = mutableListOf<EventData>()

    private var now = NOW

    private val phonePlace = DayNightRule.Place(52.4, 4.9)
    private val fixedTimes = DayNightRule.FixedTimes(dayStartMinute = 7 * 60, nightStartMinute = 19 * 60)
    private val amsterdam = TimeZoneCities().byZoneId("Europe/Amsterdam")!!.let { DayNightRule.Place(it.latitude, it.longitude) }

    @BeforeEach
    fun setUp() {
        store = PreferencesInMemory()
        // A key that was never stored reads as not there
        whenever(store.preferences.getIfExists(WearDayNightBooleanKey.UsePhoneLocation)).thenAnswer { store.values[WearDayNightBooleanKey.UsePhoneLocation] as Boolean? }
        WearPlaceUseKey.entries.forEach { key -> whenever(store.preferences.getIfExists(key)).thenAnswer { store.values[key] as String? } }
        val rxBus = mock<RxBus>()
        doAnswer { (it.arguments[0] as? EventMobileToWear)?.let { event -> sent.add(event.payload) }; Unit }.whenever(rxBus).send(any<Event>())
        val dateUtil = mock<DateUtil>()
        whenever(dateUtil.now()).thenAnswer { now }
        location = mock()
        whenever(location.hasPermission()).thenReturn(true)
        whenever(location.read()).thenReturn(phonePlace)
        val settings = WearDayNightSettings(store.preferences, location, TimeZoneCities())
        sut = WearPlaceUse(store.preferences, settings, location, rxBus, dateUtil, mock<AAPSLogger>())
    }

    private fun design(json: String) = CwfData(json, mutableMapOf(), mutableMapOf())

    private fun switchOn() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true
    }

    private fun switchOff() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false
    }

    private fun chooseAmsterdam() {
        store.values[WearDayNightStringKey.Mode] = DayNightMode.CITY.value
        store.values[WearDayNightStringKey.City] = "Europe/Amsterdam"
    }

    // ---- day and night, sun and moon: the rule ----

    @Test
    fun `needed and allowed - the rule holds the phone's rough place`() {
        switchOn()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)

        assertThat(sent).containsExactly(EventData.ActionSetDayNightRule(phonePlace))
        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isEqualTo(EventData.ActionSetDayNightRule(phonePlace))
    }

    @Test
    fun `needed but the switch is off - the location is not read`() {
        switchOff()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)

        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isEqualTo(EventData.ActionSetDayNightRule(fixedTimes))
        verify(location, never()).read()
    }

    @Test
    fun `needed but no permission - the location is not read`() {
        switchOn()
        whenever(location.hasPermission()).thenReturn(false)
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)

        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isEqualTo(EventData.ActionSetDayNightRule(fixedTimes))
        verify(location, never()).read()
    }

    @Test
    fun `allowed but not needed - nothing is read or sent`() {
        switchOn()
        sut.mainDesignReported(design(NOTHING), nightFaceOnWatch = false)

        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isNull()
        assertThat(sent).isEmpty()
        verify(location, never()).read()
        verify(location, never()).hasPermission()
    }

    @Test
    fun `neither needed nor allowed - nothing is read or sent`() {
        sut.mainDesignReported(design(NOTHING), nightFaceOnWatch = false)

        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isNull()
        assertThat(sent).isEmpty()
        verify(location, never()).read()
    }

    @Test
    fun `a night face needs the rule, whatever the design`() {
        switchOn()
        sut.mainDesignReported(design(NOTHING), nightFaceOnWatch = true)

        assertThat(sut.ruleMessage(nightFaceOnWatch = true)).isEqualTo(EventData.ActionSetDayNightRule(phonePlace))
        switchOff()
        clearInvocations(location)
        assertThat(sut.ruleMessage(nightFaceOnWatch = true)).isEqualTo(EventData.ActionSetDayNightRule(fixedTimes))
        verify(location, never()).read()
    }

    @Test
    fun `with the switch off a chosen city gives the sun and moon its public place`() {
        switchOff()
        chooseAmsterdam()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)

        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isEqualTo(EventData.ActionSetDayNightRule(amsterdam))
        verify(location, never()).read()
    }

    @Test
    fun `a design that asks for the place itself needs it too`() {
        switchOn()
        sut.mainDesignReported(design("""{"needsPlace": true}"""), nightFaceOnWatch = false)

        assertThat(sut.mainUses()).containsExactly(CwfPlaceUse.ASKED)
        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isEqualTo(EventData.ActionSetDayNightRule(phonePlace))
    }

    @Test
    fun `a new design that needs nothing tells the watch - no rule`() {
        switchOn()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)
        sent.clear()

        sut.mainDesignReported(design(NOTHING), nightFaceOnWatch = false)

        assertThat(sent).containsExactly(EventData.ActionClearDayNightRule(NOW))
        assertThat(sut.lastRule()).isEqualTo(EventData.ActionClearDayNightRule(NOW))
        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isNull()
    }

    @Test
    fun `with a night face a design change sends nothing - the night face keeps the rule`() {
        switchOn()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = true)
        sut.mainDesignReported(design(NOTHING), nightFaceOnWatch = true)

        assertThat(sent).isEmpty()
    }

    @Test
    fun `the same need again sends nothing`() {
        switchOn()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)
        sent.clear()

        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)

        assertThat(sent).isEmpty()
    }

    @Test
    fun `what the design needs is kept for the next start`() {
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)

        assertThat(store.values[WearPlaceUseKey.MainDesign]).isEqualTo("SUN_AND_MOON")
        assertThat(sut.mainNeedsRule()).isTrue()
    }

    // ---- the weather ----

    @Test
    fun `weather shown and allowed - the phone's rough place`() {
        switchOn()
        sut.mainDesignReported(design(WEATHER), nightFaceOnWatch = false)

        assertThat(sut.weatherAllowed()).isTrue()
        assertThat(sut.weatherPlace()).isEqualTo(phonePlace)
    }

    @Test
    fun `weather shown but not allowed - no place, the location is not read`() {
        switchOff()
        sut.mainDesignReported(design(WEATHER), nightFaceOnWatch = false)
        clearInvocations(location)

        assertThat(sut.weatherAllowed()).isFalse()
        assertThat(sut.weatherPlace()).isNull()
        verify(location, never()).read()
    }

    @Test
    fun `weather shown, the switch off and a city chosen - the city, the location is not read`() {
        switchOff()
        chooseAmsterdam()
        sut.mainDesignReported(design(WEATHER), nightFaceOnWatch = false)
        clearInvocations(location)

        assertThat(sut.weatherAllowed()).isTrue()
        assertThat(sut.weatherPlace()).isEqualTo(amsterdam)
        verify(location, never()).read()
    }

    @Test
    fun `allowed but no design shows the weather - no place, the location is not read`() {
        switchOn()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)
        clearInvocations(location)

        assertThat(sut.weatherAllowed()).isFalse()
        assertThat(sut.weatherPlace()).isNull()
        verify(location, never()).read()
    }

    @Test
    fun `the weather of the night design counts while the watch has it`() {
        switchOn()
        sut.mainDesignReported(design(NOTHING), nightFaceOnWatch = true)
        sut.nightDesignReported(design(WEATHER))

        assertThat(sut.weatherPlace()).isEqualTo(phonePlace)

        sut.nightDesignReported(null)
        assertThat(sut.weatherPlace()).isNull()
    }

    // ---- asking the watch for its design ----

    @Test
    fun `the phone asks the watch for its design once, when it never learned it`() {
        assertThat(sut.designRequest(nightFaceKnown = true)).isEqualTo(EventData.ActionrequestCustomWatchface(false, night = true))
        assertThat(sut.designRequest(nightFaceKnown = true)).isNull()
        // Until the watch answers it counts as a design that needs nothing
        assertThat(sut.mainNeedsRule()).isFalse()
    }

    @Test
    fun `a phone that knows the design does not ask`() {
        sut.mainDesignReported(design(NOTHING), nightFaceOnWatch = false)

        assertThat(sut.designRequest(nightFaceKnown = false)).isNull()
    }

    // ---- the weather alone: the phone looks it up, the watch gets no rule ----

    @Test
    fun `a design with only the temperature gets no rule, but the weather is looked up`() {
        switchOn()
        sut.mainDesignReported(design(TEMPERATURE), nightFaceOnWatch = false)

        assertThat(sent).isEmpty()
        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isNull()
        assertThat(sut.weatherAllowed()).isTrue()
        assertThat(sut.weatherPlace()).isEqualTo(phonePlace)
    }

    @Test
    fun `from a sun and moon design to one with only the temperature - no rule`() {
        switchOn()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)
        sent.clear()

        sut.mainDesignReported(design(TEMPERATURE), nightFaceOnWatch = false)

        assertThat(sent).containsExactly(EventData.ActionClearDayNightRule(NOW))
    }

    // ---- the watch refuses the rule: it lost its design ----

    @Test
    fun `the watch lost its design - after the refusal nothing is read or sent any more`() {
        switchOn()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)
        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isEqualTo(EventData.ActionSetDayNightRule(phonePlace))
        sent.clear()
        clearInvocations(location)

        // The watch was reset and has only the default design: it refuses the rule, the phone hears "no night face"
        val ask = sut.ruleRefused()

        // The phone asks once for the main design; the default design gives no answer
        assertThat(ask).isEqualTo(EventData.ActionrequestCustomWatchface(false, night = false))
        assertThat(sut.mainUses()).isEmpty()
        // Every resend after it: no rule, no read
        repeat(3) { assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isNull() }
        assertThat(sut.weatherPlace()).isNull()
        verify(location, never()).read()
        // And no "no rule" either: the watch has none
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a refusal while the watch still has the design - its answer brings the rule back`() {
        switchOn()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)
        sent.clear()

        assertThat(sut.ruleRefused()).isNotNull()
        // The watch answers the request with its design: the need is stored again and the rule goes
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)

        assertThat(sut.mainNeedsRule()).isTrue()
        assertThat(sent).containsExactly(EventData.ActionSetDayNightRule(phonePlace))
    }

    @Test
    fun `a watch that keeps refusing is asked only once - the phone does not read the location again and again`() {
        switchOn()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)
        assertThat(sut.ruleRefused()).isNotNull()
        sut.mainDesignReported(design(SKY), nightFaceOnWatch = false)

        // Refused again (an older watch app): forgotten, and not asked again
        assertThat(sut.ruleRefused()).isNull()
        assertThat(sut.mainNeedsRule()).isFalse()
        assertThat(sut.ruleMessage(nightFaceOnWatch = false)).isNull()
    }

    @Test
    fun `a refusal with nothing stored changes nothing`() {
        sut.mainDesignReported(design(NOTHING), nightFaceOnWatch = false)

        assertThat(sut.ruleRefused()).isNull()
    }

    @Test
    fun `no rule is for the own watch only, as the rule`() {
        assertThat(isForOwnWatchOnly(EventData.ActionClearDayNightRule(NOW))).isTrue()
    }

    private companion object {

        const val NOW = 12345L

        /** D2: the next sunrise or sunset */
        const val SKY = """{"dynData": {"sunTime": {"valueKey": "sun_next_time", "textvalue1": "@time"}}}"""

        /** Grand Complication: weather looks, which follow sunrise and sunset too */
        const val WEATHER = """{"weatherLooks": {"rain": {"images": {"Cloud": "CloudRain"}}}}"""

        /** Only the temperature of the weather */
        const val TEMPERATURE = """{"dynData": {"temperature": {"valueKey": "weather_temperature"}}}"""

        /** A design that needs nothing */
        const val NOTHING = """{"dynData": {"bg": {"valueKey": "sgv"}}}"""
    }
}
