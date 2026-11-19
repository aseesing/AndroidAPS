package app.aaps.plugins.sync.wear.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.daynight.DayNightSource
import app.aaps.shared.impl.weardata.CwfPlaceUse

/**
 * What the designs the watch reported need the place for (see `CwfPlaceNeeds`), for the Wear screen:
 * whether it offers the "Day and night" screen without a night face, and the line on that screen that
 * says what the place is used for.
 *
 * @param main what the main design needs the place for
 * @param night what the night design needs the place for; empty without a night face
 */
@Immutable
data class WearPlaceUseState(
    val main: Set<CwfPlaceUse> = emptySet(),
    val night: Set<CwfPlaceUse> = emptySet()
)

/**
 * What the place is used for now, one flag each, for the line on the "Day and night" screen.
 *
 * @param dayAndNight the night face: when it shows
 * @param sunAndMoon the real sun and moon, the day look, the next sunrise or sunset
 * @param weather the weather
 * @param asked a design that asks for the place itself
 */
@Immutable
data class WearPlaceUseLine(
    val dayAndNight: Boolean = false,
    val sunAndMoon: Boolean = false,
    val weather: Boolean = false,
    val asked: Boolean = false
)

/** True when the "Day and night" screen can be opened: with a night face, or a main design that needs the place. */
internal fun WearUiState.dayNightReachable(): Boolean = nightFace.hasNightWatchface || placeUse.main.isNotEmpty()

/** True when the main face card shows its own button to the screen: a design needs the place, and there is no night face (whose card has the button). */
internal fun WearUiState.showsPlaceEntry(): Boolean = placeUse.main.isNotEmpty() && !nightFace.hasNightWatchface

/** What the place is used for now. The night design counts only with a night face. */
internal fun WearUiState.placeUseLine(): WearPlaceUseLine {
    val uses = placeUse.main + if (nightFace.hasNightWatchface) placeUse.night else emptySet()
    return WearPlaceUseLine(
        dayAndNight = nightFace.hasNightWatchface,
        sunAndMoon = CwfPlaceUse.SUN_AND_MOON in uses,
        weather = CwfPlaceUse.WEATHER in uses,
        asked = CwfPlaceUse.ASKED in uses
    )
}

/** The button of the main face card to the screen, or null when it does not show, see [showsPlaceEntry]. */
@Composable
internal fun placeEntryButton(uiState: WearUiState, onClick: () -> Unit): ButtonDef? =
    if (uiState.showsPlaceEntry()) ButtonDef(Icons.Default.Place, stringResource(SyncStrings.wear_place_button), onClick) else null

/**
 * Under the settings of the "Day and night" screen: what the place is used for now, and with the
 * weather where the weather service gets its place from.
 */
@Composable
internal fun WearPlaceUseText(line: WearPlaceUseLine, source: DayNightSource) {
    val items = listOfNotNull(
        if (line.dayAndNight) stringResource(SyncStrings.wear_place_use_day_night) else null,
        if (line.sunAndMoon) stringResource(SyncStrings.wear_place_use_sun_moon) else null,
        if (line.weather) stringResource(SyncStrings.wear_place_use_weather) else null,
        if (line.asked) stringResource(SyncStrings.wear_place_use_asked) else null
    )
    val list = when (items.size) {
        0    -> null
        1    -> items[0]
        2    -> stringResource(SyncStrings.wear_place_list_2, items[0], items[1])
        3    -> stringResource(SyncStrings.wear_place_list_3, items[0], items[1], items[2])
        else -> stringResource(SyncStrings.wear_place_list_4, items[0], items[1], items[2], items[3])
    }
    UseText(list?.let { stringResource(SyncStrings.wear_place_used_for, it) } ?: stringResource(SyncStrings.wear_place_used_for_nothing))
    if (line.weather) UseText(
        stringResource(
            when (source) {
                DayNightSource.PHONE_LOCATION -> SyncStrings.wear_place_weather_phone
                DayNightSource.CITY           -> SyncStrings.wear_place_weather_city
                DayNightSource.FIXED_TIMES    -> SyncStrings.wear_place_weather_none
            }
        )
    )
}

/**
 * The text of what leaves the phone with the phone's place: the text of the night face with one; else
 * the text of the design, since the place then goes for the design, not for a night face. For the weather
 * alone the watch gets no place (the phone looks up the weather), and the text says so.
 */
internal fun sendsPlaceText(line: WearPlaceUseLine?): TextRef = when {
    line == null || line.dayAndNight                       -> SyncStrings.wear_day_night_sends_place
    line.weather && !line.sunAndMoon && !line.asked        -> SyncStrings.wear_place_sends_weather_only
    else                                                   -> SyncStrings.wear_place_sends_place
}

@Composable
private fun UseText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = AapsSpacing.small)
    )
}

/**
 * This state with [main] as what the main design needs the place for. The "Day and night" screen
 * closes when nothing makes it reachable any more (no night face, and the new design needs nothing).
 */
internal fun WearUiState.withMainPlaceUse(main: Set<CwfPlaceUse>): WearUiState =
    copy(placeUse = placeUse.copy(main = main)).let { it.copy(showDayNight = it.showDayNight && it.dayNightReachable()) }
