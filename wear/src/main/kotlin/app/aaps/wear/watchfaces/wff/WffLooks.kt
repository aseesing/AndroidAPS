// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.AnimLayerSpec
import app.aaps.wear.watchfaces.animation.LayerSource
import org.json.JSONObject

/**
 * What the looks (`looks`) and the colours of the wearer (`colourLooks`) of a design change, for the
 * [WffPlanner]. Both change the design on the watch while it runs: a look when its condition turns
 * true (day, the weather), a colour when the wearer picks one. A baked picture cannot follow them, the
 * data picture can (the watch draws it with the whole design), and a live part keeps the colour of
 * the design.
 *
 * Found with the parity suite: before this, such parts were baked as they were at build time, and
 * the lint said nothing.
 */
class WffLooks(design: JSONObject) {

    /** The views that a look changes, by key, also through the `dynData` entry they show. */
    val lookViews: Set<String>

    /** The pictures of the zip that a look swaps for another one. */
    val lookImages: Set<String>

    /** The views whose colour the wearer can pick. */
    val colourViews: Set<String>

    /** A look or a colour of the wearer changes the layers of the animation. */
    val changesLayers: Boolean

    init {
        val views = HashSet<String>()
        val images = HashSet<String>()
        var layers = false
        val looks = design.optJSONArray("looks")
        for (i in 0 until (looks?.length() ?: 0)) {
            val look = looks?.optJSONObject(i) ?: continue
            look.optJSONObject("patch")?.let { patch ->
                for (key in patch.keys()) when (key) {
                    "dynData"   -> patch.optJSONObject(key)?.keys()?.forEach { entry -> views += viewsShowing(design, entry) }
                    "animation" -> layers = true
                    else        -> views += key
                }
            }
            look.optJSONObject("images")?.keys()?.forEach { images += it }
        }
        // A view shows a swapped picture as its own picture or as its background
        for (key in design.keys()) {
            val view = design.optJSONObject(key) ?: continue
            val picture = if (key == "background") "Background" else view.optString("image").ifEmpty { view.optString("background") }
            if (picture in images) views += key
        }
        val colours = HashSet<String>()
        val colourLooks = design.optJSONObject("colourLooks")
        for (slot in colourLooks?.keys()?.asSequence().orEmpty()) {
            val patch = colourLooks?.optJSONObject(slot) ?: continue
            for (key in patch.keys()) when (key) {
                "dynData"   -> patch.optJSONObject(key)?.keys()?.forEach { entry -> colours += viewsShowing(design, entry) }
                "animation" -> layers = true
                else        -> colours += key
            }
        }
        lookViews = views
        lookImages = images
        colourViews = colours
        changesLayers = layers
    }

    /** True when a look or a colour of the wearer changes [layer]. */
    fun changes(layer: AnimLayerSpec): Boolean = changesLayers || (layer.source as? LayerSource.Image)?.key in lookImages

    private fun viewsShowing(design: JSONObject, entry: String): List<String> =
        design.keys().asSequence().filter { design.optJSONObject(it)?.optString("dynData") == entry }.toList()
}
