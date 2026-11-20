// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the parts of a slot of the wearer (`complication1` to `complication5`) go. Our engine draws
 * these slots with the watch's own complication renderer (androidx `ComplicationRenderer`, with its
 * `RangedValueLayoutHelper` and `ShortTextLayoutHelper`), so this repeats the steps of that renderer,
 * with the same whole pixel steps, and the WFF slot lands on the same pixels.
 *
 * It takes a slot that shows a text and an icon, without a title: what the AAPS sources of the
 * slots send. All values are whole pixels of the face, from the top left corner of the slot.
 */
internal object WffSlotLayout {

    /** A box in whole pixels, as Android's `Rect`: left, top, right and bottom. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {

        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isEmpty: Boolean get() = width <= 0 || height <= 0
        val centerX: Int get() = (left + right) shr 1
        val centerY: Int get() = (top + bottom) shr 1

        fun inset(dx: Int, dy: Int) = Box(left + dx, top + dy, right - dx, bottom - dy)
        fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)

        /** The part this box has in common with [other], or null when they do not overlap (as `Rect.intersect`). */
        fun intersect(other: Box): Box? =
            if (left < other.right && other.left < right && top < other.bottom && other.top < bottom)
                Box(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
            else null

        /** True when [other] lies fully in this box (as `Rect.contains`). */
        fun contains(other: Box): Boolean = !isEmpty && left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
    }

    /**
     * The parts of one slot.
     *
     * @param radius the corner radius of the fill and the border
     * @param ring the box of the ring's centre line (the stroke lies on both sides of it), or null when the type has no ring
     * @param icon the box of the icon, or null when there is no room for it
     * @param text the box of the text
     * @param textStart the room left of a text that starts at the left
     * @param centred the text is centred in its box; otherwise it starts at the left
     * @param textSize the size of the text: the size the design asks for, at most the height of the text box, and small enough for 4 digits
     */
    data class Parts(val radius: Int, val ring: Box?, val icon: Box?, val text: Box, val textStart: Int, val centred: Boolean, val textSize: Int)

    /**
     * The parts of a slot of [width] x [height] pixels.
     *
     * @param borderRadius the corner radius the design asks for
     * @param ringWidth the thickness of the ring
     * @param ranged the slot shows a value with a range (`RANGED_VALUE`): a ring with the icon and the text in it
     * @param textSize the text size the design asks for, or null to fill the text box
     */
    fun of(width: Int, height: Int, borderRadius: Int, ringWidth: Int, ranged: Boolean, textSize: Int?): Parts {
        val bounds = Box(0, 0, width, height)
        val radius = min(min(width, height) / 2, borderRadius)
        // LayoutUtils.isWideRectangle
        val wide = width > height * WIDE_RATIO
        var ring: Box? = null
        val icon: Box
        val text: Box
        if (ranged) {
            // RangedValueLayoutHelper: the ring in the square on the left, or in the middle of a slot that is not wide
            val rangedBox = scaled(if (wide) leftPart(bounds) else centralSquare(bounds), RANGED_VALUE_SIZE_FRACTION)
            val inner = scaled(rangedBox, (1 - INNER_SQUARE_PADDING_FRACTION * 2) * INNER_SQUARE_SIZE_FRACTION)
            // ComplicationRenderer.drawRangedValue moves the arc in by its thickness, rounded up
            ring = rangedBox.inset(ringWidth, ringWidth)
            if (wide) {
                // The icon alone in the ring, the text right of it
                icon = scaled(inner, 1 - ICON_PADDING_FRACTION * 2)
                text = rightPart(bounds)
            } else {
                // A short text slot inside the ring
                val square = Box(0, 0, inner.width, inner.height)
                icon = shortTextIcon(square).offset(inner.left, inner.top)
                text = shortTextText(square).offset(inner.left, inner.top)
            }
        } else {
            icon = shortTextIcon(bounds)
            text = shortTextText(bounds)
        }
        // ComplicationRenderer.calculateBounds: a text that starts at the left keeps a tenth of the slot
        // height free before it, as a share of the width of its box before the corners cut it
        val centred = !wide
        val padding = if (centred) 0f else TEXT_PADDING_HEIGHT_FRACTION * height / text.width
        // The rounded corners cut the text and the icon
        val innerBounds = bounds.inset(cornerInset(radius), cornerInset(radius))
        val textBox = text.intersect(innerBounds) ?: Box(0, 0, 0, 0)
        val textStart = (textBox.width * padding).toInt()
        // TextRenderer.updateLayout makes a text smaller until its first 7 letters fit the width. WFF
        // cannot do that for a text it does not know, so the size is kept small enough for 4 digits
        // (steps, a heart rate, carbs): longer texts are cut, shorter ones are a little smaller.
        val fitWidth = ((textBox.width - textStart) / (FIT_CHARACTERS * DIGIT_WIDTH)).toInt()
        return Parts(
            radius = radius,
            ring = ring,
            icon = fitSquareToBounds(icon, innerBounds),
            text = textBox,
            // TextRenderer.calculateBounds
            textStart = textStart,
            centred = centred,
            // TextRenderer.updateLayout: one line, at most as high as its box
            textSize = minOf(textBox.height, textSize ?: Int.MAX_VALUE, fitWidth)
        )
    }

    /** ShortTextLayoutHelper.getIconBounds, with an icon: the square on the left of a wide slot, else the top half of the middle square. */
    private fun shortTextIcon(bounds: Box): Box =
        if (bounds.width > bounds.height * WIDE_RATIO) leftPart(bounds) else centralSquare(topHalf(centralSquare(bounds)))

    /** ShortTextLayoutHelper.getShortTextBounds, with an icon: right of the icon in a wide slot, else the bottom half of the middle square. */
    private fun shortTextText(bounds: Box): Box =
        if (bounds.width > bounds.height * WIDE_RATIO) rightPart(bounds) else bottomHalf(centralSquare(bounds))

    // ---- LayoutUtils ----

    private fun leftPart(b: Box): Box = if (b.width < b.height) Box(0, 0, 0, 0) else Box(b.left, b.top, b.left + b.height, b.bottom)
    private fun rightPart(b: Box): Box = if (b.width < b.height) b else Box(b.left + b.height, b.top, b.right, b.bottom)
    private fun topHalf(b: Box): Box = Box(b.left, b.top, b.right, (b.top + b.bottom) / 2)
    private fun bottomHalf(b: Box): Box = Box(b.left, (b.top + b.bottom) / 2, b.right, b.bottom)

    private fun centralSquare(b: Box): Box {
        val edge = min(b.width, b.height)
        return Box(b.centerX - edge / 2, b.centerY - edge / 2, b.centerX + edge / 2, b.centerY + edge / 2)
    }

    private fun scaled(b: Box, sizeFraction: Float): Box {
        val padding = 0.5f - sizeFraction / 2
        return b.inset((b.width * padding).toInt(), (b.height * padding).toInt())
    }

    /** LayoutUtils.getInnerBounds: how far the rounded corners push the content in. */
    private fun cornerInset(radius: Int): Int = ceil((sqrt(2.0) - 1.0) * radius).toInt()

    private fun fitSquareToBounds(square: Box, container: Box): Box? {
        if (square.isEmpty) return null
        val cut = square.intersect(container) ?: return null
        val centred = centralSquare(cut)
        val moved = centred.offset(square.centerX - centred.centerX, square.centerY - centred.centerY)
        return if (container.contains(moved)) moved else centred
    }

    /** The ring thickness of our engine when the design sets none, in design pixels (`PROVISIONAL_RING_WIDTH`). */
    const val ENGINE_RING_WIDTH = 9

    /** The border thickness of our engine when the design sets none, in design pixels. */
    const val ENGINE_BORDER_WIDTH = 2

    /** The colour our engine uses for a colour key it cannot read (Android's `Color.GRAY`). */
    const val ENGINE_GRAY = "#FF888888"

    /** How many digits the text of a slot must fit, see [of]. */
    private const val FIT_CHARACTERS = 4

    /** The width of a digit of Roboto, the font of the watch, as a share of the text size. */
    private const val DIGIT_WIDTH = 0.5615

    private const val WIDE_RATIO = 2
    private const val RANGED_VALUE_SIZE_FRACTION = 0.95f
    private val INNER_SQUARE_SIZE_FRACTION = (1.0 / sqrt(2.0)).toFloat()
    private const val INNER_SQUARE_PADDING_FRACTION = 0.15f
    private const val ICON_PADDING_FRACTION = 0.15f
    private const val TEXT_PADDING_HEIGHT_FRACTION = 0.1f
}
