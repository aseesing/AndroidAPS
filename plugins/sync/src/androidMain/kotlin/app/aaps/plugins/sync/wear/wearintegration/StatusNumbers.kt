package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.aps.IobTotal

/**
 * The numbers the phone sends for the value keys of the Custom watch face (`iob`, `iob_bolus`, `iob_basal`,
 * `basalRate`, `bgi`). The status texts are formatted for the screen: their decimal sign depends on the
 * language, a percent temp basal has no rate in U/h, and the BGI text is in the user's units. Each number is
 * null when it is not known.
 */
data class StatusNumbers(
    val iob: Double? = null,
    val iobBolus: Double? = null,
    val iobBasal: Double? = null,
    val basalRate: Double? = null,
    val bgiMgdl: Double? = null
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
