package app.aaps.plugins.sync.wear.cwfsettings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.rx.weardata.CwfWatchSettings
import app.aaps.core.keys.KeysStrings
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.preference.PreferenceCategory
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.compose.preference.SwitchPreference
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.preview.CwfLivePreview

/**
 * The watch face settings over the whole screen, with the live preview of the watch face at the top.
 * A change shows in the preview at once.
 *
 * @param bitmap the static picture of the watch face, or null when the phone has none yet
 */
@Composable
internal fun CwfSettingsDialog(bitmap: ImageBitmap?, contentDescription: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val viewModel: CwfSettingsViewModel = metroViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            CwfSettingsContent(
                state = state,
                onChange = viewModel::set,
                onClose = onClose,
                preview = {
                    if (bitmap != null)
                        CwfLivePreview(bitmap = bitmap, contentDescription = contentDescription, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            )
        }
    }
}

/**
 * The settings screen without its view model: a title bar, the [preview] (kept in view while the
 * list scrolls), and the settings.
 *
 * @param onChange called with the stored name of a setting and its new value
 */
@Composable
internal fun CwfSettingsContent(
    state: CwfSettingsUiState,
    onChange: (name: String, value: Boolean) -> Unit,
    onClose: () -> Unit,
    preview: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(AapsSpacing.small)) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.back))
            }
            Text(text = stringResource(SyncStrings.wear_cwf_settings), style = MaterialTheme.typography.titleLarge)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AapsSpacing.medium),
            contentAlignment = Alignment.Center
        ) {
            // Large enough to read, small enough to leave room for the list below
            Box(modifier = Modifier.size(min(maxWidth * PREVIEW_SHARE_OF_WIDTH, maxHeight * PREVIEW_SHARE_OF_HEIGHT))) { preview() }
        }
        HorizontalDivider()
        ProvidePreferenceTheme {
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (!state.fromWatch) item {
                    Text(
                        text = stringResource(SyncStrings.wear_cwf_settings_not_from_watch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)
                    )
                }
                // All rows are shown, also those the loaded design does not use (the watch menu hides those)
                item {
                    Text(
                        text = stringResource(SyncStrings.wear_cwf_settings_only_if_used),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)
                    )
                }
                if (state.look.isNotEmpty()) {
                    item { PreferenceCategory(title = { Text(stringResource(SyncStrings.wear_cwf_settings_look)) }) }
                    items(state.look, key = { it.name }) { row -> CwfSettingItem(row, onChange) }
                }
                item { PreferenceCategory(title = { Text(stringResource(SyncStrings.wear_cwf_settings_shown_values)) }) }
                items(state.shownValues, key = { it.name }) { row -> CwfSettingItem(row, onChange) }
            }
        }
    }
}

/** One setting: a switch. */
@Composable
private fun CwfSettingItem(row: CwfSettingRow, onChange: (name: String, value: Boolean) -> Unit) {
    SwitchPreference(
        value = row.value,
        onValueChange = { onChange(row.name, it) },
        title = { Text(stringResource(row.setting.label)) },
        enabled = !row.setByDesign,
        summary = if (row.setByDesign) ({ Text(stringResource(SyncStrings.wear_cwf_settings_set_by_design, stringResource(KeysStrings.pref_title_wear_custom_watchface_authorization))) }) else null
    )
}

/** How much of the screen width the preview takes. */
private const val PREVIEW_SHARE_OF_WIDTH = 0.6f

/** How much of the screen height the preview takes at most, for a phone held sideways. */
private const val PREVIEW_SHARE_OF_HEIGHT = 0.4f

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun CwfSettingsContentPreview() {
    MaterialTheme {
        Surface {
            CwfSettingsContent(
                state = CwfSettingsUiState(
                    look = CwfWatchSettings.look.take(3).map { CwfSettingRow(it.key, it, it.default, setByDesign = false) },
                    shownValues = CwfWatchSettings.shownValues.take(4).mapIndexed { i, it -> CwfSettingRow(it.key, it, it.default, setByDesign = i == 0) },
                    fromWatch = false
                ),
                onChange = { _, _ -> },
                onClose = {},
                preview = { Box(modifier = Modifier.fillMaxSize()) }
            )
        }
    }
}
