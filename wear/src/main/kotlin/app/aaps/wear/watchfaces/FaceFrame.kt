// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import app.aaps.wear.watchfaces.CustomWatchface.RenderLayer
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.ViewEffects
import app.aaps.wear.watchfaces.utils.FrameParts
import app.aaps.wear.watchfaces.utils.ViewPainter

/**
 * The parts of the Custom watch face for its kept frames (see `FrameLayers`): the normal frame with a
 * running second hand and the frames of the wake-up animation. Each part draws only its own views of
 * the main layout, cut by [RenderLayer], exactly where they sit.
 *
 * The views of each layer, the view of each design key and where the design puts it are worked out
 * once per design ([setViews]), so a frame looks nothing up by name and makes no objects.
 *
 * @param layout measures and lays out the main layout for the frame
 * @param drawBackground the background image and colour
 * @param drawSlots the complication slots, where they are this frame
 * @param drawRest the wake layers of a slot that stay on the face, at a clock time
 */
class FaceFrame(
    private val layout: () -> Unit,
    private val drawBackground: (Canvas) -> Unit,
    private val drawSlots: (Canvas) -> Unit,
    private val drawRest: (Canvas, Long, LayerSlot) -> Unit
) : FrameParts {

    /** Screen pixels per design pixel, of the layout of [setViews]. */
    private var zoom = 1.0

    private var painter: ViewPainter? = null

    /** Per [RenderLayer], its views in paint order, and their design keys. */
    private var layerViews: Array<Array<View>> = emptyArray()
    private var layerKeys: Array<Array<String>> = emptyArray()

    /** The index of each design key in [views], [designLeft], [designCentreX] and [designCentreY]. */
    private val indexOfKey = HashMap<String, Int>()
    private var views: Array<View?> = emptyArray()
    private var designLeft = DoubleArray(0)
    private var designCentreX = DoubleArray(0)
    private var designCentreY = DoubleArray(0)

    /** The views of the data and of the top without the views left out, for [leftOut]. */
    private var leftOut: Set<String>? = null
    private var dataViews: Array<View> = emptyArray()
    private var topViews: Array<View> = emptyArray()

    /** For the keys of [orderFor]: their indexes there, in paint order. */
    private var orderFor: List<String>? = null
    private var liveOrder = IntArray(0)
    private var liveViews: Array<View?> = emptyArray()
    private var liveIndexes = IntArray(0)
    private var saved = FloatArray(0)

    /** Where the design puts a view, in design pixels. */
    class Place(val left: Double, val top: Double, val width: Double, val height: Double)

    /**
     * Takes the views of the face, once per design: [parent] is the main layout, [layers] the views of
     * each [RenderLayer] in paint order with their design keys, [places] where the design puts each view
     * (in design pixels), [zoom] the screen pixels per design pixel.
     */
    fun setViews(parent: ViewGroup, layers: List<List<Pair<String, View>>>, places: Map<String, Place>, zoom: Double) {
        painter?.childrenChanged() ?: run { painter = ViewPainter(parent) }
        this.zoom = zoom
        layerViews = Array(layers.size) { i -> Array(layers[i].size) { layers[i][it].second } }
        layerKeys = Array(layers.size) { i -> Array(layers[i].size) { layers[i][it].first } }
        indexOfKey.clear()
        val all = layers.flatten()
        views = arrayOfNulls(all.size)
        designLeft = DoubleArray(all.size)
        designCentreX = DoubleArray(all.size)
        designCentreY = DoubleArray(all.size)
        all.forEachIndexed { index, (key, view) ->
            indexOfKey[key] = index
            views[index] = view
            val place = places[key]
            designLeft[index] = place?.left ?: 0.0
            designCentreX[index] = place?.let { it.left + it.width / 2 } ?: Double.NaN
            designCentreY[index] = place?.let { it.top + it.height / 2 } ?: Double.NaN
        }
        leftOut = null
        orderFor = null
    }

    override fun prepare() = layout()

    override fun background(canvas: Canvas) = drawBackground(canvas)

    override fun rest(canvas: Canvas, nowMs: Long, slot: LayerSlot) = drawRest(canvas, nowMs, slot)

    override fun data(canvas: Canvas, leaveOut: Set<String>) {
        drawSlots(canvas)
        withoutViews(leaveOut)
        painter?.draw(canvas, dataViews)
    }

    override fun clockText(canvas: Canvas) = drawLayer(canvas, RenderLayer.CLOCK_TEXT)

    override fun top(canvas: Canvas, leaveOut: Set<String>) {
        withoutViews(leaveOut)
        painter?.draw(canvas, topViews)
    }

    override fun secondHand(canvas: Canvas) = drawLayer(canvas, RenderLayer.SECOND_HAND)

    /** Draws the views of [layer] only, where they sit in the whole face. */
    private fun drawLayer(canvas: Canvas, layer: RenderLayer) {
        val views = layerViews.getOrNull(layer.ordinal) ?: return
        painter?.draw(canvas, views)
    }

    override fun rotation(key: String): Float = viewOf(key)?.rotation ?: Float.NaN

    override fun offsetX(key: String): Float {
        val index = indexOfKey[key] ?: return Float.NaN
        val view = views[index] ?: return Float.NaN
        return (view.left / zoom - designLeft[index]).toFloat()
    }

    override fun centreX(key: String): Float = indexOfKey[key]?.let { designCentreX[it].toFloat() } ?: Float.NaN

    override fun centreY(key: String): Float = indexOfKey[key]?.let { designCentreY[it].toFloat() } ?: Float.NaN

    override fun view(canvas: Canvas, effects: ViewEffects, index: Int) {
        val view = viewOf(effects.keys[index]) ?: return
        orderLive(effects)
        liveViews[0] = view
        liveIndexes[0] = index
        drawMoved(canvas, effects, 1, liveIndexes)
    }

    override fun views(canvas: Canvas, effects: ViewEffects, members: BooleanArray) {
        orderLive(effects)
        var count = 0
        for (i in liveOrder) {
            if (!members[i] || effects.isHidden(i) || effects.isClipped(i)) continue
            liveViews[count] = viewOf(effects.keys[i])
            liveIndexes[count] = i
            count++
        }
        drawMoved(canvas, effects, count, liveIndexes)
    }

    /**
     * Draws the first [count] of [liveViews], each changed as [effects] says for the key at the same
     * place of [indexes]: moved, turned, scaled round its middle and faded. The own values of the views
     * are given back after the draw.
     */
    private fun drawMoved(canvas: Canvas, effects: ViewEffects, count: Int, indexes: IntArray) {
        for (k in 0 until count) {
            val view = liveViews[k] ?: continue
            val i = indexes[k]
            val at = k * SAVED
            saved[at] = view.rotation
            saved[at + 1] = view.translationX
            saved[at + 2] = view.translationY
            saved[at + 3] = view.scaleX
            saved[at + 4] = view.scaleY
            saved[at + 5] = view.alpha
            if (effects.isTurned(i)) view.rotation = saved[at] + effects.turn(i)
            if (effects.isMoved(i)) {
                view.translationX = saved[at + 1] + (effects.dx(i) * zoom).toFloat()
                view.translationY = saved[at + 2] + (effects.dy(i) * zoom).toFloat()
            }
            if (effects.isScaled(i)) {
                view.scaleX = saved[at + 3] * effects.scaleX(i)
                view.scaleY = saved[at + 4] * effects.scaleY(i)
            }
            if (effects.alpha(i) < 1f) view.alpha = saved[at + 5] * effects.alpha(i)
        }
        try {
            painter?.draw(canvas, liveViews, count)
        } finally {
            for (k in 0 until count) {
                val view = liveViews[k] ?: continue
                val at = k * SAVED
                view.rotation = saved[at]
                view.translationX = saved[at + 1]
                view.translationY = saved[at + 2]
                view.scaleX = saved[at + 3]
                view.scaleY = saved[at + 4]
                view.alpha = saved[at + 5]
            }
        }
    }

    private fun viewOf(key: String): View? = indexOfKey[key]?.let { views[it] }

    /** Sorts the keys of [effects] in paint order, once per set of keys. */
    private fun orderLive(effects: ViewEffects) {
        if (orderFor === effects.keys) return
        orderFor = effects.keys
        liveOrder = effects.keys.indices.sortedBy { indexOfKey[effects.keys[it]] ?: Int.MAX_VALUE }.toIntArray()
        val size = effects.keys.size.coerceAtLeast(1)
        liveViews = arrayOfNulls(size)
        liveIndexes = IntArray(size)
        saved = FloatArray(size * SAVED)
    }

    /** Works out the views of the data and the top without [leaveOut], once per set of views left out. */
    private fun withoutViews(leaveOut: Set<String>) {
        if (leftOut === leaveOut) return
        leftOut = leaveOut
        dataViews = keep(RenderLayer.DATA_BASE, leaveOut)
        topViews = keep(RenderLayer.MIDDLE, leaveOut) + keep(RenderLayer.HANDS, leaveOut)
    }

    private fun keep(layer: RenderLayer, leaveOut: Set<String>): Array<View> {
        val views = layerViews.getOrNull(layer.ordinal) ?: return emptyArray()
        val keys = layerKeys[layer.ordinal]
        return views.filterIndexed { i, _ -> keys[i] !in leaveOut }.toTypedArray()
    }

    private companion object {

        /** The own values of a view kept for a live draw: rotation, translation x and y, scale x and y, alpha. */
        const val SAVED = 6
    }
}
