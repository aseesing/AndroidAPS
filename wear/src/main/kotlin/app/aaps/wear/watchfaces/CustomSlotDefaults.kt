package app.aaps.wear.watchfaces

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationType
import app.aaps.wear.complications.CobIconComplication
import app.aaps.wear.complications.IobIconComplication
import app.aaps.wear.health.HeartRateComplication
import app.aaps.wear.health.StepsComplication

/** The Custom watch face's slots that show something before the user picks anything, by slot id. */
const val HEART_RATE_SLOT_ID = 101
const val STEPS_SLOT_ID = 102
const val COB_SLOT_ID = 103
const val IOB_SLOT_ID = 104

/**
 * What a Custom watch face slot shows until the user picks something else: heart rate, steps, carbs on
 * board and insulin on board in slots 1 to 4, all from AAPS itself, and nothing in slot 5. Only a design
 * that shows a slot makes it visible, so designs without these slots look as they always did.
 */
fun customSlotDefaultSource(context: Context, slotId: Int): DefaultComplicationDataSourcePolicy = when (slotId) {
    HEART_RATE_SLOT_ID -> aapsSource(context, HeartRateComplication::class.java, ComplicationType.RANGED_VALUE)
    STEPS_SLOT_ID      -> aapsSource(context, StepsComplication::class.java, ComplicationType.RANGED_VALUE)
    COB_SLOT_ID        -> aapsSource(context, CobIconComplication::class.java, ComplicationType.SHORT_TEXT)
    IOB_SLOT_ID        -> aapsSource(context, IobIconComplication::class.java, ComplicationType.SHORT_TEXT)
    else               -> DefaultComplicationDataSourcePolicy()
}

private fun aapsSource(context: Context, source: Class<*>, type: ComplicationType) =
    DefaultComplicationDataSourcePolicy(ComponentName(context, source), type, SystemDataSources.NO_DATA_SOURCE, ComplicationType.NOT_CONFIGURED)
