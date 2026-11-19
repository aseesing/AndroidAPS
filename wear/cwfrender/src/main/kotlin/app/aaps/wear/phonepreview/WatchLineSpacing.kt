package app.aaps.wear.phonepreview

import android.text.BoringLayout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Makes the text views of a face on the phone place their lines as the watch does.
 *
 * Android keeps old behaviour for an app that targets an old Android version. The watch app targets
 * Android 11 (`wearTargetSdk` 30). There a text of one line (a [BoringLayout]) starts at the top of
 * the font box, while a longer text starts at the ascent of the font when that is higher ("fallback
 * line spacing"). An app that targets Android 13 or newer, as the phone app does, uses the ascent for
 * both (compat change 210923482). For most fonts this is the same line, but in a font whose box ends
 * below its ascent, a text of one line lies higher on the watch than in the preview.
 *
 * [TextView.setFallbackLineSpacing] on the phone switches it on for both kinds or off for both. So
 * each view gets the rule the watch uses for its kind: off for a text of one line, on for a longer
 * one. Pure function: it changes only the views it is given.
 */
object WatchLineSpacing {

    /**
     * Gives every text view under [root] the line spacing of the watch, as its text is laid out now.
     *
     * @return true when a view changed: then its lines lie somewhere else, so draw the face again
     */
    fun match(root: View): Boolean {
        var changed = false
        fun visit(view: View) {
            if (view is TextView) {
                val layout = view.layout
                // A view that was never laid out (hidden) gets its rule when it shows
                if (layout != null) {
                    val fallback = layout !is BoringLayout
                    if (view.isFallbackLineSpacing != fallback) {
                        view.isFallbackLineSpacing = fallback
                        changed = true
                    }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        return changed
    }
}
