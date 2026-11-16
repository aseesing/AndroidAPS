// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Canvas
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class WakeMotionTest {

    private val sweep = SweepSpec(listOf("hand"), emptyAngle = -60f, fullAngle = -20f, delayMs = 100, upMs = 600, settleMs = 900)

    @Test
    fun aSweptHandWaitsGoesToFullScaleAndEndsOnItsValue() {
        assertThat(sweep.angleAt(0, -45f)).isEqualTo(-60f)
        assertThat(sweep.angleAt(100, -45f)).isEqualTo(-60f)
        assertThat(sweep.angleAt(400, -45f)).isWithin(0.01f).of(-40f)
        assertThat(sweep.angleAt(700, -45f)).isWithin(0.01f).of(-20f)
        assertThat(sweep.angleAt(1600, -45f)).isWithin(0.01f).of(-45f)
        assertThat(sweep.angleAt(5000, -45f)).isEqualTo(-45f)
    }

    @Test
    fun aSweptHandBouncesPastItsValueOnce() {
        // On the way back from -20 to -45 it goes past -45, and never further than a quarter of the way back
        val angles = (700L..1600L step 10).map { sweep.angleAt(it, -45f) }
        assertThat(angles.min()).isLessThan(-45f)
        assertThat(angles.min()).isGreaterThan(-45f - 0.25f * 25f)
        // The last part is close to the value, so the stop does not jump
        assertThat(abs(sweep.angleAt(1590, -45f) + 45f)).isLessThan(0.5f)
    }

    @Test
    fun theCurvesStartAtZeroAndEndAtOne() {
        assertThat(SweepSpec.easeInOut(0f)).isWithin(1e-6f).of(0f)
        assertThat(SweepSpec.easeInOut(1f)).isWithin(1e-6f).of(1f)
        assertThat(SweepSpec.settle(0f)).isWithin(1e-6f).of(0f)
        assertThat(SweepSpec.settle(1f)).isWithin(1e-6f).of(1f)
    }

    @Test
    fun aWobbleShakesAndDiesOut() {
        val wobble = WobbleSpec(listOf("hand"), delayMs = 200, amplitude = 16f, hz = 2.5f, decayMs = 350)
        assertThat(wobble.offsetAt(0)).isEqualTo(0f)
        assertThat(wobble.offsetAt(200)).isEqualTo(0f)
        // The first swing, a quarter turn of the wave after the start, is the biggest
        val first = wobble.offsetAt(300)
        val later = wobble.offsetAt(700)
        assertThat(first).isGreaterThan(5f)
        assertThat(abs(later)).isLessThan(abs(first))
        assertThat(wobble.offsetAt(200 + 350 * WobbleSpec.DECAYS + 1L)).isEqualTo(0f)
    }

    @Test
    fun traceAndGlintRunOnce() {
        val trace = TraceSpec(rect = DesignRect(0f, 0f, 100f, 50f), delayMs = 100, durationMs = 400)
        assertThat(trace.progressAt(0)).isEqualTo(0f)
        assertThat(trace.progressAt(300)).isWithin(1e-6f).of(0.5f)
        assertThat(trace.progressAt(900)).isEqualTo(1f)
        val glint = GlintSpec(boxes = listOf(DesignRect(0f, 0f, 10f, 10f)), delayMs = 100, durationMs = 400)
        assertThat(glint.progressAt(50)).isNull()
        assertThat(glint.progressAt(300)).isWithin(1e-6f).of(0.5f)
        assertThat(glint.progressAt(600)).isNull()
    }

    @Test
    fun valuesAreKeptSafe() {
        val wild = SweepSpec(listOf("a", "a", ""), 0f, 10f, delayMs = -5, upMs = 0, settleMs = 1_000_000).normalized()
        assertThat(wild.views).containsExactly("a")
        assertThat(wild.delayMs).isEqualTo(0)
        assertThat(wild.upMs).isEqualTo(SweepSpec.MIN_PART_MS)
        assertThat(wild.settleMs).isEqualTo(SweepSpec.MAX_PART_MS)
        assertThat(WobbleSpec(listOf("a"), amplitude = 500f, hz = 100f).normalized().let { it.amplitude to it.hz }).isEqualTo(WobbleSpec.MAX_AMPLITUDE to WobbleSpec.MAX_HZ)
        // Empty parts are dropped, so a design of only those has no animation
        val spec = WakeAnimationSpec(sweep = listOf(SweepSpec(emptyList(), 0f, 1f)), wobble = listOf(WobbleSpec(emptyList())), glint = GlintSpec()).normalized()
        assertThat(spec.hasContent).isFalse()
    }

    @Test
    fun aSlideGoesToTheEndOfItsScaleAndBackToItsValue() {
        val slide = SlideSpec(listOf("month"), fullDx = 220f, delayMs = 100, upMs = 600, settleMs = 900)
        // Waits at the design place, runs to the end, settles on the value with a bounce past it
        assertThat(slide.dxAt(0, 132f)).isEqualTo(0f)
        assertThat(slide.dxAt(100, 132f)).isEqualTo(0f)
        assertThat(slide.dxAt(700, 132f)).isEqualTo(220f)
        assertThat((700L..1600L step 10).minOf { slide.dxAt(it, 132f) }).isLessThan(132f)
        assertThat(slide.dxAt(1600, 132f)).isEqualTo(132f)
        assertThat(slide.dxAt(5000, 132f)).isEqualTo(132f)
        // Limits as for a sweep; empty views are dropped
        assertThat(SlideSpec(listOf("a", "", "a"), 10f, delayMs = -5, upMs = 1).normalized().let { Triple(it.views, it.delayMs, it.upMs) })
            .isEqualTo(Triple(listOf("a"), 0L, SweepSpec.MIN_PART_MS))
        assertThat(WakeAnimationSpec(slide = listOf(SlideSpec(emptyList(), 10f))).normalized().hasContent).isFalse()
        assertThat(WakeAnimationSpec(slide = listOf(slide)).liveViews).containsExactly("month")
    }

    @Test
    fun aSlidViewIsMovedFromWhereItsValuePutsIt() {
        val parts = object : WakeFrameParts {
            override fun background(canvas: Canvas) {}
            override fun data(canvas: Canvas, leaveOut: Set<String>) {}
            override fun clockText(canvas: Canvas) {}
            override fun top(canvas: Canvas, leaveOut: Set<String>) {}
            override fun secondHand(canvas: Canvas) {}
            override fun offsetX(key: String): Float? = if (key == "month") 132f else null
        }
        val spec = WakeAnimationSpec(slide = listOf(SlideSpec(listOf("month", "gone"), fullDx = 220f, upMs = 600)))
        val shifts = WakeAnimator(heartRate = { null }).shiftsAt(600, spec, parts)
        // At the end of the scale: 220 from the design place, so 88 right of the value
        assertThat(shifts).containsExactly("month", 88f)
    }

    @Test
    fun theMovingHandsAddUp() {
        val parts = object : WakeFrameParts {
            override fun background(canvas: Canvas) {}
            override fun data(canvas: Canvas, leaveOut: Set<String>) {}
            override fun clockText(canvas: Canvas) {}
            override fun top(canvas: Canvas, leaveOut: Set<String>) {}
            override fun secondHand(canvas: Canvas) {}
            override fun rotation(key: String): Float? = if (key == "hand") -45f else null
        }
        val spec = WakeAnimationSpec(sweep = listOf(sweep.copy(views = listOf("hand", "gone"))), wobble = listOf(WobbleSpec(listOf("hand"), amplitude = 10f)))
        val turns = WakeAnimator(heartRate = { null }).turnsAt(400, spec, parts)
        // Not there: not turned. There: the sweep (-40 for a value of -45, so +5) and the wobble
        assertThat(turns.keys).containsExactly("hand")
        assertThat(turns.getValue("hand")).isWithin(0.01f).of(5f + WobbleSpec(listOf("hand"), amplitude = 10f).offsetAt(400))
        assertThat(spec.liveViews).containsExactly("hand", "gone")
    }
}
