package app.aaps.wear.health

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
        if (atMs <= storedTime(KEY_HR_AT)) return false
        prefs.edit { putInt(KEY_HR, bpm); putLong(KEY_HR_AT, atMs) }
        return true
    }

    /** Keeps today's [steps] counted until [atMs] when that is newer than the value kept. */
    fun recordSteps(steps: Long, atMs: Long): Boolean {
        if (atMs <= storedTime(KEY_STEPS_AT)) return false
        prefs.edit { putLong(KEY_STEPS, steps); putLong(KEY_STEPS_AT, atMs) }
        return true
    }

    /** The heart rate, or null when there is none or it is older than [HEART_RATE_MAX_AGE_MS]. */
    fun heartRate(): Int? = heartRateShownUntil()?.let { prefs.getInt(KEY_HR, 0) }

    /** The time the kept heart rate becomes too old to show, or null when there is none to show. */
    fun heartRateShownUntil(): Long? {
        val at = storedTime(KEY_HR_AT)
        if (at == 0L || now() - at > HEART_RATE_MAX_AGE_MS) return null
        return at + HEART_RATE_MAX_AGE_MS
    }

    /** Today's steps: 0 when the last count was from an earlier day, null when there was none yet. */
    fun stepsToday(): Long? {
        val at = storedTime(KEY_STEPS_AT)
        if (at == 0L) return null
        return if (dayOf(at) == dayOf(now())) prefs.getLong(KEY_STEPS, 0L) else 0L
    }

    /**
     * The time stored under [key], or 0 when there is none. A time later than now (plus
     * [CLOCK_MARGIN_MS]) also counts as none: it was stored while the clock was ahead, and trusting it
     * would show an old value as current and refuse every new value until the clock passes it.
     */
    private fun storedTime(key: String): Long {
        val at = prefs.getLong(key, 0L)
        return if (at > now() + CLOCK_MARGIN_MS) 0L else at
    }

    /** Notes whether complication [id] of [kind] is on a watch face now. */
    fun setShown(kind: HealthKind, id: Int, shown: Boolean) {
        val key = shownKey(kind)
        val ids = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (shown) ids += id.toString() else ids -= id.toString()
        prefs.edit { putStringSet(key, ids) }
    }

    /**
     * Notes that complication [id] of [kind] is on a watch face, and returns true when it was not noted
     * yet. Called for every request, so the list is right again after app data was cleared or
     * restored from a backup, even though the system does not activate the complication again.
     */
    fun markShown(kind: HealthKind, id: Int): Boolean {
        if (id.toString() in prefs.getStringSet(shownKey(kind), emptySet()).orEmpty()) return false
        setShown(kind, id, true)
        return true
    }

    /** The kinds that at least one watch face shows, so only those are measured. */
    fun wanted(): Set<HealthKind> = HealthKind.entries.filter { prefs.getStringSet(shownKey(it), emptySet()).orEmpty().isNotEmpty() }.toSet()

    /**
     * Whether AAPS has a registration with Health Services that may still be active. Kept so a watch
     * that never showed one of these complications never talks to Health Services at all.
     */
    var passiveRegistered: Boolean
        get() = prefs.getBoolean(KEY_REGISTERED, false)
        set(value) = prefs.edit { putBoolean(KEY_REGISTERED, value) }

    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()

    private fun shownKey(kind: HealthKind) = "shown_${kind.name}"

    companion object {

        /** Heart rate is a value of the moment: older than this it is not shown any more. */
        const val HEART_RATE_MAX_AGE_MS = 30 * 60_000L

        /** How far a stored time may be after now before it counts as stored with a wrong clock. */
        const val CLOCK_MARGIN_MS = 5 * 60_000L

        /** The name of the preference file, see `HealthModule`. */
        const val FILE = "aaps_health_values"

        private const val KEY_HR = "heart_rate"
        private const val KEY_HR_AT = "heart_rate_at"
        private const val KEY_STEPS = "steps"
        private const val KEY_STEPS_AT = "steps_at"
        private const val KEY_REGISTERED = "passive_registered"
    }
}
