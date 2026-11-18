// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.cwfsettings

import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.compose.ButtonDef
import app.aaps.plugins.sync.wear.compose.ButtonRow
import app.aaps.plugins.sync.wear.preview.CwfLivePreview
import app.aaps.plugins.sync.wear.preview.PreviewSlot

/**
 * The live preview of a face with its own settings button under it, for the night face card. The main
 * face has the upstream button row and dialog on the wear screen instead.
 *
 * Takes the place of the static picture, with the same parameters as [Image], and [slot] for the face
 * it shows. While its settings are open the preview is drawn there, and here the static picture
 * stands in: one live preview of a face at a time is enough work.
 */
@Composable
fun CwfPreviewAndSettings(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    slot: PreviewSlot = PreviewSlot.NIGHT
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if (settingsOpen) Image(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
    else CwfLivePreview(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale, slot = slot)
    ButtonRow(button1 = ButtonDef(Icons.Default.Tune, stringResource(SyncStrings.wear_cwf_settings)) { settingsOpen = true }, button2 = null)
    if (settingsOpen && !LocalInspectionMode.current) CwfSettingsDialog(bitmap, contentDescription, onClose = { settingsOpen = false }, slot = slot)
}
