package app.aaps.core.interfaces.rx.weardata

import kotlinx.serialization.Serializable

/**
 * When it is day for the night face of the Custom watch face (see [CwfNight]). The phone makes the
 * rule, the watch only uses it. The watch keeps it only while it has a night face.
 *
 * The `toString` of a rule never shows coordinates, so a rule can never put a place into a log.
 */
@Serializable
sealed class DayNightRule {

    /**
     * Day from [dayStartMinute] to [nightStartMinute], in minutes after midnight, local time of the
     * watch. When [dayStartMinute] is after [nightStartMinute] the day runs over midnight. When both
     * are the same it is always day.
     */
    @Serializable
    data class FixedTimes(val dayStartMinute: Int, val nightStartMinute: Int) : DayNightRule()

    /**
     * Day from sunrise to sunset at this place, in degrees, north and east positive. A city the user
     * chose, or the phone's place rounded to 0.1 degree.
     */
    @Serializable
    data class Place(val latitude: Double, val longitude: Double) : DayNightRule() {

        /** Without the coordinates, see [DayNightRule]. */
        override fun toString() = "Place"
    }
}
