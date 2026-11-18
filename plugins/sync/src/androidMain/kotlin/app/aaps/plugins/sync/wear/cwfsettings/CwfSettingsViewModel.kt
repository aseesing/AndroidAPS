package app.aaps.plugins.sync.wear.cwfsettings

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.CwfWatchSetting
import app.aaps.core.interfaces.rx.weardata.CwfWatchSettings
import app.aaps.plugins.sync.wear.WearPlugin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * One row of the watch face settings on the phone.
 *
 * @param name the stored name of the setting on the watch
 * @param value the value now
 * @param setByDesign true when the loaded design sets it (with the authorization in the wear settings),
 *   so a change here would not stay
 */
@Immutable
data class CwfSettingRow(
    val name: String,
    val setting: CwfWatchSetting,
    val value: Boolean,
    val setByDesign: Boolean
)

/**
 * @param look the settings of how the face looks
 * @param shownValues which values the face shows
 * @param fromWatch false until the watch ever sent its settings: then the rows show the defaults,
 *   which may not be what the watch has, and a watch app that does not know these settings
 *   messages does not take a change from here
 */
@Immutable
data class CwfSettingsUiState(
    val look: List<CwfSettingRow> = emptyList(),
    val shownValues: List<CwfSettingRow> = emptyList(),
    val fromWatch: Boolean = true
)

/** The watch face settings screen: the settings, with the values the phone has, and those the loaded design sets. */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class CwfSettingsViewModel(
    private val sync: CwfSettingsSyncPhone,
    store: CwfWatchSettingsStore,
    wearPlugin: WearPlugin
) : ViewModel() {

    val uiState: StateFlow<CwfSettingsUiState> =
        combine(store.values, store.heardFromWatch, wearPlugin.savedCustomWatchface) { values, heard, design -> uiStateOf(values, design, heard) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CwfSettingsUiState())

    /** The user switched the setting [name] to [value]. The watch follows. */
    fun set(name: String, value: Boolean) = sync.setByUser(name, value.toString())

    internal companion object {

        const val STOP_TIMEOUT_MS = 5_000L

        fun uiStateOf(values: Map<String, CwfSettingValue>, design: CwfData?, fromWatch: Boolean): CwfSettingsUiState {
            val authorized = design?.metadata?.get(CwfMetadataKey.CWF_AUTHORIZATION)?.toBooleanStrictOrNull() == true
            fun row(setting: CwfWatchSetting): CwfSettingRow {
                val name = setting.key
                val setByDesign = authorized && setting.metadataKey?.let { design?.metadata?.get(it)?.toBooleanStrictOrNull() } != null
                val value = values[name]?.value?.toBooleanStrictOrNull() ?: setting.default
                return CwfSettingRow(name, setting, value, setByDesign)
            }
            // All rows, also those the design does not use: the screen says that such a setting has no effect
            return CwfSettingsUiState(
                look = CwfWatchSettings.look.map(::row),
                shownValues = CwfWatchSettings.shownValues.map(::row),
                fromWatch = fromWatch
            )
        }
    }
}
