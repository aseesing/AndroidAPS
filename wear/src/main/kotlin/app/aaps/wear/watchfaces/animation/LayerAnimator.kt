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
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What the view layers do to their views in one frame. */
data class ViewEffects(
    /** Degrees each view is turned more than it is. */
    val turns: Map<String, Float> = emptyMap(),
    /** Design pixels each view is moved right. */
    val shifts: Map<String, Float> = emptyMap(),
    /** The views not drawn in this frame. */
    val hidden: Set<String> = emptySet(),
    /** Views cut off right of this x, in design pixels. */
    val clipRights: Map<String, Float> = emptyMap()
)

/**
 * Draws the [AnimLayerSpec] layers of a design: reads their formulas once, then for every frame works
 * them out for every copy and draws it. A layer whose formula cannot be read is left out, so a mistake
 * in one layer never costs the rest of the design.
 *
 * Not thread safe: one animator draws on one thread.
 *
 * @param zone the time zone of `sec`, `min` and `hour`
 */
class LayerAnimator(specs: List<AnimLayerSpec>, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {

    private class Compiled(
        val spec: AnimLayerSpec,
        val props: Array<Formula?>,
        /** Per copy, its random numbers r1.. one after the other. */
        val randoms: FloatArray,
        val randomCount: Int
    ) {

        var picture: Bitmap? = null

        /** False when [picture] belongs to another layer with the same picture: then this one must not free it. */
        var ownsPicture = true
        var glow: Bitmap? = null
        val glowOffset = IntArray(2)
        var madeForScale = 0f
        var mask: Bitmap? = null
        var maskForScale = 0f

        /** True when a copy may be turned or scaled; else the canvas needs no save per copy. */
        val hasTransform = props[LayerProp.ROTATION.ordinal] != null || props[LayerProp.SCALE_X.ordinal] != null || props[LayerProp.SCALE_Y.ordinal] != null
    }

    private val variables = LayerVariables()

    /** True when a formula reads `sec`, `min` or `hour`: only then the clock time is worked out per frame. */
    private var usesClock = false
    private val layers: List<Compiled>
    private val values: DoubleArray

    /** The layers that could not be read, with the reason; for the design tests. */
    val errors: List<String>

    init {
        val errorList = ArrayList<String>()
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
                var randomCount = 0
                val formulas = arrayOfNulls<Formula>(LayerProp.entries.size)
                for ((prop, text) in spec.props) {
                    formulas[prop.ordinal] = Formula.parse(text) { name ->
                        variables.index(name)?.also { i ->
                            if (i >= LayerVariables.R1 && i < LayerVariables.R1 + LayerVariables.RANDOMS) randomCount = max(randomCount, i - LayerVariables.R1 + 1)
                            if (i == LayerVariables.SEC || i == LayerVariables.MIN || i == LayerVariables.HOUR) usesClock = true
                        }
                    }
                }
                val randoms = FloatArray(spec.copies.size * randomCount)
                if (randomCount > 0) {
                    val random = Random((spec.copies as? LayerCopies.Count)?.seed ?: index.toLong())
                    for (k in randoms.indices) randoms[k] = random.nextFloat()
                }
                compiled += Compiled(spec, formulas, randoms, randomCount)
                copies += spec.copies.size
            } catch (e: FormulaException) {
                variables.forgetValueKeysFrom(valueKeysBefore)
                errorList += "layer $index: ${e.message}"
            }
        }
        layers = compiled
        errors = errorList
        values = DoubleArray(variables.size)
        values[LayerVariables.BPM] = PulseCurve.FALLBACK_BPM.toDouble()
        values[LayerVariables.ENV] = 1.0
        for (i in variables.valueKeys.indices) values[LayerVariables.FIXED.size + i] = Double.NaN
    }

    /** True when there is a layer to draw at all. */
    val isEmpty: Boolean get() = layers.isEmpty()

    /** The keys of the views that the layers move, hide or cut, drawn live while a wake plays. */
    val viewKeys: Set<String> = layers.mapNotNullTo(LinkedHashSet()) { (it.spec.source as? LayerSource.View)?.key }

    /** True when a layer of [slot] draws something other than a view. */
    fun hasSlot(slot: LayerSlot): Boolean = layers.any { it.spec.slot == slot && it.spec.source !is LayerSource.View }

    /** True when some layer is also drawn while no animation plays. */
    val hasRest: Boolean get() = layers.any { it.spec.restSeconds != null }

    /** The value keys the formulas read, for example `watch_battery`. */
    val valueKeys: List<String> get() = variables.valueKeys

    /** Takes the values that stay the same for a whole wake: the value keys ([value]) and the heart rate. */
    fun setWakeValues(value: (String) -> Double?, bpm: Int) {
        for ((i, key) in variables.valueKeys.withIndex()) values[LayerVariables.FIXED.size + i] = value(key) ?: Double.NaN
        values[LayerVariables.BPM] = bpm.toDouble()
    }

    /** Sets the time of the frame: [elapsedMs] into a wake of [lengthMs], fade [env], at the clock time [nowMs]. */
    fun setTime(elapsedMs: Long, lengthMs: Long, env: Float, nowMs: Long) {
        values[LayerVariables.T] = elapsedMs / 1000.0
        values[LayerVariables.MS] = elapsedMs.toDouble()
        values[LayerVariables.LEN] = lengthMs.toDouble()
        values[LayerVariables.ENV] = env.toDouble()
        if (!usesClock) return
        // Not LocalTime.ofInstant: that needs API 31, the watch app runs from 30
        val time = Instant.ofEpochMilli(nowMs).atZone(zone()).toLocalTime()
        values[LayerVariables.SEC] = time.second + time.nano / 1e9
        values[LayerVariables.MIN] = time.minute.toDouble()
        values[LayerVariables.HOUR] = time.hour.toDouble()
    }

    /** Draws the pictures and shapes of [slot] for the time set by [setTime], at [scale] screen pixels per design pixel. */
    fun drawSlot(canvas: Canvas, slot: LayerSlot, scale: Float) {
        for (layer in layers) if (layer.spec.slot == slot && layer.spec.source !is LayerSource.View) drawLayer(canvas, layer, scale)
    }

    /**
     * Draws the layers that stay on the face while no animation plays, each as it is its rest time into
     * a wake, at the clock time [nowMs], with `env` 0 (nothing plays): a layer that scales its motion
     * by `env` so shows its still pose, the same as at the very start and end of a wake. The value keys
     * are read again through [value].
     */
    fun drawRest(canvas: Canvas, scale: Float, nowMs: Long, value: (String) -> Double?) {
        // A normal frame is a good moment to make the pictures, so the first frame of a wake does not stutter
        prepare(scale)
        if (!hasRest) return
        setWakeValues(value, values[LayerVariables.BPM].toInt())
        for (layer in layers) {
            val rest = layer.spec.restSeconds ?: continue
            setTime((rest * 1000).toLong(), WakeAnimationSpec.MAX_DURATION_MS, 0f, nowMs)
            drawLayer(canvas, layer, scale)
        }
    }

    /**
     * What the view layers do now. [rotation] and [offsetX] give the own turn and move of a view
     * (the variables `rot0` and `dx0`); a view the face does not have is left alone.
     */
    fun viewEffects(rotation: (String) -> Float?, offsetX: (String) -> Float?): ViewEffects {
        if (viewKeys.isEmpty()) return NO_VIEW_EFFECTS
        val turns = HashMap<String, Float>()
        val shifts = HashMap<String, Float>()
        val hidden = HashSet<String>()
        val clips = HashMap<String, Float>()
        for (layer in layers) {
            val key = (layer.spec.source as? LayerSource.View)?.key ?: continue
            val rot0 = rotation(key) ?: continue
            setCopy(layer, 0)
            values[LayerVariables.ROT0] = rot0.toDouble()
            values[LayerVariables.DX0] = (offsetX(key) ?: 0f).toDouble()
            layer.prop(LayerProp.ROTATION)?.let { turns[key] = (turns[key] ?: 0f) + it }
            layer.prop(LayerProp.X)?.let { shifts[key] = (shifts[key] ?: 0f) + it }
            layer.prop(LayerProp.ALPHA)?.let { if (!(it > ALPHA_SHOWN)) hidden += key }
            layer.prop(LayerProp.CLIP_RIGHT)?.let { clips[key] = min(clips[key] ?: Float.MAX_VALUE, it) }
        }
        return ViewEffects(turns, shifts, hidden, clips)
    }

    /** The number of layers that could be read, for the tests. */
    val size: Int get() = layers.size

    /**
     * The value of [prop] of copy [copy] of layer [layer] (counted over the layers that could be read),
     * for the time set by [setTime]; null when the layer does not give that property. For the tests.
     */
    fun propOf(layer: Int, copy: Int, prop: LayerProp): Float? {
        val compiled = layers[layer]
        setCopy(compiled, copy)
        return compiled.prop(prop)
    }

    /**
     * Makes the pictures and masks of all layers for [scale] now, when they are not made yet, so the
     * first frame of a wake does not stop to make them.
     */
    fun prepare(scale: Float) {
        for (layer in layers) {
            (layer.spec.source as? LayerSource.Image)?.let { picture(layer, it, scale) }
            layer.spec.clip.mask?.let { mask(layer, it, scale) }
        }
    }

    /** Frees the pictures; the next frame makes them again. */
    fun release() {
        // Those that share a picture first, so no layer keeps a freed one
        for (layer in layers) if (!layer.ownsPicture) releasePicture(layer)
        for (layer in layers) releasePicture(layer)
    }

    // ---- drawing ----

    private fun Compiled.prop(prop: LayerProp): Float? = props[prop.ordinal]?.evalF(values)
    private fun Compiled.value(prop: LayerProp, default: Float): Float = props[prop.ordinal]?.evalF(values) ?: default

    private fun setCopy(layer: Compiled, i: Int) {
        val n = layer.spec.copies.size
        values[LayerVariables.I] = i.toDouble()
        values[LayerVariables.N] = n.toDouble()
        values[LayerVariables.U] = if (n > 1) i / (n - 1.0) else 0.0
        val point = (layer.spec.copies as? LayerCopies.Points)?.points?.getOrNull(i)
        values[LayerVariables.PX] = point?.x?.toDouble() ?: 0.0
        values[LayerVariables.PY] = point?.y?.toDouble() ?: 0.0
        for (k in 0 until layer.randomCount) values[LayerVariables.R1 + k] = layer.randoms[i * layer.randomCount + k].toDouble()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

    /** Lays the offscreen layer of a masked layer over the face, with the blend of the layer. */
    private val layerPaint = Paint()

    /** Keeps the offscreen layer only where the mask picture is not clear. */
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val maskDst = RectF()
    private var roundFor = 0f
    private val roundPath = Path()
    private val shapePaths = HashMap<Compiled, Pair<Float, Path>>()

    /**
     * One shader per colour, made for [SHADER_SIZE] and scaled to the size of each copy. Not one per
     * size: a size that changes every frame would fill the memory.
     */
    private val bandShaders = HashMap<Int, Shader>()
    private val dotShaders = HashMap<Int, Shader>()

    /**
     * Draws all copies of [layer]. A layer with a mask is first drawn on its own, in an offscreen layer
     * the size of the mask: the copies with the normal blend, then the mask cuts away what it does not
     * keep, and only then the result is laid over the face with the blend of the layer. So the mask
     * never cuts holes in what is already on the face.
     */
    private fun drawLayer(canvas: Canvas, layer: Compiled, scale: Float) {
        val spec = layer.spec
        val picture = (spec.source as? LayerSource.Image)?.let { picture(layer, it, scale) ?: return }
        val maskSpec = spec.clip.mask
        val mask = maskSpec?.let { mask(layer, it, scale) ?: return }
        val blend = when (spec.blend) {
            LayerBlend.NORMAL -> null
            LayerBlend.ADD    -> add
            LayerBlend.SCREEN -> screen
        }
        val save = canvas.save()
        clip(canvas, layer, scale)
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
        for (i in 0 until spec.copies.size) {
            setCopy(layer, i)
            val alpha = layer.value(LayerProp.ALPHA, 1f)
            if (!(alpha > ALPHA_SHOWN)) continue
            when (val source = spec.source) {
                is LayerSource.Image -> drawPicture(canvas, layer, source, picture!!, i, alpha, scale)
                is LayerSource.Shape -> drawShape(canvas, layer, source, alpha, scale)
                is LayerSource.View  -> Unit
            }
        }
        if (mask != null) canvas.drawBitmap(mask, null, maskDst, maskPaint)
        canvas.restoreToCount(save)
        paint.xfermode = null
        paint.shader = null
    }

    /**
     * Turns and scales the canvas round the pivot, for one copy. Returns the save count to restore to,
     * or [NO_SAVE] when the layer never turns or scales and [always] is false: then nothing is saved,
     * which spares two canvas calls per copy. A value that is not a number counts as no turn or scale.
     */
    private fun transform(canvas: Canvas, layer: Compiled, defaultPivotX: Float, defaultPivotY: Float, scale: Float, always: Boolean = false): Int {
        if (!layer.hasTransform) return if (always) canvas.save() else NO_SAVE
        val save = canvas.save()
        val pivotX = layer.value(LayerProp.PIVOT_X, defaultPivotX).finiteOr(defaultPivotX) * scale
        val pivotY = layer.value(LayerProp.PIVOT_Y, defaultPivotY).finiteOr(defaultPivotY) * scale
        val rotation = layer.value(LayerProp.ROTATION, 0f).finiteOr(0f)
        if (rotation != 0f) canvas.rotate(rotation, pivotX, pivotY)
        val scaleX = layer.value(LayerProp.SCALE_X, 1f).finiteOr(1f)
        val scaleY = layer.value(LayerProp.SCALE_Y, 1f).finiteOr(1f)
        if (scaleX != 1f || scaleY != 1f) canvas.scale(scaleX, scaleY, pivotX, pivotY)
        return save
    }

    private fun Float.finiteOr(default: Float): Float = if (isFinite()) this else default

    private fun restore(canvas: Canvas, save: Int) {
        if (save != NO_SAVE) canvas.restoreToCount(save)
    }

    private fun drawPicture(canvas: Canvas, layer: Compiled, source: LayerSource.Image, picture: Bitmap, i: Int, alpha: Float, scale: Float) {
        val rect = source.rect
        val x = layer.value(LayerProp.X, 0f)
        val y = layer.value(LayerProp.Y, 0f)
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
        val glowStrength = layer.value(LayerProp.GLOW, 0f) * alpha
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
        val colorIndex = layer.value(LayerProp.COLOR, 0f).toInt().mod(source.colors.size)
        val color = source.colors[colorIndex]
        val x = layer.value(LayerProp.X, 0f)
        val y = layer.value(LayerProp.Y, 0f)
        // A formula that reads a missing value gives NaN: then this copy is not drawn
        if (!x.isFinite() || !y.isFinite()) return
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt()
        if (paint.alpha == 0) return
        when (source.kind) {
            ShapeKind.LINE   -> {
                val length = layer.value(LayerProp.LENGTH, 20f)
                val angle = Math.toRadians(layer.value(LayerProp.ANGLE, 0f).toDouble())
                val thickness = layer.value(LayerProp.THICKNESS, 2f)
                if (!length.isFinite() || !angle.isFinite() || !(thickness >= 0f)) return
                val save = transform(canvas, layer, x, y, scale)
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = thickness * scale
                canvas.drawLine(x * scale, y * scale, (x + length * sin(angle).toFloat()) * scale, (y + length * cos(angle).toFloat()) * scale, paint)
                restore(canvas, save)
            }

            ShapeKind.RECT   -> {
                val width = layer.value(LayerProp.WIDTH, 10f)
                val height = layer.value(LayerProp.HEIGHT, 10f)
                if (!width.isFinite() || !height.isFinite()) return
                val save = transform(canvas, layer, x + width / 2, y + height / 2, scale)
                canvas.drawRect(x * scale, y * scale, (x + width) * scale, (y + height) * scale, paint)
                restore(canvas, save)
            }

            ShapeKind.BAND   -> {
                val width = layer.value(LayerProp.WIDTH, 10f)
                val height = layer.value(LayerProp.HEIGHT, 10f)
                if (!(width > 0f) || !width.isFinite() || !height.isFinite()) return
                val save = transform(canvas, layer, x, y + height / 2, scale, always = true)
                val half = width * scale / 2
                paint.color = Color.WHITE
                paint.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt()
                paint.shader = bandShaders.getOrPut(color) {
                    LinearGradient(-SHADER_SIZE, 0f, SHADER_SIZE, 0f, intArrayOf(clearOf(color), color, clearOf(color)), null, Shader.TileMode.CLAMP)
                }
                canvas.translate(x * scale, 0f)
                canvas.scale(half / SHADER_SIZE, 1f)
                canvas.drawRect(-SHADER_SIZE, y * scale, SHADER_SIZE, (y + height) * scale, paint)
                restore(canvas, save)
            }

            ShapeKind.DOT    -> {
                val radius = layer.value(LayerProp.RADIUS, 4f) * scale
                if (!(radius > 0f) || !radius.isFinite()) return
                val save = transform(canvas, layer, x, y, scale, always = true)
                paint.color = Color.WHITE
                paint.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt()
                paint.shader = dotShaders.getOrPut(color) {
                    RadialGradient(0f, 0f, SHADER_SIZE, intArrayOf(color, color, clearOf(color)), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
                }
                canvas.translate(x * scale, y * scale)
                canvas.scale(radius / SHADER_SIZE, radius / SHADER_SIZE)
                canvas.drawCircle(0f, 0f, SHADER_SIZE, paint)
                restore(canvas, save)
            }

            ShapeKind.CIRCLE -> {
                val radius = layer.value(LayerProp.RADIUS, 4f) * scale
                if (!(radius > 0f) || !radius.isFinite()) return
                val save = transform(canvas, layer, x, y, scale)
                canvas.drawCircle(x * scale, y * scale, radius, paint)
                restore(canvas, save)
            }
        }
        paint.shader = null
    }

    /**
     * [color] made fully clear. A soft edge fades to this, not to clear black: else the edge of a light
     * turns grey where the colours are mixed without their alpha.
     */
    private fun clearOf(color: Int): Int = color and 0x00FFFFFF

    private fun clip(canvas: Canvas, layer: Compiled, scale: Float) {
        val clip = layer.spec.clip
        clip.rect?.let { canvas.clipRect(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale) }
        if (clip.round) {
            if (roundFor != scale) {
                roundPath.reset()
                roundPath.addCircle(DESIGN_SIZE / 2 * scale, DESIGN_SIZE / 2 * scale, DESIGN_SIZE / 2 * scale, Path.Direction.CW)
                roundFor = scale
            }
            canvas.clipPath(roundPath)
        }
        if (clip.hasShapes) {
            val kept = shapePaths[layer]
            val path = if (kept != null && kept.first == scale) kept.second else shapesPath(clip, scale).also { shapePaths[layer] = scale to it }
            canvas.clipPath(path)
        }
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

        /** [transform] saved nothing. */
        const val NO_SAVE = -1

        /** All kept pictures together, at most: a few full screens, so a mistake in a design cannot fill the memory. */
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

        /** A picture that does not fit the budget still gets at least this much. */
        const val MIN_PICTURE_BYTES = 64L * 1024
        const val BYTES_PER_PIXEL = 4

        val NO_VIEW_EFFECTS = ViewEffects()

        /** Below this alpha a copy is not drawn at all. */
        const val ALPHA_SHOWN = 0.002f

        /** The largest side of a kept picture, so a mistake cannot fill the memory. */
        const val MAX_PICTURE_SIZE = 2048

        /** The size, in screen pixels, the band and dot shaders are made for. */
        const val SHADER_SIZE = 100f
    }
}
