// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Color
import android.graphics.drawable.Drawable

/**
 * One animated layer of a design: a picture, a picture the app draws (like the moon), a simple shape,
 * a text or a view of the face, drawn once or many times, where every property can be a [Formula] of the
 * time and of the copy. A design gives them in the `layers` list of its `animation`, directly or as
 * templates (`{"use": "rain", ...}`, see [Templates]).
 *
 * Plain values only: the formulas are kept as text and read by [LayerAnimator]. Positions and sizes
 * are in the 400 x 400 design space.
 *
 * @param source what is drawn
 * @param slot where in the face it is drawn
 * @param blend how it is laid over what is below
 * @param group the name of the group the layer is in, or null. Layers of one slot that follow each other
 *   with the same name are drawn together on a sheet of their own, which is then laid over the face. So
 *   [LayerBlend.ERASE] and [LayerBlend.INSIDE] only change the layers of the group, never the face below.
 *   The sheet is as big as the [LayerClip.rect] of the first layer of the group that is drawn, or the
 *   whole face when that has none.
 * @param copies how many times it is drawn, and how the copies differ
 * @param props the formulas of the properties; a missing one has its default (see [LayerProp])
 * @param lets named formulas of this layer, in order, worked out once per copy before the properties
 *   (the `let` of the layer); each may use the ones before it
 * @param clip where it may show
 * @param glow a soft glow round a picture or a text, as strong as [LayerProp.GLOW]
 * @param maxScale the largest scale the picture is drawn at; it is made sharp for that size
 * @param restSeconds when set, the layer is also drawn while no animation plays, as it is this many
 *   seconds into a wake (with `env` 0). A view layer with a rest also places its view in every normal
 *   frame. Such a layer is still: while seconds are shown the face keeps its pictures for a whole
 *   minute, so a rest layer that changes with the time (`sec`, `min`) only moves once a minute.
 */
data class AnimLayerSpec(
    val source: LayerSource,
    val slot: LayerSlot = LayerSlot.BACK,
    val blend: LayerBlend = LayerBlend.NORMAL,
    val group: String? = null,
    val copies: LayerCopies = LayerCopies.One,
    val props: Map<LayerProp, String> = emptyMap(),
    val lets: List<Pair<String, String>> = emptyList(),
    val clip: LayerClip = LayerClip(),
    val glow: LayerGlow? = null,
    val maxScale: Float = 1f,
    val restSeconds: Float? = null
) {

    /** Values cut to safe limits; null when the layer can never draw anything. */
    fun normalized(): AnimLayerSpec? {
        if (source is LayerSource.Image && source.drawable == null) return null  // not in the zip
        if (clip.mask != null && clip.mask.drawable == null) return null       // not in the zip: never drawn without its mask
        if (source is LayerSource.View && source.key.isEmpty()) return null
        if (blend.needsGroup && group == null) return null                      // would cut holes in the face
        if (source is LayerSource.Shape && source.colors.isEmpty()) return null
        if (source is LayerSource.Text && (source.colors.isEmpty() || source.text.isEmpty() || source.text.length > Formula.MAX_LENGTH)) return null
        if (source is LayerSource.Dynamic && !(source.rect.width > 0f && source.rect.height > 0f)) return null
        if (props.values.any { it.length > Formula.MAX_LENGTH } || lets.any { it.second.length > Formula.MAX_LENGTH }) return null
        return copy(
            source = if (source is LayerSource.Text) source.normalized() else source,
            copies = copies.normalized(),
            lets = lets.take(MAX_LETS),
            maxScale = maxScale.coerceIn(0.1f, MAX_SCALE),
            glow = glow?.let { it.copy(radius = it.radius.coerceIn(0f, 40f)) }?.takeIf { it.radius > 0f },
            restSeconds = restSeconds?.coerceIn(0f, WakeAnimationSpec.MAX_DURATION_MS / 1000f),
            slot = if (source is LayerSource.View) LayerSlot.VIEWS else slot,
            group = group.takeIf { source !is LayerSource.View }
        )
    }

    /** The same values without the picture objects (of the source and the mask), which are made new each time the design is read. */
    fun withoutImage(): AnimLayerSpec = copy(
        source = if (source is LayerSource.Image) source.copy(drawable = null) else source,
        clip = if (clip.mask != null) clip.copy(mask = clip.mask.copy(drawable = null)) else clip
    )

    companion object {

        /** The most layers in one design. */
        const val MAX_LAYERS = 40

        /** The most copies of one layer. */
        const val MAX_COPIES = 400

        /** The most copies of all layers together, so a frame stays cheap. */
        const val MAX_TOTAL_COPIES = 1500

        /** The most named formulas of one layer, and of the animation. */
        const val MAX_LETS = 16

        const val MAX_SCALE = 4f
    }
}

/** What a layer draws. */
sealed interface LayerSource {

    /**
     * A picture of the design zip, drawn into [rect] (moved by `x` and `y`).
     *
     * @param key the key of the picture in the zip (the file name without extension)
     */
    data class Image(val key: String, val drawable: Drawable?, val rect: DesignRect) : LayerSource

    /**
     * A picture that comes with the app, asked for by its [name] (`"image": "@moon"` in the design): the
     * photo of the full moon (see `MoonPhoto`). Drawn in the middle of [rect] as a square of its
     * shorter side, and moved by `x` and `y` like a picture. Has no glow.
     */
    data class Dynamic(val name: String, val rect: DesignRect) : LayerSource {

        /** The square the picture is drawn in. */
        val square: DesignRect
            get() {
                val side = minOf(rect.width, rect.height)
                return DesignRect(rect.left + (rect.width - side) / 2, rect.top + (rect.height - side) / 2, side, side)
            }
    }

    /**
     * A simple shape in one of [colors]; the `colorIndex` property picks which (0 = the first). A
     * [gradient] makes the colour fade over the shape.
     */
    data class Shape(val kind: ShapeKind, val colors: List<Int> = listOf(Color.WHITE), val gradient: LayerGradient? = null) : LayerSource

    /**
     * A text, worked out by the formula [text] (see [TextFormula]) and drawn in one line at `x`,`y`, at
     * the exact place the formulas give, not snapped to whole pixels. So two text layers with the same
     * text, place and size lie exactly on each other, also in two fonts whose signs are as wide.
     * `rotation` and `scale` turn and scale it round `x`,`y`.
     *
     * @param text the formula of the text
     * @param font the key of a font of the design zip, or the name of a font of the app; empty for the default font
     * @param size the height of the font in design pixels
     * @param align which point of the line is at `x`
     * @param anchor which height of the line is at `y`, from the measures of the font (not of the
     *   signs shown, so the line does not jump when the text changes)
     * @param letterSpacing extra room between the signs, in parts of the font size
     * @param colors the colours; the `colorIndex` property picks which (0 = the first)
     * @param gradient makes the colour fade over the line: [LayerGradient.Type.LINEAR] from its left to
     *   its right end, [LayerGradient.Type.RADIAL] in a circle from its middle to its ends
     */
    data class Text(
        val text: String,
        val font: String = "",
        val size: Float = DEFAULT_SIZE,
        val align: TextAlign = TextAlign.LEFT,
        val anchor: TextAnchor = TextAnchor.BASELINE,
        val letterSpacing: Float = 0f,
        val colors: List<Int> = listOf(Color.WHITE),
        val gradient: LayerGradient? = null
    ) : LayerSource {

        /** Values cut to safe limits. */
        fun normalized(): Text = copy(size = size.coerceIn(MIN_SIZE, MAX_SIZE), letterSpacing = letterSpacing.coerceIn(-0.5f, 2f))

        companion object {

            const val DEFAULT_SIZE = 24f
            const val MIN_SIZE = 1f
            const val MAX_SIZE = 400f
        }
    }

    /**
     * A view of the face, for example `chart`. `x` and `y` move it, `rotation` turns it more than it is,
     * `scaleX`, `scaleY` and `alpha` multiply its own, and `clipLeft`, `clipTop`, `clipRight` and
     * `clipBottom` cut it. Its own turn, move and middle are the variables `rot0`, `dx0`, `cx0` and `cy0`.
     */
    data class View(val key: String) : LayerSource
}

/** Which point of a text line is at `x`: its left end, its middle or its right end. */
enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * Which height of a text line is at `y`: the line the letters stand on, the top of the font, the
 * middle between top and bottom, or the bottom of the font.
 */
enum class TextAnchor { BASELINE, TOP, MIDDLE, BOTTOM }

/**
 * The shapes a layer can draw:
 * - [LINE]: from `x`,`y` over `length`, at `angle` degrees from straight down (positive leans right), `thickness` wide, round ends
 * - [RECT]: `x`,`y` is the top left corner, `width` x `height`
 * - [CIRCLE]: a filled circle round `x`,`y`, `radius` big
 */
enum class ShapeKind { LINE, RECT, CIRCLE }

/**
 * A colour that fades over a shape: the alpha of the layer colour is multiplied by the [stops], from
 * one to the next. [LINEAR] runs along the width of a rectangle, from its left to its right edge;
 * [RADIAL] from the middle of a circle to its edge. A line has no gradient.
 *
 * A soft band is a rectangle with the stops `[[0, 0], [0.5, 1], [1, 0]]`, a soft round light a circle
 * with the radial stops `[[0, 1], [0.25, 1], [1, 0]]`.
 */
data class LayerGradient(val type: Type, val stops: List<Stop>) {

    enum class Type { LINEAR, RADIAL }

    /** At [position] (0 to 1) the alpha of the colour is multiplied by [alpha] (0 to 1). */
    data class Stop(val position: Float, val alpha: Float)
}

/**
 * Where a layer is drawn, between the parts of the face:
 * - [BACK]: on the background, below every data view
 * - [VIEWS]: with the views that move, above the other data and below the clock text
 * - [FRONT]: above all the data, below the second hand
 */
enum class LayerSlot { BACK, VIEWS, FRONT }

/**
 * How a layer is laid over what is below it. [ADD] and [SCREEN] only make things lighter: for light,
 * like an aurora or a glow. Each also has its name in the Watch Face Format ([wff]): they are drawn with
 * exactly those modes.
 *
 * [ERASE] and [INSIDE] work on the group of the layer (see [AnimLayerSpec.group]) and need one:
 * - [ERASE]: the layer is not drawn; it takes away what the group has drawn so far, as much as the
 *   layer is not clear.
 * - [INSIDE]: the layer is drawn only where the group has drawn something so far, as strong as that
 *   is, and it replaces it there. Only inside the box of the layer itself (the rectangle of a picture,
 *   the outline of a shape): keep what was drawn before inside that box.
 */
enum class LayerBlend(val wff: String, val needsGroup: Boolean = false) {

    NORMAL("SRC_OVER"), ADD("PLUS"), SCREEN("SCREEN"), ERASE("DST_OUT", true), INSIDE("SRC_IN", true);

    companion object {

        /** The blend called [name], by our name or by its name in the Watch Face Format ([wff]), in any case; null when there is none. */
        fun of(name: String): LayerBlend? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.wff.equals(name, ignoreCase = true) }
    }
}

/** How many times a layer is drawn. Each copy has its own number `i` (from 0) of `n`, and `u` = i / (n - 1). */
sealed interface LayerCopies {

    val size: Int

    fun normalized(): LayerCopies = this

    /** Drawn once. */
    data object One : LayerCopies {

        override val size: Int get() = 1
    }

    /**
     * Drawn [count] times. Each copy has its own random numbers `r1` to `r8`, from 0 to 1, the same
     * every time for the same [seed]. Made with [kotlin.random.Random]: copy 0 takes the first ones,
     * in the order `r1`, `r2`, ... of the numbers the formulas use.
     */
    data class Count(val count: Int, val seed: Long = 1) : LayerCopies {

        override val size: Int get() = count
        override fun normalized() = copy(count = count.coerceIn(0, AnimLayerSpec.MAX_COPIES))
    }

    /**
     * The picture cut into [count] strips, each drawn on its own: side by side when [vertical] (strips
     * that stand up, cut along x), else stacked. For a picture that waves, like an aurora.
     */
    data class Slices(val count: Int, val vertical: Boolean = true) : LayerCopies {

        override val size: Int get() = count
        override fun normalized() = copy(count = count.coerceIn(1, MAX_SLICES))

        companion object {

            const val MAX_SLICES = 200
        }
    }

    /**
     * One copy at each of [points], with the variables `px` and `py`, and `p0`, `p1`, ... for the extra
     * numbers of the point; for example the bulbs of a string of lights, each with its own phase.
     */
    data class Points(val points: List<DesignPoint>) : LayerCopies {

        override val size: Int get() = points.size
        override fun normalized() = copy(points = points.take(AnimLayerSpec.MAX_COPIES))
    }
}

/** A point in the 400 x 400 design space, with up to [LayerVariables.EXTRAS] [extras] (`p0`, `p1`, ...). */
data class DesignPoint(val x: Float, val y: Float, val extras: List<Double> = emptyList())

/**
 * Where a layer may show: inside [rect] when set, inside the round screen when [round], inside the
 * [rings] and [boxes] when [hasShapes] (corners of the boxes rounded by [cornerRadius]), and where the
 * picture of [mask] is not clear when there is one. The props `clipLeft` to `clipBottom` cut it more.
 *
 * @param hasShapes true when the design gives `rings` or `boxes`, also as empty lists: then the layer
 *   shows only inside them, and nowhere when there are none
 */
data class LayerClip(
    val rect: DesignRect? = null,
    val round: Boolean = true,
    val rings: List<DesignRing> = emptyList(),
    val boxes: List<DesignRect> = emptyList(),
    val cornerRadius: Float = 0f,
    val mask: LayerMask? = null,
    val hasShapes: Boolean = rings.isNotEmpty() || boxes.isNotEmpty()
) {

    /** True when the shapes leave no room at all: the layer never shows. */
    val isEmpty: Boolean get() = hasShapes && rings.isEmpty() && boxes.isEmpty()
}

/** A ring in the 400 x 400 design space: the room between two circles round ([cx], [cy]). */
data class DesignRing(val cx: Float, val cy: Float, val inner: Float, val outer: Float)

/** A rectangle in the 400 x 400 design space. */
data class DesignRect(val left: Float, val top: Float, val width: Float, val height: Float) {

    val right: Float get() = left + width
    val bottom: Float get() = top + height

    companion object {

        /** The whole face. */
        val FULL = DesignRect(0f, 0f, DESIGN_SIZE, DESIGN_SIZE)
    }
}

/** The size of the design space every design is drawn in, scaled to the screen. */
const val DESIGN_SIZE = 400f

/**
 * A picture of the design zip that says where a layer shows: as much as the picture is not clear (its
 * alpha), so a half clear pixel shows the layer at half strength. Outside [rect] the layer does not
 * show at all. For example an aurora that only shows in the sky of a photo, not over the mountains.
 *
 * The layer is drawn on its own first and then laid over the face with its [LayerBlend], so a mask
 * never cuts holes in what is below the layer.
 *
 * @param key the key of the picture in the zip (the file name without extension)
 * @param drawable the picture, or null when it is not in the zip: then the layer is left out
 * @param rect where the picture lies, in the 400 x 400 design space
 */
data class LayerMask(val key: String, val drawable: Drawable?, val rect: DesignRect = DesignRect.FULL)

/** A soft glow of [color] round a picture, blurred over [radius] design pixels. */
data class LayerGlow(val color: Int, val radius: Float)

/**
 * The properties of a layer, with their json key and their value when the layer does not give one
 * (null: worked out from the layer, see the notes).
 */
enum class LayerProp(val key: String, val default: Double?) {

    X("x", 0.0),
    Y("y", 0.0),
    SCALE_X("scaleX", 1.0),
    SCALE_Y("scaleY", 1.0),
    ROTATION("rotation", 0.0),
    ALPHA("alpha", 1.0),

    /** The point the layer turns and scales round; by default the middle of the picture, or `x`,`y` of a shape. */
    PIVOT_X("pivotX", null),
    PIVOT_Y("pivotY", null),
    LENGTH("length", 20.0),
    ANGLE("angle", 0.0),
    WIDTH("width", 10.0),
    HEIGHT("height", 10.0),
    THICKNESS("thickness", 2.0),
    RADIUS("radius", 4.0),

    /** The layer is cut off left of, above, right of and below these (design pixels). */
    CLIP_LEFT("clipLeft", null),
    CLIP_TOP("clipTop", null),
    CLIP_RIGHT("clipRight", null),
    CLIP_BOTTOM("clipBottom", null),

    /** The layer (or the copy) is drawn only where this is true (not 0): checked before anything is drawn. */
    VISIBLE("visible", 1.0),

    /** How strong the glow of a picture or a text is, 0 to 1. */
    GLOW("glowAlpha", 0.0),

    /** Which of the colours of a shape or a text, 0 = the first. */
    COLOR("colorIndex", 0.0);

    companion object {

        fun of(key: String): LayerProp? = entries.firstOrNull { it.key == key }

        /** The props that cut a layer. */
        val CLIPS = listOf(CLIP_LEFT, CLIP_TOP, CLIP_RIGHT, CLIP_BOTTOM)
    }
}
