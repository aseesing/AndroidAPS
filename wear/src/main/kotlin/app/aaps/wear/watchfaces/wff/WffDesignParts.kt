// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.wear.watchfaces.animation.Templates
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Copies of a design that each show only some of its parts, for the pictures of a converted face
 * (see [WffPlanner]). The engine draws a copy unchanged, so every picture is exactly what the watch
 * shows of those parts: no second way of drawing.
 *
 * In a copy a view that is not wanted is `gone` and a layer that is not wanted has `visible` 0. A
 * layer that moves a view is never changed: it draws nothing when its view is gone. The layers are
 * written out of their templates first, so the index of a layer is its index in the plan.
 *
 * A copy that must be see-through gets a clear picture as its background ([asCwf], [WffPictures.CLEAR_PNG]).
 */
class WffDesignParts(private val design: JSONObject, animation: WakeAnimationSpec?, private val plan: WffPlan) {

    private val layers: List<JSONObject>?

    init {
        val json = design.optJSONObject(WakeAnimationSpec.JSON_KEY)
        val errors = ArrayList<String>()
        val expanded = json?.let { Templates(own = it.optJSONObject(WakeAnimationJson.TEMPLATES)).expand(it.optJSONArray(WakeAnimationJson.LAYERS) ?: JSONArray(), errors) }.orEmpty()
        // A layer that cannot be read is left out by the engine, and then the numbers of the plan do not fit
        layers = expanded.takeIf { errors.isEmpty() && animation?.errors.isNullOrEmpty() && expanded.size == (animation?.layers?.size ?: 0) }
    }

    /** False when the layers cannot be told apart safely: then no picture is made. */
    val isUsable: Boolean get() = layers != null

    /** The static picture [run]: 0 has the background and is opaque, a later one is see-through. */
    fun static(run: Int): JSONObject = copy(
        keepBackground = run == 0,
        // The background picture of a live text view (a frame round the time) stays, without its text
        textless = if (run == 0) plan.items.filter { it.view != null && it.tier != WffTier.BAKE && it.where != WffPlanner.DATA && it.where !in WffPlanner.USER_SLOTS }.mapNotNull { it.view }.toSet() else emptySet()
    ) { it.tier == WffTier.BAKE && !it.loop && it.run == run }

    /** Everything the watch draws into the data picture. */
    fun data(): JSONObject = copy { it.where == WffPlanner.DATA }

    /** Only the layers [indices], for the frames of a wake loop or the rest pose of such layers. */
    fun layers(indices: Collection<Int>, keepBackground: Boolean = false): JSONObject = copy(keepBackground = keepBackground) { it.layer != null && it.layer in indices }

    private fun copy(keepBackground: Boolean = false, textless: Set<String> = emptySet(), wanted: (WffPlanItem) -> Boolean): JSONObject {
        val copy = JSONObject(design.toString())
        val shown = plan.items.filter(wanted)
        val shownViews = shown.mapNotNull { it.view }.toSet()
        for (key in copy.keys().asSequence().toList()) {
            val view = copy.optJSONObject(key) ?: continue
            if (!view.has(VISIBILITY) || key == BACKGROUND || key in shownViews) continue
            if (key in textless && view.has(BACKGROUND)) view.put("fontColor", "#00000000") else view.put(VISIBILITY, "gone")
        }
        if (!keepBackground) copy.put(BACKGROUND, JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put(VISIBILITY, "visible"))
        val animation = copy.optJSONObject(WakeAnimationSpec.JSON_KEY)
        if (animation != null && layers != null) {
            val shownLayers = shown.mapNotNull { it.layer }.toSet()
            animation.put(WakeAnimationJson.LAYERS, JSONArray().apply {
                layers.forEachIndexed { index, layer ->
                    val l = JSONObject(layer.toString())
                    if (index !in shownLayers && !l.has(VIEW)) l.put(VISIBLE, "0")
                    put(l)
                }
            })
        }
        return copy
    }

    companion object {

        private const val VIEW = "view"
        private const val VISIBLE = "visible"
        private const val VISIBILITY = "visibility"
        private const val BACKGROUND = "background"

        /** The background pictures of a design, by the name of their file. */
        private val BACKGROUND_FILES = listOf("Background", "BackgroundHigh", "BackgroundLow")

        /**
         * The design [source] with [json] as its json. A [transparent] one gets a clear background
         * picture: without one the face draws its own default background.
         */
        fun asCwf(source: CwfData, json: JSONObject, transparent: Boolean): CwfData {
            val res = if (!transparent) source.resData else source.resData.toMutableMap().apply {
                BACKGROUND_FILES.forEach { remove(it) }
                put(BACKGROUND_FILES.first(), ResData(WffPictures.CLEAR_PNG, ResFormat.PNG))
            }
            return CwfData(json.toString(), source.metadata, res)
        }

        /**
         * The data picture's copy of the design [source], as the watch needs it; null when the design
         * has nothing for the data picture or its layers cannot be told apart.
         */
        fun dataOf(source: CwfData): CwfData? {
            val design = JSONObject(source.json)
            val animation = WakeAnimationJson({ _, default -> default }) { null }.read(design)
            val plan = WffPlanner().plan(design, animation)
            if (plan.items.none { it.where == WffPlanner.DATA }) return null
            val parts = WffDesignParts(design, animation, plan)
            return if (parts.isUsable) asCwf(source, parts.data(), transparent = true) else null
        }
    }
}
