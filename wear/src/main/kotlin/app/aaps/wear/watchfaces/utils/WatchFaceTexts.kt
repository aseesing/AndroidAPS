package app.aaps.wear.watchfaces.utils

import android.content.Context
import android.graphics.Typeface
import android.text.format.DateFormat
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R
import app.aaps.wear.watchfaces.animation.FaceTexts
import java.util.Locale

/**
 * What the text layers of a design read of this watch: the fonts of the design, the language, 12 or
 * 24 hours, and the glucose unit the phone sent.
 *
 * @param sp the settings of the watch, asked when a text is made (they are not there yet when the face is made)
 * @param fontOf the font of a font key of the design (a font of the zip or of the app)
 */
class WatchFaceTexts(private val context: Context, private val sp: () -> SP, private val fontOf: (String) -> Typeface?) : FaceTexts {

    override fun font(key: String): Typeface? = fontOf(key)

    override val locale: Locale get() = context.resources.configuration.locales[0] ?: Locale.getDefault()

    override val is24Hour: Boolean get() = DateFormat.is24HourFormat(context)

    override val unitsMgdl: Boolean get() = sp().getBoolean(R.string.key_units_mgdl, true)
}
