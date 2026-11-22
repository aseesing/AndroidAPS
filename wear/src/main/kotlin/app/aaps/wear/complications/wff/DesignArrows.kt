package app.aaps.wear.complications.wff

import android.content.res.Resources
import android.graphics.drawable.Icon
import app.aaps.core.interfaces.rx.weardata.CwfData
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Keeps the arrow pictures of the loaded face's design ([GlucoseArrows.fromDesign]) until the design
 * changes. Making them costs up to nine pictures of 96 x 96, and [WffGlucoseComplication] needs them
 * for every glucose update, while the design changes only when the phone sends a new face.
 *
 * It lives as long as the process: the system binds the complication only while it answers.
 */
@SingleIn(AppScope::class)
@Inject
class DesignArrows {

    /** The design that [arrows] were made from. `WffFace.dataDesign` gives the same object until the face changes. */
    private var from: CwfData? = null
    private var arrows: Map<String, Icon> = emptyMap()

    /** The arrows of [design] by symbol, made again only for another design. Without a design: none. */
    @Synchronized
    fun of(design: CwfData?, resources: Resources): Map<String, Icon> {
        if (design !== from) {
            // The pictures of the design before are dropped here; a face may still hold one, so they are not recycled
            arrows = GlucoseArrows.fromDesign(design, resources)
            from = design
        }
        return arrows
    }
}
