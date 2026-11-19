// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Typeface
import java.util.Locale

/** What the text layers ask of the face: the fonts of the design and what the watch is set to. */
interface FaceTexts {

    /** The font of [key]: a font of the design zip or a font of the app; null for one that is not known. */
    fun font(key: String): Typeface? = null

    /** The language of the watch. */
    val locale: Locale get() = Locale.getDefault()

    /** True when the watch shows 24 hours. */
    val is24Hour: Boolean get() = true

    /** True for glucose in mg/dL, false for mmol/L. */
    val unitsMgdl: Boolean get() = true
}
