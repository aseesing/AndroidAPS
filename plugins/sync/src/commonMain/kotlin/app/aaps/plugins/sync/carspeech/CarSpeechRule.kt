// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.carspeech

import kotlin.math.abs

/**
 * When the car speech plugin says a glucose reading out loud.
 *
 * Based on the rule of xDrip+ `BgToSpeech` (GPLv3, NightscoutFoundation/xDrip), changed as agreed:
 * - only while the phone is in the car (vehicle mode),
 * - never a reading older than [MAX_READING_AGE_MS], so a late backfill is not read out,
 * - a value outside the range (too low or too high) is said, and repeated at most every
 *   [Settings.outOfRangeRepeatMs],
 * - a value inside the range is said only when it moved at least [Settings.changeThresholdMgdl]
 *   away from the value said last time, or when it just came back from a low or a high.
 *
 * Pure logic, so it is the same on every platform and can be tested without a phone.
 */
object CarSpeechRule {

    /** xDrip+ does not read out values older than 4 minutes. */
    const val MAX_READING_AGE_MS = 4 * 60 * 1000L

    data class Settings(
        val lowMgdl: Double,
        val highMgdl: Double,
        val changeThresholdMgdl: Double,
        val outOfRangeRepeatMs: Long
    )

    /** What was said last time. `null` when nothing was said yet in this car trip. */
    data class LastSpoken(val valueMgdl: Double, val timestamp: Long)

    enum class Reason {

        /** Not in the car, or the reading is too old: say nothing. */
        NONE,

        /** Below the low mark. */
        LOW,

        /** Above the high mark. */
        HIGH,

        /** First reading of this car trip. */
        FIRST,

        /** Inside the range and moved enough since the value said last time. */
        CHANGED
    }

    fun decide(
        inVehicle: Boolean,
        valueMgdl: Double,
        readingTimestamp: Long,
        now: Long,
        settings: Settings,
        lastSpoken: LastSpoken?
    ): Reason {
        if (!inVehicle) return Reason.NONE
        if (now - readingTimestamp > MAX_READING_AGE_MS) return Reason.NONE
        // The same reading is never said twice (a database update can report it again)
        if (lastSpoken != null && readingTimestamp <= lastSpoken.timestamp) return Reason.NONE

        val outOfRange = when {
            valueMgdl < settings.lowMgdl  -> Reason.LOW
            valueMgdl > settings.highMgdl -> Reason.HIGH
            else                          -> null
        }
        if (outOfRange != null) {
            val repeatDue = lastSpoken == null || readingTimestamp - lastSpoken.timestamp >= settings.outOfRangeRepeatMs
            // Leaving the range is a change too: say it at once, even inside the repeat time
            val justLeftRange = lastSpoken != null && lastSpoken.valueMgdl >= settings.lowMgdl && lastSpoken.valueMgdl <= settings.highMgdl
            return if (repeatDue || justLeftRange) outOfRange else Reason.NONE
        }
        if (lastSpoken == null) return Reason.FIRST
        // Back in range after a low or a high is a change, also when the step is small
        val wasOutOfRange = lastSpoken.valueMgdl < settings.lowMgdl || lastSpoken.valueMgdl > settings.highMgdl
        val movedEnough = abs(valueMgdl - lastSpoken.valueMgdl) >= settings.changeThresholdMgdl
        return if (wasOutOfRange || movedEnough) Reason.CHANGED else Reason.NONE
    }
}
