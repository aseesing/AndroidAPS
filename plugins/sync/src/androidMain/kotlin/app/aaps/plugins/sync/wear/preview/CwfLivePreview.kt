// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
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
 * The Custom watch face as it looks on the watch now: drawn live by the watch's own code, with the
 * current values, the moving clock and the wake-up animation. A tap plays the animation again.
 *
 * Takes the place of the static picture of the design, with the same parameters as [Image]. Shows
 * [bitmap], the static picture, until the first frame is drawn, and for good if the preview fails.
 * Frames are only drawn while the screen is visible.
 */
@Composable
fun CwfLivePreview(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    // Compose previews have no app graph: the static picture
    if (LocalInspectionMode.current) {
        Image(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
        return
    }
    val viewModel: CwfLivePreviewViewModel = metroViewModel()
    BoxWithConstraints(modifier = modifier) {
        val sizePx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, MAX_SIZE_PX)
        val frames = remember(sizePx) { viewModel.frames(sizePx) }
        val frame by frames.collectAsStateWithLifecycle(initialValue = null)
        val live = frame
        if (live == null)
            Image(bitmap = bitmap, contentDescription = contentDescription, modifier = Modifier.fillMaxWidth(), contentScale = contentScale)
        else
            Image(
                bitmap = live,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .clickable { viewModel.wake() },
                contentScale = contentScale
            )
    }
}

/** Larger than a watch screen gives no more detail, only more work: the picture is scaled up instead. */
private const val MAX_SIZE_PX = 480
