// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.content.res.Resources
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R
import lecho.lib.hellocharts.model.Axis
import lecho.lib.hellocharts.model.AxisValue
import lecho.lib.hellocharts.model.Line
import lecho.lib.hellocharts.model.LineChartData
import lecho.lib.hellocharts.model.PointValue

/**
 * The glucose graph of the watch faces with a log time scale ([LogTimeScale]): a whole day, the last
 * minutes in detail, like the graphs of TMOG. A setting of the watch, on by default, also offered by
 * the phone (it is a row of the Custom watch face menu, see `CwfSyncedSettings`). The phone sends the
 * older glucose with fewer points when it is on.
 *
 * `BgGraphBuilder` places every point with the scale; this class adds the time axis, with lines and
 * labels for how long ago ([LogTimeScale.LABEL_AGES_MS]). The labels are drawn inside the graph, so a
 * small graph keeps its height: outside, they and their margins take the whole graph of a small design.
 */
class LogTimeGraph(private val resources: Resources) {

    /**
     * Puts the time into [data], in [colour]: a line at now and at each label age, and the labels of how
     * long ago. Takes away the value axis and the lines without values. Returns [data].
     *
     * The chart keeps a margin round the plot of the biggest point of all lines, plus 4 dp, also for a
     * line with no values (the bolus and carbs markers are twice the size of a glucose point). On a
     * watch that is a third of a small graph; without the empty lines it is less. The time lines are
     * lines of the plot, not of the axis: the chart draws axis lines over the whole view, margin and all.
     */
    fun label(data: LineChartData, colour: Int): LineChartData {
        val lines = data.lines.filter { it.values.isNotEmpty() }
        val ys = lines.flatMap { line -> line.values.map { it.y } }
        data.lines = if (ys.isEmpty()) lines else timeLines(ys.min(), ys.max(), colour) + lines
        data.axisXBottom = timeAxis(colour)
        data.axisYLeft = null
        return data
    }

    /** Lines from [bottom] to [top] at now and at each label age, below everything else. */
    internal fun timeLines(bottom: Float, top: Float, colour: Int): List<Line> =
        (listOf(0f) + LogTimeScale.LABEL_AGES_MS.map { LogTimeScale.xOfOffset(-it) }).map { x ->
            Line(listOf(PointValue(x, bottom), PointValue(x, top))).apply {
                color = lineColour(colour)
                strokeWidth = 1
                setHasPoints(false)
            }
        }

    /**
     * The labels of how long ago, drawn inside the graph, at the label ages, in [labelColour] of the grid
     * colour [colour].
     */
    internal fun timeAxis(colour: Int): Axis {
        val values = LogTimeScale.LABEL_AGES_MS.map { age -> AxisValue(LogTimeScale.xOfOffset(-age)).setLabel(ageLabel(age)) }
        return Axis(values).apply {
            setHasLines(false)
            setInside(true)
            setHasSeparationLine(false)
            textSize = TEXT_SIZE_SP
            // Lines and labels closer than this many characters are left out by the chart
            maxLabelChars = MAX_LABEL_CHARS
            lineColor = colour
            textColor = labelColour(colour)
        }
    }

    /** "30m", "1h" and so on. */
    internal fun ageLabel(ageMs: Long): String =
        if (ageMs < HOUR_MS) resources.getString(R.string.log_graph_minutes, (ageMs / MINUTE_MS).toInt())
        else resources.getString(R.string.log_graph_hours, (ageMs / HOUR_MS).toInt())

    companion object {

        /** On until the wearer turns it off. */
        const val DEFAULT = true

        /** The stored name of the setting (the text of `R.string.key_graph_log_time`), for the phone. */
        const val SETTING_NAME = "graph_log_time"

        private const val TEXT_SIZE_SP = 7

        /** How far the labels are taken from the grid colour toward white. */
        const val LABEL_LIGHTER = 0.4f

        /**
         * The grey of the time lines and labels on a design without a grid: many designs make the grid colour
         * see-through to hide the grid. A middle grey reads on light and on dark graphs alike.
         */
        val NO_GRID_GREY = Color.rgb(0x8A, 0x90, 0x99)

        /** The alpha of the time lines in [NO_GRID_GREY]: faint, like a grid. */
        const val NO_GRID_LINE_ALPHA = 110

        /** Below this alpha the grid colour counts as no grid at all. */
        private const val MIN_GRID_ALPHA = 64

        private fun hasGrid(colour: Int) = Color.alpha(colour) >= MIN_GRID_ALPHA

        /** The colour of the time lines for the grid colour [colour]: that colour, or a faint grey without a grid. */
        fun lineColour(colour: Int): Int = if (hasGrid(colour)) colour else ColorUtils.setAlphaComponent(NO_GRID_GREY, NO_GRID_LINE_ALPHA)

        /**
         * The colour of the labels for the grid colour [colour]: lighter, with its own alpha. The grid colour of a
         * design is often nearly the colour of the graph's background, fine for a line, but text in it cannot be
         * read on a watch. Without a grid: [NO_GRID_GREY].
         */
        fun labelColour(colour: Int): Int =
            if (hasGrid(colour)) ColorUtils.blendARGB(colour, Color.WHITE, LABEL_LIGHTER).let { ColorUtils.setAlphaComponent(it, Color.alpha(colour)) }
            else NO_GRID_GREY
        private const val MAX_LABEL_CHARS = 2
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS

        /** True when the watch graphs use the log time scale. */
        fun isOn(sp: SP): Boolean = sp.getBoolean(R.string.key_graph_log_time, DEFAULT)

        /** The scale for a graph made at [now], or null when the setting is off. */
        fun scaleOrNull(sp: SP, now: Long): LogTimeScale? = if (isOn(sp)) LogTimeScale(now) else null

        /** The row of the setting in the Custom watch face menu. */
        internal fun row(): WatchFaceSettingRow.Toggle = WatchFaceSettingRow.Toggle(R.string.key_graph_log_time, R.string.pref_graph_log_time, DEFAULT)
    }
}
