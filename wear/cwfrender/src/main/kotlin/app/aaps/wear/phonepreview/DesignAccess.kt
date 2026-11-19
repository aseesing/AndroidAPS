// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.colour.ColourLooks
import app.aaps.wear.watchfaces.daynight.MoonPhoto
import app.aaps.wear.watchfaces.daynight.StoredWeather
import app.aaps.wear.watchfaces.design.Looks
import app.aaps.wear.watchfaces.utils.ComplicationStyleValues
import app.aaps.wear.watchfaces.utils.FrameParts
import org.json.JSONObject
import kotlin.math.max

/**
 * Public access to a few internal parts of the watch face code, for the repository of the watch face
 * designs (`aaps-watchfaces`). That repository makes the design zips with this module, from another
 * Gradle build, where `internal` cannot be seen. Nothing in AAPS uses this.
 */
object DesignAccess {

    /** The wake-up animation of [json] as [face] reads it; [image] finds a picture of the design by its key. */
    fun readWakeAnimation(face: CustomWatchface, json: JSONObject, image: (String) -> Drawable?): WakeAnimationSpec? =
        face.readWakeAnimation(json, image)

    /** The parts of [face] that a wake frame draws: the real views, with a clock of your own. */
    fun wakeParts(face: CustomWatchface): FrameParts = face.wakeParts

    /** The picture of a `@moon` layer of [face] (the photo of the full moon), in a square of [size] pixels, as the watch draws it. */
    fun wakeMoon(face: CustomWatchface, epochMs: Long, size: Int): Bitmap? = face.dynamicImage(MoonPhoto.NAME, epochMs, size)

    /**
     * [design] as the watch shows it in the day ([isDay]) or at night, in [weather]
     * (`clear`, `partly`, `cloudy`, `rain`, or null when unknown): its looks laid over it, see [Looks].
     */
    fun lookOf(design: JSONObject, isDay: Boolean, weather: String?): JSONObject = look(design, isDay, weather).json

    /** The pictures [images] (name to data) of [design] as the watch shows them, for [isDay] and [weather] as in [lookOf]. */
    fun <T> picturesOf(design: JSONObject, images: Map<String, T>, isDay: Boolean, weather: String?): Map<String, T> =
        look(design, isDay, weather).swapped(images)

    private fun look(design: JSONObject, isDay: Boolean, weather: String?): Looks.Look {
        val values = mapOf("is_day" to if (isDay) 1.0 else 0.0, StoredWeather.KIND_VALUE_KEY to StoredWeather.kindNumber(weather))
        return Looks().apply(design) { values[it] }
    }

    /** How many colour choices the watch shows for [design]. */
    fun colourChoiceCount(design: JSONObject?): Int = ColourLooks.rows(design).size

    /**
     * Styles [drawable] for the slot [view] of a design, with [typeface] for its texts: the keys the view
     * leaves out get the values of the complication library.
     */
    fun styleSlot(drawable: ComplicationDrawable, view: JSONObject, typeface: Typeface) {
        fun color(key: String, default: Int) = view.optString(key).takeIf { it.isNotEmpty() }?.let { Color.parseColor(it) } ?: default
        val defaults = ComplicationStyleValues.LIBRARY_DEFAULTS
        val textColor = color("fontColor", defaults.textColor)
        ComplicationStyleValues(
            textColor = textColor,
            titleColor = defaults.titleColor,
            iconColor = color("iconColor", textColor),
            backgroundColor = color("color", defaults.backgroundColor),
            textTypeface = typeface,
            titleTypeface = typeface,
            borderRadius = view.optInt("borderRadius"),
            ringWidth = max(view.optInt("ringWidth"), ComplicationStyleValues.MIN_RING_WIDTH_PX),
            textSize = view.optInt("textsize").takeIf { it > 0 } ?: ComplicationStyleValues.TEXT_SIZE_FIT_BOX,
            titleSize = ComplicationStyleValues.TEXT_SIZE_FIT_BOX,
            ringPrimaryColor = color("ringPrimaryColor", defaults.ringPrimaryColor),
            ringSecondaryColor = color("ringSecondaryColor", defaults.ringSecondaryColor),
            borderColor = color("borderColor", defaults.borderColor),
            borderWidth = view.optInt("borderWidth")
        ).applyTo(drawable)
    }
}
