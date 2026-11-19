package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.wear.watchfaces.utils.LogTimeGraph

/**
 * How much history the watch gets for its glucose graph. With the log time graph of the watch on
 * ([LogTimeGraph], a setting of the watch that the phone has a copy of), the graph shows a whole day,
 * so the watch gets a day of glucose and treatments. The further back, the less a point shows on the
 * log scale, so older glucose goes with fewer points: all of the last [FULL_DETAIL_MS] (the other
 * graphs of the watch, the complication, tile and activity, show up to 6 hours), then one point per
 * a twelfth of its age (30 minutes at 6 hours, 2 hours at a day). That is about 90 points, close to
 * the 72 of 6 hours without it.
 *
 * @param plainMs how much glucose goes without the log graph
 * @param logGraphOn true when the watch shows the log time graph
 */
class WearGraphHistory(private val plainMs: Long, private val logGraphOn: () -> Boolean) {

    /** The glucose of [points] to send at [now], in the order of [points]. */
    fun <T> pick(points: List<T>, now: Long, timeOf: (T) -> Long): List<T> {
        if (!logGraphOn()) return points.filter { timeOf(it) >= now - plainMs }
        val keep = HashSet<Long>()
        var lastKept = Long.MAX_VALUE
        for (time in points.map(timeOf).sortedDescending()) {
            if (time < now - DAY_MS) break
            val age = now - time
            if (age <= FULL_DETAIL_MS || lastKept - time >= age / THIN_PARTS) {
                keep += time
                lastKept = time
            }
        }
        return points.filter { timeOf(it) in keep }
    }

    /** From when treatments (basal, temp basals, boluses and carbs) are sent at [now]. */
    fun treatmentsFrom(now: Long): Long = now - if (logGraphOn()) DAY_MS else PLAIN_TREATMENTS_MS

    companion object {

        private const val HOUR_MS = 60 * 60_000L

        /** Without the log graph: the treatments the phone always sent. */
        const val PLAIN_TREATMENTS_MS = 5 * HOUR_MS + 30 * 60_000L

        /** With the log graph. */
        const val DAY_MS = 24 * HOUR_MS
        const val FULL_DETAIL_MS = 6 * HOUR_MS
        const val THIN_PARTS = 12

        /** True when the watch shows the log time graph, from the phone's copy of the watch settings [values]. */
        fun logGraphOn(values: Map<String, CwfSettingValue>): Boolean =
            values[LogTimeGraph.SETTING_NAME]?.value?.toBooleanStrictOrNull() ?: LogTimeGraph.DEFAULT
    }
}
