// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import app.aaps.core.interfaces.rx.weardata.EventData.SingleBg
import app.aaps.core.interfaces.rx.weardata.EventData.TreatmentData.Treatment
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.wear.R
import com.google.common.truth.Truth.assertThat
import lecho.lib.hellocharts.model.LineChartData
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.math.abs

class LogTimeScaleTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_699_999_980_000L   // a whole minute

    @Test
    fun nowIsZeroThePastIsLeftAndTheFutureRight() {
        val scale = LogTimeScale(now)
        assertThat(scale.x(now)).isEqualTo(0f)
        assertThat(scale.x(now - hour)).isLessThan(0f)
        assertThat(scale.x(now + 10 * minute)).isGreaterThan(0f)
        // The same distance on both sides
        assertThat(scale.x(now + 10 * minute)).isWithin(1e-4f).of(-scale.x(now - 10 * minute))
    }

    @Test
    fun theLastMinutesAreWideAndTheOldHoursNarrow() {
        val day = -LogTimeScale.xOfOffset(-LogTimeScale.PAST_MS)
        fun part(ageMs: Long) = -LogTimeScale.xOfOffset(-ageMs) / day
        assertThat(part(30 * minute)).isWithin(0.01f).of(0.18f)
        assertThat(part(hour)).isWithin(0.01f).of(0.28f)
        assertThat(part(3 * hour)).isWithin(0.01f).of(0.50f)
        assertThat(part(6 * hour)).isWithin(0.01f).of(0.66f)
        assertThat(part(12 * hour)).isWithin(0.01f).of(0.82f)
        // Near now about a minute per unit, far back much less
        assertThat(LogTimeScale.xOfOffset(-minute)).isWithin(0.05f).of(-1f)
        val lastHourOfTheDay = LogTimeScale.xOfOffset(-23 * hour) - LogTimeScale.xOfOffset(-24 * hour)
        assertThat(lastHourOfTheDay).isLessThan(3f)
    }

    @Test
    fun nowIsTheWholeMinuteSoTheGraphStaysTheSameWithinAMinute() {
        assertThat(LogTimeScale(now + 59_999).now).isEqualTo(now)
        assertThat(LogTimeScale(now + 59_999).x(now - hour)).isEqualTo(LogTimeScale(now).x(now - hour))
        assertThat(LogTimeScale(now + 60_000).now).isEqualTo(now + 60_000)
    }

    @Test
    fun theGraphShowsADayBackAndAtMostAnHourAhead() {
        val scale = LogTimeScale(now)
        assertThat(scale.start).isEqualTo(now - 24 * hour)
        assertThat(scale.end(null)).isEqualTo(now + LogTimeScale.MIN_FUTURE_MS)
        assertThat(scale.end(now + 2 * minute)).isEqualTo(now + LogTimeScale.MIN_FUTURE_MS)
        assertThat(scale.end(now + 40 * minute)).isEqualTo(now + 40 * minute)
        assertThat(scale.end(now + 3 * hour)).isEqualTo(now + hour)
        assertThat(scale.shows(now - 23 * hour)).isTrue()
        assertThat(scale.shows(now - 25 * hour)).isFalse()
        assertThat(scale.shows(now + 61 * minute)).isFalse()
    }

    // ------------------------------------------------------------------------------------------
    // The graph builder with the scale on
    // ------------------------------------------------------------------------------------------

    private fun sp(logTime: Boolean, predictions: Boolean = true): SP {
        val sp: SP = mock()
        whenever(sp.getBoolean(R.string.key_graph_log_time, LogTimeGraph.DEFAULT)).thenReturn(logTime)
        whenever(sp.getBoolean(R.string.key_prediction_lines, true)).thenReturn(predictions)
        whenever(sp.getBoolean(R.string.key_show_graph_bolus, true)).thenReturn(true)
        return sp
    }

    private fun bg(timeStamp: Long, sgv: Double) = SingleBg(dataset = 0, timeStamp = timeStamp, sgv = sgv, high = 180.0, low = 70.0)

    private fun graph(sp: SP, bgs: List<SingleBg>, predictions: List<SingleBg> = emptyList(), boluses: ArrayList<Treatment> = ArrayList()): LineChartData =
        BgGraphBuilder(
            sp = sp, dateUtil = mock<DateUtil>(), bgDataList = bgs, predictionsList = predictions, tempWatchDataList = emptyList(),
            basalWatchDataList = ArrayList(), bolusWatchDataList = boluses, pointSize = 2, highColor = 1, lowColor = 2, midColor = 3, gridColour = 4,
            basalBackgroundColor = 5, basalCenterColor = 6, bolusInvalidColor = 7, carbsColor = 8, timeSpan = 3
        ).lineData()

    @Test
    fun withTheScaleOnTheGraphGoesBackADayOnTheLogScale() {
        val t = System.currentTimeMillis()
        val bgs = listOf(bg(t - 20 * hour, 100.0), bg(t - 5 * hour, 110.0), bg(t - 10 * minute, 120.0))
        val inRange = graph(sp(logTime = true), bgs).lines[2].values
        // All three, also the one of 20 hours ago, which a 3 hour graph leaves out
        assertThat(inRange.map { it.y }).containsExactly(100f, 110f, 120f)
        // On the scale of the whole minute of the graph
        val scale = LogTimeScale(t)
        assertThat(inRange[0].x).isWithin(0.5f).of(scale.x(t - 20 * hour))
        assertThat(inRange[2].x).isWithin(0.1f).of(scale.x(t - 10 * minute))
        // Off: the plain scale of 3 hours
        assertThat(graph(sp(logTime = false), bgs).lines[2].values.map { it.y }).containsExactly(120f)
    }

    @Test
    fun theHighLineSpansTheDayAndAtMostAnHourOfPredictions() {
        val t = System.currentTimeMillis()
        val predictions = (1..36).map { bg(t + it * 5 * minute, 150.0) }   // three hours
        val data = graph(sp(logTime = true), listOf(bg(t - 5 * minute, 120.0)), predictions)
        val highLine = data.lines[0].values
        assertThat(highLine.first().x).isWithin(0.5f).of(LogTimeScale.xOfOffset(-LogTimeScale.PAST_MS))
        assertThat(highLine.last().x).isWithin(0.5f).of(LogTimeScale.xOfOffset(LogTimeScale.FUTURE_MS))
        // Predictions past the hour are not drawn
        val maxX = data.lines.flatMap { it.values }.maxOf { it.x }
        assertThat(maxX).isAtMost(LogTimeScale.xOfOffset(LogTimeScale.FUTURE_MS) + 0.5f)
    }

    @Test
    fun aBolusOfLongAgoIsOnTheGraph() {
        val t = System.currentTimeMillis()
        val boluses = arrayListOf(Treatment(t - 8 * hour, 2.0, 0.0, false, true))
        val data = graph(sp(logTime = true), listOf(bg(t - 5 * minute, 120.0)), boluses = boluses)
        val xs = data.lines.flatMap { it.values }.map { it.x }
        assertThat(xs.any { abs(it - LogTimeScale.xOfOffset(-8 * hour)) < 0.5f }).isTrue()
    }
}
