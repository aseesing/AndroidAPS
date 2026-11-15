// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import app.aaps.shared.impl.weardata.JsonKeys
import org.json.JSONObject

/**
 * A picture in the seconds view that turns a step each second, like a small wheel of a watch movement.
 *
 * The design gives the `second` view a picture (and see-through text) and [JSON_KEY]: the degrees it
 * turns each second, round the middle of the view. The seconds are drawn every second anyway, so
 * this costs nothing more. Pure functions only.
 */
object SecondSpin {

    /** The key in the view json of `second`: degrees per second. */
    const val JSON_KEY = "spinPerSecond"

    /** The rotation of the seconds view at [second] (0..59), or null when the design does not turn it. */
    fun rotation(viewJson: JSONObject?, second: Int): Float? {
        if (viewJson == null || !viewJson.has(JSON_KEY)) return null
        val perSecond = viewJson.optDouble(JSON_KEY, 0.0).takeIf { it.isFinite() } ?: return null
        return ((viewJson.optInt(JsonKeys.ROTATION.key) + perSecond * second) % 360).toFloat()
    }
}
