// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.values

import app.aaps.core.interfaces.rx.weardata.EventData

/**
 * The value keys of the insulin and carbs numbers of the phone.
 * A design reads them as `v_<key>` in a formula and a look condition, and as `valueKey` of a `dynData`.
 * The keys of the second and third phone end in `_Ext1` and `_Ext2`, as the other keys of those phones do.
 *
 * `ic` (g/U), `isf` (mg/dl per U), `max_iob` (U) and `max_carbs` (g) are the ratios and limits of the wearer,
 * for scales: a bar of COB as insulin (`v_cob / v_ic`) next to one of IOB, both up to the usual maximum.
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
    const val IC = "ic"
    const val ISF = "isf"
    const val MAX_IOB = "max_iob"
    const val MAX_CARBS = "max_carbs"

    /** The key of the phone with this [dataset]: 0 the own phone, 1 and 2 the others. */
    fun key(key: String, dataset: Int): String = if (dataset == 0) key else "${key}_Ext$dataset"

    /** Carbs on board in grams, or null when unknown (the phone sends -1 then). */
    fun cob(status: EventData.Status): Double? = status.cobValue.takeIf { it >= 0.0 }

    /** The carb ratio in g/U, or null when unknown. */
    fun ic(status: EventData.Status): Double? = positive(status.icValue)

    /** The ISF in mg/dl per U, or null when unknown. */
    fun isf(status: EventData.Status): Double? = positive(status.isfMgdlValue)

    /** The max IOB of the loop in U, or null when unknown. */
    fun maxIob(status: EventData.Status): Double? = positive(status.maxIobValue)

    /** The max carbs limit in grams, or null when unknown. */
    fun maxCarbs(status: EventData.Status): Double? = positive(status.maxCarbsValue)

    /**
     * Only a number above 0: a design divides by these ratios and limits, so 0 counts as unknown and the
     * design sees the value as missing.
     */
    private fun positive(value: Double?): Double? = value?.takeIf { it > 0.0 && it.isFinite() }
}
