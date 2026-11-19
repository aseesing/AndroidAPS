package app.aaps.plugins.sync.wear.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.metroViewModel

/**
 * The Custom watch face drawn live by the watch's own code, with the values the phone last sent to the
 * watch and the moving clock. The frame is square: the phone does not know whether the watch is round.
 * Drawn round, as a round watch shows it, with the wake-up animation; a tap plays it again.
 *
 * Takes the place of the static picture of the design, with the same parameters as [Image], and
 * [slot] for the face it shows (the day face or the night face). Shows
 * [bitmap], the static picture, until the first frame is drawn, while the whole design is not on the
 * phone, and for good if the preview fails. Frames are only drawn while the screen is visible.
 */
@Composable
fun CwfLivePreview(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    slot: PreviewSlot = PreviewSlot.DAY // the day face or the night face
) {
    // Compose previews have no app graph: the static picture
    if (LocalInspectionMode.current) {
        Image(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
        return
    }
    val viewModel: CwfLivePreviewViewModel = metroViewModel()
    BoxWithConstraints(modifier = modifier) {
        val sizePx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, MAX_SIZE_PX)
        val frames = remember(sizePx, slot) { viewModel.frames(sizePx, slot) }
        val frame by frames.collectAsStateWithLifecycle(initialValue = null)
        val live = frame
        if (live == null)
            Image(bitmap = bitmap, contentDescription = contentDescription, modifier = Modifier.fillMaxWidth(), contentScale = contentScale)
        else
            Image(
                // One painter per frame, so every frame is drawn, also one in a bitmap shown before
                painter = remember(live) { PreviewFramePainter(live) },
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CircleShape) // round, as the watch
                    .clickable { viewModel.wake(slot) }, // a tap plays the wake-up animation
                contentScale = contentScale
            )
    }
}

/** Larger than a watch screen gives no more detail, only more work: the picture is scaled up instead. */
private const val MAX_SIZE_PX = 480
