// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import android.content.Context

/**
 * What the watch knows about whether the Watch Face Format face from a face zip is on its screen.
 * Kept on disk: the app's process comes and goes.
 *
 * Two signs, both from the system:
 * - a complication source of the face is in use or no longer in use ([activated], [deactivated]),
 *   see `WffFaceShown`;
 * - a code-based face of this app (the Custom watchface and the others) is on the screen
 *   ([otherFaceShown]). Only one face is on the screen, so the face from the zip is not. This sign
 *   does not need the system to say that it let go of a complication, which it does not always do.
 *
 * @param context any context of the wear app
 */
class WffShownSlots(private val context: Context) {

    private val prefs get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True while a complication of the face is in use, false when none is, null when the system never said. */
    val shown: Boolean? get() = prefs.getStringSet(SLOTS, null)?.isNotEmpty()

    /** The system started to use the complication [slot] of the face. */
    fun activated(slot: String) = change { it + slot }

    /** The system stopped using the complication [slot] of the face. */
    fun deactivated(slot: String) = change { it - slot }

    /**
     * A code-based face of this app came on the screen. Writes only when that is news, so it costs
     * nothing on the other wakes. Never throws: it is called while a watch face starts to draw.
     */
    fun otherFaceShown() {
        try {
            if (shown != false) change { emptySet() }
        } catch (_: Exception) {
            // The phone then keeps the line a little longer; the face must draw
        }
    }

    private fun change(next: (Set<String>) -> Set<String>) = synchronized(LOCK) {
        prefs.edit().putStringSet(SLOTS, next(prefs.getStringSet(SLOTS, null) ?: emptySet())).commit()
    }

    private companion object {

        const val FILE = "wff_face_shown"
        const val SLOTS = "slots"

        /** One writer at a time, also between two objects of this class: the sources and the faces each make their own. */
        val LOCK = Any()
    }
}
