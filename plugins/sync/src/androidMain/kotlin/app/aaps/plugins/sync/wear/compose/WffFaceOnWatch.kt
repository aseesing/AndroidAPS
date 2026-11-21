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
 * - the watch says its system took the face off the screen (`designShown` false): not shown;
 * - a watch with Watch Face Push holds another face in its slot: not shown;
 * - else it is shown. An older watch app does not send `designShown`; then the rule above decides
 *   with Watch Face Push, and without it the installed face is named as before.
 */
internal fun wffFaceOnWatch(status: EventData.WatchFacePushStatus?): String? {
    val name = status?.designName ?: return null
    return when {
        status.designShown == false                                              -> null
        status.supported && status.installedFace != PushedWatchfaceId.DESIGN -> null
        else                                                                     -> name
    }
}
