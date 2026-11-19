// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Color
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject

/** Reads a colour text as the face does for the tests: `#RRGGBB` or `#AARRGGBB`, else [default]. */
internal fun testColor(text: String, default: Int): Int = runCatching { Color.parseColor(text) }.getOrDefault(default)

/** The animation [animation] (the object of the `animation` key) read as the watch reads it, with the pictures [images]. */
internal fun readAnimation(animation: JSONObject, images: Map<String, Drawable> = emptyMap()): WakeAnimationSpec =
    WakeAnimationJson(::testColor) { images[it] }.read(JSONObject().put(WakeAnimationSpec.JSON_KEY, animation))!!

/** An animation of [layers] (plain layers or `use` entries), with the templates of the manual ([TemplatesForTests]). */
internal fun animationOf(vararg layers: JSONObject): JSONObject =
    JSONObject().put(WakeAnimationJson.TEMPLATES, TemplatesForTests.all()).put("layers", JSONArray().apply { layers.forEach { put(it) } })

/** The design json [design] with the templates of the manual ([TemplatesForTests]) in its `animation`. */
internal fun withTestTemplates(design: String): String =
    JSONObject(design).also { json -> json.optJSONObject(WakeAnimationSpec.JSON_KEY)?.put(WakeAnimationJson.TEMPLATES, TemplatesForTests.all()) }.toString()

/** One `use` of the template [name] with [params]. */
internal fun use(name: String, vararg params: Pair<String, Any?>): JSONObject =
    JSONObject().put(Templates.USE, name).apply { params.forEach { (key, value) -> put(key, value) } }

/** The animator of [animation] as the watch makes it; the reading and the formulas must have no errors. */
internal fun animatorOf(animation: JSONObject, images: Map<String, Drawable> = emptyMap()): LayerAnimator {
    val spec = readAnimation(animation, images).normalized()
    check(spec.errors.isEmpty()) { "${spec.errors}" }
    return LayerAnimator(spec.layers, spec.lets, functions = spec.functions).also { check(it.errors.isEmpty()) { "${it.errors}" } }
}

/** [color] as a colour text of a design. */
internal fun colorText(color: Int): String = String.format("#%08X", color)

/** [rect] as the json of a design. */
internal fun rectJson(rect: DesignRect): JSONObject =
    JSONObject().put("left", rect.left.toDouble()).put("top", rect.top.toDouble()).put("width", rect.width.toDouble()).put("height", rect.height.toDouble())

/** Sets the animation of the timer as `WakeAnimator.setDesign` does: the same values again do not start it again. */
internal fun WakeTimer.use(spec: WakeAnimationSpec?) = setSpec(spec, spec?.withoutImage() == this.spec?.withoutImage())

/** A small animation with one layer, for the tests of the timer. */
internal fun oneLayer(durationMs: Long = 6000, fps: Int = 24, fadeMs: Long = 600) =
    WakeAnimationSpec(durationMs = durationMs, fps = fps, fadeMs = fadeMs, layers = listOf(AnimLayerSpec(LayerSource.Shape(ShapeKind.LINE))))
