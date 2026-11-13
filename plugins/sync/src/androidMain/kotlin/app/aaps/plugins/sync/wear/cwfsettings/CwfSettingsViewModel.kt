// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.cwfsettings

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.plugins.sync.wear.WearPlugin
import app.aaps.wear.watchfaces.settingsync.CwfSettingNames
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSetting
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * One row of the watch face settings on the phone.
 *
 * @param name the stored name of the setting on the watch
 * @param value the value now, as text (`true` / `false` for an on/off setting)
 * @param setByDesign true when the loaded design sets it (with the authorization in the wear settings),
 *   so a change here would not stay
 */
@Immutable
data class CwfSettingRow(
    val name: String,
    val setting: CwfSyncedSetting,
    val value: String,
    val setByDesign: Boolean
)

/**
 * @param look the settings of how the face looks, only those the design uses, as on the watch
 * @param shownValues which values the face shows
 * @param fromWatch false until the watch sent its settings (or the user set one here): then the
 *   rows show the defaults, which may not be what the watch has
 */
@Immutable
data class CwfSettingsUiState(
    val look: List<CwfSettingRow> = emptyList(),
    val shownValues: List<CwfSettingRow> = emptyList(),
    val fromWatch: Boolean = true
)

/** The watch face settings screen: the settings of the loaded design, with the values the phone has. */
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
        combine(store.values, wearPlugin.savedCustomWatchface) { values, design -> uiStateOf(sync.names, values, design) }
            .flowOn(Dispatchers.Default) // reads the design json
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CwfSettingsUiState())

    /** The user chose [value] for the setting [name]. The preview and the watch follow. */
    fun set(name: String, value: String) = sync.setByUser(name, value)

    internal companion object {

        const val STOP_TIMEOUT_MS = 5_000L

        fun uiStateOf(names: CwfSettingNames, values: Map<String, CwfSettingValue>, design: CwfData?): CwfSettingsUiState {
            val authorized = design?.metadata?.get(CwfMetadataKey.CWF_AUTHORIZATION)?.toBooleanStrictOrNull() == true
            fun row(setting: CwfSyncedSetting): CwfSettingRow {
                val name = names.nameOf(setting)
                val setByDesign = authorized && setting.metadataKey?.let { design?.metadata?.get(it)?.toBooleanStrictOrNull() } != null
                return CwfSettingRow(name, setting, values[name]?.value ?: setting.defaultValue, setByDesign)
            }
            return CwfSettingsUiState(
                look = CwfSyncedSettings.faceLookFor(design?.json).map(::row),
                shownValues = CwfSyncedSettings.shownValues.map(::row),
                fromWatch = values.isNotEmpty()
            )
        }
    }
}
