package app.aaps.wear.watchfaces.values

import app.aaps.core.interfaces.rx.weardata.EventData

/**
 * The value keys of the insulin and carbs numbers of the phone, and of the heart rate and steps of the watch.
 * A design reads them as `v_<key>` in a formula and a look condition, and as `valueKey` of a `dynData`.
 * The keys of the second and third phone end in `_Ext1` and `_Ext2`, as the other keys of those phones do.
 *
 * `basalRate` and `bgi` are the keys of the views that show these numbers, so the `dynData` of such a view
 * reads its own number without a `valueKey`.
 */
object StatusValues {

    const val IOB = "iob"
    const val IOB_BOLUS = "iob_bolus"
    const val IOB_BASAL = "iob_basal"
    const val COB = "cob"
    const val BASAL_RATE = "basalRate"
    const val BGI = "bgi"
    const val HEART_RATE = "heart_rate"
    const val STEPS = "steps"

    /** The key of the phone with this [dataset]: 0 the own phone, 1 and 2 the others. */
    fun key(key: String, dataset: Int): String = if (dataset == 0) key else "${key}_Ext$dataset"

    /** Carbs on board in grams, or null when unknown (the phone sends -1 then). */
    fun cob(status: EventData.Status): Double? = status.cobValue.takeIf { it >= 0.0 }
}
