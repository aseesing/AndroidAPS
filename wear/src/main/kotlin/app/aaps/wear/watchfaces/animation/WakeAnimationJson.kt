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
 * Reads the optional `animation` key of a design json into a [WakeAnimationSpec].
 *
 * Missing keys get the defaults of the spec classes. Never throws: a broken animation must not cost
 * the design. A broken entry of the `layers` list is left out, the other layers are kept.
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
        val rain = RainSpec()
        val pulse = PulseSpec()
        val glitch = GlitchSpec()
        WakeAnimationSpec(
            durationMs = animation.optLong("durationMs", spec.durationMs),
            fps = animation.optInt("fps", spec.fps),
            fadeMs = animation.optLong("fadeMs", spec.fadeMs),
            rain = animation.optJSONObject("rain")?.let {
                RainSpec(
                    count = it.optInt("count", rain.count), seed = it.optLong("seed", rain.seed), speed = it.float("speed", rain.speed),
                    angle = it.float("angle", rain.angle), length = it.float("length", rain.length),
                    lengthVariation = it.float("lengthVariation", rain.lengthVariation), thickness = it.float("thickness", rain.thickness),
                    color = color(it.optString("color"), rain.color), alpha = it.optInt("alpha", rain.alpha),
                    region = it.optJSONObject("region")?.rect(rain.region) ?: rain.region
                )
            },
            pulse = animation.optJSONObject("pulse")?.let {
                val key = it.optString("image")
                PulseSpec(
                    image = key, drawable = key.takeIf { k -> k.isNotEmpty() }?.let(image), rect = it.rect(pulse.rect),
                    amplitude = it.float("amplitude", pulse.amplitude), secondBeat = it.float("secondBeat", pulse.secondBeat),
                    glowColor = color(it.optString("glowColor"), pulse.glowColor), glowAlpha = it.optInt("glowAlpha", pulse.glowAlpha),
                    glowRadius = it.float("glowRadius", pulse.glowRadius), fallbackBpm = it.optInt("fallbackBpm", pulse.fallbackBpm)
                )
            },
            glitch = animation.optJSONObject("glitch")?.let {
                GlitchSpec(
                    periodMs = it.optLong("periodMs", glitch.periodMs), chance = it.float("chance", glitch.chance),
                    burstMs = it.optLong("burstMs", glitch.burstMs), bands = it.optInt("bands", glitch.bands), seed = it.optLong("seed", glitch.seed),
                    minHeight = it.float("minHeight", glitch.minHeight), maxHeight = it.float("maxHeight", glitch.maxHeight),
                    minWidth = it.float("minWidth", glitch.minWidth), maxWidth = it.float("maxWidth", glitch.maxWidth), jump = it.float("jump", glitch.jump),
                    color1 = color(it.optString("color1"), glitch.color1), color2 = color(it.optString("color2"), glitch.color2),
                    alpha = it.optInt("alpha", glitch.alpha), region = it.optJSONObject("region")?.rect(glitch.region) ?: glitch.region
                )
            },
            blink = animation.objects("blink").map {
                BlinkSpec(views = it.strings("views"), valueKey = it.optString("valueKey"), atOrBelow = it.optDouble("atOrBelow", 0.0), periodMs = it.optLong("periodMs", 1000))
            },
            sweep = animation.objects("sweep").map {
                val sweep = SweepSpec(emptyList(), 0f, 0f)
                SweepSpec(
                    views = it.strings("views"), emptyAngle = it.float("emptyAngle", 0f), fullAngle = it.float("fullAngle", 0f),
                    delayMs = it.optLong("delayMs", sweep.delayMs), upMs = it.optLong("upMs", sweep.upMs), settleMs = it.optLong("settleMs", sweep.settleMs)
                )
            },
            slide = animation.objects("slide").map {
                val slide = SlideSpec(emptyList(), 0f)
                SlideSpec(
                    views = it.strings("views"), fullDx = it.float("fullDx", 0f),
                    delayMs = it.optLong("delayMs", slide.delayMs), upMs = it.optLong("upMs", slide.upMs), settleMs = it.optLong("settleMs", slide.settleMs)
                )
            },
            wobble = animation.objects("wobble").map {
                val wobble = WobbleSpec(emptyList())
                WobbleSpec(
                    views = it.strings("views"), delayMs = it.optLong("delayMs", wobble.delayMs), amplitude = it.float("amplitude", wobble.amplitude),
                    hz = it.float("hz", wobble.hz), decayMs = it.optLong("decayMs", wobble.decayMs)
                )
            },
            trace = animation.optJSONObject("trace")?.let {
                val trace = TraceSpec(rect = DesignRect.FULL)
                TraceSpec(
                    view = it.optString("view", trace.view), rect = it.optJSONObject("rect")?.rect(trace.rect) ?: trace.rect,
                    delayMs = it.optLong("delayMs", trace.delayMs), durationMs = it.optLong("durationMs", trace.durationMs),
                    color = color(it.optString("color"), trace.color), alpha = it.optInt("alpha", trace.alpha), width = it.float("width", trace.width)
                )
            },
            glint = animation.optJSONObject("glint")?.let {
                val glint = GlintSpec()
                GlintSpec(
                    rings = it.rings(), boxes = it.boxes(), cornerRadius = it.float("cornerRadius", glint.cornerRadius),
                    delayMs = it.optLong("delayMs", glint.delayMs), durationMs = it.optLong("durationMs", glint.durationMs), angle = it.float("angle", glint.angle),
                    width = it.float("width", glint.width), color = color(it.optString("color"), glint.color), alpha = it.optInt("alpha", glint.alpha)
                )
            },
            layers = animation.optJSONArray(LAYERS)?.let { list ->
                (0 until list.length()).mapNotNull { index -> list.optJSONObject(index)?.let { runCatching { layer(it) }.getOrNull() } }
            } ?: emptyList()
        )
    }.getOrNull()

    /** One entry of the `layers` list, or null when it is broken. Formulas are kept as text, not checked here. */
    private fun layer(json: JSONObject): AnimLayerSpec? {
        val source = source(json) ?: return null
        val copies = copies(json) ?: return null
        return AnimLayerSpec(
            source = source,
            slot = enumOf(json.optString("slot"), LayerSlot.BACK),
            blend = enumOf(json.optString("blend"), LayerBlend.NORMAL),
            copies = copies,
            props = props(json),
            clip = json.optJSONObject("clip")?.let { clip ->
                LayerClip(
                    rect = clip.optJSONObject("rect")?.rect(DesignRect.FULL), round = clip.optBoolean("round", true),
                    rings = clip.rings(), boxes = clip.boxes(), cornerRadius = clip.float("cornerRadius", 0f), mask = mask(clip)
                )
            } ?: LayerClip(),
            glow = json.optJSONObject("glow")?.let { LayerGlow(color(it.optString("color"), Color.WHITE), it.float("radius", DEFAULT_GLOW_RADIUS)) },
            maxScale = json.float("maxScale", 1f),
            restSeconds = json.optDouble("rest").takeIf { it.isFinite() }?.toFloat()
        )
    }

    /**
     * Exactly one of `image`, `shape`, `view` and `moon` (`"moon": true`, the moon of now drawn in its
     * `rect`); null when none or more than one is given, or the shape is unknown. `"moon": false` counts
     * as not given.
     */
    private fun source(json: JSONObject): LayerSource? {
        val moon = json.optBoolean(MOON)
        if (SOURCES.count { json.has(it) } + (if (moon) 1 else 0) != 1) return null
        return when {
            moon              -> LayerSource.Moon(json.optJSONObject("rect")?.rect(DesignRect.FULL) ?: DesignRect.FULL)

            json.has("image") -> json.optString("image").let { key ->
                LayerSource.Image(key, key.takeIf { it.isNotEmpty() }?.let(image), json.optJSONObject("rect")?.rect(DesignRect.FULL) ?: DesignRect.FULL)
            }

            json.has("shape") -> {
                val kind = ShapeKind.entries.firstOrNull { it.name.equals(json.optString("shape"), ignoreCase = true) } ?: return null
                val colors = json.optJSONArray("colors")?.let { list -> (0 until list.length()).map { color(list.optString(it), Color.WHITE) } }
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf(color(json.optString("color"), Color.WHITE))
                LayerSource.Shape(kind, colors)
            }

            else              -> LayerSource.View(json.optString("view"))
        }
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
            json.has("count")  -> LayerCopies.Count(json.optInt("count", 1), json.optLong("seed", 1))
            json.has("slices") -> LayerCopies.Slices(json.optInt("slices", 1), vertical = !json.optString("slicesAlong").equals("y", ignoreCase = true))
            else               -> LayerCopies.Points(points(json.optJSONArray("points")))
        }

        else -> null
    }

    /** The `[x, y]` pairs of [list]; a pair that is not two numbers is left out. */
    private fun points(list: JSONArray?): List<DesignPoint> = list?.let {
        (0 until it.length()).mapNotNull { index ->
            val pair = it.optJSONArray(index)?.takeIf { p -> p.length() == 2 } ?: return@mapNotNull null
            val x = pair.optDouble(0)
            val y = pair.optDouble(1)
            if (x.isFinite() && y.isFinite()) DesignPoint(x.toFloat(), y.toFloat()) else null
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

    /** The text of one property value, or null when it is not a number or a text. */
    private fun propText(value: Any?): String? = when (value) {
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is String -> value.trim().takeIf { it.isNotEmpty() }
        else      -> null
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default

    private fun JSONObject.float(key: String, default: Float) = optDouble(key, default.toDouble()).toFloat()
    private fun JSONObject.objects(key: String) = optJSONArray(key)?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } } ?: emptyList()
    private fun JSONObject.strings(key: String) = optJSONArray(key)?.let { list -> (0 until list.length()).map { list.optString(it) } } ?: emptyList()
    private fun JSONObject.rect(default: DesignRect) =
        DesignRect(float("left", default.left), float("top", default.top), float("width", default.width), float("height", default.height))

    private fun JSONObject.rings() = objects("rings").map { r -> DesignRing(r.float("cx", 200f), r.float("cy", 200f), r.float("inner", 0f), r.float("outer", 0f)) }
    private fun JSONObject.boxes() = objects("boxes").map { b -> b.rect(DesignRect(0f, 0f, 0f, 0f)) }

    companion object {

        /** The key of the layer list inside the `animation` object. */
        const val LAYERS = "layers"

        /** The glow radius of a layer whose `glow` gives none, in design pixels. */
        const val DEFAULT_GLOW_RADIUS = 10f

        /** The key of a layer that draws the moon of now (see [LayerSource.Moon]). */
        const val MOON = "moon"

        private val SOURCES = listOf("image", "shape", "view")
        private val COPIES = listOf("count", "slices", "points")
    }
}
