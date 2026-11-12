// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.health

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What a complication of this package shows. */
enum class HealthKind { HEART_RATE, STEPS }

/**
 * The latest heart rate and today's steps from Health Services, kept for the watch face complications,
 * and which of these complications are on a watch face now.
 *
 * In a preference file of its own, so it survives a restart of the app and the complications have a
 * value at once, without waiting for the next batch from Health Services.
 */
class HealthValues(
    private val prefs: SharedPreferences,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    /** Keeps [bpm] measured at [atMs] when it is newer than the value kept. */
    fun recordHeartRate(bpm: Int, atMs: Long): Boolean {
        if (atMs <= prefs.getLong(KEY_HR_AT, 0L)) return false
        prefs.edit { putInt(KEY_HR, bpm); putLong(KEY_HR_AT, atMs) }
        return true
    }

    /** Keeps today's [steps] counted until [atMs] when that is newer than the value kept. */
    fun recordSteps(steps: Long, atMs: Long): Boolean {
        if (atMs <= prefs.getLong(KEY_STEPS_AT, 0L)) return false
        prefs.edit { putLong(KEY_STEPS, steps); putLong(KEY_STEPS_AT, atMs) }
        return true
    }

    /** The heart rate, or null when there is none or it is older than [HEART_RATE_MAX_AGE_MS]. */
    fun heartRate(): Int? {
        val at = prefs.getLong(KEY_HR_AT, 0L)
        if (at == 0L || now() - at > HEART_RATE_MAX_AGE_MS) return null
        return prefs.getInt(KEY_HR, 0)
    }

    /** Today's steps: 0 when the last count was from an earlier day, null when there was none yet. */
    fun stepsToday(): Long? {
        val at = prefs.getLong(KEY_STEPS_AT, 0L)
        if (at == 0L) return null
        return if (dayOf(at) == dayOf(now())) prefs.getLong(KEY_STEPS, 0L) else 0L
    }

    /** Notes whether complication [id] of [kind] is on a watch face now. */
    fun setShown(kind: HealthKind, id: Int, shown: Boolean) {
        val key = shownKey(kind)
        val ids = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (shown) ids += id.toString() else ids -= id.toString()
        prefs.edit { putStringSet(key, ids) }
    }

    /** The kinds that at least one watch face shows, so only those are measured. */
    fun wanted(): Set<HealthKind> = HealthKind.entries.filter { prefs.getStringSet(shownKey(it), emptySet()).orEmpty().isNotEmpty() }.toSet()

    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()

    private fun shownKey(kind: HealthKind) = "shown_${kind.name}"

    companion object {

        /** Heart rate is a value of the moment: older than this it is not shown any more. */
        const val HEART_RATE_MAX_AGE_MS = 30 * 60_000L

        private const val FILE = "aaps_health_values"
        private const val KEY_HR = "heart_rate"
        private const val KEY_HR_AT = "heart_rate_at"
        private const val KEY_STEPS = "steps"
        private const val KEY_STEPS_AT = "steps_at"

        fun of(context: Context) = HealthValues(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
