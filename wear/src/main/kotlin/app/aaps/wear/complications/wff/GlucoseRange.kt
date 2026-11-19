// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.complications.wff

import app.aaps.core.interfaces.rx.weardata.EventData

/**
 * The range of the glucose value that [WffGlucoseComplication] sends. A converted face writes the
 * same numbers into its `Condition`s and gauge hands, so they live in one place.
 */
object GlucoseRange {

    /** Lowest value a sensor reports, in mg/dl. */
    const val MIN = 39f

    /** Highest value a sensor reports, in mg/dl. */
    const val MAX = 400f

    /** True when [bg] holds a real reading (a time and a glucose above 0). */
    fun hasReading(bg: EventData.SingleBg): Boolean = bg.timeStamp > 0 && bg.sgv > 0.0

    /** [sgv] in mg/dl, kept inside [MIN] to [MAX] as a ranged value must be. */
    fun value(sgv: Double): Float = sgv.toFloat().coerceIn(MIN, MAX)
}
