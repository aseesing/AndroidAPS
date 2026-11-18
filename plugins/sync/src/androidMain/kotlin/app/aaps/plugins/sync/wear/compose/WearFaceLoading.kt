package app.aaps.plugins.sync.wear.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the name of a watch face that is on its way to the watch, for a loading indicator.
 *
 * The Wear screen shows the face the watch reports. After the wearer picked a new zip, the watch
 * needs some time to take it and report it back. Until then the screen showed the old face, as if
 * nothing had happened.
 *
 * A face loads from [start] until the watch reports a face, until [stop] (the watch disconnected),
 * or until [timeoutMs] passed: the watch may never answer. Then the screen shows what the watch has
 * again.
 *
 * @param onChange called with the name of the loading face, or null when nothing loads any more
 */
internal class WearFaceLoadingTracker(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val onChange: (String?) -> Unit
) {

    private var job: Job? = null

    /**
     * The face [name] was just sent to the watch. [reported] is the face the watch reports:
     * loading ends when it changes. A new start replaces the one before, with its own timeout.
     */
    fun start(name: String, reported: StateFlow<CwfData?>) {
        val before = reported.value
        job?.cancel()
        onChange(name)
        // Undispatched, so a report that comes at once is not missed
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Each report of the watch is a new object, also for the same design
            withTimeoutOrNull(timeoutMs) { reported.first { it !== before } }
            onChange(null)
        }
    }

    /** Ends a load at once, for example when the watch disconnected. Does nothing when nothing loads. */
    fun stop() {
        val loading = job?.isActive == true
        job?.cancel()
        job = null
        if (loading) onChange(null)
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
