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

/** What a Custom watch face slot shows until the user picks something else. */
enum class CustomSlotDefault {

    /** Carbs on board, from AAPS. */
    COB,

    /** Insulin on board, from AAPS. */
    IOB,

    /** Nothing. */
    NONE
}

/**
 * What the slot with id [slotId] shows until the user picks something else: carbs on board in slot 1
 * and insulin on board in slot 2, both from AAPS itself, and nothing in slots 3 to 5. Only a design
 * that shows a slot makes it visible, so designs without these slots look as they always did.
 *
 * Heart rate and steps come from the watch's own health complications, which the user picks.
 */
fun customSlotDefault(slotId: Int): CustomSlotDefault = when (slotId) {
    COB_SLOT_ID -> CustomSlotDefault.COB
    IOB_SLOT_ID -> CustomSlotDefault.IOB
    else        -> CustomSlotDefault.NONE
}

/** The [customSlotDefault] of a slot as the policy the system reads. */
fun customSlotDefaultSource(context: Context, slotId: Int): DefaultComplicationDataSourcePolicy = when (customSlotDefault(slotId)) {
    CustomSlotDefault.COB  -> aapsSource(context, CobIconComplication::class.java)
    CustomSlotDefault.IOB  -> aapsSource(context, IobIconComplication::class.java)
    CustomSlotDefault.NONE -> DefaultComplicationDataSourcePolicy()
}

private fun aapsSource(context: Context, source: Class<*>) =
    DefaultComplicationDataSourcePolicy(ComponentName(context, source), ComplicationType.SHORT_TEXT, SystemDataSources.NO_DATA_SOURCE, ComplicationType.NOT_CONFIGURED)
