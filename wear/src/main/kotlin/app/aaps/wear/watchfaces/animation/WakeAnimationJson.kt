// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Color
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Reads the optional `animation` key of a design json into a [WakeAnimationSpec]:
 *
 * ```
 * "animation": {
 *   "durationMs": 6000, "fps": 24, "fadeMs": 600, "fallbackBpm": 60,
 *   "let": {"mx": "200 + 120 * cos(2 * pi * v_moon_cycle)"},
 *   "templates": {"snow": {"params": {...}, "layers": [...]}},
 *   "layers": [{"use": "snow", "count": 90}, {"image": "Halo", "x": "mx", ...}]
 * }
 * ```
 *
 * The layers that `use` a template of `templates` are expanded first (see [Templates]); the app has no
 * templates of its own. A number of a layer may also
 * be given as a formula of fixed numbers (`"maxScale": "1 + 0.2"`), which is what a template gives
 * after its parameters are filled in.
 *
 * Missing keys get the defaults of the spec classes. Never throws: a broken animation must not cost
 * the design. A broken entry of the `layers` list is left out, the other layers are kept, and the
 * reason is in [WakeAnimationSpec.errors].
 *
 * @param color reads a colour text of the design (`#AARRGGBB`, `#RRGGBB` or a colour key of the design),
 *   or gives the second value when the text is not a colour
 * @param image finds a picture of the design zip by its key, or null when it is not there
 */
class WakeAnimationJson(
    private val color: (String, Int) -> Int,
    private val image: (String) -> Drawable?
) {

    /** The animation of the design [json], or null when it has none (or it is not an object). */
    fun read(json: JSONObject): WakeAnimationSpec? = runCatching {
        val animation = json.optJSONObject(WakeAnimationSpec.JSON_KEY) ?: return@runCatching null
        val spec = WakeAnimationSpec()
        val errors = ArrayList<String>()
        val expanded = Templates(own = animation.optJSONObject(TEMPLATES)).expand(animation.optJSONArray(LAYERS) ?: JSONArray(), errors)
        val layers = expanded.mapIndexedNotNull { index, layer ->
            runCatching { layer(layer) }.getOrNull() ?: run {
                errors += "layer $index: no single source (image, shape or view), or a wrong copy, shape or gradient"
                null
            }
        }
        WakeAnimationSpec(
            durationMs = animation.optLong("durationMs", spec.durationMs),
            fps = animation.optInt("fps", spec.fps),
            fadeMs = animation.optLong("fadeMs", spec.fadeMs),
            fallbackBpm = animation.optInt("fallbackBpm", spec.fallbackBpm),
            lets = lets(animation),
            layers = layers,
            errors = errors
        )
    }.getOrNull()

    /** One plain layer, or null when it is broken. Formulas are kept as text, not checked here. */
    private fun layer(json: JSONObject): AnimLayerSpec? {
        val source = source(json) ?: return null
        val copies = copies(json) ?: return null
        return AnimLayerSpec(
            source = source,
            // A view is always drawn with the views
            slot = if (source is LayerSource.View) LayerSlot.VIEWS else enumOf(json.optString("slot"), LayerSlot.BACK),
            blend = enumOf(json.optString("blend"), LayerBlend.NORMAL),
            copies = copies,
            props = props(json),
            lets = lets(json),
            clip = json.optJSONObject("clip")?.let { clip ->
                LayerClip(
                    rect = clip.optJSONObject("rect")?.rect(DesignRect.FULL), round = clip.optBoolean("round", true),
                    rings = clip.rings(), boxes = clip.boxes(), cornerRadius = clip.float("cornerRadius", 0f), mask = mask(clip),
                    hasShapes = clip.has("rings") || clip.has("boxes")
                )
            } ?: LayerClip(),
            glow = json.optJSONObject("glow")?.let { LayerGlow(color(it.optString("color"), Color.WHITE), it.float("radius", DEFAULT_GLOW_RADIUS)) },
            maxScale = json.float("maxScale", 1f),
            restSeconds = number(json, "rest")?.takeIf { it.isFinite() }?.toFloat()
        )
    }

    /**
     * Exactly one of `image`, `shape` and `view`; null when none or more than one is given, or the shape
     * is unknown. An `image` that starts with `@` is a picture the app draws itself (`@moon`).
     */
    private fun source(json: JSONObject): LayerSource? {
        if (SOURCES.count { json.has(it) } != 1) return null
        val rect = json.optJSONObject("rect")?.rect(DesignRect.FULL) ?: DesignRect.FULL
        return when {
            json.has("image") -> json.optString("image").let { key ->
                if (key.startsWith(DYNAMIC_PREFIX)) LayerSource.Dynamic(key.removePrefix(DYNAMIC_PREFIX), rect)
                else LayerSource.Image(key, key.takeIf { it.isNotEmpty() }?.let(image), rect)
            }

            json.has("shape") -> {
                val kind = ShapeKind.entries.firstOrNull { it.name.equals(json.optString("shape"), ignoreCase = true) } ?: return null
                val colors = json.optJSONArray("colors")?.let { list -> (0 until list.length()).map { color(list.optString(it), Color.WHITE) } }
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf(color(json.optString("color"), Color.WHITE))
                val gradient = if (json.has("gradient")) gradient(json.optJSONObject("gradient")) ?: return null else null
                LayerSource.Shape(kind, colors, gradient)
            }

            else              -> LayerSource.View(json.optString("view"))
        }
    }

    /** `{"type": "linear" | "radial", "stops": [[position, alpha], ...]}`; null when it is not that. */
    private fun gradient(json: JSONObject?): LayerGradient? {
        json ?: return null
        val type = LayerGradient.Type.entries.firstOrNull { it.name.equals(json.optString("type"), ignoreCase = true) } ?: return null
        val list = json.optJSONArray("stops") ?: return null
        val stops = (0 until list.length()).map { index ->
            val stop = list.optJSONArray(index)?.takeIf { it.length() == 2 } ?: return null
            val position = stop.optDouble(0)
            val alpha = stop.optDouble(1)
            if (!position.isFinite() || !alpha.isFinite()) return null
            LayerGradient.Stop(position.toFloat().coerceIn(0f, 1f), alpha.toFloat().coerceIn(0f, 1f))
        }
        return if (stops.size >= 2) LayerGradient(type, stops) else null
    }

    /**
     * The `mask` of a `clip`, a picture key, with its optional `maskRect` (default the full face); null
     * when there is no mask. A key that is not in the zip gives a mask without a picture, so the layer is
     * left out later, not drawn without its mask.
     */
    private fun mask(clip: JSONObject): LayerMask? {
        if (clip.isNull("mask")) return null
        val key = clip.optString("mask")
        return LayerMask(key, key.takeIf { it.isNotEmpty() }?.let(image), clip.optJSONObject("maskRect")?.rect(DesignRect.FULL) ?: DesignRect.FULL)
    }

    /** At most one of `count`, `slices` and `points`; null when more than one is given. */
    private fun copies(json: JSONObject): LayerCopies? = when (COPIES.count { json.has(it) }) {
        0    -> LayerCopies.One
        1    -> when {
            json.has("count")  -> LayerCopies.Count(number(json, "count")?.toInt() ?: 1, number(json, "seed")?.toLong() ?: 1)
            json.has("slices") -> LayerCopies.Slices(number(json, "slices")?.toInt() ?: 1, vertical = !json.optString("slicesAlong").equals("y", ignoreCase = true))
            else               -> LayerCopies.Points(points(json.optJSONArray("points")))
        }

        else -> null
    }

    /** The `[x, y, p0, p1, ...]` lists of [list]; one that does not start with two numbers is left out. */
    private fun points(list: JSONArray?): List<DesignPoint> = list?.let {
        (0 until it.length()).mapNotNull { index ->
            val point = it.optJSONArray(index)?.takeIf { p -> p.length() >= 2 } ?: return@mapNotNull null
            val x = point.optDouble(0)
            val y = point.optDouble(1)
            val extras = (2 until minOf(point.length(), 2 + LayerVariables.EXTRAS)).map { k -> point.optDouble(k) }
            if (x.isFinite() && y.isFinite()) DesignPoint(x.toFloat(), y.toFloat(), extras) else null
        }
    } ?: emptyList()

    /**
     * The properties of [json]: a number is kept as its decimal text, a text as it is (a formula).
     * `scale` sets both `scaleX` and `scaleY`, unless they are given themselves.
     */
    private fun props(json: JSONObject): Map<LayerProp, String> {
        val props = LinkedHashMap<LayerProp, String>()
        LayerProp.entries.forEach { prop -> propText(json.opt(prop.key))?.let { props[prop] = it } }
        propText(json.opt("scale"))?.let { scale ->
            props.putIfAbsent(LayerProp.SCALE_X, scale)
            props.putIfAbsent(LayerProp.SCALE_Y, scale)
        }
        return props
    }

    /** The `let` of [json], in its order: name and formula text. */
    private fun lets(json: JSONObject): List<Pair<String, String>> = json.optJSONObject(LET)?.let { let ->
        let.keys().asSequence().mapNotNull { name -> propText(let.opt(name))?.let { name to it } }.toList()
    } ?: emptyList()

    /** The text of one property value, or null when it is not a number or a text. */
    private fun propText(value: Any?): String? = when (value) {
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is String -> value.trim().takeIf { it.isNotEmpty() }
        else      -> null
    }

    /** The number [key] of [json]: a number, or a formula of fixed numbers; null when there is none or it is not one. */
    private fun number(json: JSONObject, key: String): Double? = when (val value = json.opt(key)) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: runCatching { Formula.parse(value) { null }.takeIf { it.isConstant }?.eval(DoubleArray(0)) }.getOrNull()
        else      -> null
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default

    private fun JSONObject.float(key: String, default: Float) = (number(this, key) ?: default.toDouble()).toFloat()
    private fun JSONObject.objects(key: String) = optJSONArray(key)?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } } ?: emptyList()
    private fun JSONObject.rect(default: DesignRect) =
        DesignRect(float("left", default.left), float("top", default.top), float("width", default.width), float("height", default.height))

    private fun JSONObject.rings() = objects("rings").map { r -> DesignRing(r.float("cx", 200f), r.float("cy", 200f), r.float("inner", 0f), r.float("outer", 0f)) }
    private fun JSONObject.boxes() = objects("boxes").map { b -> b.rect(DesignRect(0f, 0f, 0f, 0f)) }

    companion object {

        /** The key of the layer list inside the `animation` object. */
        const val LAYERS = "layers"

        /** The key of the named formulas of the animation and of a layer. */
        const val LET = "let"

        /** The key of the design's own templates inside the `animation` object. */
        const val TEMPLATES = "templates"

        /** The glow radius of a layer whose `glow` gives none, in design pixels. */
        const val DEFAULT_GLOW_RADIUS = 10f

        /** An image key that starts with this is a picture the app draws itself, like `@moon`. */
        const val DYNAMIC_PREFIX = "@"

        private val SOURCES = listOf("image", "shape", "view")
        private val COPIES = listOf("count", "slices", "points")
    }
}
