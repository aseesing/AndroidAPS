// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import app.aaps.core.interfaces.rx.weardata.EventData.SingleBg
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import com.google.common.truth.Truth.assertThat
import lecho.lib.hellocharts.model.Line
import lecho.lib.hellocharts.model.PointValue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class GlucoseLineTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val green = 0x00FF00
    private val yellow = 0xFFFF00
    private val red = 0xFF0000
    private val white = 0xFFFFFF

    /** A reading [minutesAgo] before [NOW], drawn one x per minute. */
    private fun point(minutesAgo: Long, y: Float, colour: Int = green) =
        GlucoseLine.Point(NOW - minutesAgo * minute, (-minutesAgo).toFloat(), y, colour)

    private fun Line.xs() = values.map { it.x }

    @Test
    fun aDesignWithoutAWidthKeepsTheDots() {
        assertThat(GlucoseLine.of(0, null)).isNull()
        assertThat(GlucoseLine.of(-1, red)).isNull()
        assertThat(GlucoseLine.of(2, red)).isEqualTo(GlucoseLine(2, red))
        assertThat(GlucoseLine.of(30, null)!!.width).isEqualTo(GlucoseLine.MAX_WIDTH)
    }

    @Test
    fun readingsOfOneColourAreOneLineWithADotAtTheNewest() {
        val lines = GlucoseLine(2, null).lines(listOf(point(10, 120f), point(0, 110f), point(5, 100f)))
        assertThat(lines).hasSize(2)
        val line = lines[0]
        assertThat(line.xs()).containsExactly(-10f, -5f, 0f).inOrder()
        assertThat(line.hasLines()).isTrue()
        assertThat(line.hasPoints()).isFalse()
        assertThat(line.strokeWidth).isEqualTo(2)
        assertThat(line.color).isEqualTo(green)
        val newest = lines[1]
        assertThat(newest.values.single().x).isEqualTo(0f)
        assertThat(newest.hasLines()).isFalse()
        assertThat(newest.hasPoints()).isTrue()
        assertThat(newest.pointRadius).isEqualTo(2)
    }

    @Test
    fun theColourChangesHalfWayBetweenTwoReadings() {
        val lines = GlucoseLine(3, null).lines(listOf(point(10, 150f), point(5, 170f), point(0, 190f, yellow)))
        assertThat(lines).hasSize(3)
        assertThat(lines[0].color).isEqualTo(green)
        assertThat(lines[0].xs()).containsExactly(-10f, -5f, -2.5f).inOrder()
        assertThat(lines[1].color).isEqualTo(yellow)
        assertThat(lines[1].xs()).containsExactly(-2.5f, 0f).inOrder()
        // Both pieces meet in the same place
        assertThat(lines[0].values.last().y).isEqualTo(180f)
        assertThat(lines[1].values.first().y).isEqualTo(180f)
        assertThat(lines[2].color).isEqualTo(yellow)
    }

    @Test
    fun withItsOwnColourTheLineIsOnePieceInThatColour() {
        val lines = GlucoseLine(2, white).lines(listOf(point(10, 60f, red), point(5, 100f), point(0, 200f, yellow)))
        assertThat(lines).hasSize(2)
        assertThat(lines[0].xs()).containsExactly(-10f, -5f, 0f).inOrder()
        assertThat(lines.map { it.color }).containsExactly(white, white)
    }

    @Test
    fun missingReadingsBreakTheLine() {
        // 20 minutes without readings, then one reading alone, then 20 minutes again
        val lines = GlucoseLine(2, null).lines(listOf(point(50, 100f), point(45, 100f), point(25, 110f), point(5, 120f), point(0, 120f)))
        assertThat(lines).hasSize(4)
        assertThat(lines[0].xs()).containsExactly(-50f, -45f).inOrder()
        // The reading alone is a dot, or it would not show
        assertThat(lines[1].xs()).containsExactly(-25f)
        assertThat(lines[1].hasPoints()).isTrue()
        assertThat(lines[1].pointRadius).isEqualTo(1)
        assertThat(lines[2].xs()).containsExactly(-5f, 0f).inOrder()
        assertThat(lines[2].hasPoints()).isFalse()
    }

    /** The phone sends older glucose with fewer points, one per a twelfth of its age; those steps are no gap. */
    @Test
    fun theFewerPointsOfOldGlucoseAreNoGap() {
        val newest = NOW
        assertThat(GlucoseLine.isGap(newest - 10 * minute, newest - 5 * minute, newest)).isFalse()
        assertThat(GlucoseLine.isGap(newest - 30 * minute, newest - 10 * minute, newest)).isTrue()
        // 12 hours ago the phone sends one point per hour
        assertThat(GlucoseLine.isGap(newest - 12 * hour, newest - 11 * hour, newest)).isFalse()
        assertThat(GlucoseLine.isGap(newest - 12 * hour, newest - 9 * hour, newest)).isTrue()
        // A day ago one per 2 hours
        assertThat(GlucoseLine.isGap(newest - 24 * hour, newest - 22 * hour, newest)).isFalse()
    }

    @Test
    fun noReadingsNoLines() {
        assertThat(GlucoseLine(2, null).lines(emptyList())).isEmpty()
    }

    @Test
    fun theLineReplacesTheDotsAndIsDrawnLast() {
        val before = Line(emptyList())
        val after = Line(emptyList())
        val times = listOf(NOW - 10 * minute, NOW - 5 * minute, NOW)
        val xOf = { time: Long -> ((time - NOW) / minute).toFloat() }
        val inRange = Line(listOf(PointValue(-10f, 100f), PointValue(-5f, 110f))).apply { color = green }
        val high = Line(listOf(PointValue(0f, 200f))).apply { color = yellow }
        val lines = mutableListOf(before, inRange, Line(emptyList()).apply { color = red }, high, after)
        GlucoseLine(2, null).replaceDots(lines, lines.subList(1, 4).toList(), times, xOf)
        // The other lines keep their order, the glucose comes last, over them
        assertThat(lines[0]).isSameInstanceAs(before)
        assertThat(lines[1]).isSameInstanceAs(after)
        val glucose = lines.drop(2)
        assertThat(glucose.none { it === inRange || it === high }).isTrue()
        assertThat(glucose.flatMap { it.xs() }).containsAtLeast(-10f, -5f, 0f).inOrder()
        assertThat(glucose.map { it.color }).containsExactly(green, yellow, yellow).inOrder()
    }

    @Test
    fun theGraphBuilderDrawsTheLineWhenTheDesignAsksForIt() {
        val now = System.currentTimeMillis()
        val readings = listOf(now - 15 * minute, now - 10 * minute, now - 5 * minute).map { SingleBg(dataset = 0, timeStamp = it, sgv = 120.0, high = 180.0, low = 70.0) }
        val sp: SP = mock()
        val dateUtil: DateUtil = mock()
        fun builder() = BgGraphBuilder(sp, dateUtil, readings, emptyList(), emptyList(), ArrayList(), ArrayList(), 3, yellow, red, green, white, 0, 0, 0, 0, 1)
        // Dots: the in-range line has points and no lines
        val dots = builder().lineData().lines[2]
        assertThat(dots.values).hasSize(3)
        assertThat(dots.hasLines()).isFalse()
        // The line: three readings joined, then the dot of the newest
        val lines = builder().apply { glucoseLine = GlucoseLine(2, null) }.lineData().lines
        val glucose = lines.filter { it.values.isNotEmpty() && it.color == green }
        assertThat(glucose).hasSize(2)
        assertThat(glucose[0].values).hasSize(3)
        assertThat(glucose[0].hasLines()).isTrue()
        assertThat(glucose[0].hasPoints()).isFalse()
        assertThat(glucose[1].values).hasSize(1)
        // Drawn last, over the basal and treatment lines that the builder adds after the glucose
        assertThat(lines.takeLast(2)).containsExactlyElementsIn(glucose).inOrder()
    }

    private companion object {

        const val NOW = 1_800_000_000_000L
    }
}
