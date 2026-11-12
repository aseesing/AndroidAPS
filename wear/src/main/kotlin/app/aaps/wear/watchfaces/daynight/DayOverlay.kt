// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.CwfResDataMap
import org.json.JSONObject

/**
 * The day look and the weather looks of a Custom watch face design. Private patch.
 *
 * A design can have two optional objects:
 * - [JSON_KEY] (`dayLook`): laid over the design from sunrise to sunset,
 * - [WEATHER_KEY] (`weatherLooks`): looks for the weather, by the name [lookName] gives, like
 *   `day_cloudy` or `night_rain`. The one that fits is laid over the result last.
 *
 * Laying one object over another:
 * - an object is merged key by key into the object of the same key, as deep as it goes,
 * - `null` removes the key (for example `"rain": null` in the animation),
 * - every other value replaces the value of the design.
 *
 * Pictures: in the day a picture whose name ends with [IMAGE_SUFFIX] replaces the picture without
 * it (`BackgroundDay` is shown instead of `Background`). And a look can name pictures to swap
 * under [IMAGES_KEY]: `"images": {"Background": "BackgroundRain"}` shows `BackgroundRain` where
 * the design shows `Background`.
 *
 * Pure functions only; the watch face decides when it is day and what the weather is.
 */
object DayOverlay {

    /** The key of the day look in the design json. */
    const val JSON_KEY = "dayLook"

    /** The key of the weather looks in the design json. */
    const val WEATHER_KEY = "weatherLooks"

    /** The key in a look that names the pictures to swap. */
    const val IMAGES_KEY = "images"

    /** The end of the name of a picture that replaces another one in the day. */
    const val IMAGE_SUFFIX = "Day"

    /** True when [design] has a day look at all. */
    fun hasDayLook(design: JSONObject): Boolean = design.optJSONObject(JSON_KEY) != null

    /** True when [design] has a look for any weather. */
    fun hasWeatherLooks(design: JSONObject): Boolean = design.optJSONObject(WEATHER_KEY) != null

    /** The name of the weather look for [day] or night and [weather], like `day_rain`. */
    fun lookName(day: Boolean, weather: String): String = (if (day) "day_" else "night_") + weather

    /**
     * [design] as it looks in the [day] or at night, in [weather] (null when it is not known), as a
     * new object; [design] itself is not changed. The looks themselves are left out of the result.
     * The pictures to swap are in the result under [IMAGES_KEY], for [resources].
     */
    fun apply(design: JSONObject, day: Boolean = true, weather: String? = null): JSONObject {
        val result = deepCopy(design)
        result.remove(JSON_KEY)
        result.remove(WEATHER_KEY)
        result.remove(IMAGES_KEY)
        if (day) design.optJSONObject(JSON_KEY)?.let { merge(result, it) }
        weather?.let { design.optJSONObject(WEATHER_KEY)?.optJSONObject(lookName(day, it)) }?.let { merge(result, it) }
        return result
    }

    /** [resources] with every `XDay` picture put in the place of `X`. The `XDay` entries stay too. */
    fun dayResources(resources: CwfResDataMap): CwfResDataMap {
        val result: CwfResDataMap = resources.toMutableMap()
        resources.forEach { (name, data) ->
            if (name.length > IMAGE_SUFFIX.length && name.endsWith(IMAGE_SUFFIX))
                result[name.removeSuffix(IMAGE_SUFFIX)] = data
        }
        return result
    }

    /**
     * The pictures for a [look] made by [apply]: in the [day] the `XDay` pictures in the place of `X`,
     * and then the swaps the look names under [IMAGES_KEY]. A swap to a picture the zip does not
     * have is left out.
     */
    fun resources(resources: CwfResDataMap, look: JSONObject, day: Boolean): CwfResDataMap {
        val result = if (day) dayResources(resources) else resources.toMutableMap()
        look.optJSONObject(IMAGES_KEY)?.let { swaps ->
            swaps.keys().forEach { target -> resources[swaps.optString(target)]?.let { result[target] = it } }
        }
        return result
    }

    private fun merge(target: JSONObject, overlay: JSONObject) {
        overlay.keys().forEach { key ->
            val value = overlay.opt(key)
            val current = target.optJSONObject(key)
            when {
                value == null || value == JSONObject.NULL -> target.remove(key)
                value is JSONObject && current != null    -> merge(current, value)
                value is JSONObject                       -> target.put(key, deepCopy(value))
                else                                      -> target.put(key, value)
            }
        }
    }

    private fun deepCopy(source: JSONObject): JSONObject = JSONObject(source.toString())
}
