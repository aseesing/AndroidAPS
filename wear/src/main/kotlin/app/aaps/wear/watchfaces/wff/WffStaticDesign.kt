// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.Templates
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * The design json for the baked static picture of a converted face: the same design with every part
 * that the face draws live, or that a complication draws, left out. The engine draws this copy at
 * rest, unchanged, so the picture is exactly what the watch shows of those parts.
 *
 * - a view whose tier is not [WffTier.BAKE] is `gone`;
 * - the layers are written out of their templates first, so the index of a layer is its index in the
 *   plan; a layer that moves a view is removed (the view decides), any other layer that is not baked
 *   gets `visible` 0.
 */
object WffStaticDesign {

    /** The static copy of [design] for [plan], or null when the animation has errors: then the plan's layer numbers may not match. */
    fun of(design: JSONObject, animation: WakeAnimationSpec?, plan: WffPlan): JSONObject? {
        if (animation != null && animation.errors.isNotEmpty()) return null
        val copy = JSONObject(design.toString())
        for (item in plan.items) {
            val view = item.view ?: continue
            if (item.tier != WffTier.BAKE) copy.optJSONObject(view)?.put("visibility", "gone")
        }
        val json = copy.optJSONObject(WakeAnimationSpec.JSON_KEY) ?: return copy
        val errors = ArrayList<String>()
        val layers = Templates(own = json.optJSONObject(WakeAnimationJson.TEMPLATES)).expand(json.optJSONArray(WakeAnimationJson.LAYERS) ?: JSONArray(), errors)
        if (errors.isNotEmpty()) return null
        val removed = HashSet<Int>()
        for (item in plan.items) {
            val index = item.layer ?: continue
            if (item.tier == WffTier.BAKE) continue
            val layer = layers.getOrNull(index) ?: return null
            if (layer.has(VIEW)) removed += index else layer.put(VISIBLE, "0")
        }
        json.remove(WakeAnimationJson.TEMPLATES)
        json.put(WakeAnimationJson.LAYERS, JSONArray().apply { layers.forEachIndexed { i, layer -> if (i !in removed) put(layer) } })
        return copy
    }

    private const val VIEW = "view"
    private const val VISIBLE = "visible"
}
