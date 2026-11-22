// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.keys.WffFaceCoverKey

/**
 * What the phone knows by itself about the Watch Face Format face on the watch, for the line
 * "On the watch: ..." (see `wffFaceOnWatch`).
 *
 * The watch says whether the face is on its screen (`designShown`), but not every watch does, and
 * not always in time: an older watch app says nothing, and a watch that had another face on the
 * screen when its app was updated has nothing to say until the wearer picks the face again. So the
 * line does not rest on the watch alone. When the phone sends a design (a CWF) to the watch, the
 * wearer wants that design on the watch, and the phone stops naming the face at once ([designSent]).
 * Only for a watch without Watch Face Push: with it the watch says which face its slot holds.
 * It names it again when:
 * - the wearer sends the face zip again, or chooses the face on the phone ([faceChosen]);
 * - the watch says the face came on its screen, after it said it was not ([watchReported]). A watch
 *   that only ever says "shown" does not count: that can be an old state.
 *
 * The state is in the preferences, so it lasts over a restart of the app. An object of this class
 * holds nothing itself: make one where it is needed.
 */
class WffFaceCover(private val preferences: Preferences) {

    /** True while a design the phone sent is the last thing it knows: the face is then not named. */
    val designSentAfter: Boolean get() = preferences.get(WffFaceCoverKey.DesignSentAfter)

    /** The phone sent a design for the main face to the watch. */
    fun designSent() = set(WffFaceCoverKey.DesignSentAfter, true)

    /** The phone sent the face zip to the watch, or the wearer chose that face on the phone. */
    fun faceChosen() = set(WffFaceCoverKey.DesignSentAfter, false)

    /** The watch sent [status]. */
    fun watchReported(status: EventData.WatchFacePushStatus) {
        when (status.designShown) {
            false -> set(WffFaceCoverKey.WatchSaidNotShown, true)

            true  -> if (preferences.get(WffFaceCoverKey.WatchSaidNotShown)) {
                set(WffFaceCoverKey.WatchSaidNotShown, false)
                set(WffFaceCoverKey.DesignSentAfter, false)
            }

            null  -> Unit
        }
    }

    /** Writes only a change: the watch reports with every sync. */
    private fun set(key: WffFaceCoverKey, value: Boolean) {
        if (preferences.get(key) != value) preferences.put(key, value)
    }
}
