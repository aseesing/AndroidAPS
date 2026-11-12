// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.ui.UiStrings

/**
 * What a status row shows about how long a part still works: a countdown to its end, its color
 * and the progress bar.
 *
 * When the [DeviceLifetime] is known, the countdown goes to the real end, grace period included.
 * The row turns yellow when less than one day is left, and red once the grace period has started.
 * When it is not known, the countdown goes to the user's critical threshold and the user's warning
 * and critical thresholds give the color, as the age display did before.
 *
 * This is a personal addition, not in upstream AAPS. It is kept in its own file so that a new AAPS
 * release merges without conflicts in `StatusViewModel`.
 */
class StatusTimeLeft(
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val preferences: Preferences
) {

    class View(val timeLeft: String, val status: StatusLevel, val percent: Float)

    /** [change] is the last change of the part, or null when there is none. */
    fun of(change: TE?, lifetime: DeviceLifetime?, warnKey: IntPreferenceKey, criticalKey: IntPreferenceKey): View {
        change ?: return View("-", StatusLevel.UNSPECIFIED, 0f)
        val start = change.timestamp
        val now = dateUtil.now()
        if (lifetime == null) {
            val end = start + preferences.get(criticalKey) * HOUR
            return View(format(now, end), thresholdStatus(now - start, warnKey, criticalKey), thresholdPercent(now - start, criticalKey))
        }
        val graceStart = start + lifetime.lifetimeHours * HOUR
        val end = graceStart + lifetime.graceHours * HOUR
        val status = when {
            now >= graceStart -> StatusLevel.CRITICAL
            end - now < DAY   -> StatusLevel.WARNING
            else              -> StatusLevel.NORMAL
        }
        val percent = if (end > start) ((now - start).toDouble() / (end - start)).coerceIn(0.0, 1.0).toFloat() else 1f
        return View(format(now, end), status, percent)
    }

    /** The time until [end], or the time since it with a minus once it has passed. */
    private fun format(now: Long, end: Long): String =
        if (now <= end) formatDuration(end - now)
        else rh.gs(UiStrings.status_overdue, formatDuration(now - end))

    // Always the short form, on every screen size: a status row is read at a glance, and long words
    // pushed the row's own name out of its place on a narrow screen. Under 10 hours the minutes count,
    // so they are shown and the days are dropped: "9h05m", or "45m" in the last hour. Above that it is
    // "3d4h", or "15h" when there is no full day.
    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / MINUTE
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            ms < 10 * HOUR -> if (hours > 0) rh.gs(UiStrings.status_time_left_hours_minutes, hours, minutes) else rh.gs(UiStrings.status_time_left_minutes, minutes)
            hours < 24     -> rh.gs(UiStrings.status_age_short_hours, hours)
            else           -> rh.gs(UiStrings.status_age_short, hours / 24, hours % 24)
        }
    }

    private fun thresholdStatus(ageMs: Long, warnKey: IntPreferenceKey, criticalKey: IntPreferenceKey): StatusLevel {
        val ageHours = ageMs / HOUR
        return when {
            ageHours >= preferences.get(criticalKey) -> StatusLevel.CRITICAL
            ageHours >= preferences.get(warnKey)     -> StatusLevel.WARNING
            else                                     -> StatusLevel.NORMAL
        }
    }

    private fun thresholdPercent(ageMs: Long, criticalKey: IntPreferenceKey): Float {
        val criticalHours = preferences.get(criticalKey)
        if (criticalHours <= 0) return 0f
        return (ageMs.toDouble() / (criticalHours * HOUR)).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {

        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
