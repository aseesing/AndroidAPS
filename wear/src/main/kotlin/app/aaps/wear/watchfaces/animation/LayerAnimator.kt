// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.animation.LayerVariables.Rate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Where the views of a face are, for the view layers (the variables `rot0`, `dx0`, `cx0` and `cy0`). */
interface ViewPlaces {

    /** How far the view with the design key [key] is turned now, in degrees; NaN when the face has no such view. */
    fun rotation(key: String): Float

    /**
     * How far right of its place in the design the view with the design key [key] is now (moved by its
     * value), in design pixels; NaN when it is not known, which counts as 0.
     */
    fun offsetX(key: String): Float

    /** The middle of the view [key] where the design puts it, in design pixels; NaN when it is not known, which counts as 0. */
    fun centreX(key: String): Float = Float.NaN

    /** See [centreX]. */
    fun centreY(key: String): Float = Float.NaN
}

/**
 * What the view layers do to their views in one frame, by view: the index of a view is its place in
 * [keys]. Filled again for every frame by [LayerAnimator.viewEffects], so a frame makes no objects.
 *
 * Several layers of one view add up: moves and turns are added, scales and alpha multiplied, cuts
 * laid over each other.
 */
class ViewEffects internal constructor(
    /** The keys of the views the view layers change, drawn live. */
    val keys: List<String>
) {

    private val dxs = FloatArray(keys.size)
    private val dys = FloatArray(keys.size)
    private val moved = BooleanArray(keys.size)
    private val turns = FloatArray(keys.size)
    private val turned = BooleanArray(keys.size)
    private val scaleXs = FloatArray(keys.size)
    private val scaleYs = FloatArray(keys.size)
    private val scaled = BooleanArray(keys.size)
    private val alphas = FloatArray(keys.size)
    private val clips = FloatArray(keys.size * 4)
    private val clipped = BooleanArray(keys.size)

    /** True when view [i] is drawn moved, by [dx] and [dy] design pixels. */
    fun isMoved(i: Int): Boolean = moved[i]
    fun dx(i: Int): Float = dxs[i]
    fun dy(i: Int): Float = dys[i]

    /** True when view [i] is drawn turned, by [turn] degrees more than it is. */
    fun isTurned(i: Int): Boolean = turned[i]
    fun turn(i: Int): Float = turns[i]

    /** True when view [i] is drawn scaled, its own scale times [scaleX] and [scaleY], round its middle. */
    fun isScaled(i: Int): Boolean = scaled[i]
    fun scaleX(i: Int): Float = scaleXs[i]
    fun scaleY(i: Int): Float = scaleYs[i]

    /** The alpha view [i] is drawn with, times its own: 1 for unchanged. */
    fun alpha(i: Int): Float = alphas[i]

    /** True when view [i] is not drawn in this frame: its alpha is too low. */
    fun isHidden(i: Int): Boolean = !(alphas[i] > ALPHA_SHOWN)

    /**
     * True when view [i] is cut: only the part between [clipLeft] and [clipRight], [clipTop] and
     * [clipBottom] shows (design pixels; an open side is infinite).
     */
    fun isClipped(i: Int): Boolean = clipped[i]
    fun clipLeft(i: Int): Float = clips[4 * i]
    fun clipTop(i: Int): Float = clips[4 * i + 1]
    fun clipRight(i: Int): Float = clips[4 * i + 2]
    fun clipBottom(i: Int): Float = clips[4 * i + 3]

    internal fun clear() {
        moved.fill(false)
        turned.fill(false)
        scaled.fill(false)
        clipped.fill(false)
        alphas.fill(1f)
    }

    internal fun addMove(i: Int, dx: Float, dy: Float) {
        dxs[i] = (if (moved[i]) dxs[i] else 0f) + dx
        dys[i] = (if (moved[i]) dys[i] else 0f) + dy
        moved[i] = true
    }

    internal fun addTurn(i: Int, degrees: Float) {
        turns[i] = (if (turned[i]) turns[i] else 0f) + degrees
        turned[i] = true
    }

    internal fun addScale(i: Int, x: Float, y: Float) {
        scaleXs[i] = (if (scaled[i]) scaleXs[i] else 1f) * x
        scaleYs[i] = (if (scaled[i]) scaleYs[i] else 1f) * y
        scaled[i] = true
    }

    internal fun multiplyAlpha(i: Int, alpha: Float) {
        alphas[i] *= alpha
    }

    internal fun addClip(i: Int, left: Float, top: Float, right: Float, bottom: Float) {
        val k = 4 * i
        if (!clipped[i]) {
            clips[k] = Float.NEGATIVE_INFINITY
            clips[k + 1] = Float.NEGATIVE_INFINITY
            clips[k + 2] = Float.POSITIVE_INFINITY
            clips[k + 3] = Float.POSITIVE_INFINITY
            clipped[i] = true
        }
        clips[k] = max(clips[k], left)
        clips[k + 1] = max(clips[k + 1], top)
        clips[k + 2] = min(clips[k + 2], right)
        clips[k + 3] = min(clips[k + 3], bottom)
    }

    internal companion object {

        /** Below this alpha a view or a copy is not drawn at all. */
        const val ALPHA_SHOWN = 0.002f
    }
}

/**
 * Where a view layer with a `rest` puts its view in every normal frame, added to where the design puts
 * it: moved by [dx] and [dy] design pixels, turned by [rotation] degrees more, its scale and alpha
 * multiplied by [scaleX], [scaleY] and [alpha]. See [LayerAnimator.restPlace].
 */
data class ViewRest(val dx: Double, val dy: Double, val rotation: Float, val scaleX: Float, val scaleY: Float, val alpha: Float)

/**
 * Draws the [AnimLayerSpec] layers of a design: reads their formulas once, then for every frame works
 * them out for every copy and draws it. A layer whose formula cannot be read is left out, so a mistake
 * in one layer never costs the rest of the design.
 *
 * Not thread safe: one animator draws on one thread.
 *
 * @param specs the layers, in paint order inside each slot
 * @param lets the named formulas of the animation, worked out once per frame (the `let` of the animation)
 * @param zone the time zone of `sec`, `min` and `hour`
 * @param images a picture the app draws itself, by its name (for example `moon`), at a clock time
 *   (epoch ms), in a square of so many pixels, for a [LayerSource.Dynamic] layer; null when there is
 *   none. The caller keeps the picture (see `DynamicImages`): it is asked again only once a minute or
 *   when the size changes, and it is never freed here.
 */
class LayerAnimator(
    specs: List<AnimLayerSpec>,
    lets: List<Pair<String, String>> = emptyList(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val images: (String, Long, Int) -> Bitmap? = { _, _, _ -> null }
) {

    private class Compiled(
        val spec: AnimLayerSpec,
        val props: Array<Formula?>,
        val rates: IntArray,
        /** The `let` of the layer that is not a fixed number: where each value is kept, its formula, its rate. */
        val letSlots: IntArray,
        val letFormulas: Array<Formula>,
        val letRates: IntArray,
        /** Per copy, its random numbers r1.. one after the other. */
        val randoms: DoubleArray,
        val randomCount: Int,
        /** True when some formula reads the time of the frame. */
        val readsTime: Boolean
    ) {

        /** The value of each property that is not worked out per copy, from [beginLayer]. */
        val kept = DoubleArray(props.size)

        /** The [wakeGeneration] the [Rate.WAKE] values in [kept] are of; -1 for none yet. */
        var keptWake = -1

        /** The points of the copies (see [LayerCopies.Points]) as numbers, so a copy reads them without boxing; null for other copies. */
        val pointX: DoubleArray? = (spec.copies as? LayerCopies.Points)?.points?.let { points -> DoubleArray(points.size) { points[it].x.toDouble() } }
        val pointY: DoubleArray? = (spec.copies as? LayerCopies.Points)?.points?.let { points -> DoubleArray(points.size) { points[it].y.toDouble() } }

        /** The extra numbers of the points, [LayerVariables.EXTRAS] per point, NaN where a point has fewer. */
        val pointExtras: DoubleArray? = (spec.copies as? LayerCopies.Points)?.points?.takeIf { points -> points.any { it.extras.isNotEmpty() } }?.let { points ->
            DoubleArray(points.size * LayerVariables.EXTRAS) { k -> points[k / LayerVariables.EXTRAS].extras.getOrElse(k % LayerVariables.EXTRAS) { Double.NaN } }
        }

        var picture: Bitmap? = null

        /** False when [picture] belongs to another layer with the same picture: then this one must not free it. */
        var ownsPicture = true
        var glow: Bitmap? = null
        val glowOffset = IntArray(2)
        var madeForScale = 0f
        var mask: Bitmap? = null
        var maskForScale = 0f

        /** The picture of a [LayerSource.Dynamic] layer, owned by the caller; with the minute and the size it was asked for. */
        var dynamic: Bitmap? = null
        var dynamicMinute = Long.MIN_VALUE
        var dynamicSize = 0

        /** For a view layer, the index of its view in [viewEffects] (live views) ; -1 for other layers and for views placed only at rest. */
        var viewIndex = -1

        /** True for a view layer with a `rest` that does not change with the time: it only places its view, in the normal frames too. */
        val placesOnly: Boolean get() = spec.source is LayerSource.View && spec.restSeconds != null && !readsTime

        /** For a view layer with a `rest`: where [restPlace] last put the view, so a live frame moves it only by the change. */
        val rest = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

        /** One gradient shader per colour, made for [SHADER_SIZE] and scaled to the size of each copy. */
        val shaders = HashMap<Int, Shader>()

        /** True when a copy may be turned or scaled; else the canvas needs no save per copy. */
        val hasTransform = props[LayerProp.ROTATION.ordinal] != null || props[LayerProp.SCALE_X.ordinal] != null || props[LayerProp.SCALE_Y.ordinal] != null

        /** True when the layer gives one of the clip props. */
        val hasClipProps = LayerProp.CLIPS.any { props[it.ordinal] != null }
    }

    private val variables = LayerVariables()

    /** True when a formula reads `sec`, `min` or `hour`: only then the clock time is worked out per frame. */
    private var usesClock = false

    /** The clock time of the frame, from [setTime]: a [LayerSource.Dynamic] picture is the one of then. */
    private var nowMs = 0L

    /** Turns the clock time into `sec`, `min` and `hour`. Its zone is read once per wake (and per rest frame), not every frame. */
    private val clock = WallClock()

    /** The clock time [clock] last gave `sec`, `min` and `hour` for; set again only for another time. */
    private var clockMs = Long.MIN_VALUE

    /**
     * Changes each time a value that stays the same for a whole wake changes (the value keys, `bpm`
     * or `len`), so a formula of only those is worked out once per wake and not every frame.
     */
    private var wakeGeneration = 0

    /** An array, not a list: a frame goes through it several times, and a list makes an iterator each time. */
    private val layers: Array<Compiled>
    private val values: DoubleArray

    /** The `let` of the animation that is not a fixed number, in order: where each value is kept, and its formula. */
    private val sharedSlots: IntArray
    private val sharedFormulas: Array<Formula>

    /** The layers that could not be read, with the reason; for the design tests. */
    val errors: List<String>

    init {
        val errorList = ArrayList<String>()
        // The let of the animation: a fixed number is folded into the formulas that use it
        val sharedConstants = HashMap<String, Double>()
        val slots = ArrayList<Int>()
        val formulas = ArrayList<Formula>()
        for ((name, text) in lets.take(AnimLayerSpec.MAX_LETS)) {
            try {
                if (!LayerVariables.isName(name) || name in sharedConstants) throw FormulaException("'$name' cannot be the name of a let")
                val formula = Formula.parse(text, { sharedConstants[it] }) { variables.index(it) }
                if (formula.isConstant) sharedConstants[name] = formula.eval(NO_VARIABLES)
                else {
                    slots += variables.addShared(name, formula) ?: throw FormulaException("'$name' cannot be the name of a let")
                    formulas += formula
                }
                noteClock(formula)
            } catch (e: FormulaException) {
                errorList += "let $name: ${e.message}"
            }
        }
        sharedSlots = slots.toIntArray()
        sharedFormulas = formulas.toTypedArray()

        val compiled = ArrayList<Compiled>()
        var copies = 0
        for ((index, given) in specs.withIndex()) {
            if (compiled.size >= AnimLayerSpec.MAX_LAYERS) {
                errorList += "layer $index: more than ${AnimLayerSpec.MAX_LAYERS} layers"
                continue
            }
            // A negative count (a spec that was not normalized) must not break the whole animation
            val spec = if (given.copies.size < 0) given.copy(copies = LayerCopies.Count(0)) else given
            if (copies + spec.copies.size > AnimLayerSpec.MAX_TOTAL_COPIES) {
                errorList += "layer $index: more than ${AnimLayerSpec.MAX_TOTAL_COPIES} copies in all"
                continue
            }
            // The value keys this layer adds are taken back when one of its formulas cannot be read
            val valueKeysBefore = variables.valueKeys.size
            try {
                compiled += compile(spec, index, sharedConstants)
                copies += spec.copies.size
            } catch (e: FormulaException) {
                variables.forgetValueKeysFrom(valueKeysBefore)
                errorList += "layer $index: ${e.message}"
            }
        }
        layers = compiled.toTypedArray()
        errors = errorList
        values = DoubleArray(variables.size)
        values[LayerVariables.BPM] = PulseCurve.FALLBACK_BPM.toDouble()
        values[LayerVariables.ENV] = 1.0
        for (i in variables.valueIndices) values[i] = Double.NaN
        // A fixed number is worked out once, here. It reads no variable.
        for (layer in layers) for (i in layer.props.indices) if (layer.rates[i] == Rate.CONSTANT) layer.kept[i] = layer.props[i]!!.eval(NO_VARIABLES)
    }

    /** Reads the formulas of [spec], the layer at [index] of the list. */
    private fun compile(spec: AnimLayerSpec, index: Int, sharedConstants: Map<String, Double>): Compiled {
        val scope = HashMap<String, Int>()
        val localConstants = HashMap<String, Double>()
        fun constantOf(name: String): Double? = localConstants[name] ?: if (name in scope) null else sharedConstants[name]
        fun parse(text: String) = Formula.parse(text, ::constantOf) { variables.index(it, scope) }.also(::noteClock)
        val letSlots = ArrayList<Int>()
        val letFormulas = ArrayList<Formula>()
        for ((name, text) in spec.lets) {
            if (!LayerVariables.isName(name) || name in scope || name in localConstants) throw FormulaException("'$name' cannot be the name of a let")
            val formula = parse(text)
            if (formula.isConstant) localConstants[name] = formula.eval(NO_VARIABLES)
            else {
                val slot = variables.addLocal(formula)
                scope[name] = slot
                letSlots += slot
                letFormulas += formula
            }
        }
        val formulas = arrayOfNulls<Formula>(LayerProp.entries.size)
        for ((prop, text) in spec.props) formulas[prop.ordinal] = parse(text)
        val all = formulas.filterNotNull() + letFormulas
        var randomCount = 0
        for (formula in all) for (i in formula.variables) if (i >= LayerVariables.R1 && i < LayerVariables.R1 + LayerVariables.RANDOMS) randomCount = max(randomCount, i - LayerVariables.R1 + 1)
        val randoms = DoubleArray(spec.copies.size * randomCount)
        if (randomCount > 0) {
            val random = Random((spec.copies as? LayerCopies.Count)?.seed ?: index.toLong())
            for (k in randoms.indices) randoms[k] = random.nextFloat().toDouble()
        }
        return Compiled(
            spec = spec,
            props = formulas,
            rates = IntArray(formulas.size) { formulas[it]?.let(variables::rateOf) ?: NONE },
            letSlots = letSlots.toIntArray(),
            letFormulas = letFormulas.toTypedArray(),
            letRates = IntArray(letFormulas.size) { variables.rateOf(letFormulas[it]) },
            randoms = randoms,
            randomCount = randomCount,
            readsTime = all.any(variables::readsTime)
        )
    }

    private fun noteClock(formula: Formula) {
        for (i in formula.variables) if (i == LayerVariables.SEC || i == LayerVariables.MIN || i == LayerVariables.HOUR) usesClock = true
    }

    /** True when there is a layer to draw at all. */
    val isEmpty: Boolean get() = layers.isEmpty()

    /**
     * True when a wake has something to show: a layer other than a view layer that only places its view
     * (see [ViewRest]). A design with nothing else has no wake to play.
     */
    val hasWakeContent: Boolean = layers.any { !it.placesOnly }

    /** The keys of the views that the layers change in a wake, drawn live while a wake plays. */
    val viewKeys: Set<String> = layers.filter { !it.placesOnly }.mapNotNullTo(LinkedHashSet()) { (it.spec.source as? LayerSource.View)?.key }

    /** The keys of the views that a layer with a `rest` places in the normal frames, see [restPlace]. */
    val restViewKeys: Set<String> = layers.filter { it.spec.source is LayerSource.View && it.spec.restSeconds != null }
        .mapTo(HashSet()) { (it.spec.source as LayerSource.View).key }

    /** What the view layers do in the current frame, filled by [viewEffects]. */
    private val effects = ViewEffects(viewKeys.toList()).also { effects ->
        for (layer in layers) if (!layer.placesOnly) (layer.spec.source as? LayerSource.View)?.let { layer.viewIndex = effects.keys.indexOf(it.key) }
    }

    /**
     * What the views slot draws, in order: a layer (its index in [layers]), or a group of live views
     * (-1 - its index in [viewGroups]). Views that follow each other in the layer list are one group,
     * drawn together in the paint order of the face; a view is drawn in the group of its first layer.
     */
    private val viewSteps: IntArray
    private val viewGroups: List<BooleanArray>

    init {
        val steps = ArrayList<Int>()
        val groups = ArrayList<BooleanArray>()
        val placed = BooleanArray(effects.keys.size)
        for ((index, layer) in layers.withIndex()) {
            if (layer.spec.slot != LayerSlot.VIEWS) continue
            val view = layer.viewIndex
            when {
                layer.spec.source !is LayerSource.View -> steps += index
                view < 0 || placed[view]               -> Unit
                else                                   -> {
                    if (steps.lastOrNull()?.let { it < 0 } != true) {
                        groups += BooleanArray(effects.keys.size)
                        steps += -groups.size
                    }
                    groups.last()[view] = true
                    placed[view] = true
                }
            }
        }
        viewSteps = steps.toIntArray()
        viewGroups = groups
    }

    /** True when a layer of [slot] draws something other than a view. */
    fun hasSlot(slot: LayerSlot): Boolean = layers.any { it.spec.slot == slot && it.spec.source !is LayerSource.View }

    /** True when some layer other than a view layer is also drawn while no animation plays. */
    val hasRest: Boolean = layers.any { it.spec.restSeconds != null && it.spec.source !is LayerSource.View }

    /** The value keys the formulas read, for example `watch_battery`. */
    val valueKeys: List<String> get() = variables.valueKeys

    /** Takes the values that stay the same for a whole wake: the value keys ([value]) and the heart rate. */
    fun setWakeValues(value: (String) -> Double?, bpm: Int) {
        val keys = variables.valueKeys
        for (i in keys.indices) values[variables.valueIndices[i]] = value(keys[i]) ?: Double.NaN
        values[LayerVariables.BPM] = bpm.toDouble()
        wakeGeneration++
        // The zone is read again with the next time: it can change between wakes (a journey)
        clock.zone = null
        clockMs = Long.MIN_VALUE
        refreshLets()
    }

    /** Sets the time of the frame: [elapsedMs] into a wake of [lengthMs], fade [env], at the clock time [nowMs]. */
    fun setTime(elapsedMs: Long, lengthMs: Long, env: Float, nowMs: Long) {
        setElapsed(elapsedMs)
        setLength(lengthMs)
        values[LayerVariables.ENV] = env.toDouble()
        setClock(nowMs)
        refreshLets()
    }

    /** Works out the `let` of the animation for the values set now. */
    private fun refreshLets() {
        for (k in sharedSlots.indices) values[sharedSlots[k]] = sharedFormulas[k].eval(values)
    }

    private fun setElapsed(elapsedMs: Long) {
        values[LayerVariables.T] = elapsedMs / 1000.0
        values[LayerVariables.MS] = elapsedMs.toDouble()
    }

    private fun setLength(lengthMs: Long) {
        val length = lengthMs.toDouble()
        if (length != values[LayerVariables.LEN]) {
            values[LayerVariables.LEN] = length
            wakeGeneration++
        }
    }

    /** Sets the clock time [nowMs], and `sec`, `min` and `hour` once per clock time. */
    private fun setClock(nowMs: Long) {
        this.nowMs = nowMs
        if (!usesClock || nowMs == clockMs) return
        clockMs = nowMs
        if (clock.zone == null) clock.zone = zone()
        clock.set(nowMs)
        values[LayerVariables.SEC] = clock.second
        values[LayerVariables.MIN] = clock.minute
        values[LayerVariables.HOUR] = clock.hour
    }

    /**
     * Draws the pictures and shapes of [slot] for the time set by [setTime], at [scale] screen pixels per
     * design pixel. In the views slot [views] draws each group of live views (the members of the view
     * keys, see [viewEffects]) where it lies between the shapes.
     */
    fun drawSlot(canvas: Canvas, slot: LayerSlot, scale: Float, views: ((Canvas, BooleanArray) -> Unit)? = null) {
        // Layers in a row that are cut to the round screen and to nothing before it share one cut: the
        // same cut, in the same order of cuts, made once instead of for each layer
        var roundSave = NO_SAVE
        endGroup(canvas)
        fun endRound() {
            if (roundSave != NO_SAVE) canvas.restoreToCount(roundSave)
            roundSave = NO_SAVE
        }
        fun draw(layer: Compiled) {
            if (!prepareLayer(layer, scale)) return
            // A group has its own sheet: it shares no cut with the layers around it
            val sharesRound = layer.spec.group == null && layer.spec.clip.round && layer.spec.clip.rect == null
            if (layer.spec.group != groupName) {
                endGroup(canvas)
                if (layer.spec.group != null) endRound()
                beginGroup(canvas, layer, scale)
            }
            if (sharesRound && roundSave == NO_SAVE) {
                roundSave = canvas.save()
                canvas.clipPath(roundPath(scale))
            } else if (!sharesRound) endRound()
            paintLayer(canvas, layer, scale, roundDone = sharesRound)
        }
        if (slot == LayerSlot.VIEWS) {
            for (step in viewSteps) {
                if (step >= 0) draw(layers[step])
                else {
                    endGroup(canvas)
                    endRound()
                    views?.invoke(canvas, viewGroups[-step - 1])
                }
            }
        } else
            for (layer in layers) {
                if (layer.spec.slot != slot || layer.spec.source is LayerSource.View) continue
                draw(layer)
            }
        endGroup(canvas)
        endRound()
    }

    /**
     * Draws the layers of [slot] that stay on the face while no animation plays, each as it is its rest
     * time into a wake, at the clock time [nowMs], with `env` 0 (nothing plays): a layer that scales its
     * motion by `env` so shows its still pose, the same as at the very start and end of a wake. The value
     * keys are read again through [value].
     */
    fun drawRest(canvas: Canvas, scale: Float, nowMs: Long, slot: LayerSlot = LayerSlot.BACK, value: (String) -> Double?) {
        if (!hasRest) return
        setRestTime(nowMs, value)
        // A normal frame is a good moment to make the pictures, so the first frame of a wake does not stutter
        if (slot == LayerSlot.BACK) prepare(scale)
        for (layer in layers) {
            if (layer.spec.slot != slot || layer.spec.source is LayerSource.View) continue
            val rest = layer.spec.restSeconds ?: continue
            setElapsed((rest * 1000).toLong())
            refreshLets()
            drawLayer(canvas, layer, scale)
        }
        endGroup(canvas)
    }

    /** The values of a normal frame: those of now through [value], the longest wake, `env` 0, the clock time [nowMs]. */
    private fun setRestTime(nowMs: Long, value: (String) -> Double?) {
        setWakeValues(value, values[LayerVariables.BPM].toInt())
        // The same for every rest layer: only the time into the wake differs
        setLength(WakeAnimationSpec.MAX_DURATION_MS)
        values[LayerVariables.ENV] = 0.0
        setClock(nowMs)
    }

    /**
     * Where the view layers with a `rest` put the view [key] in a normal frame, at the clock time [nowMs]
     * with the values of now ([value]), or null when no such layer has this view. [rot0] is the turn the
     * design gives the view, [cx0] and [cy0] its middle where the design puts it, in design pixels.
     * Several layers of one view add up as in [ViewEffects].
     */
    fun restPlace(key: String, rot0: Float, cx0: Double, cy0: Double, nowMs: Long, value: (String) -> Double?): ViewRest? {
        if (key !in restViewKeys) return null
        setRestTime(nowMs, value)
        var dx = 0.0
        var dy = 0.0
        var rotation = 0f
        var scaleX = 1f
        var scaleY = 1f
        var alpha = 1f
        for (layer in layers) {
            val rest = layer.spec.restSeconds ?: continue
            if ((layer.spec.source as? LayerSource.View)?.key != key) continue
            setElapsed((rest * 1000).toLong())
            refreshLets()
            setView(layer, rot0, 0f, cx0, cy0)
            val x = layer.value(LayerProp.X, 0.0).finiteOr(0.0)
            val y = layer.value(LayerProp.Y, 0.0).finiteOr(0.0)
            val turn = layer.value(LayerProp.ROTATION, 0.0).toFloat().finiteOr(0f)
            val sx = layer.value(LayerProp.SCALE_X, 1.0).toFloat().finiteOr(1f)
            val sy = layer.value(LayerProp.SCALE_Y, 1.0).toFloat().finiteOr(1f)
            val a = layer.value(LayerProp.ALPHA, 1.0).toFloat().finiteOr(1f).coerceIn(0f, 1f)
            layer.rest[0] = x
            layer.rest[1] = y
            layer.rest[2] = turn.toDouble()
            layer.rest[3] = sx.toDouble()
            layer.rest[4] = sy.toDouble()
            layer.rest[5] = a.toDouble()
            dx += x
            dy += y
            rotation += turn
            scaleX *= sx
            scaleY *= sy
            alpha *= a
        }
        return ViewRest(dx, dy, rotation, scaleX, scaleY, alpha)
    }

    /**
     * What the view layers do now, for the views where [places] says they are (the variables `rot0`,
     * `dx0`, `cx0` and `cy0`); a view the face does not have is left alone. A layer with a `rest` gives only
     * the change from its rest place, which the view already has (see [restPlace]). The same object every
     * frame, filled again.
     */
    fun viewEffects(places: ViewPlaces): ViewEffects {
        effects.clear()
        if (viewKeys.isEmpty()) return effects
        for (layer in layers) {
            val index = layer.viewIndex
            if (index < 0) continue
            val key = effects.keys[index]
            val rot0 = places.rotation(key)
            if (rot0.isNaN()) continue
            val dx0 = places.offsetX(key)
            setView(layer, rot0, if (dx0.isNaN()) 0f else dx0, places.centreX(key).toDouble().finiteOr(0.0), places.centreY(key).toDouble().finiteOr(0.0))
            val fromRest = layer.spec.restSeconds != null
            if (layer.has(LayerProp.X) || layer.has(LayerProp.Y)) {
                val x = layer.value(LayerProp.X, 0.0) - (if (fromRest) layer.rest[0] else 0.0)
                val y = layer.value(LayerProp.Y, 0.0) - (if (fromRest) layer.rest[1] else 0.0)
                effects.addMove(index, x.toFloat(), y.toFloat())
            }
            if (layer.has(LayerProp.ROTATION)) effects.addTurn(index, (layer.value(LayerProp.ROTATION, 0.0) - (if (fromRest) layer.rest[2] else 0.0)).toFloat())
            if (layer.has(LayerProp.SCALE_X) || layer.has(LayerProp.SCALE_Y)) {
                val sx = layer.value(LayerProp.SCALE_X, 1.0) / (if (fromRest) layer.rest[3] else 1.0)
                val sy = layer.value(LayerProp.SCALE_Y, 1.0) / (if (fromRest) layer.rest[4] else 1.0)
                effects.addScale(index, sx.toFloat().finiteOr(1f), sy.toFloat().finiteOr(1f))
            }
            if (layer.has(LayerProp.ALPHA)) {
                val alpha = layer.value(LayerProp.ALPHA, 1.0) / (if (fromRest) layer.rest[5] else 1.0)
                effects.multiplyAlpha(index, if (alpha.isNaN()) 0f else alpha.toFloat().coerceIn(0f, 1f))
            }
            if (layer.has(LayerProp.VISIBLE) && !truth(layer.value(LayerProp.VISIBLE, 1.0))) effects.multiplyAlpha(index, 0f)
            if (layer.hasClipProps)
                effects.addClip(
                    index,
                    layer.value(LayerProp.CLIP_LEFT, Double.NEGATIVE_INFINITY).toFloat().notNaN(Float.NEGATIVE_INFINITY),
                    layer.value(LayerProp.CLIP_TOP, Double.NEGATIVE_INFINITY).toFloat().notNaN(Float.NEGATIVE_INFINITY),
                    layer.value(LayerProp.CLIP_RIGHT, Double.POSITIVE_INFINITY).toFloat().notNaN(Float.POSITIVE_INFINITY),
                    layer.value(LayerProp.CLIP_BOTTOM, Double.POSITIVE_INFINITY).toFloat().notNaN(Float.POSITIVE_INFINITY)
                )
        }
        return effects
    }

    /** Sets the own place of a view for the formulas of the view layer [layer]. */
    private fun setView(layer: Compiled, rot0: Float, dx0: Float, cx0: Double, cy0: Double) {
        beginLayer(layer)
        values[LayerVariables.ROT0] = rot0.toDouble()
        values[LayerVariables.DX0] = dx0.toDouble()
        values[LayerVariables.CX0] = cx0
        values[LayerVariables.CY0] = cy0
        setCopy(layer, 0)
    }

    /** The number of layers that could be read, for the tests. */
    val size: Int get() = layers.size

    /**
     * The value of [prop] of copy [copy] of layer [layer] (counted over the layers that could be read),
     * for the time set by [setTime]; null when the layer does not give that property. For the tests.
     */
    fun propOf(layer: Int, copy: Int, prop: LayerProp): Float? {
        val compiled = layers[layer]
        if (!compiled.has(prop)) return null
        beginLayer(compiled)
        setCopy(compiled, copy)
        return compiled.value(prop, 0.0).toFloat()
    }

    /**
     * Makes the pictures and masks of all layers for [scale] now, when they are not made yet, so the
     * first frame of a wake does not stop to make them.
     */
    fun prepare(scale: Float) {
        for (layer in layers) {
            (layer.spec.source as? LayerSource.Image)?.let { picture(layer, it, scale) }
            (layer.spec.source as? LayerSource.Dynamic)?.let { dynamicPicture(layer, it, scale) }
            layer.spec.clip.mask?.let { mask(layer, it, scale) }
        }
    }

    /** Frees the pictures; the next frame makes them again. */
    fun release() {
        // Those that share a picture first, so no layer keeps a freed one
        for (layer in layers) if (!layer.ownsPicture) releasePicture(layer)
        for (layer in layers) releasePicture(layer)
    }

    // ---- working out ----

    /** True when [layer] gives [prop]. */
    private fun Compiled.has(prop: LayerProp): Boolean = rates[prop.ordinal] != NONE

    /**
     * The value of [prop], or [default] when the layer does not give it. After [beginLayer] for the
     * frame and [setCopy] for the copy: a formula of the copy is worked out now, any other was worked
     * out before.
     */
    private fun Compiled.value(prop: LayerProp, default: Double): Double {
        val i = prop.ordinal
        return when (rates[i]) {
            NONE       -> default
            Rate.COPY  -> props[i]!!.eval(values)
            else       -> kept[i]
        }
    }

    /** [value] as a Float. */
    private fun Compiled.valueF(prop: LayerProp, default: Float): Float = value(prop, default.toDouble()).toFloat()

    /**
     * Works out the `let` and the formulas of [layer] that are the same for all its copies, for the time
     * set by [setTime]: those of the frame every time, those of the wake only when the wake values changed.
     */
    private fun beginLayer(layer: Compiled) {
        val wake = layer.keptWake != wakeGeneration
        for (k in layer.letRates.indices) {
            val rate = layer.letRates[k]
            if (rate == Rate.FRAME || (wake && rate == Rate.WAKE)) values[layer.letSlots[k]] = layer.letFormulas[k].eval(values)
        }
        for (i in layer.rates.indices) {
            val rate = layer.rates[i]
            if (rate == Rate.FRAME || (wake && rate == Rate.WAKE)) layer.kept[i] = layer.props[i]!!.eval(values)
        }
        layer.keptWake = wakeGeneration
    }

    /** Sets the variables of copy [i] of [layer], and works out the `let` of the layer that differs per copy. */
    private fun setCopy(layer: Compiled, i: Int) {
        val n = layer.spec.copies.size
        values[LayerVariables.I] = i.toDouble()
        values[LayerVariables.N] = n.toDouble()
        values[LayerVariables.U] = if (n > 1) i / (n - 1.0) else 0.0
        val pointX = layer.pointX
        val pointY = layer.pointY
        values[LayerVariables.PX] = if (pointX != null && i < pointX.size) pointX[i] else 0.0
        values[LayerVariables.PY] = if (pointY != null && i < pointY.size) pointY[i] else 0.0
        val extras = layer.pointExtras
        if (extras != null) for (k in 0 until LayerVariables.EXTRAS) values[LayerVariables.P0 + k] = extras.getOrElse(i * LayerVariables.EXTRAS + k) { Double.NaN }
        for (k in 0 until layer.randomCount) values[LayerVariables.R1 + k] = layer.randoms[i * layer.randomCount + k]
        for (k in layer.letRates.indices) if (layer.letRates[k] == Rate.COPY) values[layer.letSlots[k]] = layer.letFormulas[k].eval(values)
    }

    // ---- drawing ----

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    private val erase = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val inside = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

    /** Lays the offscreen layer of a masked layer over the face, with the blend of the layer. */
    private val layerPaint = Paint()

    /** Keeps the offscreen layer only where the mask picture is not clear. */
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val maskDst = RectF()
    private var roundFor = 0f
    private val roundPath = Path()
    private val shapePaths = HashMap<Compiled, Pair<Float, Path>>()

    /**
     * Draws all copies of [layer]. A layer with a mask is first drawn on its own, in an offscreen layer
     * the size of the mask: the copies with the normal blend, then the mask cuts away what it does not
     * keep, and only then the result is laid over the face with the blend of the layer. So the mask
     * never cuts holes in what is already on the face.
     */
    private fun drawLayer(canvas: Canvas, layer: Compiled, scale: Float) {
        if (!prepareLayer(layer, scale)) return
        if (layer.spec.group != groupName) {
            endGroup(canvas)
            beginGroup(canvas, layer, scale)
        }
        paintLayer(canvas, layer, scale, roundDone = false)
    }

    /** The group that is open now (see [AnimLayerSpec.group]) and the save count that closes it. */
    private var groupName: String? = null
    private var groupSave = NO_SAVE

    /**
     * Opens the sheet of the group of [layer], when it has one: as big as the `clip.rect` of this
     * layer, the first of the group that is drawn, or as the whole canvas.
     */
    private fun beginGroup(canvas: Canvas, layer: Compiled, scale: Float) {
        val name = layer.spec.group ?: return
        val rect = layer.spec.clip.rect
        groupSave = if (rect == null) canvas.saveLayer(null, null)
        else canvas.saveLayer(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale, null)
        groupName = name
    }

    /** Lays the sheet of the open group over the face, when there is one. */
    private fun endGroup(canvas: Canvas) {
        if (groupSave != NO_SAVE) canvas.restoreToCount(groupSave)
        groupSave = NO_SAVE
        groupName = null
    }

    /**
     * Makes the pictures of [layer] when they are not made yet and works out its formulas that are the
     * same for all copies. False when nothing of it can show in this frame: no picture, no copies, not
     * visible, empty clip shapes, or an alpha that is the same for every copy and too low. Then the
     * canvas is not touched at all.
     */
    private fun prepareLayer(layer: Compiled, scale: Float): Boolean {
        val spec = layer.spec
        if (spec.clip.isEmpty) return false
        when (val source = spec.source) {
            is LayerSource.Image   -> picture(layer, source, scale) ?: return false
            is LayerSource.Dynamic -> dynamicPicture(layer, source, scale) ?: return false
            else                   -> Unit
        }
        spec.clip.mask?.let { mask(layer, it, scale) ?: return false }
        beginLayer(layer)
        if (spec.copies.size <= 0) return false
        val visibleRate = layer.rates[LayerProp.VISIBLE.ordinal]
        if (visibleRate != NONE && visibleRate != Rate.COPY && !truth(layer.kept[LayerProp.VISIBLE.ordinal])) return false
        val alphaRate = layer.rates[LayerProp.ALPHA.ordinal]
        return alphaRate == NONE || alphaRate == Rate.COPY || layer.kept[LayerProp.ALPHA.ordinal] > ALPHA_SHOWN
    }

    /** Draws [layer] after [prepareLayer] said it can show. [roundDone]: the canvas is already cut to the round screen. */
    private fun paintLayer(canvas: Canvas, layer: Compiled, scale: Float, roundDone: Boolean) {
        val spec = layer.spec
        val picture = when (spec.source) {
            is LayerSource.Image   -> layer.picture
            is LayerSource.Dynamic -> layer.dynamic
            else                   -> null
        }
        val maskSpec = spec.clip.mask
        val mask = layer.mask
        val blend = when (spec.blend) {
            LayerBlend.NORMAL -> null
            LayerBlend.ADD    -> add
            LayerBlend.SCREEN -> screen
            LayerBlend.ERASE  -> erase
            LayerBlend.INSIDE -> inside
        }
        val save = canvas.save()
        clip(canvas, layer, scale, roundDone)
        // The clip props of the layer, once for all copies when they are the same for all
        val clipPerCopy = layer.hasClipProps && LayerProp.CLIPS.any { layer.rates[it.ordinal] == Rate.COPY }
        if (layer.hasClipProps && !clipPerCopy) clipProps(canvas, layer, scale)
        if (maskSpec != null) {
            val rect = maskSpec.rect
            maskDst.set(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)
            // The offscreen layer only as big as the part of the mask that can show: inside the clip of
            // the layer (for example the sky). Outside it nothing is drawn anyway, and a smaller layer is
            // cheaper to clear and to lay over the face every frame.
            if (!canvas.getClipBounds(clipBounds)) {
                canvas.restoreToCount(save)
                return
            }
            layerBounds.set(clipBounds)
            if (!layerBounds.intersect(maskDst)) {
                canvas.restoreToCount(save)
                return
            }
            layerPaint.xfermode = blend
            canvas.saveLayer(layerBounds, layerPaint)
            paint.xfermode = null
        } else
            paint.xfermode = blend
        val visiblePerCopy = layer.rates[LayerProp.VISIBLE.ordinal] == Rate.COPY
        for (i in 0 until spec.copies.size) {
            setCopy(layer, i)
            if (visiblePerCopy && !truth(layer.value(LayerProp.VISIBLE, 1.0))) continue
            val alpha = layer.valueF(LayerProp.ALPHA, 1f)
            if (!(alpha > ALPHA_SHOWN)) continue
            val copySave = if (clipPerCopy) canvas.save().also { clipProps(canvas, layer, scale) } else NO_SAVE
            when (val source = spec.source) {
                is LayerSource.Image   -> drawPicture(canvas, layer, source.rect, picture!!, i, alpha, scale)
                is LayerSource.Dynamic -> drawPicture(canvas, layer, source.square, picture!!, i, alpha, scale)
                is LayerSource.Shape   -> drawShape(canvas, layer, source, alpha, scale)
                is LayerSource.View    -> Unit
            }
            restore(canvas, copySave)
        }
        if (maskSpec != null && mask != null) canvas.drawBitmap(mask, null, maskDst, maskPaint)
        canvas.restoreToCount(save)
        paint.xfermode = null
        paint.shader = null
    }

    /** Cuts the canvas to the clip props of [layer]; a side that is not given or not a number stays open. */
    private fun clipProps(canvas: Canvas, layer: Compiled, scale: Float) {
        val left = layer.valueF(LayerProp.CLIP_LEFT, -OPEN).notNaN(-OPEN)
        val top = layer.valueF(LayerProp.CLIP_TOP, -OPEN).notNaN(-OPEN)
        val right = layer.valueF(LayerProp.CLIP_RIGHT, OPEN).notNaN(OPEN)
        val bottom = layer.valueF(LayerProp.CLIP_BOTTOM, OPEN).notNaN(OPEN)
        canvas.clipRect(left.coerceIn(-OPEN, OPEN) * scale, top.coerceIn(-OPEN, OPEN) * scale, right.coerceIn(-OPEN, OPEN) * scale, bottom.coerceIn(-OPEN, OPEN) * scale)
    }

    /**
     * Turns and scales the canvas round the pivot, for one copy. Returns the save count to restore to,
     * or [NO_SAVE] when the layer never turns or scales and [always] is false: then nothing is saved,
     * which spares two canvas calls per copy. A value that is not a number counts as no turn or scale.
     */
    private fun transform(canvas: Canvas, layer: Compiled, defaultPivotX: Float, defaultPivotY: Float, scale: Float, always: Boolean = false): Int {
        if (!layer.hasTransform) return if (always) canvas.save() else NO_SAVE
        val rotation = layer.valueF(LayerProp.ROTATION, 0f).finiteOr(0f)
        val scaleX = layer.valueF(LayerProp.SCALE_X, 1f).finiteOr(1f)
        val scaleY = layer.valueF(LayerProp.SCALE_Y, 1f).finiteOr(1f)
        // Not turned and not scaled in this frame: no pivot to work out, and nothing to save
        if (rotation == 0f && scaleX == 1f && scaleY == 1f) return if (always) canvas.save() else NO_SAVE
        val save = canvas.save()
        val pivotX = layer.valueF(LayerProp.PIVOT_X, defaultPivotX).finiteOr(defaultPivotX) * scale
        val pivotY = layer.valueF(LayerProp.PIVOT_Y, defaultPivotY).finiteOr(defaultPivotY) * scale
        if (rotation != 0f) canvas.rotate(rotation, pivotX, pivotY)
        if (scaleX != 1f || scaleY != 1f) canvas.scale(scaleX, scaleY, pivotX, pivotY)
        return save
    }

    private fun Float.finiteOr(default: Float): Float = if (isFinite()) this else default
    private fun Double.finiteOr(default: Double): Double = if (isFinite()) this else default
    private fun Float.notNaN(default: Float): Float = if (isNaN()) default else this

    private fun restore(canvas: Canvas, save: Int) {
        if (save != NO_SAVE) canvas.restoreToCount(save)
    }

    private fun drawPicture(canvas: Canvas, layer: Compiled, rect: DesignRect, picture: Bitmap, i: Int, alpha: Float, scale: Float) {
        val x = layer.valueF(LayerProp.X, 0f)
        val y = layer.valueF(LayerProp.Y, 0f)
        // A formula that reads a missing value gives NaN: then this copy is not drawn
        if (!x.isFinite() || !y.isFinite()) return
        val copies = layer.spec.copies
        when (copies) {
            is LayerCopies.Slices -> if (copies.vertical) {
                src.set(picture.width * i / copies.count, 0, picture.width * (i + 1) / copies.count, picture.height)
                dst.set(rect.left + rect.width * i / copies.count, rect.top, rect.left + rect.width * (i + 1) / copies.count, rect.bottom)
            } else {
                src.set(0, picture.height * i / copies.count, picture.width, picture.height * (i + 1) / copies.count)
                dst.set(rect.left, rect.top + rect.height * i / copies.count, rect.right, rect.top + rect.height * (i + 1) / copies.count)
            }

            else                  -> {
                src.set(0, 0, picture.width, picture.height)
                dst.set(rect.left, rect.top, rect.right, rect.bottom)
            }
        }
        dst.offset(x, y)
        val save = transform(canvas, layer, dst.centerX(), dst.centerY(), scale)
        dst.set(dst.left * scale, dst.top * scale, dst.right * scale, dst.bottom * scale)
        if (copies is LayerCopies.Slices) {
            // Strips meet on whole pixels: no thin seam between two strips, and no line where they overlap
            if (copies.vertical) dst.set(dst.left.roundToInt().toFloat(), dst.top, dst.right.roundToInt().toFloat(), dst.bottom)
            else dst.set(dst.left, dst.top.roundToInt().toFloat(), dst.right, dst.bottom.roundToInt().toFloat())
        }
        val glow = layer.glow
        val glowStrength = layer.valueF(LayerProp.GLOW, 0f) * alpha
        if (glow != null && glowStrength > 0f && copies !is LayerCopies.Slices) {
            val factor = dst.width() / picture.width
            val left = dst.left + layer.glowOffset[0] * factor
            val top = dst.top + layer.glowOffset[1] * factor
            glowPaint.color = layer.spec.glow?.color ?: Color.WHITE
            glowPaint.alpha = (255 * glowStrength.coerceIn(0f, 1f)).roundToInt()
            glowDst.set(left, top, left + glow.width * factor, top + glow.height * factor)
            canvas.drawBitmap(glow, null, glowDst, glowPaint)
        }
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt()
        canvas.drawBitmap(picture, src, dst, paint)
        restore(canvas, save)
    }

    private fun drawShape(canvas: Canvas, layer: Compiled, source: LayerSource.Shape, alpha: Float, scale: Float) {
        val colorIndex = layer.valueF(LayerProp.COLOR, 0f).toInt().mod(source.colors.size)
        val color = source.colors[colorIndex]
        val xD = layer.value(LayerProp.X, 0.0)
        val yD = layer.value(LayerProp.Y, 0.0)
        val x = xD.toFloat()
        val y = yD.toFloat()
        // A formula that reads a missing value gives NaN: then this copy is not drawn
        if (!x.isFinite() || !y.isFinite()) return
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt()
        if (paint.alpha == 0) return
        val gradient = source.gradient
        when (source.kind) {
            ShapeKind.LINE   -> {
                val length = layer.valueF(LayerProp.LENGTH, 20f)
                val angle = Math.toRadians(layer.valueF(LayerProp.ANGLE, 0f).toDouble())
                val thickness = layer.valueF(LayerProp.THICKNESS, 2f)
                if (!length.isFinite() || !angle.isFinite() || !(thickness >= 0f)) return
                val save = transform(canvas, layer, x, y, scale)
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = thickness * scale
                canvas.drawLine(x * scale, y * scale, (x + length * sin(angle).toFloat()) * scale, (y + length * cos(angle).toFloat()) * scale, paint)
                restore(canvas, save)
            }

            ShapeKind.RECT   -> {
                val widthD = layer.value(LayerProp.WIDTH, 10.0)
                val width = widthD.toFloat()
                val height = layer.valueF(LayerProp.HEIGHT, 10f)
                if (!width.isFinite() || !height.isFinite()) return
                if (gradient == null) {
                    val save = transform(canvas, layer, x + width / 2, y + height / 2, scale)
                    canvas.drawRect(x * scale, y * scale, (x + width) * scale, (y + height) * scale, paint)
                    restore(canvas, save)
                } else {
                    if (!(width > 0f)) return
                    // The middle worked out in full precision: the same number as a design that gives the middle itself
                    val middleX = (xD + widthD / 2).toFloat()
                    val save = transform(canvas, layer, middleX, y + height / 2, scale, always = true)
                    shaded(layer, color, alpha, gradient)
                    if (gradient.type == LayerGradient.Type.LINEAR) {
                        canvas.translate(middleX * scale, 0f)
                        canvas.scale(width * scale / 2 / SHADER_SIZE, 1f)
                        canvas.drawRect(-SHADER_SIZE, y * scale, SHADER_SIZE, (y + height) * scale, paint)
                    } else {
                        // From the middle to the nearer edge, stretched to the rectangle
                        canvas.translate(middleX * scale, (y + height / 2) * scale)
                        canvas.scale(width * scale / 2 / SHADER_SIZE, height * scale / 2 / SHADER_SIZE)
                        canvas.drawRect(-SHADER_SIZE, -SHADER_SIZE, SHADER_SIZE, SHADER_SIZE, paint)
                    }
                    restore(canvas, save)
                }
            }

            ShapeKind.CIRCLE -> {
                val radius = layer.valueF(LayerProp.RADIUS, 4f) * scale
                if (!(radius > 0f) || !radius.isFinite()) return
                if (gradient == null) {
                    val save = transform(canvas, layer, x, y, scale)
                    canvas.drawCircle(x * scale, y * scale, radius, paint)
                    restore(canvas, save)
                } else {
                    val save = transform(canvas, layer, x, y, scale, always = true)
                    shaded(layer, color, alpha, gradient)
                    canvas.translate(x * scale, y * scale)
                    canvas.scale(radius / SHADER_SIZE, radius / SHADER_SIZE)
                    canvas.drawCircle(0f, 0f, SHADER_SIZE, paint)
                    restore(canvas, save)
                }
            }
        }
        paint.shader = null
    }

    /**
     * Sets [paint] to draw [gradient] in [color] at [alpha]: white with the alpha of the copy, and the
     * colour with its own alpha in the shader. One shader per colour and layer, made for [SHADER_SIZE]
     * and scaled to each copy: one per size would fill the memory when the size changes every frame.
     */
    private fun shaded(layer: Compiled, color: Int, alpha: Float, gradient: LayerGradient) {
        paint.color = Color.WHITE
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt()
        paint.shader = layer.shaders.getOrPut(color) {
            val colors = IntArray(gradient.stops.size) { k -> faded(color, gradient.stops[k].alpha) }
            val evenly = gradient.stops.indices.all { k -> gradient.stops[k].position == k.toFloat() / (gradient.stops.size - 1) }
            val positions = if (evenly) null else FloatArray(gradient.stops.size) { k -> gradient.stops[k].position }
            if (gradient.type == LayerGradient.Type.LINEAR) LinearGradient(-SHADER_SIZE, 0f, SHADER_SIZE, 0f, colors, positions, Shader.TileMode.CLAMP)
            else RadialGradient(0f, 0f, SHADER_SIZE, colors, positions, Shader.TileMode.CLAMP)
        }
    }

    /**
     * [color] with its alpha times [factor]. A soft edge fades to the colour made clear, not to clear
     * black: else the edge of a light turns grey where the colours are mixed without their alpha.
     */
    private fun faded(color: Int, factor: Float): Int = when {
        factor >= 1f -> color
        factor <= 0f -> color and 0x00FFFFFF
        else         -> (color and 0x00FFFFFF) or ((Color.alpha(color) * factor).roundToInt() shl 24)
    }

    private fun clip(canvas: Canvas, layer: Compiled, scale: Float, roundDone: Boolean) {
        val clip = layer.spec.clip
        clip.rect?.let { canvas.clipRect(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale) }
        if (clip.round && !roundDone) canvas.clipPath(roundPath(scale))
        if (clip.hasShapes) {
            val kept = shapePaths[layer]
            val path = if (kept != null && kept.first == scale) kept.second else shapesPath(clip, scale).also { shapePaths[layer] = scale to it }
            canvas.clipPath(path)
        }
    }

    /** The round screen at [scale]. */
    private fun roundPath(scale: Float): Path {
        if (roundFor != scale) {
            roundPath.reset()
            roundPath.addCircle(DESIGN_SIZE / 2 * scale, DESIGN_SIZE / 2 * scale, DESIGN_SIZE / 2 * scale, Path.Direction.CW)
            roundFor = scale
        }
        return roundPath
    }

    /** The rings and boxes of [clip] at [scale], as one shape. */
    private fun shapesPath(clip: LayerClip, scale: Float): Path {
        val all = Path()
        for (ring in clip.rings) {
            val outer = Path().apply { addCircle(ring.cx * scale, ring.cy * scale, ring.outer * scale, Path.Direction.CW) }
            val inner = Path().apply { addCircle(ring.cx * scale, ring.cy * scale, ring.inner * scale, Path.Direction.CW) }
            outer.op(inner, Path.Op.DIFFERENCE)
            all.op(outer, Path.Op.UNION)
        }
        for (box in clip.boxes) {
            val r = clip.cornerRadius * scale
            val path = Path().apply { addRoundRect(box.left * scale, box.top * scale, box.right * scale, box.bottom * scale, r, r, Path.Direction.CW) }
            all.op(path, Path.Op.UNION)
        }
        return all
    }

    /**
     * The picture of [source] at the largest size it is drawn ([AnimLayerSpec.maxScale]), with its glow,
     * made again when the surface scale changes. A blur is slow and not every hardware canvas can do it,
     * so the glow is made once here, in software.
     */
    private fun picture(layer: Compiled, source: LayerSource.Image, scale: Float): Bitmap? {
        val kept = layer.picture
        if (kept != null && !kept.isRecycled && layer.madeForScale == scale) return kept
        releaseOwnPicture(layer)
        val drawable = source.drawable ?: return null
        val largest = layer.spec.maxScale
        var width = ceil(source.rect.width * scale * largest).toInt().coerceIn(1, MAX_PICTURE_SIZE)
        var height = ceil(source.rect.height * scale * largest).toInt().coerceIn(1, MAX_PICTURE_SIZE)
        // Layers that show the same picture at the same size share one bitmap, for example a curtain
        // and its fainter copy
        val same = layers.firstOrNull { other ->
            other !== layer && other.ownsPicture && other.madeForScale == scale && other.picture?.isRecycled == false &&
                (other.spec.source as? LayerSource.Image)?.drawable === drawable && other.picture?.width == width && other.picture?.height == height
        }
        val bitmap = if (same != null) {
            layer.ownsPicture = false
            same.picture!!
        } else {
            // All kept pictures together stay inside a budget: a picture that does not fit is made smaller
            val free = MAX_TOTAL_BYTES - keptBytes()
            val wanted = width.toLong() * height * BYTES_PER_PIXEL
            if (wanted > free) {
                val factor = sqrt(free.coerceAtLeast(MIN_PICTURE_BYTES).toDouble() / wanted)
                width = (width * factor).toInt().coerceAtLeast(1)
                height = (height * factor).toInt().coerceAtLeast(1)
            }
            layer.ownsPicture = true
            render(drawable, width, height)
        }
        layer.picture = bitmap
        layer.madeForScale = scale
        val glow = layer.spec.glow
        if (glow != null)
            layer.glow = runCatching {
                val blur = Paint().apply { maskFilter = BlurMaskFilter(glow.radius * scale * largest, BlurMaskFilter.Blur.NORMAL) }
                bitmap.extractAlpha(blur, layer.glowOffset)
            }.getOrNull()
        return bitmap
    }

    /**
     * The picture of a [LayerSource.Dynamic] layer at the largest size it is drawn
     * ([AnimLayerSpec.maxScale]), from [images]. Asked again when the minute or the size changes, so a
     * frame costs nothing: the caller makes a new picture only when it looks different (see
     * `DynamicImages`). Null when there is none.
     */
    private fun dynamicPicture(layer: Compiled, source: LayerSource.Dynamic, scale: Float): Bitmap? {
        val size = ceil(source.square.width * scale * layer.spec.maxScale).toInt().coerceIn(1, MAX_DYNAMIC_SIZE)
        val minute = Math.floorDiv(nowMs, DYNAMIC_ASK_MS)
        if (minute != layer.dynamicMinute || size != layer.dynamicSize) {
            layer.dynamic = runCatching { images(source.name, nowMs, size) }.getOrNull()
            layer.dynamicMinute = minute
            layer.dynamicSize = size
        }
        return layer.dynamic?.takeIf { !it.isRecycled }
    }

    /** The bytes of all pictures, glows and masks kept now, each shared picture counted once. */
    private fun keptBytes(): Long = layers.sumOf { layer ->
        (if (layer.ownsPicture) layer.picture.bytes() else 0L) + layer.glow.bytes() + layer.mask.bytes()
    }

    private fun Bitmap?.bytes(): Long = if (this == null || isRecycled) 0L else allocationByteCount.toLong()

    /**
     * The picture of [mask] at the size it is drawn at [scale], made again when the scale changes. Kept
     * in full colour: an alpha-only (`ALPHA_8`) copy would be 4 times smaller, but drawn with `DST_IN` it
     * did not cut at all (tested, `LayerAnimatorTest`), so it is not used.
     */
    private fun mask(layer: Compiled, mask: LayerMask, scale: Float): Bitmap? {
        val kept = layer.mask
        if (kept != null && !kept.isRecycled && layer.maskForScale == scale) return kept
        layer.mask?.recycle()
        layer.mask = null
        val drawable = mask.drawable ?: return null
        val width = ceil(mask.rect.width * scale).toInt().coerceIn(1, MAX_PICTURE_SIZE)
        val height = ceil(mask.rect.height * scale).toInt().coerceIn(1, MAX_PICTURE_SIZE)
        val full = render(drawable, width, height)
        return full.also {
            layer.mask = it
            layer.maskForScale = scale
        }
    }

    /** [drawable] drawn on a new bitmap of [width] x [height]. */
    private fun render(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val pictureWidth = drawable.intrinsicWidth
        val pictureHeight = drawable.intrinsicHeight
        if (drawable is PictureDrawable && pictureWidth > 0 && pictureHeight > 0) {
            // An SVG from the zip: a PictureDrawable does not scale to its bounds, so scale the canvas
            canvas.scale(width.toFloat() / pictureWidth, height.toFloat() / pictureHeight)
            drawable.setBounds(0, 0, pictureWidth, pictureHeight)
        } else
            drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun releasePicture(layer: Compiled) {
        releaseOwnPicture(layer)
        layer.mask?.recycle()
        layer.mask = null
        layer.maskForScale = 0f
        // The dynamic picture belongs to the caller: only let go
        layer.dynamic = null
        layer.dynamicMinute = Long.MIN_VALUE
        layer.dynamicSize = 0
    }

    /** Frees the picture and glow of [layer]; a picture shared from another layer is only let go. */
    private fun releaseOwnPicture(layer: Compiled) {
        if (layer.ownsPicture) layer.picture?.recycle()
        layer.glow?.recycle()
        layer.picture = null
        layer.ownsPicture = true
        layer.glow = null
        layer.madeForScale = 0f
    }

    private val clipBounds = Rect()
    private val layerBounds = RectF()
    private val glowDst = RectF()

    private companion object {

        /** The layer does not give the property (beside the rates of [Rate]). */
        const val NONE = 0

        /** For a formula that reads no variable. */
        val NO_VARIABLES = DoubleArray(0)

        /** [transform] saved nothing. */
        const val NO_SAVE = -1

        /** An open side of the clip props, in design pixels: far outside any screen. */
        const val OPEN = 100_000f

        /** All kept pictures together, at most: a few full screens, so a mistake in a design cannot fill the memory. */
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

        /** A picture that does not fit the budget still gets at least this much. */
        const val MIN_PICTURE_BYTES = 64L * 1024
        const val BYTES_PER_PIXEL = 4

        /** Below this alpha a copy is not drawn at all. */
        const val ALPHA_SHOWN = ViewEffects.ALPHA_SHOWN

        /** The largest side of a kept picture, so a mistake cannot fill the memory. */
        const val MAX_PICTURE_SIZE = 2048

        /** The size, in screen pixels, the gradient shaders are made for. */
        const val SHADER_SIZE = 100f

        /**
         * The largest side of a dynamic picture, so a mistake in a design cannot ask for a huge one.
         */
        const val MAX_DYNAMIC_SIZE = 512

        /** How often a dynamic layer asks for its picture again. */
        const val DYNAMIC_ASK_MS = 60_000L

        /** True for a number that counts as true in a formula: not 0 and not NaN. */
        fun truth(x: Double): Boolean = x != 0.0 && !x.isNaN()
    }
}
