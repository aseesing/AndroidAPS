package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.ui.UiStrings
import dev.zacsweers.metro.Inject

/** What a status row shows about the age or the time left of a part: the text, its color and the progress bar. */
class AgeDisplay(val text: String, val status: StatusLevel, val percent: Float)

/**
 * The countdown of a status row: the time left until a part with a known [DeviceLifetime] stops,
 * grace period included.
 *
 * The row turns yellow when less than one day is left, and red once the grace period has started.
 * The user's warning and critical thresholds are not used here: they are hours of age, and the
 * maker's lifetime is the better limit when it is known.
 */
@Inject
class StatusTimeLeft(
    private val rh: TextResolver,
    private val dateUtil: DateUtil
) {

    /** [changedAt] is the time of the last change of the part. */
    fun of(changedAt: Long, lifetime: DeviceLifetime): AgeDisplay {
        val now = dateUtil.now()
        val graceStart = changedAt + lifetime.lifetimeHours * HOUR
        val end = graceStart + lifetime.graceHours * HOUR
        val status = when {
            now >= graceStart -> StatusLevel.CRITICAL
            end - now < DAY   -> StatusLevel.WARNING
            else              -> StatusLevel.NORMAL
        }
        val percent = if (end > changedAt) ((now - changedAt).toDouble() / (end - changedAt)).coerceIn(0.0, 1.0).toFloat() else 1f
        return AgeDisplay(format(now, end), status, percent)
    }

    /**
     * The time until [end] with the word "left", so it is never read as an age ("3d4h left" beside an
     * age of "2d12h"), on screen and by a screen reader. Once the end has passed: the time since it,
     * with a minus. An age is never negative, so that is not read as an age either.
     */
    private fun format(now: Long, end: Long): String =
        if (now <= end) rh.gs(UiStrings.status_time_left, formatDuration(end - now))
        else rh.gs(UiStrings.status_time_overdue, formatDuration(now - end))

    // The short form, like the age. Under 10 hours the minutes count, so they are shown and the days
    // are dropped: "9h05m", or "45m" in the last hour. Above that it is "3d4h", or "15h" when there is
    // no full day.
    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / MINUTE
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            ms < 10 * HOUR ->
                if (hours > 0) rh.gs(UiStrings.status_time_left_hours_minutes, hours, minutes)
                else rh.gs(UiStrings.status_time_left_minutes, minutes)

            hours < 24     -> rh.gs(UiStrings.status_age_short_hours, hours)
            else           -> rh.gs(UiStrings.status_age_short, hours / 24, hours % 24)
        }
    }

    private companion object {

        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
