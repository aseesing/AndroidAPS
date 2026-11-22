// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.PushedWatchfaceId

/**
 * The name of the Watch Face Format face that the watch shows now, for the line "On the watch: ...",
 * or null when the watch shows something else.
 *
 * The watch keeps a face from a face zip also while it shows another face, and it reports the name
 * all that time (`designName`), so the phone can offer the face as a choice. That name alone does
 * not say what is on the screen. The face is named here only when nothing speaks against it:
 * - the phone sent a design (a CWF) to the watch after the face ([designSentAfter], see
 *   `WffFaceCover`): not shown. The phone knows this by itself, so the line goes at once, also on a
 *   watch that never says whether the face is on its screen;
 * - the watch says the face is not on its screen (`designShown` false): not shown;
 * - a watch with Watch Face Push holds another face in its slot: not shown;
 * - else it is shown. An older watch app does not send `designShown`; then the rules above decide.
 *
 * The design the watch shows instead is not named in this line: its card right below already shows
 * its name and picture, and two names for one watch would be one too many.
 */
internal fun wffFaceOnWatch(status: EventData.WatchFacePushStatus?, designSentAfter: Boolean): String? {
    val name = status?.designName ?: return null
    return when {
        designSentAfter                                                          -> null
        status.designShown == false                                              -> null
        status.supported && status.installedFace != PushedWatchfaceId.DESIGN -> null
        else                                                                     -> name
    }
}
