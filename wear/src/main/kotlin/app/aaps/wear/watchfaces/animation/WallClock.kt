// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import java.time.Instant
import java.time.ZoneId

/**
 * The time of day of a clock time in [zone]: the variables `sec`, `min` and `hour` of a formula.
 *
 * The same numbers as `Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()`, but without making
 * objects every frame: the offset of the zone is looked up once and kept until the next change of the
 * clock in that zone (summer time), or until the time goes back.
 *
 * Not thread safe.
 */
class WallClock {

    /** The zone of the clock; null until it is set. Setting it forgets the kept offset. */
    var zone: ZoneId? = null
        set(value) {
            field = value
            validFrom = Long.MAX_VALUE
            validUntil = Long.MIN_VALUE
        }

    /** The seconds of the minute, with the part of a second: 0 to below 60. */
    var second = 0.0
        private set

    /** The minute of the hour, 0 to 59. */
    var minute = 0.0
        private set

    /** The hour of the day, 0 to 23. */
    var hour = 0.0
        private set

    /** The number of the day, counted from 1970 in [zone]: another number on another day. */
    var day = 0.0
        private set

    private var offsetMs = 0L

    /** From when (including) until when (not including) [offsetMs] holds, in epoch ms. */
    private var validFrom = Long.MAX_VALUE
    private var validUntil = Long.MIN_VALUE

    /** Sets [second], [minute] and [hour] for [epochMs]. [zone] must be set. */
    fun set(epochMs: Long) {
        if (epochMs < validFrom || epochMs >= validUntil) findOffset(epochMs)
        val msOfDay = Math.floorMod(epochMs + offsetMs, DAY_MS)
        val secondOfDay = (msOfDay / 1000).toInt()
        val nanos = (msOfDay % 1000).toInt() * 1_000_000
        // The same sum as LocalTime gives: whole seconds plus nanoseconds / 1e9
        second = secondOfDay % 60 + nanos / 1e9
        minute = (secondOfDay / 60 % 60).toDouble()
        hour = (secondOfDay / 3600).toDouble()
        day = Math.floorDiv(epochMs + offsetMs, DAY_MS).toDouble()
    }

    private fun findOffset(epochMs: Long) {
        val rules = checkNotNull(zone) { "no zone" }.rules
        val instant = Instant.ofEpochMilli(epochMs)
        offsetMs = rules.getOffset(instant).totalSeconds * 1000L
        // Up to the next change of the offset; a time before this one looks it up again
        validFrom = epochMs
        validUntil = rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
    }

    private companion object {

        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
