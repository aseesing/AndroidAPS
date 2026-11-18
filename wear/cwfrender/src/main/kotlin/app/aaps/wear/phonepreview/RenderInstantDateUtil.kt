// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import app.aaps.core.interfaces.utils.DateUtil

/**
 * The [DateUtil] of the watch face on the phone: "now" is the instant the face draws for, when it draws
 * for one (see `CustomWatchface.setRenderInstant`), else the real clock.
 *
 * So the clock and date fields of the upstream `BaseWatchFace.setDateAndTime`, which ask for the texts
 * of now, show the instant of the preview frame, without a change to that upstream code. Everything
 * else goes to [real].
 *
 * @param instant the instant the face draws for now, or null for the real clock
 */
class RenderInstantDateUtil(private val real: DateUtil, private val instant: () -> Long?) : DateUtil by real {

    override fun now(): Long = instant() ?: real.now()

    override fun timeString(): String = real.timeString(now())
    override fun minuteString(): String = real.minuteString(now())
    override fun hourString(): String = real.hourString(now())
    override fun amPm(): String = real.amPm(now())
    override fun dayNameString(format: String): String = real.dayNameString(now(), format)
    override fun dayString(): String = real.dayString(now())
    override fun monthString(format: String): String = real.monthString(now(), format)
    override fun weekString(): String = real.weekString(now())
}
