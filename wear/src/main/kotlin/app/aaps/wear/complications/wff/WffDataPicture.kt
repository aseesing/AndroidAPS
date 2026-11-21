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

/**
 * Draws the data picture of a Watch Face Format face: the design of the face's `data.zip` (see
 * `WffFace`), which holds only the parts that show AAPS data and that the face does not draw
 * itself, on a see-through picture as large as the screen.
 *
 * It is the Custom watch face that draws, so there is no second way of drawing a design.
 *
 * Call [draw] on the main thread only: the face owns views.
 */
internal class WffDataPicture(context: Context, logger: AAPSLogger) {

    private val app = context.applicationContext
    private var design: CwfData? = null
    private var source: CwfData? = null // PERSONAL PATCH: the design that [design] was made from

    private val face = CustomWatchface().apply {
        designFilter = { stored -> design ?: stored }
        prepareForRendering(app)
    }
    private val pipeline = CwfFramePipeline(app, logger) { face }

    /** The data picture of [design] at [nowMs]. */
    fun draw(design: CwfData, nowMs: Long): Bitmap {
        if (design !== source) this.design = WffClearBackground.of(design).also { source = design } // PERSONAL PATCH: always see-through, see WffClearBackground
        val size = app.resources.displayMetrics.widthPixels
        pipeline.invalidateData()
        return pipeline.compose(nowMs, ambient = false, width = size, height = size)
    }
}
