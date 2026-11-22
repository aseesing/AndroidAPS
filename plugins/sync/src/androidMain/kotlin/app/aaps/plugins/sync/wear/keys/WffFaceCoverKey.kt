// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

/**
 * What the phone itself knows about the Watch Face Format face on the watch, see `WffFaceCover`.
 * Kept, so the phone knows it after a restart too. Not exported: it is about the watch of this phone.
 */
enum class WffFaceCoverKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = false
) : BooleanNonPreferenceKey {

    /** True from the moment the phone sent a design to the watch, until the face from a face zip is chosen or seen again. */
    DesignSentAfter(key = "wear_wff_face_design_sent_after", defaultValue = false),

    /** True when the last word of the watch was that the face is not on its screen. */
    WatchSaidNotShown(key = "wear_wff_face_watch_said_not_shown", defaultValue = false),
    ;
}
