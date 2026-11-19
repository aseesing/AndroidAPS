package app.aaps.wear.health

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The latest heart rate from Health Services, for the `bpm` of the Custom watch face, and whether the
 * design on that face uses `bpm` at all.
 *
 * In a preference file of its own, so it survives a restart of the app and the face has a heart rate at
 * once, without waiting for the next batch from Health Services.
 */
class HealthValues(
    private val prefs: SharedPreferences,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Called with the new value each time [faceWantsHeartRate] changes. Set once, by
     * `PassiveHealthRegistration.start` (not in this file's phone copy, see wear/cwfrender).
     */
    var onFaceWishChanged: ((Boolean) -> Unit)? = null

    /** Keeps [bpm] measured at [atMs] when it is newer than the value kept. */
    fun recordHeartRate(bpm: Int, atMs: Long): Boolean {
        if (atMs <= storedTime()) return false
        prefs.edit { putInt(KEY_HR, bpm); putLong(KEY_HR_AT, atMs) }
        return true
    }

    /** The heart rate, or null when there is none or it is older than [HEART_RATE_MAX_AGE_MS]. */
    fun heartRate(): Int? {
        val at = storedTime()
        if (at == 0L || now() - at > HEART_RATE_MAX_AGE_MS) return null
        return prefs.getInt(KEY_HR, 0)
    }

    /**
     * The time of the kept heart rate, or 0 when there is none. A time later than now (plus
     * [CLOCK_MARGIN_MS]) also counts as none: it was stored while the clock was ahead, and trusting it
     * would show an old value as current and refuse every new value until the clock passes it.
     */
    private fun storedTime(): Long {
        val at = prefs.getLong(KEY_HR_AT, 0L)
        return if (at > now() + CLOCK_MARGIN_MS) 0L else at
    }

    /**
     * True while the design of the Custom watch face uses `bpm`, so only then the heart rate is asked
     * from Health Services. Written each time the face reads its design; a change calls [onFaceWishChanged].
     */
    var faceWantsHeartRate: Boolean
        get() = prefs.getBoolean(KEY_FACE_WANTS_HR, false)
        set(value) {
            if (value == faceWantsHeartRate) return
            prefs.edit { putBoolean(KEY_FACE_WANTS_HR, value) }
            onFaceWishChanged?.invoke(value)
        }

    /**
     * Whether AAPS has a registration with Health Services that may still be active. Kept so a watch
     * whose face never used `bpm` never talks to Health Services at all.
     */
    var passiveRegistered: Boolean
        get() = prefs.getBoolean(KEY_REGISTERED, false)
        set(value) = prefs.edit { putBoolean(KEY_REGISTERED, value) }

    companion object {

        /** Heart rate is a value of the moment: older than this it is not used any more. */
        const val HEART_RATE_MAX_AGE_MS = 30 * 60_000L

        /** How far a stored time may be after now before it counts as stored with a wrong clock. */
        const val CLOCK_MARGIN_MS = 5 * 60_000L

        /** The name of the preference file, see `HealthModule`. */
        const val FILE = "aaps_health_values"

        private const val KEY_HR = "heart_rate"
        private const val KEY_HR_AT = "heart_rate_at"
        private const val KEY_FACE_WANTS_HR = "face_wants_heart_rate"
        private const val KEY_REGISTERED = "passive_registered"
    }
}
