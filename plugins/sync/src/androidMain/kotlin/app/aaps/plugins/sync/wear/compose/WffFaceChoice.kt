// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.aaps.core.keys.PushedWatchfaceId
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings

// The parts of the Wear screen (`WearScreen.kt`) for a Watch Face Format face loaded from a face zip.

/**
 * The faces the wearer can choose between on a watch with Watch Face Push: the two embedded ones, and
 * the face from a file once the watch has one (or while it is the choice).
 */
internal fun pushedFaceChoices(uiState: WearUiState): List<String> =
    listOf(PushedWatchfaceId.CWF, PushedWatchfaceId.WFS) +
        listOfNotNull(PushedWatchfaceId.DESIGN.takeIf { uiState.wffFaceName != null || uiState.pushedWatchface == it })

/** The name of the face from a file for its row in the choice, or null for another [face] or when the watch told no name. */
internal fun wffFaceChoiceLabel(uiState: WearUiState, face: String): String? =
    uiState.wffFaceName?.takeIf { face == PushedWatchfaceId.DESIGN }

/**
 * The line "On the watch: ..." with the [name] of the Watch Face Format face loaded with "Import
 * designs", while the watch shows it. Nothing for null: the watch shows another face, see `WffFaceOnWatch.kt`.
 */
@Composable
internal fun WffFaceOnWatchLine(name: String?) {
    name ?: return
    Text(
        text = stringResource(SyncStrings.wear_wff_face_on_watch, name),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = AapsSpacing.small)
    )
}
