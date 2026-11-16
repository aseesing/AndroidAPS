// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import lecho.lib.hellocharts.model.Line
import lecho.lib.hellocharts.model.PointValue
import kotlin.math.max

/**
 * The glucose of the graph drawn as a line, [width] dp wide, in place of the loose dots that
 * `BgGraphBuilder` draws. A design asks for it with [WIDTH_KEY] in its json, and can give the line one
 * colour with [COLOUR_KEY]. Without a colour ([colour] null) each part of the line keeps the colour of
 * its glucose dots: in range, high or low. Where the colour changes, it changes half way between the
 * two readings.
 *
 * The line breaks where readings are missing ([isGap]), so a sensor gap is not drawn as a straight line.
 * The newest reading gets a dot with a radius of [width], so the end of the line shows. A reading alone
 * between two gaps is a dot too, or it would not show at all.
 */
data class GlucoseLine(val width: Int, val colour: Int?) {

    /** One glucose reading: its [time], where it is drawn ([x], [y]) and the colour of its dot. */
    data class Point(val time: Long, val x: Float, val y: Float, val colour: Int)

    /**
     * Takes the glucose [dots] (lines of [lines], one per colour, as `BgGraphBuilder` makes them) out of
     * [lines] and puts the glucose line at the end of [lines]: it is drawn last, over the basal and the
     * treatments, so they never hide it. [times] are the times of the readings and [xOf] gives where a
     * time is drawn: the dots do not carry their time.
     */
    fun replaceDots(lines: MutableList<Line>, dots: List<Line>, times: List<Long>, xOf: (Long) -> Float) {
        if (dots.none { dot -> lines.any { it === dot } }) return
        val timeAt = times.associateBy(xOf)
        val points = dots.flatMap { dot -> dot.values.mapNotNull { value -> timeAt[value.x]?.let { Point(it, value.x, value.y, dot.color) } } }
        lines.removeAll { line -> dots.any { it === line } }
        lines.addAll(lines(points))
    }

    /** The lines that draw [points], in any order. */
    fun lines(points: List<Point>): List<Line> {
        val sorted = points.sortedWith(compareBy({ it.x }, { it.time }))
        val newest = sorted.lastOrNull() ?: return emptyList()
        val result = mutableListOf<Line>()
        var part = mutableListOf(sorted.first().value())
        var partColour = colourOf(sorted.first())
        for ((older, newer) in sorted.zipWithNext()) {
            val newerColour = colourOf(newer)
            when {
                isGap(older.time, newer.time, newest.time) -> {
                    result.add(part(part, partColour))
                    part = mutableListOf(newer.value())
                }

                newerColour != partColour                  -> {
                    val middle = PointValue((older.x + newer.x) / 2, (older.y + newer.y) / 2)
                    part.add(middle)
                    result.add(part(part, partColour))
                    part = mutableListOf(PointValue(middle.x, middle.y), newer.value())
                }

                else                                       -> part.add(newer.value())
            }
            partColour = newerColour
        }
        result.add(part(part, partColour))
        result.add(Line(listOf(newest.value())).apply {
            color = colourOf(newest)
            setHasLines(false)
            setHasPoints(true)
            pointRadius = width
        })
        return result
    }

    private fun colourOf(point: Point): Int = colour ?: point.colour

    private fun Point.value() = PointValue(x, y)

    /** One piece of the line. A piece of one reading has no length, so it is a dot of the line's width. */
    private fun part(values: List<PointValue>, partColour: Int): Line =
        Line(values).apply {
            color = partColour
            strokeWidth = width
            setHasLines(true)
            setHasPoints(values.size == 1)
            pointRadius = max(1, width / 2)
        }

    companion object {

        /** The design json key of the width of the line, in dp. Without it, or 0, the glucose stays dots. */
        const val WIDTH_KEY = "graphLineWidth"

        /** The design json key of the colour of the whole line. Without it the line has the colours of the dots. */
        const val COLOUR_KEY = "graphLineColor"

        /** No wider than this many dp: the graph of a watch is small. */
        const val MAX_WIDTH = 8

        /** Readings further apart than this always break the line: 3 readings missed. */
        const val MIN_GAP_MS = 15 * 60_000L

        /**
         * With the log time graph the phone sends older glucose with fewer points, one per a twelfth of its
         * age (`WearGraphHistory`). Twice that step is still no gap.
         */
        const val GAP_PARTS = 6

        /** The glucose line a design asks for with [width] (in dp, from [WIDTH_KEY]) and [colour], or null for dots. */
        fun of(width: Int, colour: Int?): GlucoseLine? =
            if (width > 0) GlucoseLine(width.coerceAtMost(MAX_WIDTH), colour) else null

        /** True when readings at [older] and [newer] are too far apart to be joined, with [newest] the newest reading. */
        fun isGap(older: Long, newer: Long, newest: Long): Boolean =
            newer - older > max(MIN_GAP_MS, (newest - older) / GAP_PARTS)
    }
}
