// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.preview.PreviewSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * A loading indicator on the Wear screen while a watch face is on its way to the watch.
 *
 * The screen shows the face the watch reports. After the wearer picked a new zip, the watch needs
 * some time to take it and report it back, and until then the screen showed the old face, as if
 * nothing had happened. Now the card shows a loading indicator with the name of the new face instead.
 */

/**
 * The faces on their way to the watch: the name of the new face, or null when nothing is loading.
 */
@Immutable
data class WearFaceLoading(val day: String? = null, val night: String? = null) {

    fun of(slot: PreviewSlot): String? = when (slot) {
        PreviewSlot.DAY   -> day
        PreviewSlot.NIGHT -> night
    }

    fun with(slot: PreviewSlot, name: String?): WearFaceLoading = when (slot) {
        PreviewSlot.DAY   -> copy(day = name)
        PreviewSlot.NIGHT -> copy(night = name)
    }
}

/**
 * Keeps [WearFaceLoading] up to date: a face loads from [start] until the watch reports a face for
 * its slot, or until [timeoutMs] passed (the watch may never answer, for example when it lost the
 * connection; then the screen shows what the watch has again).
 *
 * @param onChange called with every new state
 */
internal class WearFaceLoadingTracker(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val onChange: (WearFaceLoading) -> Unit
) {

    private var state = WearFaceLoading()
    private val jobs = mutableMapOf<PreviewSlot, Job>()

    /**
     * The face [name] was just sent for [slot]. [reported] is the face the watch reports for that
     * slot: loading ends when it changes.
     */
    fun start(slot: PreviewSlot, name: String, reported: StateFlow<CwfData?>) {
        val before = reported.value
        jobs[slot]?.cancel()
        set(slot, name)
        // Undispatched, so a report that comes at once is not missed
        jobs[slot] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeoutMs) { reported.first { it !== before } }
            set(slot, null)
        }
    }

    private fun set(slot: PreviewSlot, name: String?) {
        state = state.with(slot, name)
        onChange(state)
    }

    companion object {

        /** A zip of about 300 KB takes well under a minute over Bluetooth. */
        const val TIMEOUT_MS = 3 * 60 * 1000L
    }
}

/** Takes the place of the face picture while the face [name] loads on the watch. */
@Composable
internal fun FaceLoadingIndicator(name: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AapsSpacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(SyncStrings.wear_face_loading, name),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun FaceLoadingIndicatorPreview() {
    MaterialTheme {
        FaceLoadingIndicator(name = "AAPS Classic")
    }
}
