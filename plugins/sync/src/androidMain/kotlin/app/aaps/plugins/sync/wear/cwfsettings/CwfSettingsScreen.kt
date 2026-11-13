// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.cwfsettings

import android.content.res.Configuration
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.preference.ListPreference
import app.aaps.core.ui.compose.preference.PreferenceCategory
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.compose.preference.SwitchPreference
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.compose.ButtonDef
import app.aaps.plugins.sync.wear.compose.ButtonRow
import app.aaps.plugins.sync.wear.preview.CwfLivePreview
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSetting
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSettings
import androidx.compose.ui.res.stringResource as androidStringResource

/**
 * The live preview of the Custom watch face on the wear screen, with a button under it that opens
 * the watch face settings. Takes the place of the static picture, with the same parameters as [Image].
 *
 * While the settings are open the preview is drawn there, and here the static picture stands in: one
 * live preview at a time is enough work.
 */
@Composable
fun CwfPreviewAndSettings(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if (settingsOpen) Image(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    else CwfLivePreview(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    ButtonRow(button1 = ButtonDef(Icons.Default.Tune, stringResource(SyncStrings.wear_cwf_settings)) { settingsOpen = true }, button2 = null)
    if (settingsOpen && !LocalInspectionMode.current) CwfSettingsDialog(bitmap, contentDescription, onClose = { settingsOpen = false })
}

/** The watch face settings over the whole screen, with the live preview at the top. */
@Composable
private fun CwfSettingsDialog(bitmap: ImageBitmap, contentDescription: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val viewModel: CwfSettingsViewModel = metroViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            CwfSettingsContent(
                state = state,
                onChange = viewModel::set,
                onClose = onClose,
                preview = { CwfLivePreview(bitmap = bitmap, contentDescription = contentDescription, modifier = Modifier.fillMaxSize()) }
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
    onChange: (name: String, value: String) -> Unit,
    onClose: () -> Unit,
    preview: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(AapsSpacing.small)) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.close))
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

/** One setting: a switch, or a list of choices. */
@Composable
private fun CwfSettingItem(row: CwfSettingRow, onChange: (name: String, value: String) -> Unit) {
    val title: @Composable () -> Unit = { Text(androidStringResource(row.setting.title)) }
    val setByDesign: @Composable (() -> Unit)? =
        if (row.setByDesign) ({ Text(stringResource(SyncStrings.wear_cwf_settings_set_by_design)) }) else null
    when (val setting = row.setting) {
        is CwfSyncedSetting.Toggle -> SwitchPreference(
            value = row.value.toBooleanStrictOrNull() ?: setting.default,
            onValueChange = { onChange(row.name, it.toString()) },
            title = title,
            enabled = !row.setByDesign,
            summary = setByDesign
        )

        is CwfSyncedSetting.Choice -> {
            val labels = stringArrayResource(setting.entries)
            val values = stringArrayResource(setting.entryValues).toList()
            val labelOf = { value: String -> labels.getOrNull(values.indexOf(value)) ?: value }
            ListPreference(
                value = row.value,
                onValueChange = { onChange(row.name, it) },
                values = values,
                title = title,
                summary = { Text(labelOf(row.value)) },
                valueToText = { AnnotatedString(labelOf(it)) }
            )
        }
    }
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
                    look = CwfSyncedSettings.faceLook.take(3).map { CwfSettingRow(it.toString(), it, it.defaultValue, setByDesign = false) },
                    shownValues = CwfSyncedSettings.shownValues.take(4).mapIndexed { i, it -> CwfSettingRow(it.toString(), it, it.defaultValue, setByDesign = i == 0) },
                    fromWatch = false
                ),
                onChange = { _, _ -> },
                onClose = {},
                preview = { Box(modifier = Modifier.fillMaxSize()) }
            )
        }
    }
}
