package app.aaps.plugins.sync.wear.watchapp

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.metroViewModel
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

    val embedded: EmbeddedWatchApp? get() = watchAppUpdater.embedded
    val state: StateFlow<WatchAppUpdateState> get() = watchAppUpdater.state

    fun update() = watchAppUpdater.start()
}

/** The watch app card of the Wear screen. Shows nothing in a phone app without a built-in watch app. */
@Composable
internal fun WatchAppUpdateSection(viewModel: WatchAppUpdateViewModel = metroViewModel()) {
    val embedded = viewModel.embedded ?: return
    val state by viewModel.state.collectAsStateWithLifecycle()
    WatchAppUpdateCard(embedded, state, viewModel::update)
}

/**
 * @see WatchAppUpdateCardPreview
 */
@Composable
internal fun WatchAppUpdateCard(
    embedded: EmbeddedWatchApp,
    state: WatchAppUpdateState,
    onUpdate: () -> Unit
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
                text = stringResource(SyncStrings.wear_app_update_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = AapsSpacing.small)
            )
            Text(
                text = stringResource(SyncStrings.wear_app_update_embedded, embedded.label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = AapsSpacing.small)
            )
            if (state is WatchAppUpdateState.Sending) {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AapsSpacing.small)
                )
            }
            stateText(state)?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is WatchAppUpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AapsSpacing.small)
                )
            }
            OutlinedButton(
                onClick = onUpdate,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                Text(
                    text = stringResource(SyncStrings.wear_app_update_button),
                    modifier = Modifier.padding(start = AapsSpacing.small)
                )
            }
        }
    }
}

@Composable
private fun stateText(state: WatchAppUpdateState): String? =
    when (state) {
        WatchAppUpdateState.Idle          -> null
        WatchAppUpdateState.Connecting    -> stringResource(SyncStrings.wear_app_update_connecting)
        is WatchAppUpdateState.Sending    -> stringResource(SyncStrings.wear_app_update_sending, state.watchName, state.percent)
        is WatchAppUpdateState.Installing -> stringResource(SyncStrings.wear_app_update_installing, state.watchName)
        is WatchAppUpdateState.Sent       -> stringResource(SyncStrings.wear_app_update_sent, state.watchName)
        is WatchAppUpdateState.Failed     -> when (state.reason) {
            WatchAppUpdateState.Reason.NOT_EMBEDDED -> stringResource(SyncStrings.wear_app_update_not_embedded)
            WatchAppUpdateState.Reason.NO_WATCH     -> stringResource(SyncStrings.wear_app_update_no_watch)
            WatchAppUpdateState.Reason.NO_ANSWER    -> stringResource(SyncStrings.wear_app_update_no_answer)
            WatchAppUpdateState.Reason.TIMEOUT      -> stringResource(SyncStrings.wear_app_update_timeout)
            WatchAppUpdateState.Reason.WATCH_ERROR,
            WatchAppUpdateState.Reason.ERROR        -> stringResource(SyncStrings.wear_app_update_failed, state.detail)
        }
    }

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun WatchAppUpdateCardPreview() {
    MaterialTheme {
        WatchAppUpdateCard(
            embedded = EmbeddedWatchApp("4.0.1 (aaps-wear-4.0.1.apk)", 20_000_000),
            state = WatchAppUpdateState.Sending("Pixel Watch 3", 42),
            onUpdate = {}
        )
    }
}
