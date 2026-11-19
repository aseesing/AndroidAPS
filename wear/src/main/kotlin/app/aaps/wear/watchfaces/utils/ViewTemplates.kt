// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.animation.DESIGN_SIZE
import app.aaps.wear.watchfaces.animation.LayerAnimator
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.LayerSource
import app.aaps.wear.watchfaces.animation.PulseCurve
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * The picture of a view that shows a layer template: `"image": {"use": "moon"}` (see [ViewImages]).
 * The layers of the template are drawn on a square picture as big as the shorter side of the view,
 * where the whole 400 x 400 design space of the layers is that square. So a template that fills the
 * design space by default, like `moon`, fills the view.
 *
 * The layers are drawn still, as layers that stay on the face are (`rest` 0: the start of a wake, with
 * `env` 0), with the values of now. View layers of the template are left out. A new picture is drawn
 * only when a value the layers read changed, so a style pass every minute costs nearly nothing.
 *
 * @param read reads a design json with only an `animation` into its layers, as the face reads its own
 * @param images the app pictures of the layers, as [LayerAnimator] takes them
 * @param value the value of a value key now, or null
 */
class ViewTemplates(
    private val resources: Resources,
    private val read: (JSONObject) -> WakeAnimationSpec?,
    private val images: (String, Long, Int) -> Bitmap?,
    private val value: (String) -> Double?
) {

    private class Entry(val use: String, val size: Int, val animator: LayerAnimator) {

        var values: DoubleArray? = null
        var drawable: Drawable? = null
    }

    private val entries = HashMap<String, Entry>()

    /**
     * The picture of the view [key] of [width] x [height] pixels that shows the template [use]
     * (`{"use": "name", ...}` with its parameters) at the clock time [nowMs]; null when it cannot be made.
     */
    fun drawable(key: String, use: JSONObject, width: Int, height: Int, nowMs: Long): Drawable? {
        val size = minOf(width, height).coerceAtMost(MAX_SIZE)
        if (size <= 0) return null
        val text = use.toString()
        val entry = entries[key]?.takeIf { it.use == text && it.size == size } ?: run {
            entries.remove(key)?.animator?.release()
            val spec = read(JSONObject().put(WakeAnimationSpec.JSON_KEY, JSONObject().put("layers", JSONArray().put(JSONObject(text)))))?.normalized() ?: return null
            Entry(text, size, LayerAnimator(spec.layers.filter { it.source !is LayerSource.View }, spec.lets, images = images)).also { entries[key] = it }
        }
        if (entry.animator.isEmpty) return null
        val keys = entry.animator.valueKeys
        val now = DoubleArray(keys.size) { value(keys[it]) ?: Double.NaN }
        if (entry.drawable == null || !now.contentEquals(entry.values)) {
            // A new picture each time: the view may still show the one before
            val bitmap = createBitmap(size, size)
            val canvas = Canvas(bitmap)
            val scale = size / DESIGN_SIZE
            entry.animator.setWakeValues(value, PulseCurve.FALLBACK_BPM)
            entry.animator.setTime(0, WakeAnimationSpec.MAX_DURATION_MS, 0f, nowMs)
            for (slot in LayerSlot.entries) entry.animator.drawSlot(canvas, slot, scale)
            entry.values = now
            entry.drawable = BitmapDrawable(resources, bitmap)
        }
        return entry.drawable
    }

    /** Frees the pictures the layers keep. Only when nothing shows them any more, like when the face goes. */
    fun release() {
        for (entry in entries.values) entry.animator.release()
        entries.clear()
    }

    private companion object {

        /** The largest side of the picture of a view, so a mistake cannot fill the memory. */
        const val MAX_SIZE = 512
    }
}
