// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings

/**
 * The night face on the Wear screen, see `CwfNight`.
 *
 * @param hasNightWatchface whether the watch reported a night face
 * @param importForNight whether the open import list sends the chosen zip as the night face
 */
@Immutable
data class WearNightFaceState(
    val hasNightWatchface: Boolean = false,
    val nightWatchfaceName: String = "",
    val nightWatchfaceImage: ImageBitmap? = null,
    val importForNight: Boolean = false
)

/** The title of the main face card: the day face, or the only face, day and night. */
@Composable
internal fun dayWatchfaceTitle(uiState: WearUiState): String =
    stringResource(
        if (uiState.nightFace.hasNightWatchface) SyncStrings.wear_day_night_day_title else SyncStrings.wear_day_night_both_title,
        uiState.watchfaceName
    )

/** The title of the import list: [mainTitle] for the main face, its own title for the night face. */
@Composable
internal fun importListTitle(uiState: WearUiState, mainTitle: String): String =
    if (uiState.nightFace.importForNight) stringResource(SyncStrings.wear_day_night_select_night_face) else mainTitle

/**
 * The night face card: its name and preview, a button to load one from the same list as the main
 * face, and a button to remove it.
 *
 * @see NightWatchfaceCardPreview
 */
@Composable
internal fun NightWatchfaceCard(
    state: WearNightFaceState,
    onLoadNightWatchface: () -> Unit,
    onRemoveNightWatchface: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AapsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) {
            Text(
                text = if (state.hasNightWatchface) stringResource(SyncStrings.wear_day_night_night_title, state.nightWatchfaceName)
                else stringResource(SyncStrings.wear_day_night_no_night_face),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = AapsSpacing.small)
            )

            ButtonRow(
                button1 = ButtonDef(Icons.Default.Upload, stringResource(SyncStrings.wear_day_night_load_night_face), onLoadNightWatchface),
                button2 = if (state.hasNightWatchface)
                    ButtonDef(Icons.Default.Delete, stringResource(SyncStrings.wear_day_night_remove_night_face), onRemoveNightWatchface)
                else null
            )

            state.nightWatchfaceImage?.let { image ->
                Spacer(modifier = Modifier.height(AapsSpacing.small))
                Image(
                    bitmap = image,
                    contentDescription = state.nightWatchfaceName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AapsSpacing.extraLarge),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun NightWatchfaceCardPreview() {
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            NightWatchfaceCard(
                state = WearNightFaceState(hasNightWatchface = true, nightWatchfaceName = "Cyberpunk"),
                onLoadNightWatchface = {},
                onRemoveNightWatchface = {}
            )
            NightWatchfaceCard(
                state = WearNightFaceState(),
                onLoadNightWatchface = {},
                onRemoveNightWatchface = {}
            )
        }
    }
}
