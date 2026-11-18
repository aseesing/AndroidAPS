package app.aaps.plugins.sync.wear.watchapp

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
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
class WatchAppUpdateViewModel(private val watchAppUpdater: WatchAppUpdater) : ViewModel() {

    /** The built-in watch app; null in a phone app built without one, which then offers no update */
    val embedded: EmbeddedWatchApp? get() = watchAppUpdater.embedded
    val state: StateFlow<WatchAppUpdateState> get() = watchAppUpdater.state

    fun update() = watchAppUpdater.start()
}

/** The small button on the Wear screen that opens the watch app update page */
@Composable
internal fun WatchAppUpdateButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null)
        Text(
            text = stringResource(SyncStrings.wear_app_update_button),
            modifier = Modifier.padding(start = AapsSpacing.small)
        )
    }
}

/** The watch app update page, a sub-page of the Wear screen */
@Composable
internal fun WatchAppUpdateContent(viewModel: WatchAppUpdateViewModel, modifier: Modifier = Modifier) {
    val embedded = viewModel.embedded ?: return
    val state by viewModel.state.collectAsStateWithLifecycle()
    WatchAppUpdatePage(embedded, state, viewModel::update, modifier)
}

/**
 * The information scrolls; the send button stays at the bottom, always visible, and disabled while an
 * update runs, as in `.claude/procedures/dialog-design.md`.
 *
 * @see WatchAppUpdatePagePreview
 */
@Composable
internal fun WatchAppUpdatePage(
    embedded: EmbeddedWatchApp,
    state: WatchAppUpdateState,
    onSend: () -> Unit,
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
                        text = stringResource(SyncStrings.wear_app_update_embedded, embedded.label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(SyncStrings.wear_app_update_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state is WatchAppUpdateState.Sending) {
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
                            color = if (state is WatchAppUpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        // Fixed at the bottom, always visible; disabled while an update runs
        Button(
            onClick = onSend,
            enabled = !state.isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null)
            Text(
                text = stringResource(SyncStrings.wear_app_update_send),
                modifier = Modifier.padding(start = AapsSpacing.small)
            )
        }
    }
}

@Composable
private fun stateText(state: WatchAppUpdateState): String? =
    when (state) {
        WatchAppUpdateState.Idle          -> null
        WatchAppUpdateState.Connecting    -> stringResource(SyncStrings.wear_app_update_connecting)
        is WatchAppUpdateState.Sending    -> state.secondsLeft?.let { seconds ->
            // Whole minutes, rounded up: "about 0 min left" would read as done
            val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
            stringResource(SyncStrings.wear_app_update_sending_time_left, state.watchName, state.percent, stringResource(CoreUiStrings.format_mins, minutes))
        } ?: stringResource(SyncStrings.wear_app_update_sending, state.watchName, state.percent)

        is WatchAppUpdateState.Installing -> stringResource(SyncStrings.wear_app_update_installing, state.watchName)
        is WatchAppUpdateState.Sent       -> stringResource(SyncStrings.wear_app_update_sent, state.watchName)
        is WatchAppUpdateState.Failed     -> when (state.reason) {
            WatchAppUpdateState.Reason.NOT_EMBEDDED  -> stringResource(SyncStrings.wear_app_update_not_embedded)
            WatchAppUpdateState.Reason.NO_WATCH      -> stringResource(SyncStrings.wear_app_update_no_watch)
            WatchAppUpdateState.Reason.NO_ANSWER     -> stringResource(SyncStrings.wear_app_update_no_answer)
            WatchAppUpdateState.Reason.TIMEOUT       -> stringResource(SyncStrings.wear_app_update_timeout)
            WatchAppUpdateState.Reason.CHECK_WATCH   -> stringResource(SyncStrings.wear_app_update_check_watch)
            WatchAppUpdateState.Reason.OLD_WATCH_APP -> stringResource(SyncStrings.wear_app_update_old_watch_app)
            WatchAppUpdateState.Reason.OLD_PHONE_APP -> stringResource(SyncStrings.wear_app_update_old_phone_app)
            WatchAppUpdateState.Reason.BUSY          -> stringResource(SyncStrings.wear_app_update_busy)
            WatchAppUpdateState.Reason.WATCH_ERROR,
            WatchAppUpdateState.Reason.ERROR         -> stringResource(SyncStrings.wear_app_update_failed, state.detail)
        }
    }

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun WatchAppUpdatePagePreview() {
    MaterialTheme {
        WatchAppUpdatePage(
            embedded = EmbeddedWatchApp("4.0.1 (aaps-wear-4.0.1.apk)", 20_000_000, 58_000_000),
            state = WatchAppUpdateState.Sending("Pixel Watch 3", 42, secondsLeft = 20, overWifi = true),
            onSend = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun WatchAppUpdateButtonPreview() {
    MaterialTheme {
        WatchAppUpdateButton(onClick = {})
    }
}
