package app.aaps.wear.complications.wff

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.toDrawable
import app.aaps.wear.R

/**
 * The trend arrow pictures for [WffGlucoseComplication]: the design's own `Arrow*` files where the
 * design has them, else the app's own arrows. The symbols are the ones the phone sends in
 * `SingleBg.slopeArrow`, the same table as the Custom watch face uses.
 */
object GlucoseArrows {

    private class Arrow(val symbol: String, val file: ResFileMap, @DrawableRes val icon: Int)

    private val ARROWS = listOf(
        Arrow("??", ResFileMap.ARROW_NONE, R.drawable.ic_invalid),
        Arrow("X", ResFileMap.ARROW_DOUBLE_UP, R.drawable.ic_doubleup),
        Arrow("⇈", ResFileMap.ARROW_DOUBLE_UP, R.drawable.ic_doubleup),
        Arrow("↑", ResFileMap.ARROW_SINGLE_UP, R.drawable.ic_singleup),
        Arrow("↗", ResFileMap.ARROW_FORTY_FIVE_UP, R.drawable.ic_fortyfiveup),
        Arrow("→", ResFileMap.ARROW_FLAT, R.drawable.ic_flat),
        Arrow("↘", ResFileMap.ARROW_FORTY_FIVE_DOWN, R.drawable.ic_fortyfivedown),
        Arrow("↓", ResFileMap.ARROW_SINGLE_DOWN, R.drawable.ic_singledown),
        Arrow("⇊", ResFileMap.ARROW_DOUBLE_DOWN, R.drawable.ic_doubledown)
    )

    /** Size of a design arrow picture, in pixels. The face scales it into its own box. */
    private const val SIZE = 96

    /** The app's own arrow for [symbol]; an unknown symbol gives the "no arrow" picture. */
    fun appArrow(context: Context, symbol: String): Icon =
        Icon.createWithResource(context, (ARROWS.firstOrNull { it.symbol == symbol } ?: ARROWS.first()).icon)

    /** The design's own arrows by symbol, only for the symbols the design has a picture for. */
    fun fromDesign(design: CwfData?, resources: Resources): Map<String, Icon> {
        design ?: return emptyMap()
        val result = mutableMapOf<String, Icon>()
        for (arrow in ARROWS) {
            val drawable = design.resData[arrow.file.fileName]?.toDrawable(resources) ?: continue
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, SIZE, SIZE)
            drawable.draw(Canvas(bitmap))
            result[arrow.symbol] = Icon.createWithBitmap(bitmap)
        }
        return result
    }
}
