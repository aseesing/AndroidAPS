package app.aaps.plugins.sync.wear.compose

import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.plugins.sync.wear.PreferencesInMemory
import app.aaps.plugins.sync.wear.daynight.DayNightMode
import app.aaps.plugins.sync.wear.daynight.DayNightPlaceNames
import app.aaps.plugins.sync.wear.daynight.DayNightSource
import app.aaps.plugins.sync.wear.daynight.PhoneRoughLocation
import app.aaps.plugins.sync.wear.daynight.TimeZoneCities
import app.aaps.plugins.sync.wear.daynight.WearDayNightSettings
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightIntKey
import app.aaps.plugins.sync.wear.keys.WearDayNightStringKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.text.Collator

@OptIn(ExperimentalCoroutinesApi::class)
internal class WearDayNightViewModelTest {

    private lateinit var store: PreferencesInMemory
    private lateinit var location: PhoneRoughLocation
    private var phoneZone = listOf("Europe/Amsterdam")

    /** Plain names: the country code, and the city from the zone id */
    private val names = object : DayNightPlaceNames {
        override fun country(countryCode: String) = "Country $countryCode"
        override fun city(zoneId: String) = zoneId.substringAfterLast('/')
        override fun phoneZoneIds() = phoneZone
    }

    private lateinit var sut: WearDayNightViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = PreferencesInMemory()
        location = mock()
        whenever(location.hasPermission()).thenReturn(false)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun create(): WearDayNightViewModel {
        val cities = TimeZoneCities()
        sut = WearDayNightViewModel(store.preferences, WearDayNightSettings(store.preferences, location, cities), cities, names, location)
        return sut
    }

    private fun state() = sut.uiState.value

    @Test
    fun `the defaults are fixed times from 07 00 to 19 00 and the switch off`() {
        create()

        assertThat(state().usePhoneLocation).isFalse()
        assertThat(state().permissionMissing).isFalse()
        assertThat(state().mode).isEqualTo(DayNightMode.FIXED_TIMES)
        assertThat(state().dayStartMinute).isEqualTo(7 * 60)
        assertThat(state().nightStartMinute).isEqualTo(19 * 60)
        assertThat(state().source).isEqualTo(DayNightSource.FIXED_TIMES)
        assertThat(state().equalTimes).isFalse()
        assertThat(store.writes).isEqualTo(0)
    }

    @Test
    fun `turning the switch on without permission asks for it and stores nothing`() = runTest {
        create()
        val asked = mutableListOf<String>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) { sut.permissionRequests.collect { asked.add(it) } }

        sut.setUsePhoneLocation(true)

        assertThat(asked).containsExactly(PhoneRoughLocation.PERMISSION)
        assertThat(store.values[WearDayNightBooleanKey.UsePhoneLocation]).isNull()
        assertThat(state().usePhoneLocation).isFalse()
        collector.cancel()
    }

    @Test
    fun `a denied permission leaves the switch off and says so`() {
        create()

        sut.onPermissionResult(false)

        assertThat(state().usePhoneLocation).isFalse()
        assertThat(state().permissionMissing).isTrue()
        assertThat(state().source).isEqualTo(DayNightSource.FIXED_TIMES)
        assertThat(store.values[WearDayNightBooleanKey.UsePhoneLocation]).isNull()
    }

    @Test
    fun `a granted permission turns the switch on`() {
        create()
        whenever(location.hasPermission()).thenReturn(true)
        whenever(location.read()).thenReturn(DayNightRule.Place(52.4, 4.9))

        sut.onPermissionResult(true)

        assertThat(store.values[WearDayNightBooleanKey.UsePhoneLocation]).isEqualTo(true)
        assertThat(state().usePhoneLocation).isTrue()
        assertThat(state().source).isEqualTo(DayNightSource.PHONE_LOCATION)
        assertThat(state().noKnownPlace).isFalse()
    }

    @Test
    fun `with the switch on but no known place the screen says what the watch really gets`() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true
        store.values[WearDayNightStringKey.Mode] = DayNightMode.CITY.value
        store.values[WearDayNightStringKey.City] = "Europe/Amsterdam"
        whenever(location.hasPermission()).thenReturn(true)
        create()

        sut.refresh()

        assertThat(state().usePhoneLocation).isTrue()
        assertThat(state().noKnownPlace).isTrue()
        assertThat(state().source).isEqualTo(DayNightSource.CITY)
    }

    @Test
    fun `the place is read only once the screen shows, and only with the switch on`() {
        whenever(location.hasPermission()).thenReturn(true)
        create()
        sut.refresh()
        // Switch off: never read
        verify(location, never()).read()

        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true
        create()
        // The view model is made with the Wear screen: that reads no place
        verify(location, never()).read()

        sut.refresh()
        verify(location).read()
    }

    @Test
    fun `with the permission the switch goes on and off at once`() {
        whenever(location.hasPermission()).thenReturn(true)
        create()

        sut.setUsePhoneLocation(true)
        assertThat(state().usePhoneLocation).isTrue()

        sut.setUsePhoneLocation(false)
        assertThat(state().usePhoneLocation).isFalse()
        assertThat(store.values[WearDayNightBooleanKey.UsePhoneLocation]).isEqualTo(false)
    }

    @Test
    fun `a permission taken away later shows the switch off, not on while nothing works`() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true
        whenever(location.hasPermission()).thenReturn(true)
        create()
        assertThat(state().usePhoneLocation).isTrue()

        whenever(location.hasPermission()).thenReturn(false)
        sut.refresh()

        assertThat(state().usePhoneLocation).isFalse()
        assertThat(state().permissionMissing).isTrue()
        assertThat(state().source).isEqualTo(DayNightSource.FIXED_TIMES)
    }

    @Test
    fun `times are stored and equal times are shown`() {
        create()

        sut.setDayStart(20 * 60)
        sut.setNightStart(8 * 60)
        assertThat(store.values[WearDayNightIntKey.DayStart]).isEqualTo(20 * 60)
        assertThat(state().nightStartMinute).isEqualTo(8 * 60)
        assertThat(state().equalTimes).isFalse()

        sut.setNightStart(20 * 60)
        assertThat(state().equalTimes).isTrue()
    }

    @Test
    fun `choosing the city mode suggests the city of the phone's time zone`() {
        create()

        sut.setMode(DayNightMode.CITY)

        assertThat(store.values[WearDayNightStringKey.City]).isEqualTo("Europe/Amsterdam")
        assertThat(state().city).isEqualTo(DayNightCityItem("Europe/Amsterdam", "Amsterdam", "Country NL"))
        assertThat(state().source).isEqualTo(DayNightSource.CITY)
    }

    @Test
    fun `without a city for the phone's zone the city mode waits for a choice`() {
        phoneZone = listOf("UTC")
        create()

        sut.setMode(DayNightMode.CITY)

        assertThat(state().mode).isEqualTo(DayNightMode.CITY)
        assertThat(state().city).isNull()
        assertThat(state().source).isEqualTo(DayNightSource.FIXED_TIMES)
        sut.openCityPicker()
        assertThat((state().picker as DayNightCityPicker.Countries).suggestion).isNull()
    }

    @Test
    fun `a city is chosen by country and then city`() {
        create()
        sut.openCityPicker()
        val countries = state().picker as DayNightCityPicker.Countries
        assertThat(countries.suggestion?.zoneId).isEqualTo("Europe/Amsterdam")
        // Sorted by the shown name
        assertThat(countries.countries.map { it.name }).isInOrder(Collator.getInstance())

        sut.chooseCountry(countries.countries.single { it.code == "AU" })
        val cities = state().picker as DayNightCityPicker.Cities
        assertThat(cities.cities.map { it.zoneId }).contains("Australia/Sydney")
        assertThat(cities.cities.map { it.city }).isInOrder(Collator.getInstance())

        // Back goes to the countries, not out of the screen
        sut.backInPicker()
        assertThat(state().picker).isInstanceOf(DayNightCityPicker.Countries::class.java)
        sut.chooseCountry(countries.countries.single { it.code == "AU" })

        sut.chooseCity("Australia/Sydney")

        assertThat(state().picker).isNull()
        assertThat(store.values[WearDayNightStringKey.City]).isEqualTo("Australia/Sydney")
        assertThat(state().mode).isEqualTo(DayNightMode.CITY)
        assertThat(state().city?.city).isEqualTo("Sydney")
    }

    @Test
    fun `a country with one city chooses it at once`() {
        create()
        sut.openCityPicker()

        sut.chooseCountry(DayNightCountryItem("NL", "Country NL"))

        assertThat(state().picker).isNull()
        assertThat(store.values[WearDayNightStringKey.City]).isEqualTo("Europe/Amsterdam")
    }

    @Test
    fun `back from the countries closes the picker`() {
        create()
        sut.openCityPicker()

        sut.backInPicker()

        assertThat(state().picker).isNull()
    }
}
