// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

/**
 * The heights of a font in parts of its size, as Android's `Paint.FontMetrics` gives them: [top] and
 * [ascent] lie above the base line and are below 0, [descent] and [bottom] lie below it.
 */
data class WffFontMetrics(val top: Double, val ascent: Double, val descent: Double, val bottom: Double) {

    companion object {

        /** Roboto, the font of the watch when a design names none. */
        val ROBOTO = WffFontMetrics(top = -1.056, ascent = -0.928, descent = 0.244, bottom = 0.271)
    }
}

/** Gives the [WffFontMetrics] of a font of the design: a key of its zip, or a system font such as `default`. */
fun interface WffFontMeasure {

    fun metrics(font: String, bold: Boolean): WffFontMetrics
}

/**
 * Where a text sits in its box. WFF centres a line of text in its box by the ascent and the descent of
 * the font (proven on the emulator with the parity suite). A text view of the classic face (Android's
 * `TextView`) places it by the top and the bottom of the font instead, which leave room for accents,
 * and a text that is higher than its view starts at the top of the view. So the same box shows the
 * text at another height, and the converter moves the WFF box by the difference.
 */
object WffTextPlace {

    /** The base line of a WFF text in a box of [height], from the top of the box, for a font of [size]. */
    fun wffBaseline(m: WffFontMetrics, size: Double, height: Double): Double = (height - (m.descent - m.ascent) * size) / 2 - m.ascent * size

    /**
     * The base line of a classic text view of [height], from its top. [centred]: the view's gravity is
     * `center`. `left` and `right` keep the text at the top of the view.
     */
    fun viewBaseline(m: WffFontMetrics, size: Double, height: Double, centred: Boolean): Double {
        val textHeight = (m.bottom - m.top) * size
        val offset = if (centred && textHeight < height) (height - textHeight) / 2 else 0.0
        return offset - m.top * size
    }

    /** How far a WFF box with the place of a classic text view must move down, so its text shows where the view shows it. */
    fun viewShift(m: WffFontMetrics, size: Double, height: Double, centred: Boolean): Double =
        viewBaseline(m, size, height, centred) - wffBaseline(m, size, height)
}
