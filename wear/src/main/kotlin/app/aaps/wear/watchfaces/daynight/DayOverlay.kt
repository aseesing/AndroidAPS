package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.CwfResDataMap
import org.json.JSONObject

/**
 * The day look of a Custom watch face design. Private patch.
 *
 * A design can have an optional object under [JSON_KEY]. In the day it is laid over the design:
 * - an object is merged key by key into the object of the same key, as deep as it goes,
 * - `null` removes the key (for example `"rain": null` in the animation),
 * - every other value replaces the value of the design.
 *
 * And a picture of the zip whose name ends with [IMAGE_SUFFIX] replaces the picture without it:
 * `BackgroundDay` is shown instead of `Background`, `Moon05Day` instead of `Moon05`.
 *
 * Pure functions only; the watch face decides when it is day.
 */
object DayOverlay {

    /** The key of the day overlay in the design json. */
    const val JSON_KEY = "dayLook"

    /** The end of the name of a picture that replaces another one in the day. */
    const val IMAGE_SUFFIX = "Day"

    /** True when [design] has a day look at all. */
    fun hasDayLook(design: JSONObject): Boolean = design.optJSONObject(JSON_KEY) != null

    /**
     * [design] with its day overlay laid over it, as a new object; [design] itself is not changed.
     * Without an overlay this is a copy of [design]. The overlay itself is left out of the result.
     */
    fun apply(design: JSONObject): JSONObject {
        val result = deepCopy(design)
        result.remove(JSON_KEY)
        design.optJSONObject(JSON_KEY)?.let { merge(result, it) }
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
