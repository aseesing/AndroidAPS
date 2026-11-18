package app.aaps.plugins.sync.wear.daynight

import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.plugins.sync.wear.PreferencesInMemory
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightIntKey
import app.aaps.plugins.sync.wear.keys.WearDayNightStringKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WearDayNightSettingsTest {

    private lateinit var store: PreferencesInMemory
    private lateinit var location: PhoneRoughLocation
    private lateinit var sut: WearDayNightSettings

    private val phonePlace = DayNightRule.Place(52.4, 4.9)

    @BeforeEach
    fun setUp() {
        store = PreferencesInMemory()
        location = mock()
        whenever(location.hasPermission()).thenReturn(true)
        whenever(location.read()).thenReturn(phonePlace)
        sut = WearDayNightSettings(store.preferences, location, TimeZoneCities())
    }

    private fun set(usePhoneLocation: Boolean? = null, mode: DayNightMode? = null, city: String? = null, dayStart: Int? = null, nightStart: Int? = null) {
        usePhoneLocation?.let { store.values[WearDayNightBooleanKey.UsePhoneLocation] = it }
        mode?.let { store.values[WearDayNightStringKey.Mode] = it.value }
        city?.let { store.values[WearDayNightStringKey.City] = it }
        dayStart?.let { store.values[WearDayNightIntKey.DayStart] = it }
        nightStart?.let { store.values[WearDayNightIntKey.NightStart] = it }
    }

    @Test
    fun `the default is fixed times from 07 00 to 19 00 without reading the location`() {
        assertThat(sut.mode()).isEqualTo(DayNightMode.FIXED_TIMES)
        assertThat(sut.source()).isEqualTo(DayNightSource.FIXED_TIMES)
        assertThat(sut.rule()).isEqualTo(DayNightRule.FixedTimes(dayStartMinute = 7 * 60, nightStartMinute = 19 * 60))
        verify(location, never()).read()
    }

    @Test
    fun `fixed times go as they are, also over midnight and when equal`() {
        set(dayStart = 20 * 60, nightStart = 8 * 60)
        assertThat(sut.rule()).isEqualTo(DayNightRule.FixedTimes(20 * 60, 8 * 60))

        // Equal times mean always day; the watch decides that, the phone sends them as they are
        set(dayStart = 9 * 60, nightStart = 9 * 60)
        assertThat(sut.rule()).isEqualTo(DayNightRule.FixedTimes(9 * 60, 9 * 60))
    }

    @Test
    fun `a chosen city sends the place of that city`() {
        set(mode = DayNightMode.CITY, city = "Europe/Amsterdam")

        assertThat(sut.source()).isEqualTo(DayNightSource.CITY)
        val rule = sut.rule() as DayNightRule.Place
        assertThat(rule.latitude).isWithin(0.01).of(52.37)
        assertThat(rule.longitude).isWithin(0.01).of(4.9)
        verify(location, never()).read()
    }

    @Test
    fun `a city mode without a known city uses the fixed times`() {
        set(mode = DayNightMode.CITY, dayStart = 6 * 60)
        assertThat(sut.source()).isEqualTo(DayNightSource.FIXED_TIMES)
        assertThat(sut.rule()).isEqualTo(DayNightRule.FixedTimes(6 * 60, 19 * 60))

        set(city = "Nowhere/Atlantis")
        assertThat(sut.city()).isNull()
        assertThat(sut.rule()).isEqualTo(DayNightRule.FixedTimes(6 * 60, 19 * 60))
    }

    @Test
    fun `a city stays stored but is not used in fixed times mode`() {
        set(mode = DayNightMode.FIXED_TIMES, city = "Europe/Amsterdam")

        assertThat(sut.rule()).isInstanceOf(DayNightRule.FixedTimes::class.java)
    }

    @Test
    fun `an unknown mode counts as fixed times`() {
        store.values[WearDayNightStringKey.Mode] = "something else"

        assertThat(sut.mode()).isEqualTo(DayNightMode.FIXED_TIMES)
    }

    @Test
    fun `with the switch on and the permission the phone's place is used`() {
        set(usePhoneLocation = true, mode = DayNightMode.CITY, city = "Europe/Amsterdam")

        assertThat(sut.source()).isEqualTo(DayNightSource.PHONE_LOCATION)
        assertThat(sut.rule()).isEqualTo(phonePlace)
    }

    @Test
    fun `with the switch on but no permission the location is not read and the choice below is used`() {
        whenever(location.hasPermission()).thenReturn(false)
        set(usePhoneLocation = true, mode = DayNightMode.CITY, city = "Europe/Amsterdam")

        assertThat(sut.source()).isEqualTo(DayNightSource.CITY)
        assertThat(sut.rule()).isInstanceOf(DayNightRule.Place::class.java)
        assertThat(sut.rule()).isNotEqualTo(phonePlace)
        verify(location, never()).read()
    }

    @Test
    fun `with the switch on but no known place the choice below is used`() {
        whenever(location.read()).thenReturn(null)
        set(usePhoneLocation = true)

        assertThat(sut.usesPhoneLocation()).isTrue()
        assertThat(sut.source()).isEqualTo(DayNightSource.FIXED_TIMES)
        assertThat(sut.rule()).isEqualTo(DayNightRule.FixedTimes(7 * 60, 19 * 60))

        set(mode = DayNightMode.CITY, city = "Europe/Amsterdam")
        assertThat(sut.source()).isEqualTo(DayNightSource.CITY)
        assertThat(sut.rule()).isEqualTo(DayNightRule.Place(sut.city()!!.latitude, sut.city()!!.longitude))
    }

    @Test
    fun `the fallback source reads no location`() {
        set(usePhoneLocation = true, mode = DayNightMode.CITY, city = "Europe/Amsterdam")

        assertThat(sut.fallbackSource()).isEqualTo(DayNightSource.CITY)
        verify(location, never()).read()
    }

    @Test
    fun `the phone's place is rounded to 0 1 degree`() {
        assertThat(PhoneRoughLocation.rounded(52.3731, 4.8922)).isEqualTo(DayNightRule.Place(52.4, 4.9))
        assertThat(PhoneRoughLocation.rounded(-33.8688, 151.2093)).isEqualTo(DayNightRule.Place(-33.9, 151.2))
    }
}
