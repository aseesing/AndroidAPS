package app.aaps.plugins.sync.wear.compose

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LabeledSwitch
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.dialogs.TimePickerModal
import app.aaps.core.ui.compose.is24HourClock
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.daynight.DayNightMode
import app.aaps.plugins.sync.wear.daynight.DayNightSource

/**
 * The "Day and night" screen: when the watch shows the night face. The settings, or one step of
 * choosing a city.
 */
@Composable
internal fun WearDayNightScreen(
    viewModel: WearDayNightViewModel,
    modifier: Modifier = Modifier,
    placeUse: WearPlaceUseLine? = null // PERSONAL PATCH: what the place is used for, see WearPlaceUseUi.kt
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermissionResult(granted)
    }
    LaunchedEffect(Unit) {
        viewModel.permissionRequests.collect { launcher.launch(it) }
    }
    // When the screen shows, and again when it comes back: the permission can change in the system
    // settings while the screen is away, and the phone's place can change too
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val dateUtil = LocalDateUtil.current
    when (val picker = state.picker) {
        is DayNightCityPicker.Countries -> DayNightCountryList(picker, viewModel::chooseCountry, viewModel::chooseCity, modifier)
        is DayNightCityPicker.Cities    -> DayNightCityList(picker.cities, viewModel::chooseCity, modifier)
        null                            -> WearDayNightContent(
            state = state,
            timeText = { minute -> dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(minute * 60)) },
            onUsePhoneLocation = viewModel::setUsePhoneLocation,
            onMode = viewModel::setMode,
            onDayStart = viewModel::setDayStart,
            onNightStart = viewModel::setNightStart,
            onChooseCity = viewModel::openCityPicker,
            modifier = modifier,
            placeUse = placeUse // PERSONAL PATCH
        )
    }
}

/** The title of the "Day and night" screen for its current step. */
@Composable
internal fun wearDayNightTitle(picker: DayNightCityPicker?): String = when (picker) {
    is DayNightCityPicker.Countries -> stringResource(SyncStrings.wear_day_night_choose_country)
    is DayNightCityPicker.Cities    -> picker.country.name
    null                            -> stringResource(SyncStrings.wear_day_night_settings)
}

/**
 * The settings of the "Day and night" screen.
 *
 * @param timeText a time as shown, from minutes after midnight
 * @see WearDayNightContentPreview
 */
@Composable
internal fun WearDayNightContent(
    state: WearDayNightUiState,
    timeText: (Int) -> String,
    onUsePhoneLocation: (Boolean) -> Unit,
    onMode: (DayNightMode) -> Unit,
    onDayStart: (Int) -> Unit,
    onNightStart: (Int) -> Unit,
    onChooseCity: () -> Unit,
    modifier: Modifier = Modifier,
    placeUse: WearPlaceUseLine? = null // PERSONAL PATCH: what the place is used for, see WearPlaceUseUi.kt
) {
    // The time picker that is open: for the day start, for the night start, or none. Kept on rotation.
    var editing by rememberSaveable { mutableStateOf<DayNightEdit?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AapsSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
    ) {
        AapsCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                LabeledSwitch(
                    label = stringResource(SyncStrings.wear_day_night_use_phone_location),
                    checked = state.usePhoneLocation,
                    onCheckedChange = onUsePhoneLocation
                )
                Text(
                    text = stringResource(SyncStrings.wear_day_night_use_phone_location_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.permissionMissing) {
                    Text(
                        text = stringResource(SyncStrings.wear_day_night_no_permission),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (state.noKnownPlace) {
                    Text(
                        text = stringResource(SyncStrings.wear_day_night_no_place),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // The choice below the switch: used while the switch is off, and as its fallback. Always
        // shown, so the user can see and change what the watch gets without a known place.
        AapsCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(AapsSpacing.large), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                Text(text = stringResource(SyncStrings.wear_day_night_by), style = MaterialTheme.typography.titleSmall)
                if (state.usePhoneLocation) {
                    Text(
                        text = stringResource(SyncStrings.wear_day_night_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(modifier = Modifier.selectableGroup()) {
                    ModeRow(stringResource(SyncStrings.wear_day_night_fixed_times), state.mode == DayNightMode.FIXED_TIMES) { onMode(DayNightMode.FIXED_TIMES) }
                    ModeRow(stringResource(SyncStrings.wear_day_night_chosen_place), state.mode == DayNightMode.CITY) { onMode(DayNightMode.CITY) }
                }
                HorizontalDivider()
                when (state.mode) {
                    DayNightMode.FIXED_TIMES -> {
                        TimeRow(stringResource(SyncStrings.wear_day_night_day_starts), timeText(state.dayStartMinute)) { editing = DayNightEdit.DAY_START }
                        TimeRow(stringResource(SyncStrings.wear_day_night_night_starts), timeText(state.nightStartMinute)) { editing = DayNightEdit.NIGHT_START }
                        if (state.equalTimes) {
                            Text(
                                text = stringResource(SyncStrings.wear_day_night_equal_times),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DayNightMode.CITY        -> {
                        Text(
                            text = state.city?.let { stringResource(SyncStrings.wear_day_night_city_in_country, it.city, it.country) }
                                ?: stringResource(SyncStrings.wear_day_night_no_city),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        OutlinedButton(onClick = onChooseCity, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Place, contentDescription = null)
                            Text(text = stringResource(SyncStrings.wear_day_night_choose_city), modifier = Modifier.padding(start = AapsSpacing.medium))
                        }
                    }
                }
            }
        }

        // What leaves the phone in the mode that is used now
        Text(
            text = stringResource(
                when (state.source) {
                    DayNightSource.PHONE_LOCATION -> sendsPlaceText(placeUse) // PERSONAL PATCH: also for a design that needs the place
                    DayNightSource.CITY           -> SyncStrings.wear_day_night_sends_city
                    DayNightSource.FIXED_TIMES    -> SyncStrings.wear_day_night_sends_times
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AapsSpacing.small)
        )
        placeUse?.let { WearPlaceUseText(it, state.source) } // PERSONAL PATCH: what the place is used for
    }

    editing?.let { edit ->
        val minute = if (edit == DayNightEdit.DAY_START) state.dayStartMinute else state.nightStartMinute
        TimePickerModal(
            onTimeSelected = { hour, min ->
                val picked = hour * 60 + min
                if (edit == DayNightEdit.DAY_START) onDayStart(picked) else onNightStart(picked)
            },
            onDismiss = { editing = null },
            initialHour = minute / 60,
            initialMinute = minute % 60,
            // The same 12 or 24 hour clock as the times shown on the screen
            is24Hour = is24HourClock()
        )
    }
}

private enum class DayNightEdit { DAY_START, NIGHT_START }

@Composable
private fun ModeRow(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = AapsSpacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = AapsSpacing.medium))
    }
}

@Composable
private fun TimeRow(label: String, time: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AapsSpacing.medium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = time, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** The first step of choosing a city: the city of the phone's time zone, then all countries, with a search field. */
@Composable
private fun DayNightCountryList(
    picker: DayNightCityPicker.Countries,
    onCountry: (DayNightCountryItem) -> Unit,
    onCity: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var search by rememberSaveable { mutableStateOf("") }
    val shown = remember(search, picker.countries) { picker.countries.filter { it.name.contains(search.trim(), ignoreCase = true) } }
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text(stringResource(SyncStrings.wear_day_night_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(AapsSpacing.large)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            picker.suggestion?.let { city ->
                if (search.isBlank()) {
                    item {
                        Text(
                            text = stringResource(SyncStrings.wear_day_night_suggested),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.small)
                        )
                    }
                    item { PickerRow(stringResource(SyncStrings.wear_day_night_city_in_country, city.city, city.country)) { onCity(city.zoneId) } }
                    item { HorizontalDivider() }
                }
            }
            items(shown, key = { it.code }) { country -> PickerRow(country.name) { onCountry(country) } }
        }
    }
}

/** The second step of choosing a city: the cities of one country. */
@Composable
private fun DayNightCityList(cities: List<DayNightCityItem>, onCity: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(cities, key = { it.zoneId }) { city -> PickerRow(city.city) { onCity(city.zoneId) } }
    }
}

@Composable
private fun PickerRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.large)
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun WearDayNightContentPreview() {
    MaterialTheme {
        WearDayNightContent(
            state = WearDayNightUiState(mode = DayNightMode.CITY, city = DayNightCityItem("Europe/Amsterdam", "Amsterdam", "Netherlands"), source = DayNightSource.CITY),
            timeText = { minute -> (minute / 60).toString() },
            onUsePhoneLocation = {},
            onMode = {},
            onDayStart = {},
            onNightStart = {},
            onChooseCity = {}
        )
    }
}
