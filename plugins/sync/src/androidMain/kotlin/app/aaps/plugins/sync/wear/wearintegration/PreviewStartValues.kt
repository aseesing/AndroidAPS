// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The values the live preview of the watch face starts with, each time it opens ([now]).
 *
 * They are the values the phone last sent to the watch ([sent]). Two things can be missing in them:
 *
 * - Right after a start of the app the phone has sent nothing yet. The units, the status (IOB, COB,
 *   basal), the treatments and the weather all come with a resend, and the first one waits for the
 *   first calculation. Until then the preview drew the glucose in mg/dl, also for a wearer who uses
 *   mmol/l, and showed no status. So when the preferences or the status were not sent yet, the first
 *   resend is done here ([firstResend]), once in a run of the app. It is the same resend the watch
 *   would get a moment later, built by the same code, so the preview and the watch show the same.
 * - The phone sends the glucose only once it has its table of readings. So the last glucose the phone
 *   has now ([lastBg]) is added when it is newer than the one sent, or when none was sent, and the
 *   graph ([graph]) when none was sent.
 *
 * @param sent the values last sent to the watch, read again after the resend
 * @param firstResend sends everything to the watch, as after a new glucose value
 * @param lastBg the last glucose of the phone as the message for the watch, or null when it has none
 * @param graph builds the graph message; only called when no graph was sent
 */
internal class PreviewStartValues(
    private val sent: () -> List<EventData>,
    private val firstResend: suspend () -> Unit,
    private val lastBg: suspend () -> EventData.SingleBg?,
    private val graph: suspend () -> EventData.GraphData?
) {

    /** True once the first resend was asked for here: never twice, also when it brought nothing. */
    private val resendAsked = AtomicBoolean(false)

    /** What the phone has for the watch face now. */
    suspend fun now(): List<EventData> {
        if (firstResendMissing(sent()) && resendAsked.compareAndSet(false, true)) {
            try {
                firstResend()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The preview then shows what the phone has, and the rest with the next resend
            }
        }
        val values = sent()
        val sentBg = values.firstOrNull { it is EventData.SingleBg && it.dataset == 0 } as EventData.SingleBg?
        val newerBg = lastBg()?.takeIf { sentBg == null || it.timeStamp > sentBg.timeStamp }
        val missingGraph = if (values.none { it is EventData.GraphData }) graph() else null
        return values.filterNot { newerBg != null && it === sentBg } + listOfNotNull(newerBg, missingGraph)
    }

    /** True while the phone has not sent its preferences (the units) or its own status in this run. */
    private fun firstResendMissing(values: List<EventData>): Boolean =
        values.none { it is EventData.Preferences } || values.none { it is EventData.Status && it.dataset == 0 }
}
