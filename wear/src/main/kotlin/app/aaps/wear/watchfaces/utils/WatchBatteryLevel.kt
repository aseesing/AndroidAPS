// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * The battery level of the watch itself, for the value `watch_battery` of a Custom watch face design.
 *
 * Read from the last battery broadcast that the system keeps (a sticky intent). No receiver is
 * registered and nothing runs in the background. The level changes slowly, so a read is kept for
 * [MAX_AGE_MS]: a frame asks for the value several times, and this way it costs one system call.
 *
 * @param context any context of the watch app
 * @param clock a time that never jumps, in ms
 */
class WatchBatteryLevel(private val context: Context, private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {

    private var cached: Double? = null
    private var readAt = NEVER

    /** The battery level in percent (0 to 100), or null when the system does not tell it. */
    fun percent(): Double? {
        val now = clock()
        if (readAt != NEVER && now - readAt in 0 until MAX_AGE_MS) return cached
        cached = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()?.let {
            percentOf(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
        }
        readAt = now
        return cached
    }

    companion object {

        /** The value key in the design json. */
        const val KEY = "watch_battery"

        /** How long one read is used. */
        const val MAX_AGE_MS = 30_000L

        private const val NEVER = Long.MIN_VALUE

        /** [level] of [scale] as a whole percent, or null when the values are not valid. */
        fun percentOf(level: Int, scale: Int): Double? =
            if (level < 0 || scale <= 0) null else (level * 100.0 / scale).roundToInt().coerceIn(0, 100).toDouble()
    }
}
