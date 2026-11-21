package app.aaps.plugins.sync.wear.compose

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.rowAction
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings

/**
 * The toolbar button of the import list. It opens the Android file picker, so designs can come from
 * Google Drive or from the phone storage. The picked zips go to [onImport].
 *
 * @param importing true while an import runs: the button is off, so a second import cannot start
 */
@Composable
internal fun CwfImportAction(importing: Boolean, onImport: (List<Uri>) -> Unit, lastPlace: () -> String? = { null }) { // PERSONAL PATCH: last import place, see CwfDesignImportPlace.kt
    val picker = rememberLauncherForActivityResult(OpenDesignsAt(lastPlace), onImport) // PERSONAL PATCH: last import place, was ActivityResultContracts.OpenMultipleDocuments()
    IconButton(onClick = { picker.launch(CwfDesignImport.ZIP_TYPES) }, enabled = !importing) {
        Icon(imageVector = Icons.Default.FileOpen, contentDescription = stringResource(SyncStrings.wear_design_import))
    }
}

/**
 * @param importing true while picked designs are copied: a progress bar shows above the list
 *
 * @see CwfImportContentPreview
 */
@Composable
internal fun CwfImportContent(
    items: List<CwfImportItemState>,
    importing: Boolean,
    onItemClick: (CwfImportItemState) -> Unit,
    onItemDelete: (CwfImportItemState) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(AapsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
    ) {
        if (importing) {
            item(key = "importing") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        items(items, key = { it.fileName }) { item ->
            CwfImportItemCard(item = item, onClick = { onItemClick(item) }, onDelete = { onItemDelete(item) })
        }
    }
}

@Composable
private fun CwfImportItemCard(
    item: CwfImportItemState,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The delete button asks first: the zip is gone from AAPS/export then
    var askDelete by remember { mutableStateOf(false) }
    if (askDelete) {
        OkCancelDialog(
            title = stringResource(CoreUiStrings.delete),
            message = stringResource(SyncStrings.wear_design_delete_question, item.cwfFile.cwfData.metadata[CwfMetadataKey.CWF_NAME] ?: ""),
            onConfirm = {
                askDelete = false
                onDelete()
            },
            onDismiss = { askDelete = false }
        )
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(AapsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Watchface preview image
            item.watchfaceImage?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = item.name,
                    modifier = Modifier.size(100.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(AapsSpacing.medium))
            }

            // Metadata column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // PERSONAL PATCH: every entry says what it is, a Watch Face Format face zip (WffFaceDesigns.kt) or a design zip
                Text(
                    text = stringResource(if (item.wffFace) SyncStrings.wear_design_type_wff else SyncStrings.wear_design_type_cwf),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = item.createdAt,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)
                ) {
                    Text(
                        text = item.version,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isVersionOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    if (item.prefCount > 0) {
                        Text(
                            text = "${item.prefCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.hasPrefAuthorization) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = if (item.hasPrefAuthorization) Icons.Default.Warning else Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (item.hasPrefAuthorization) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            IconButton(onClick = { askDelete = true }) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = rowAction(CoreUiStrings.delete, item.name))
            }
        }
    }
}
