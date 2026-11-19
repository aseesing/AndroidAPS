// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import app.aaps.core.interfaces.rx.weardata.CwfResDataMap
import app.aaps.shared.impl.weardata.toDrawable
import org.json.JSONObject

/**
 * The picture a view of a design shows with the view key [KEY] (`"image"`): a picture of the zip by
 * its name, an app picture by `@` and its name (`"@moon"`, the photo of the full moon, see
 * [DynamicImages]), or a layer template of the design drawn in the view (`{"use": "moon"}`, see
 * [ViewTemplates]). It is the background of the view, like the `background` key, and is coloured the
 * same way.
 *
 * Keeps the drawable of each view while its picture stays the same, so a style pass every minute makes
 * no new drawable. One instance per watch face.
 */
class ViewImages(private val resources: Resources, private val dynamic: DynamicImages, private val templates: ViewTemplates? = null) {

    private val kept = HashMap<String, Pair<Any, Drawable>>()

    /**
     * The picture of the view [key] (design json [view]) of [width] x [height] pixels, at the clock
     * time [nowMs], with the pictures of the zip [zip]; null when the view has no [KEY] or the picture
     * is not there.
     */
    fun drawable(key: String, view: JSONObject?, width: Int, height: Int, nowMs: Long, zip: CwfResDataMap): Drawable? {
        (view?.opt(KEY) as? JSONObject)?.let { use -> return templates?.drawable(key, use, width, height, nowMs) }
        val name = view?.optString(KEY)?.takeIf { it.isNotEmpty() } ?: return null
        if (name.startsWith(DYNAMIC_PREFIX)) {
            val bitmap = dynamic.bitmap(name.removePrefix(DYNAMIC_PREFIX), nowMs, minOf(width, height)) ?: return null
            kept[key]?.let { if (it.first === bitmap) return it.second }
            return BitmapDrawable(resources, bitmap).also { kept[key] = bitmap to it }
        }
        val data = zip[name] ?: return null
        kept[key]?.let { if (it.first === data) return it.second }
        return data.toDrawable(resources, width, height)?.also { kept[key] = data to it }
    }

    companion object {

        /** The view key of the picture. */
        const val KEY = "image"

        /** A name that starts with this is a picture the app draws itself. */
        const val DYNAMIC_PREFIX = "@"
    }
}
