// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Draws one [PreviewFrame] and tells it so. One painter per frame: a new frame is a new painter, so the
 * screen draws again even when the frame lies in a bitmap it showed before.
 */
class PreviewFramePainter(private val frame: PreviewFrame) : Painter() {

    override val intrinsicSize: Size = Size(frame.image.width.toFloat(), frame.image.height.toFloat())

    override fun DrawScope.onDraw() {
        frame.drawn()
        drawImage(frame.image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
}
