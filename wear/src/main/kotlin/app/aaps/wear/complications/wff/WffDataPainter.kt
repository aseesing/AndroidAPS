// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.complications.wff

import android.content.Context
import android.graphics.Bitmap
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.CwfData
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Keeps the painter of the data picture ([WffDataPicture]) for the life of the process: the system
 * binds [WffDataComplication] only while it answers, and making the face again for each request would
 * cost most of the time (see `CwfFaceComplication`). It holds the application context only.
 *
 * Call [draw] on the main thread only: the face owns views.
 */
@SingleIn(AppScope::class)
@Inject
class WffDataPainter(private val context: Context, private val aapsLogger: AAPSLogger) {

    private var picture: WffDataPicture? = null

    /** The data picture of [design] at [nowMs]. The first call makes the painter. */
    fun draw(design: CwfData, nowMs: Long): Bitmap =
        (picture ?: WffDataPicture(context, aapsLogger).also { picture = it }).draw(design, nowMs)
}
