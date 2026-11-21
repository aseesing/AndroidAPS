// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.aps.IobTotal

/**
 * The numbers the phone sends for the value keys of the Custom watch face (`iob`, `iob_bolus`, `iob_basal`,
 * `basalRate`, `bgi`, and `ic`, `isf`, `max_iob`, `max_carbs` from [WearRatiosAndLimits]). The status texts
 * are formatted for the screen: their decimal sign depends on the language, a percent temp basal has no rate in U/h, and the BGI text is in the user's units. Each number is
 * null when it is not known.
 */
data class StatusNumbers(
    val iob: Double? = null,
    val iobBolus: Double? = null,
    val iobBasal: Double? = null,
    val basalRate: Double? = null,
    val bgiMgdl: Double? = null,
    val ic: Double? = null, // g/U
    val isfMgdl: Double? = null, // mg/dl per U
    val maxIob: Double? = null, // U
    val maxCarbs: Double? = null // g
) {

    companion object {

        /**
         * The numbers from the bolus and basal IOB (already rounded), the basal rate that runs now in U/h,
         * and the ISF in mg/dl, or null when there is no glucose status (then the phone shows no BGI either).
         */
        fun of(bolusIob: IobTotal, basalIob: IobTotal, basalRate: Double, isfMgdl: Double?): StatusNumbers =
            StatusNumbers(
                iob = bolusIob.iob + basalIob.basaliob,
                iobBolus = bolusIob.iob,
                iobBasal = basalIob.basaliob,
                basalRate = basalRate,
                bgiMgdl = isfMgdl?.let { -(bolusIob.activity + basalIob.activity) * 5 * it }
            )
    }
}
