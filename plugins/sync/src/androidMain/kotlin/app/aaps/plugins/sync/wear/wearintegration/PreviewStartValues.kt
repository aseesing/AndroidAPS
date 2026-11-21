// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData

/**
 * The values the live preview of the watch face starts with, each time it opens.
 *
 * They are the values the phone last sent to the watch ([sent]). But the phone sends the glucose
 * only with a resend, and only once it has its table of readings, so right after a start of the app
 * there can be a status and no glucose. The preview then showed "no data" until the next resend. So
 * the last glucose the phone has now ([lastBg]) is added when it is newer than the one sent, or when
 * none was sent, and the graph ([graph]) when none was sent.
 *
 * @param lastBg the last glucose of the phone as the message for the watch, or null when it has none
 * @param graph builds the graph message; only called when no graph was sent
 */
internal suspend fun previewStartValues(sent: List<EventData>, lastBg: EventData.SingleBg?, graph: suspend () -> EventData.GraphData?): List<EventData> {
    val sentBg = sent.firstOrNull { it is EventData.SingleBg && it.dataset == 0 } as EventData.SingleBg?
    val newerBg = lastBg?.takeIf { sentBg == null || it.timeStamp > sentBg.timeStamp }
    val missingGraph = if (sent.none { it is EventData.GraphData }) graph() else null
    return sent.filterNot { newerBg != null && it === sentBg } + listOfNotNull(newerBg, missingGraph)
}
