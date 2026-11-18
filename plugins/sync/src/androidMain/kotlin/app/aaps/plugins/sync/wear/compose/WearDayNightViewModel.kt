package app.aaps.plugins.sync.wear.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.daynight.DayNightMode
import app.aaps.plugins.sync.wear.daynight.DayNightPlaceNames
import app.aaps.plugins.sync.wear.daynight.DayNightSource
import app.aaps.plugins.sync.wear.daynight.PhoneRoughLocation
import app.aaps.plugins.sync.wear.daynight.TimeZoneCities
import app.aaps.plugins.sync.wear.daynight.TimeZoneCity
import app.aaps.plugins.sync.wear.daynight.WearDayNightSettings
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightIntKey
import app.aaps.plugins.sync.wear.keys.WearDayNightStringKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.Collator

/** A city as shown: names in the user's language. */
@Immutable
data class DayNightCityItem(val zoneId: String, val city: String, val country: String)

/** A country as shown. */
@Immutable
data class DayNightCountryItem(val code: String, val name: String)

/** The open step of choosing a city: first a country, then a city in it. */
sealed interface DayNightCityPicker {

    /** @param suggestion the city of the phone's time zone, or null when it is not in the table */
    @Immutable
    data class Countries(val suggestion: DayNightCityItem?, val countries: List<DayNightCountryItem>) : DayNightCityPicker

    @Immutable
    data class Cities(val country: DayNightCountryItem, val cities: List<DayNightCityItem>) : DayNightCityPicker
}

/**
 * The "Day and night" screen.
 *
 * @param usePhoneLocation the switch as it shows: on only when it is stored on and the permission
 *   is granted, so it never looks on while nothing works
 * @param permissionMissing the user wants the phone's location, but the permission is not granted
 * @param source what the watch gets now, which is also what leaves the phone. With the switch on
 *   this needs the phone's place, so it is only true once the screen shows, see
 *   [WearDayNightViewModel.refresh]
 */
@Immutable
data class WearDayNightUiState(
    val usePhoneLocation: Boolean = false,
    val permissionMissing: Boolean = false,
    val mode: DayNightMode = DayNightMode.FIXED_TIMES,
    val dayStartMinute: Int = 7 * 60,
    val nightStartMinute: Int = 19 * 60,
    val city: DayNightCityItem? = null,
    val source: DayNightSource = DayNightSource.FIXED_TIMES,
    val picker: DayNightCityPicker? = null
) {

    /** Both times the same: then it is always day (see `DayNightClock` on the watch). */
    val equalTimes: Boolean get() = dayStartMinute == nightStartMinute

    /** The switch is on, but the phone knows no recent place: the choice below the switch is used. */
    val noKnownPlace: Boolean get() = usePhoneLocation && source != DayNightSource.PHONE_LOCATION
}

/** The "Day and night" screen of the Wear plugin: when the watch shows the night face. See [WearDayNightSettings]. */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class WearDayNightViewModel(
    private val preferences: Preferences,
    private val settings: WearDayNightSettings,
    private val cities: TimeZoneCities,
    private val names: DayNightPlaceNames,
    private val location: PhoneRoughLocation
) : ViewModel() {

    private val _uiState = MutableStateFlow(WearDayNightUiState())
    val uiState: StateFlow<WearDayNightUiState> = _uiState.asStateFlow()

    private val _permissionRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** The permission the screen must ask the user for. */
    val permissionRequests: SharedFlow<String> = _permissionRequests.asSharedFlow()

    /** The user said no to the permission on this screen. */
    private var permissionDenied = false

    private val collator: Collator = Collator.getInstance()

    init {
        // The view model lives with the Wear screen, so this must not read the phone's place
        update(readPlace = false)
    }

    /**
     * Reads the settings, the permission and, with the switch on, the phone's place again. Only for
     * the time the screen shows: when it shows (again) and after each change on it.
     */
    fun refresh() = update(readPlace = true)

    private fun update(readPlace: Boolean) {
        val granted = location.hasPermission()
        val stored = settings.usePhoneLocationStored()
        _uiState.update {
            it.copy(
                usePhoneLocation = stored && granted,
                permissionMissing = !granted && (stored || permissionDenied),
                mode = settings.mode(),
                dayStartMinute = settings.dayStart(),
                nightStartMinute = settings.nightStart(),
                city = settings.city()?.let { city -> item(city) },
                source = if (readPlace) settings.source() else settings.fallbackSource()
            )
        }
    }

    /** The switch "Use the phone's location". On asks for the permission first, when it is missing. */
    fun setUsePhoneLocation(on: Boolean) {
        if (on && !location.hasPermission()) {
            _permissionRequests.tryEmit(PhoneRoughLocation.PERMISSION)
            return
        }
        if (!on) permissionDenied = false
        preferences.put(WearDayNightBooleanKey.UsePhoneLocation, on)
        refresh()
    }

    /** The answer to the permission request. Without permission the switch stays off. */
    fun onPermissionResult(granted: Boolean) {
        permissionDenied = !granted
        if (granted) preferences.put(WearDayNightBooleanKey.UsePhoneLocation, true)
        refresh()
    }

    /** For a chosen city without a city yet, the city of the phone's time zone is taken. */
    fun setMode(mode: DayNightMode) {
        preferences.put(WearDayNightStringKey.Mode, mode.value)
        if (mode == DayNightMode.CITY && settings.city() == null) phoneZoneCity()?.let { preferences.put(WearDayNightStringKey.City, it.zoneId) }
        refresh()
    }

    fun setDayStart(minute: Int) {
        preferences.put(WearDayNightIntKey.DayStart, minute)
        refresh()
    }

    fun setNightStart(minute: Int) {
        preferences.put(WearDayNightIntKey.NightStart, minute)
        refresh()
    }

    /** Opens the list of countries, with the city of the phone's time zone on top. */
    fun openCityPicker() {
        val countries = cities.countryCodes().map { DayNightCountryItem(it, names.country(it)) }.sortedWith(compareBy(collator) { it.name })
        _uiState.update { it.copy(picker = DayNightCityPicker.Countries(phoneZoneCity()?.let { city -> item(city) }, countries)) }
    }

    /** A country is chosen: its cities, or at once its only city. */
    fun chooseCountry(country: DayNightCountryItem) {
        val inCountry = cities.inCountry(country.code).map { item(it) }.sortedWith(compareBy(collator) { it.city })
        if (inCountry.size == 1) chooseCity(inCountry.single().zoneId)
        else _uiState.update { it.copy(picker = DayNightCityPicker.Cities(country, inCountry)) }
    }

    /** A city is chosen: it is stored by its zone id, and day and night follow it. */
    fun chooseCity(zoneId: String) {
        preferences.put(WearDayNightStringKey.City, zoneId)
        preferences.put(WearDayNightStringKey.Mode, DayNightMode.CITY.value)
        _uiState.update { it.copy(picker = null) }
        refresh()
    }

    /** One step back: from the cities to the countries, from the countries to the settings. */
    fun backInPicker() {
        if (_uiState.value.picker is DayNightCityPicker.Cities) openCityPicker()
        else _uiState.update { it.copy(picker = null) }
    }

    private fun phoneZoneCity(): TimeZoneCity? = cities.firstKnown(names.phoneZoneIds())

    private fun item(city: TimeZoneCity) = DayNightCityItem(city.zoneId, names.city(city.zoneId), names.country(city.countryCode))
}
