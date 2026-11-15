// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

/**
 * How many hours the sensor or the pump part (pod or cannula) still works, as values for a Custom watch
 * face design (`dynData`), for example for the hand of a time-left gauge. Private patch.
 *
 * The phone sends when each part stops (`EventData.Status`), with the same ends as its status lights.
 */
object PartTimeLeft {

    /** The value of the sensor. */
    const val SENSOR_KEY = "sensor_left"

    /** The value of the pod, or of the cannula of another pump. */
    const val PUMP_KEY = "pump_left"

    private const val HOUR_MS = 60 * 60 * 1000.0

    /** The hours from [now] until [endsAt]; below 0 once it has passed. Null when the end is not known. */
    fun hours(endsAt: Long?, now: Long): Double? = endsAt?.let { (it - now) / HOUR_MS }
}
