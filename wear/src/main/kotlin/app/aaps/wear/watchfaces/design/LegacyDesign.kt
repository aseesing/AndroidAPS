// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.design

import app.aaps.shared.impl.weardata.CwfPlaceNeeds
import app.aaps.wear.watchfaces.animation.BuiltInTemplates
import app.aaps.wear.watchfaces.animation.Templates
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.daynight.StoredWeather
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reads the older design keys: rewrites a design json with the old personal keys into the new form when
 * it is read, so the existing design zips keep working unchanged. This is the only place that knows the
 * old keys; the engine knows only the new ones. To be removed once the designs are ported (see
 * ANIMATION_TODO.md).
 *
 * What it rewrites:
 * - the effects of the `animation` (`rain`, `glitch`, `trace`, `blink`, `sweep`, `slide`, `wobble`,
 *   `pulse`, `glint`) become `{"use": "<name>", ...}` entries at the start of `layers`, in that order
 *   (a list effect gives one entry per view); the `fallbackBpm` of `pulse` goes to the animation;
 * - a layer `"moon": true` becomes `{"use": "moon", ...}` in the square in the middle of its `rect`, with
 *   its `slot` and `rest` (its other keys are dropped: no design used them); `"shape": "band"` a rectangle with a linear
 *   gradient (its `x` was the middle, now the left edge), `"shape": "dot"` a circle with a radial one;
 * - a view with `"moonLight": true` gets `"image": {"use": "moon"}`; a view with an `orbit` gets a view layer
 *   with `rest: 0` at the end of `layers` that moves its middle onto the arc;
 * - `dayLook` (with the `XDay` pictures) and `weatherLooks` become `looks`, in the order they were laid
 *   over the design: the day look first, then the weather look. A look that changed the animation
 *   (or an orbit) gets the whole new animation, as a list of layers cannot be merged.
 *
 * Pure functions: the result is a new object; a design without old keys is given back as it is.
 */
object LegacyDesign {

    /** The old key of the day look. */
    const val DAY_LOOK_KEY = CwfPlaceNeeds.DAY_LOOK

    /** The old key of the weather looks, by `day_<weather>`, `night_<weather>` or `<weather>`. */
    const val WEATHER_LOOKS_KEY = CwfPlaceNeeds.WEATHER_LOOKS

    /** The key in an old look (and in a new one) that names the pictures to swap. */
    const val IMAGES_KEY = Looks.IMAGES

    /** In the day a picture `XDay` showed in the place of `X`. */
    const val DAY_SUFFIX = "Day"

    /** The old key of a view that shows the moon. */
    const val MOON_LIGHT_KEY = CwfPlaceNeeds.MOON_LIGHT

    /** The old key of a layer that draws the moon. */
    const val MOON_KEY = CwfPlaceNeeds.ANIMATION_MOON

    /** The old key of a view that moves along an arc, see [Orbit]. */
    const val ORBIT_KEY = "orbit"

    /** The old effects of the animation, in the order they were drawn. */
    val EFFECTS = listOf("rain", "glitch", "trace", "blink", "sweep", "slide", "wobble", "pulse", "glint")

    /** The effects that were a list, one entry per group of views. */
    private val LIST_EFFECTS = setOf("blink", "sweep", "slide", "wobble")

    /** The weathers the phone sends, in the order of their number in `v_weather_kind`. */
    val WEATHERS = StoredWeather.KINDS

    /** The built-in template that draws the moon, in a view or as a layer. */
    private const val MOON_TEMPLATE = CwfPlaceNeeds.MOON_TEMPLATE

    /** The new picture of a view that showed the moon: the template, filling the view. */
    private fun moonImage(): JSONObject = JSONObject().put(Templates.USE, MOON_TEMPLATE)

    /**
     * A view that moved along an arc, from the old `orbit` key of a view:
     * `"orbit": {"valueKey": "sun_progress", "centerX": 200, "centerY": 200, "radius": 188, "startAngle": 200, "endAngle": 340}`.
     * The value (0..1) of [valueKey] placed the middle of the view on the arc: 0 at [startAngle], 1 at
     * [endAngle]; angles in degrees on the screen, 0 to the right, 90 down.
     */
    data class Orbit(
        val valueKey: String,
        val centerX: Float = 200f,
        val centerY: Float = 200f,
        val radius: Float = 188f,
        val startAngle: Float = 200f,
        val endAngle: Float = 340f
    ) {

        /** The point on the arc for [value] (cut to 0..1), in the design space, as the old engine placed it. */
        fun point(value: Double): Pair<Float, Float> {
            val angle = Math.toRadians(startAngle + (endAngle - startAngle) * value.coerceIn(0.0, 1.0))
            return Pair(centerX + radius * cos(angle).toFloat(), centerY + radius * sin(angle).toFloat())
        }

        companion object {

            /** The orbit of a view, or null when it has none or no value key. */
            fun read(view: JSONObject?): Orbit? {
                val json = view?.optJSONObject(ORBIT_KEY) ?: return null
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

    /** True when [design] has an old key that [translate] rewrites. */
    fun hasOldKeys(design: JSONObject): Boolean =
        design.has(DAY_LOOK_KEY) || design.has(WEATHER_LOOKS_KEY) || hasOldViewKeys(design) || design.optJSONObject(WakeAnimationSpec.JSON_KEY)?.let(::hasOldAnimationKeys) == true

    /**
     * [design] in the new form; [images] are the keys of the pictures of the zip, for the `XDay`
     * pictures of the day look. [design] itself when it has no old key.
     */
    fun translate(design: JSONObject, images: Set<String> = emptySet()): JSONObject {
        if (!hasOldKeys(design)) return design
        val base = JSONObject(design.toString())
        base.remove(DAY_LOOK_KEY)
        base.remove(WEATHER_LOOKS_KEY)
        val result = newViews(base)
        animationOf(base)?.let { result.put(WakeAnimationSpec.JSON_KEY, it) } ?: result.remove(WakeAnimationSpec.JSON_KEY)
        val looks = looksOf(design, base, images)
        if (looks.length() > 0) {
            design.optJSONArray(Looks.JSON_KEY)?.let { own -> for (i in 0 until own.length()) looks.put(own.opt(i)) }
            result.put(Looks.JSON_KEY, looks)
        }
        return result
    }

    // ---- looks ----

    private fun looksOf(design: JSONObject, base: JSONObject, images: Set<String>): JSONArray {
        val looks = JSONArray()
        val day = design.optJSONObject(DAY_LOOK_KEY)
        if (day != null) {
            val swaps = JSONObject()
            images.filter { it.length > DAY_SUFFIX.length && it.endsWith(DAY_SUFFIX) }.sorted().forEach { swaps.put(it.removeSuffix(DAY_SUFFIX), it) }
            day.optJSONObject(IMAGES_KEY)?.let { own -> own.keys().forEach { swaps.put(it, own.opt(it)) } }
            looks.put(look("v_is_day == 1", patchOf(base, day), swaps))
        }
        val weather = design.optJSONObject(WEATHER_LOOKS_KEY) ?: return looks
        val dayBase = day?.let { merged(base, it) }
        WEATHERS.forEachIndexed { kind, name ->
            val atNight = weather.optJSONObject("night_$name") ?: weather.optJSONObject(name)
            // Without a day look the face was never "day" for the weather looks: always the night look
            val atDay = if (day != null) weather.optJSONObject("day_$name") ?: weather.optJSONObject(name) else null
            val isWeather = "v_weather_kind == $kind"
            when {
                day == null                                                                                ->
                    atNight?.let { looks.put(look(isWeather, patchOf(base, it), it.optJSONObject(IMAGES_KEY))) }

                atDay === atNight && (atNight == null || !changesAnimation(atNight) || !changesAnimation(day)) ->
                    atNight?.let { looks.put(look(isWeather, patchOf(base, it), it.optJSONObject(IMAGES_KEY))) }

                else                                                                                       -> {
                    atDay?.let { looks.put(look("v_is_day == 1 && $isWeather", patchOf(dayBase!!, it), it.optJSONObject(IMAGES_KEY))) }
                    atNight?.let { looks.put(look("v_is_day == 0 && $isWeather", patchOf(base, it), it.optJSONObject(IMAGES_KEY))) }
                }
            }
        }
        return looks
    }

    private fun look(condition: String, patch: JSONObject, images: JSONObject?): JSONObject = JSONObject().apply {
        put(Looks.WHEN, condition)
        put(Looks.PATCH, patch)
        if (images != null && images.length() > 0) put(Looks.IMAGES, JSONObject(images.toString()))
    }

    /** The new patch of the old look [overlay] laid over [base] (the old design it was laid over). */
    private fun patchOf(base: JSONObject, overlay: JSONObject): JSONObject {
        val patch = JSONObject(overlay.toString())
        patch.remove(IMAGES_KEY)
        patch.remove(WakeAnimationSpec.JSON_KEY)
        patch.keys().asSequence().toList().forEach { key ->
            val view = patch.optJSONObject(key) ?: return@forEach
            view.remove(ORBIT_KEY)
            if (view.has(MOON_LIGHT_KEY)) {
                val on = view.optBoolean(MOON_LIGHT_KEY)
                view.remove(MOON_LIGHT_KEY)
                view.put(VIEW_IMAGE, if (on) moonImage() else JSONObject.NULL)
            }
        }
        if (changesAnimation(overlay)) {
            val before = animationOf(base)
            val after = animationOf(merged(base, overlay))
            when {
                after == null  -> if (before != null) patch.put(WakeAnimationSpec.JSON_KEY, JSONObject.NULL)
                before == null -> patch.put(WakeAnimationSpec.JSON_KEY, after)
                else           -> {
                    // The whole animation, with the keys it lost removed, so the merge gives exactly it
                    before.keys().forEach { if (!after.has(it)) after.put(it, JSONObject.NULL) }
                    patch.put(WakeAnimationSpec.JSON_KEY, after)
                }
            }
        }
        return patch
    }

    /** True when the old look [overlay] changes the animation: the `animation` itself or an orbit. */
    private fun changesAnimation(overlay: JSONObject): Boolean =
        overlay.has(WakeAnimationSpec.JSON_KEY) || overlay.keys().asSequence().any { overlay.optJSONObject(it)?.has(ORBIT_KEY) == true }

    private fun merged(base: JSONObject, overlay: JSONObject): JSONObject = JSONObject(base.toString()).also { Looks.merge(it, overlay) }

    // ---- views ----

    /** The key of a view that shows a picture: of the zip, of the app (`@moon`), or a template (`{"use": "moon"}`). */
    const val VIEW_IMAGE = "image"

    private fun hasOldViewKeys(design: JSONObject): Boolean =
        design.keys().asSequence().any { key -> design.optJSONObject(key)?.let { it.has(MOON_LIGHT_KEY) || it.has(ORBIT_KEY) } == true }

    /** A copy of the old design [old] with its views in the new form: no `moonLight` and no `orbit`. */
    private fun newViews(old: JSONObject): JSONObject {
        val result = JSONObject(old.toString())
        result.keys().asSequence().toList().forEach { key ->
            if (key == WakeAnimationSpec.JSON_KEY) return@forEach
            val view = result.optJSONObject(key) ?: return@forEach
            view.remove(ORBIT_KEY)
            if (view.has(MOON_LIGHT_KEY)) {
                val on = view.optBoolean(MOON_LIGHT_KEY)
                view.remove(MOON_LIGHT_KEY)
                if (on) view.put(VIEW_IMAGE, moonImage())
            }
        }
        return result
    }

    // ---- animation ----

    private fun hasOldAnimationKeys(animation: JSONObject): Boolean =
        EFFECTS.any { animation.has(it) } || animation.optJSONArray(WakeAnimationJson.LAYERS)?.let { layers ->
            (0 until layers.length()).any { i -> layers.optJSONObject(i)?.let { it.has(MOON_KEY) || it.optString("shape").lowercase() in setOf("band", "dot") } == true }
        } == true

    /**
     * The new `animation` of the old design [old]: its effects as `use` entries, then its own layers in
     * the new form, then a layer for every view with an orbit. Null when it has neither an animation nor
     * an orbit.
     */
    private fun animationOf(old: JSONObject): JSONObject? {
        val given = old.optJSONObject(WakeAnimationSpec.JSON_KEY)
        val orbits = orbitLayers(old)
        if (given == null && orbits.isEmpty()) return null
        val animation = given?.let { JSONObject(it.toString()) } ?: JSONObject()
        val layers = JSONArray()
        for (effect in EFFECTS) {
            val value = animation.opt(effect) ?: continue
            animation.remove(effect)
            when {
                effect in LIST_EFFECTS && value is JSONArray -> for (i in 0 until value.length()) value.optJSONObject(i)?.let { uses(effect, it).forEach(layers::put) }
                value is JSONObject                          -> {
                    if (effect == "pulse" && value.has("fallbackBpm")) animation.put("fallbackBpm", value.opt("fallbackBpm"))
                    use(effect, value)?.let(layers::put)
                }
            }
        }
        animation.optJSONArray(WakeAnimationJson.LAYERS)?.let { own -> for (i in 0 until own.length()) own.optJSONObject(i)?.let { layers.put(newLayer(it)) } }
        orbits.forEach(layers::put)
        animation.put(WakeAnimationJson.LAYERS, layers)
        return animation
    }

    /** One `use` of [effect] with the values of the old [params] that the template knows; null where the old engine drew nothing. */
    private fun use(effect: String, params: JSONObject): JSONObject? {
        when (effect) {
            "trace" -> if (params.has("view") && params.optString("view").isEmpty()) return null
            "glint" -> if (params.objects("rings").isEmpty() && params.objects("boxes").isEmpty()) return null
        }
        val known = BuiltInTemplates.all.optJSONObject(effect)?.optJSONObject(Templates.PARAMS) ?: return null
        return JSONObject().apply {
            put(Templates.USE, effect)
            params.keys().forEach { key -> if (known.has(key)) put(key, params.opt(key)) }
        }
    }

    /**
     * The `use` entries of one entry of a list effect: one per view of its `views` (empty ones and twins
     * left out, as the old engine did), none when a `blink` has no value key.
     */
    private fun uses(effect: String, params: JSONObject): List<JSONObject> {
        if (effect == "blink" && params.optString("valueKey").isEmpty()) return emptyList()
        val views = params.optJSONArray("views")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty().filter { it.isNotEmpty() }.distinct()
        val rest = JSONObject(params.toString()).apply { remove("views") }
        return views.mapNotNull { view -> use(effect, JSONObject(rest.toString()).put("view", view)) }
    }

    /**
     * The built-in moon in place of the old moon layer [old]: in the square in the middle of its `rect`
     * (the whole face without one), in its slot. Without a `rest` it shows only while a wake plays.
     */
    private fun moonUse(old: JSONObject): JSONObject {
        val rect = old.optJSONObject("rect")
        val left = rect?.optDouble("left", 0.0) ?: 0.0
        val top = rect?.optDouble("top", 0.0) ?: 0.0
        val width = rect?.optDouble("width", 400.0) ?: 400.0
        val height = rect?.optDouble("height", 400.0) ?: 400.0
        val size = minOf(width, height)
        return JSONObject().put(Templates.USE, MOON_TEMPLATE)
            .put("left", left + (width - size) / 2).put("top", top + (height - size) / 2).put("size", size)
            .put("rest", if (old.has("rest")) old.opt("rest") else "")
            .apply { if (old.has("slot")) put("slot", old.opt("slot")) }
    }

    /** An own layer of the old animation in the new form. */
    private fun newLayer(old: JSONObject): JSONObject {
        val layer = JSONObject(old.toString())
        if (layer.has(MOON_KEY)) {
            val on = layer.optBoolean(MOON_KEY)
            layer.remove(MOON_KEY)
            if (on) return moonUse(layer)
        }
        when (layer.optString("shape").lowercase()) {
            "band" -> {
                // The old band had its middle at x; a rectangle has its left edge there
                val x = text(layer.opt("x")) ?: "0"
                val width = text(layer.opt("width")) ?: "10"
                layer.put("shape", "rect")
                layer.put("x", "($x) - ($width) / 2")
                layer.put("gradient", JSONObject("""{"type": "linear", "stops": [[0, 0], [0.5, 1], [1, 0]]}"""))
            }

            "dot"  -> {
                layer.put("shape", "circle")
                layer.put("gradient", JSONObject("""{"type": "radial", "stops": [[0, 1], [0.25, 1], [1, 0]]}"""))
            }
        }
        return layer
    }

    /** The layers that move the views with an orbit, in the order of the views in [old]. */
    private fun orbitLayers(old: JSONObject): List<JSONObject> = old.keys().asSequence().mapNotNull { key ->
        if (key == WakeAnimationSpec.JSON_KEY) return@mapNotNull null
        val orbit = Orbit.read(old.optJSONObject(key)) ?: return@mapNotNull null
        val value = "v_${orbit.valueKey}"
        JSONObject().apply {
            put("view", key)
            put("rest", 0)
            // The same maths as Orbit.point: the middle of the view onto the arc
            put(
                WakeAnimationJson.LET, JSONObject().put(
                    "angle", "(${n(orbit.startAngle)} + ${n(orbit.endAngle - orbit.startAngle)} * clamp(if($value == $value, $value, 0), 0, 1)) * (pi / 180)"
                )
            )
            put("x", "${n(orbit.centerX)} + ${n(orbit.radius)} * cos(angle) - cx0")
            put("y", "${n(orbit.centerY)} + ${n(orbit.radius)} * sin(angle) - cy0")
        }
    }.toList()

    private fun n(value: Float): String = Templates.numberText(value.toString().toDouble())

    private fun text(value: Any?): String? = when (value) {
        is Number -> Templates.numberText(value)
        is String -> value.trim().takeIf { it.isNotEmpty() }
        else      -> null
    }

    private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } } ?: emptyList()
}
