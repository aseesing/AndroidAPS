// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.complications.wff

import android.content.Context
import android.graphics.Bitmap
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.wear.complications.cwf.CwfFramePipeline
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.wff.WffDesignParts

/**
 * Draws the data picture of a Watch Face Format face: every part of the design that shows AAPS data
 * and is not live in the face itself (see `WffPlanner`), on a see-through picture as large as the
 * screen.
 *
 * It is the Custom watch face itself that draws, with a copy of the design that has only those
 * parts ([WffDesignParts.dataOf]). So the picture is exactly what the watch would show, with the
 * wearer's own settings, and there is no second way of drawing.
 *
 * Call [draw] on the main thread only: the face owns views.
 */
internal class WffDataPicture(context: Context, logger: AAPSLogger) {

    private val app = context.applicationContext
    private var source: CwfData? = null
    private var copy: CwfData? = null

    private val face = CustomWatchface().apply {
        designFilter = { design -> copyOf(design) ?: design }
        prepareForRendering(app)
    }
    private val pipeline = CwfFramePipeline(app, logger) { face }

    /** The data picture of [design] at [nowMs], or null when the design has nothing for it. */
    fun draw(design: CwfData, nowMs: Long): Bitmap? {
        copyOf(design) ?: return null
        val size = app.resources.displayMetrics.widthPixels
        pipeline.invalidateData()
        return pipeline.compose(nowMs, ambient = false, width = size, height = size)
    }

    /** The copy of [design] with only the data parts, kept while the design stays the same. */
    private fun copyOf(design: CwfData): CwfData? {
        if (design !== source) {
            source = design
            copy = WffDesignParts.dataOf(design)
        }
        return copy
    }
}
