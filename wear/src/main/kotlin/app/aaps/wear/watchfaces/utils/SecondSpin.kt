// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.view.View
import app.aaps.shared.impl.weardata.JsonKeys
import org.json.JSONObject

/**
 * A picture in the seconds view that turns a step each second, like a small wheel of a watch movement.
 *
 * The design gives the `second` view a picture (and see-through text) and [JSON_KEY]: the degrees it
 * turns each second, round the middle of the view. The seconds are drawn every second anyway, so
 * this costs nothing more.
 *
 * With [BOX_12H_KEY] the view is drawn smaller and somewhere else when the watch shows 12 hours, to
 * make room for AM / PM. Only stateless functions; [place] sets the view.
 */
object SecondSpin {

    /** The key in the view json of `second`: degrees per second. */
    const val JSON_KEY = "spinPerSecond"

    /**
     * The key in the view json of `second`: the box (`left`, `top`, `width`, in design pixels) the view
     * is drawn in when the watch shows 12 hours. The view keeps its shape: only the width counts.
     */
    const val BOX_12H_KEY = "box12h"

    /** How the view is drawn: [scale] round its middle, and moved by [dx] and [dy] design pixels. */
    data class Placement(val scale: Float, val dx: Float, val dy: Float)

    /** No change: the view as the design places it. */
    val AS_DESIGNED = Placement(1f, 0f, 0f)

    /**
     * Where the seconds view is drawn, with 24 hours ([is24h]) or 12. Null when the design has no
     * [BOX_12H_KEY] or no size.
     */
    fun placement(viewJson: JSONObject?, is24h: Boolean): Placement? {
        val box = viewJson?.optJSONObject(BOX_12H_KEY) ?: return null
        val width = viewJson.optDouble(JsonKeys.WIDTH.key, 0.0)
        val height = viewJson.optDouble(JsonKeys.HEIGHT.key, 0.0)
        if (width <= 0 || height <= 0) return null
        if (is24h) return AS_DESIGNED
        val scale = box.optDouble("width", width) / width
        val dx = box.optDouble("left", 0.0) + scale * width / 2 - (viewJson.optDouble(JsonKeys.LEFTMARGIN.key, 0.0) + width / 2)
        val dy = box.optDouble("top", 0.0) + scale * height / 2 - (viewJson.optDouble(JsonKeys.TOPMARGIN.key, 0.0) + height / 2)
        return Placement(scale.toFloat(), dx.toFloat(), dy.toFloat())
    }

    /** Draws [view] as [placement] says, on a face of [zoom] screen pixels per design pixel. */
    fun place(view: View, placement: Placement?, zoom: Double) {
        placement ?: return
        view.scaleX = placement.scale
        view.scaleY = placement.scale
        view.translationX = (placement.dx * zoom).toFloat()
        view.translationY = (placement.dy * zoom).toFloat()
    }

    /** The rotation of the seconds view at [second] (0..59), or null when the design does not turn it. */
    fun rotation(viewJson: JSONObject?, second: Int): Float? {
        if (viewJson == null || !viewJson.has(JSON_KEY)) return null
        val perSecond = viewJson.optDouble(JSON_KEY, 0.0).takeIf { it.isFinite() } ?: return null
        return ((viewJson.optInt(JsonKeys.ROTATION.key) + perSecond * second) % 360).toFloat()
    }
}
