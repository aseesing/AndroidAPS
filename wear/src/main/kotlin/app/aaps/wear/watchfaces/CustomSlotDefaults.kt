// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationType
import app.aaps.wear.complications.CobIconComplication
import app.aaps.wear.complications.IobIconComplication

/** The Custom watch face's slots that show something before the user picks anything, by slot id. */
const val COB_SLOT_ID = 101
const val IOB_SLOT_ID = 102

/**
 * What a Custom watch face slot shows until the user picks something else: carbs on board in slot 1
 * and insulin on board in slot 2, both from AAPS itself, and nothing in slots 3 to 5. Only a design
 * that shows a slot makes it visible, so designs without these slots look as they always did.
 *
 * Heart rate and steps come from the watch's own health complications, which the user picks.
 */
fun customSlotDefaultSource(context: Context, slotId: Int): DefaultComplicationDataSourcePolicy = when (slotId) {
    COB_SLOT_ID -> aapsSource(context, CobIconComplication::class.java, ComplicationType.SHORT_TEXT)
    IOB_SLOT_ID -> aapsSource(context, IobIconComplication::class.java, ComplicationType.SHORT_TEXT)
    else        -> DefaultComplicationDataSourcePolicy()
}

private fun aapsSource(context: Context, source: Class<*>, type: ComplicationType) =
    DefaultComplicationDataSourcePolicy(ComponentName(context, source), type, SystemDataSources.NO_DATA_SOURCE, ComplicationType.NOT_CONFIGURED)
