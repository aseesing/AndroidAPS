// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Color
import android.graphics.drawable.Drawable

/**
 * One animated layer of a design: a picture, a simple shape or a view of the face, drawn once or many
 * times, where every property can be a [Formula] of the time and of the copy. All the effects of the
 * wake-up animation are made of these (see [allLayers]); a design can also give its own in the
 * `layers` list of its `animation`.
 *
 * Plain values only: the formulas are kept as text and read by [LayerAnimator]. Positions and sizes
 * are in the 400 x 400 design space.
 *
 * @param source what is drawn
 * @param slot where in the face it is drawn
 * @param blend how it is laid over what is below
 * @param copies how many times it is drawn, and how the copies differ
 * @param props the formulas of the properties; a missing one has its default (see [LayerProp])
 * @param clip where it may show
 * @param glow a soft glow round a picture, as strong as [LayerProp.GLOW]
 * @param maxScale the largest scale the picture is drawn at; it is made sharp for that size
 * @param restSeconds when set, the layer is also drawn while no animation plays, as it is this many
 *   seconds into a wake. Only for the back slot: so an aurora or lights stay on the face after the wake.
 *   Such a layer is still: while seconds are shown the face keeps its background picture for a whole
 *   minute, so a rest layer that changes with the time (`sec`, `min`) only moves once a minute.
 */
data class AnimLayerSpec(
    val source: LayerSource,
    val slot: LayerSlot = LayerSlot.BACK,
    val blend: LayerBlend = LayerBlend.NORMAL,
    val copies: LayerCopies = LayerCopies.One,
    val props: Map<LayerProp, String> = emptyMap(),
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
        if (source is LayerSource.Shape && source.colors.isEmpty()) return null
        if (props.values.any { it.length > Formula.MAX_LENGTH }) return null
        return copy(
            copies = copies.normalized(),
            maxScale = maxScale.coerceIn(0.1f, MAX_SCALE),
            glow = glow?.let { it.copy(radius = it.radius.coerceIn(0f, 40f)) }?.takeIf { it.radius > 0f },
            restSeconds = restSeconds?.takeIf { slot == LayerSlot.BACK && source !is LayerSource.View }?.coerceIn(0f, WakeAnimationSpec.MAX_DURATION_MS / 1000f),
            slot = if (source is LayerSource.View) LayerSlot.VIEWS else slot
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

    /** A simple shape in one of [colors]; the `colorIndex` property picks which (0 = the first). */
    data class Shape(val kind: ShapeKind, val colors: List<Int> = listOf(Color.WHITE)) : LayerSource

    /**
     * A view of the face, for example `chart`: it can be turned (`rotation`, added to its own), moved
     * right (`x`), hidden (`alpha` 0) and cut at the right (`clipRight`). Its own turn and move are the
     * variables `rot0` and `dx0`.
     */
    data class View(val key: String) : LayerSource
}

/**
 * The shapes a layer can draw:
 * - [LINE]: from `x`,`y` over `length`, at `angle` degrees from straight down (positive leans right), `thickness` wide, round ends
 * - [RECT]: `x`,`y` is the top left corner, `width` x `height`
 * - [BAND]: a soft band, `width` wide and `height` high, its middle at `x`, its top at `y`, bright in the middle and clear at the sides
 * - [DOT]: a soft round light at `x`,`y`, `radius` big, bright in the middle and clear at the edge
 * - [CIRCLE]: a filled circle at `x`,`y`, `radius` big
 */
enum class ShapeKind { LINE, RECT, BAND, DOT, CIRCLE }

/**
 * Where a layer is drawn, between the parts of the face:
 * - [BACK]: on the background, below every data view
 * - [VIEWS]: with the views that move, above the other data and below the clock text
 * - [FRONT]: above all the data, below the second hand
 */
enum class LayerSlot { BACK, VIEWS, FRONT }

/** How a layer is laid over what is below it. [ADD] and [SCREEN] only make things lighter: for light, like an aurora or a glow. */
enum class LayerBlend { NORMAL, ADD, SCREEN }

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

    /** One copy at each of [points], with the variables `px` and `py`; for example the bulbs of a string of lights. */
    data class Points(val points: List<DesignPoint>) : LayerCopies {

        override val size: Int get() = points.size
        override fun normalized() = copy(points = points.take(AnimLayerSpec.MAX_COPIES))
    }
}

/** A point in the 400 x 400 design space. */
data class DesignPoint(val x: Float, val y: Float)

/**
 * Where a layer may show: inside [rect] when set, inside the round screen when [round], inside the
 * [rings] and [boxes] when there are any (corners of the boxes rounded by [cornerRadius]), and where the
 * picture of [mask] is not clear when there is one.
 */
data class LayerClip(
    val rect: DesignRect? = null,
    val round: Boolean = true,
    val rings: List<DesignRing> = emptyList(),
    val boxes: List<DesignRect> = emptyList(),
    val cornerRadius: Float = 0f,
    val mask: LayerMask? = null
) {

    val hasShapes: Boolean get() = rings.isNotEmpty() || boxes.isNotEmpty()
}

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

    /** A view is cut off right of this x (design pixels). */
    CLIP_RIGHT("clipRight", null),

    /** How strong the glow of a picture is, 0 to 1. */
    GLOW("glowAlpha", 0.0),

    /** Which of the shape's colours, 0 = the first. */
    COLOR("colorIndex", 0.0);

    companion object {

        fun of(key: String): LayerProp? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The names a formula of a layer may use, and where their values are kept. One table per animation:
 * the `v_` names are added as the formulas use them.
 *
 * - `t` seconds since the wake started, `ms` the same in milliseconds, `len` the length of this wake in ms
 * - `env` 0 to 1: rises at the start of a wake and falls at its end (see [WakeAnimationSpec.fadeMs])
 * - `i`, `n`, `u` the copy (see [LayerCopies]); `px`, `py` its point; `r1` to `r8` its random numbers
 * - `bpm` the heart rate, `sec`, `min`, `hour` the time of day
 * - `rot0`, `dx0` the own turn and move of a view (see [LayerSource.View])
 * - `v_<key>` the value of a value key, for example `v_watch_battery`; NaN when there is none.
 *   Read once at the start of a wake.
 */
class LayerVariables {

    private val names = LinkedHashMap<String, Int>().apply { FIXED.forEachIndexed { index, name -> put(name, index) } }

    /** The value keys used, in the order of their index. */
    val valueKeys = ArrayList<String>()

    /** The index of [name], or null when a formula may not use it. */
    fun index(name: String): Int? = names[name] ?: if (name.startsWith(VALUE_PREFIX) && name.length > VALUE_PREFIX.length && valueKeys.size < MAX_VALUES) {
        val index = names.size
        names[name] = index
        valueKeys += name.removePrefix(VALUE_PREFIX)
        index
    } else null

    /**
     * Forgets the value keys added after the first [count], for a layer that could not be read. Safe
     * because value keys always come last: no kept formula uses their indices.
     */
    fun forgetValueKeysFrom(count: Int) {
        while (valueKeys.size > count) names.remove(VALUE_PREFIX + valueKeys.removeAt(valueKeys.size - 1))
    }

    /** Room for every variable known now. */
    val size: Int get() = names.size

    companion object {

        const val VALUE_PREFIX = "v_"
        const val MAX_VALUES = 16

        val FIXED = listOf("t", "ms", "len", "env", "i", "n", "u", "px", "py", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "bpm", "sec", "min", "hour", "rot0", "dx0")
        const val T = 0
        const val MS = 1
        const val LEN = 2
        const val ENV = 3
        const val I = 4
        const val N = 5
        const val U = 6
        const val PX = 7
        const val PY = 8
        const val R1 = 9
        const val RANDOMS = 8
        const val BPM = 17
        const val SEC = 18
        const val MIN = 19
        const val HOUR = 20
        const val ROT0 = 21
        const val DX0 = 22
    }
}
