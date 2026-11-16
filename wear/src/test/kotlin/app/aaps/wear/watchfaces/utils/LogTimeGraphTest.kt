// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.graphics.Color
import app.aaps.wear.R
import com.google.common.truth.Truth.assertThat
import lecho.lib.hellocharts.model.Axis
import lecho.lib.hellocharts.model.Line
import lecho.lib.hellocharts.model.LineChartData
import lecho.lib.hellocharts.model.PointValue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LogTimeGraphTest {

    private val resources = RuntimeEnvironment.getApplication().resources
    private val minute = 60_000L
    private val hour = 60 * minute

    /** The grid colour of D2: nearly the colour of the glass. */
    private val GRID = Color.parseColor("#26302B")

    @Test
    fun theLabelsSayHowLongAgo() {
        val graph = LogTimeGraph(resources)
        assertThat(graph.ageLabel(30 * minute)).isEqualTo("30m")
        assertThat(graph.ageLabel(hour)).isEqualTo("1h")
        assertThat(graph.ageLabel(12 * hour)).isEqualTo("12h")
    }

    @Test
    fun theTimeHasLinesInThePlotAndLabelsInsideTheGraph() {
        val glucose = Line(listOf(PointValue(-10f, 90f), PointValue(-1f, 160f)))
        val empty = Line(emptyList())
        val data = LineChartData(listOf(glucose, empty)).apply { axisYLeft = Axis() }
        LogTimeGraph(resources).label(data, GRID)
        // A line at now and at each age, from the lowest to the highest value, then the glucose; the empty line is gone
        assertThat(data.lines).hasSize(6)
        assertThat(data.lines.last()).isSameInstanceAs(glucose)
        val timeLines = data.lines.dropLast(1)
        assertThat(timeLines.first().values.first().x).isEqualTo(0f)
        for ((line, age) in timeLines.drop(1).zip(LogTimeScale.LABEL_AGES_MS)) assertThat(line.values.first().x).isWithin(1e-3f).of(LogTimeScale.xOfOffset(-age))
        for (line in timeLines) {
            assertThat(line.values.map { it.y }).containsExactly(90f, 160f)
            assertThat(line.color).isEqualTo(GRID)
            assertThat(line.hasPoints()).isFalse()
        }
        // The labels: inside, no lines of their own
        val axis = data.axisXBottom
        assertThat(axis.isInside).isTrue()
        assertThat(axis.hasLines()).isFalse()
        assertThat(axis.textColor).isEqualTo(LogTimeGraph.labelColour(GRID))
        assertThat(data.axisYLeft).isNull()
        assertThat(axis.values.map { String(it.labelAsChars) }).containsExactly("1h", "3h", "6h", "12h").inOrder()
        assertThat(axis.values[1].value).isWithin(1e-3f).of(LogTimeScale.xOfOffset(-3 * hour))
    }

    @Test
    fun theLabelsAreLighterThanTheGridAndKeepItsAlpha() {
        val label = LogTimeGraph.labelColour(GRID)
        assertThat(Color.red(label)).isGreaterThan(Color.red(GRID) + 60)
        assertThat(Color.green(label)).isGreaterThan(Color.green(GRID) + 60)
        assertThat(Color.alpha(label)).isEqualTo(255)
        assertThat(Color.alpha(LogTimeGraph.labelColour(Color.argb(128, 38, 48, 43)))).isEqualTo(128)
    }

    @Test
    fun theSettingIsOnByDefaultAndKeepsItsNameForThePhone() {
        assertThat(LogTimeGraph.DEFAULT).isTrue()
        assertThat(resources.getString(R.string.key_graph_log_time)).isEqualTo(LogTimeGraph.SETTING_NAME)
        assertThat(LogTimeGraph.row().defaultValue).isTrue()
    }
}
