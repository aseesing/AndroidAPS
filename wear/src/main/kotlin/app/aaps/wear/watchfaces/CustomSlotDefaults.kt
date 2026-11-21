// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationType
import app.aaps.wear.complications.CobIconComplication
import app.aaps.wear.complications.IobIconComplication

/** The Custom watch face's slots that show something before the user picks anything, by slot id. */
const val HEART_RATE_SLOT_ID = 101
const val STEPS_SLOT_ID = 102
const val COB_SLOT_ID = 103
const val IOB_SLOT_ID = 104

/** What a Custom watch face slot shows until the user picks something else. */
enum class CustomSlotDefault {

    /** The heart rate source of the watch itself (a system source, not AAPS code). */
    HEART_RATE,

    /** The steps source of the watch itself (a system source, not AAPS code). */
    STEPS,

    /** Carbs on board, from AAPS. */
    COB,

    /** Insulin on board, from AAPS. */
    IOB,

    /** Nothing. */
    NONE
}

/**
 * What the slot with id [slotId] shows until the user picks something else: the heart rate and the
 * steps of the watch itself in slots 1 and 2, carbs on board and insulin on board from AAPS in slots
 * 3 and 4, and nothing in slot 5. A Watch Face Format face made from a design starts its slots the
 * same way. Only a design that shows a slot makes it visible, so designs without these slots look as
 * they always did.
 */
fun customSlotDefault(slotId: Int): CustomSlotDefault = when (slotId) {
    HEART_RATE_SLOT_ID -> CustomSlotDefault.HEART_RATE
    STEPS_SLOT_ID      -> CustomSlotDefault.STEPS
    COB_SLOT_ID        -> CustomSlotDefault.COB
    IOB_SLOT_ID        -> CustomSlotDefault.IOB
    else               -> CustomSlotDefault.NONE
}

/**
 * The [customSlotDefault] of a slot as the policy the system reads. The heart rate and the steps are
 * system sources: AAPS has no complications of its own for them. The system knows the heart rate
 * source from Android 14 on; on an older watch that slot starts empty.
 */
fun customSlotDefaultSource(context: Context, slotId: Int): DefaultComplicationDataSourcePolicy = when (customSlotDefault(slotId)) {
    CustomSlotDefault.HEART_RATE ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) systemSource(SystemDataSources.DATA_SOURCE_HEART_RATE) else DefaultComplicationDataSourcePolicy()

    CustomSlotDefault.STEPS      -> systemSource(SystemDataSources.DATA_SOURCE_STEP_COUNT)
    CustomSlotDefault.COB        -> aapsSource(context, CobIconComplication::class.java)
    CustomSlotDefault.IOB        -> aapsSource(context, IobIconComplication::class.java)
    CustomSlotDefault.NONE       -> DefaultComplicationDataSourcePolicy()
}

private fun systemSource(source: Int) = DefaultComplicationDataSourcePolicy(source, ComplicationType.SHORT_TEXT)

private fun aapsSource(context: Context, source: Class<*>) =
    DefaultComplicationDataSourcePolicy(ComponentName(context, source), ComplicationType.SHORT_TEXT, SystemDataSources.NO_DATA_SOURCE, ComplicationType.NOT_CONFIGURED)
