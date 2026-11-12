// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * A view of a Custom watch face design that moves along an arc, like the sun along the edge of the
 * screen. Read from the optional `orbit` key of a view in the design json:
 *
 * `"orbit": {"valueKey": "sun_progress", "centerX": 200, "centerY": 200, "radius": 188, "startAngle": 200, "endAngle": 340}`
 *
 * The value (0..1) of [valueKey] places the middle of the view on the arc: 0 at [startAngle], 1 at
 * [endAngle]. Angles are in degrees on the screen: 0 is to the right, 90 down, 180 left, 270 up, so
 * 200 to 340 goes from the left over the top to the right. All in the 400 x 400 design space.
 */
data class Orbit(
    val valueKey: String,
    val centerX: Float = 200f,
    val centerY: Float = 200f,
    val radius: Float = 188f,
    val startAngle: Float = 200f,
    val endAngle: Float = 340f
) {

    /** The point on the arc for [value] (cut to 0..1), in the design space. */
    fun point(value: Double): Pair<Float, Float> {
        val angle = Math.toRadians(startAngle + (endAngle - startAngle) * value.coerceIn(0.0, 1.0))
        return Pair(centerX + radius * cos(angle).toFloat(), centerY + radius * sin(angle).toFloat())
    }

    companion object {

        /** The key of an orbit in a view of the design json. */
        const val JSON_KEY = "orbit"

        /** The orbit of a view, or null when it has none or no value key. */
        fun read(view: JSONObject?): Orbit? {
            val json = view?.optJSONObject(JSON_KEY) ?: return null
            val key = json.optString("valueKey").takeIf { it.isNotEmpty() } ?: return null
            val default = Orbit(key)
            fun float(name: String, fallback: Float) = json.optDouble(name, fallback.toDouble()).toFloat()
            return Orbit(
                valueKey = key,
                centerX = float("centerX", default.centerX),
                centerY = float("centerY", default.centerY),
                radius = float("radius", default.radius),
                startAngle = float("startAngle", default.startAngle),
                endAngle = float("endAngle", default.endAngle)
            )
        }
    }
}
