package app.aaps.plugins.sync.wear.watchlog

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.StateFlow

@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class WatchLogViewModel(private val watchLogDownloader: WatchLogDownloader) : ViewModel() {

    val state: StateFlow<WatchLogState> get() = watchLogDownloader.state

    fun download() = watchLogDownloader.start()
}

/** The small button on the Wear screen that opens the watch log page */
@Composable
internal fun WatchLogButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.Download, contentDescription = null)
        Text(
            text = stringResource(SyncStrings.wear_log_button),
            modifier = Modifier.padding(start = AapsSpacing.small)
        )
    }
}

/** The watch log page, a sub-page of the Wear screen */
@Composable
internal fun WatchLogContent(viewModel: WatchLogViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WatchLogPage(state, viewModel::download, modifier)
}

/**
 * The information scrolls; the download button stays at the bottom, always visible, and disabled
 * while a download runs, as in `.claude/procedures/dialog-design.md`.
 *
 * @see WatchLogPagePreview
 */
@Composable
internal fun WatchLogPage(
    state: WatchLogState,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AapsSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
                ) {
                    Text(
                        text = stringResource(SyncStrings.wear_log_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state is WatchLogState.Receiving) {
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(if (state.overWifi) SyncStrings.wear_app_update_over_wifi else SyncStrings.wear_app_update_over_bluetooth),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    stateText(state)?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state is WatchLogState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        // Fixed at the bottom, always visible; disabled while a download runs
        Button(
            onClick = onDownload,
            enabled = !state.isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)
        ) {
            Icon(Icons.Default.Download, contentDescription = null)
            Text(
                text = stringResource(SyncStrings.wear_log_download),
                modifier = Modifier.padding(start = AapsSpacing.small)
            )
        }
    }
}

@Composable
private fun stateText(state: WatchLogState): String? =
    when (state) {
        WatchLogState.Idle           -> null
        WatchLogState.Connecting     -> stringResource(SyncStrings.wear_app_update_connecting)
        is WatchLogState.Preparing   -> stringResource(SyncStrings.wear_log_preparing, state.watchName)
        is WatchLogState.Receiving   -> state.secondsLeft?.let { seconds ->
            // Whole minutes, rounded up: "about 0 min left" would read as done
            val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
            stringResource(SyncStrings.wear_log_receiving_time_left, state.watchName, state.percent, stringResource(CoreUiStrings.format_mins, minutes))
        } ?: stringResource(SyncStrings.wear_log_receiving, state.watchName, state.percent)

        is WatchLogState.Received    -> stringResource(SyncStrings.wear_log_saving)
        is WatchLogState.Saved       -> stringResource(SyncStrings.wear_log_saved, state.fileName)
        is WatchLogState.Failed      -> when (state.reason) {
            WatchLogState.Reason.NO_WATCH      -> stringResource(SyncStrings.wear_app_update_no_watch)
            WatchLogState.Reason.NO_ANSWER     -> stringResource(SyncStrings.wear_log_no_answer)
            WatchLogState.Reason.TIMEOUT       -> stringResource(SyncStrings.wear_app_update_timeout)
            WatchLogState.Reason.OLD_WATCH_APP -> stringResource(SyncStrings.wear_log_old_watch_app)
            WatchLogState.Reason.BUSY          -> stringResource(SyncStrings.wear_log_busy)
            WatchLogState.Reason.WATCH_ERROR,
            WatchLogState.Reason.ERROR         -> stringResource(SyncStrings.wear_log_failed, state.detail)
        }
    }

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun WatchLogPagePreview() {
    MaterialTheme {
        WatchLogPage(
            state = WatchLogState.Receiving("Pixel Watch 3", 42, secondsLeft = 20, overWifi = true),
            onDownload = {}
        )
    }
}
