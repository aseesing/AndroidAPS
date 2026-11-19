package app.aaps.wear.watchfaces.utils

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

/**
 * Draws some of the child views of [parent], each exactly where the parent would draw it, and leaves
 * the other children out. Parts drawn one after another in paint order so give the whole picture
 * again, the way the watch face is cut into layers.
 *
 * Each view is drawn as `ViewGroup.drawChild` draws it on a software canvas: moved to its place,
 * turned and scaled by its own matrix, cut to its own bounds. That costs nothing for the views left
 * out. Where that would not be the same as the parent's own drawing (a view that is see-through or
 * has its own layer or animation, a parent with a background, padding or views with a height), it
 * falls back to drawing the whole parent with the other children made `INVISIBLE` for the draw.
 * `INVISIBLE`, not `GONE`, so nothing else moves. That fallback is not the same as the parent's own
 * drawing either when the parent has a background or a foreground: each part draws them again, so
 * the parts stacked in paint order draw them more than once. The main layout of the watch face has
 * neither.
 *
 * Not thread safe: one painter draws on one thread. Makes no objects per draw.
 */
class ViewPainter(private val parent: ViewGroup) {

    /** The visibility of each child before a fallback draw, so it can be given back. */
    private var shown = BooleanArray(0)

    /** True when no child has a height (`z`), see [childrenChanged]. */
    private var noChildHasHeight = noChildHasHeight()

    /** Looks at the children of the parent again; call when they changed (a new design). Not per draw. */
    fun childrenChanged() {
        noChildHasHeight = noChildHasHeight()
    }

    /**
     * Draws the first [count] views of [views], children of [parent] given in the parent's own order,
     * onto [canvas]. A view that is not `VISIBLE` is not drawn, as the parent does; null is skipped.
     */
    fun draw(canvas: Canvas, views: Array<out View?>, count: Int = views.size) {
        if (!parentDrawsChildrenPlainly()) return drawHidingOthers(canvas, views, count)
        for (i in 0 until count) if (views[i]?.let { isPlain(it) } == false) return drawHidingOthers(canvas, views, count)
        for (i in 0 until count) {
            val child = views[i] ?: continue
            if (child.visibility != View.VISIBLE) continue
            drawChild(canvas, child)
        }
    }

    /** The software path of `View.draw(Canvas, ViewGroup, long)` for a plain child, see [isPlain]. */
    private fun drawChild(canvas: Canvas, child: View) {
        val save = canvas.save()
        val sx = child.scrollX
        val sy = child.scrollY
        canvas.translate((child.left - sx).toFloat(), (child.top - sy).toFloat())
        val matrix = child.matrix
        if (!matrix.isIdentity) {
            canvas.translate(sx.toFloat(), sy.toFloat())
            canvas.concat(matrix)
            canvas.translate(-sx.toFloat(), -sy.toFloat())
        }
        if (parent.clipChildren) canvas.clipRect(sx, sy, sx + child.width, sy + child.height)
        child.clipBounds?.let { canvas.clipRect(it) }
        child.draw(canvas)
        canvas.restoreToCount(save)
    }

    /** True when the parent itself paints nothing and draws its children in their own order, unclipped by padding. */
    private fun parentDrawsChildrenPlainly(): Boolean =
        parent.background == null && parent.foreground == null &&
            parent.paddingLeft == 0 && parent.paddingTop == 0 && parent.paddingRight == 0 && parent.paddingBottom == 0 &&
            parent.scrollX == 0 && parent.scrollY == 0 && noChildHasHeight

    /** A child with a height (`z`) is drawn out of order by the parent. */
    private fun noChildHasHeight(): Boolean {
        for (i in 0 until parent.childCount) if (parent.getChildAt(i).z != 0f) return false
        return true
    }

    /** True when [child] is drawn by the parent with nothing more than its place, its matrix and its clip. */
    private fun isPlain(child: View): Boolean =
        child.parent === parent && child.animation == null && child.layerType == View.LAYER_TYPE_NONE &&
            child.alpha * child.transitionAlpha >= 1f

    /** Draws the whole [parent] with every child that is not among the first [count] of [views] made `INVISIBLE`. */
    private fun drawHidingOthers(canvas: Canvas, views: Array<out View?>, count: Int) {
        val children = parent.childCount
        if (shown.size < children) shown = BooleanArray(children)
        for (i in 0 until children) {
            val child = parent.getChildAt(i)
            shown[i] = child.visibility == View.VISIBLE && !isAmong(child, views, count)
            if (shown[i]) child.visibility = View.INVISIBLE
        }
        try {
            parent.draw(canvas)
        } finally {
            for (i in 0 until children) if (shown[i]) parent.getChildAt(i).visibility = View.VISIBLE
        }
    }

    private fun isAmong(view: View, views: Array<out View?>, count: Int): Boolean {
        for (i in 0 until count) if (views[i] === view) return true
        return false
    }
}
