// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The presets of the views that move (sweep, slide, wobble), draw themselves in (trace), blink, and of
 * the light (glint): the layer formulas give the same numbers as the old code, which is kept here as
 * the reference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WakeMotionTest {

    // ---- the old math, as the reference ----

    private fun oldEaseInOut(u: Float): Float = (0.5 - 0.5 * cos(PI * u.coerceIn(0f, 1f))).toFloat()

    private fun oldSettle(u: Float): Float {
        val x = u.coerceIn(0f, 1f).toDouble()
        val spring = 1 - exp(-5 * x) * cos(3 * PI * x)
        val rest = (1 - exp(-5.0) * cos(3 * PI)) - 1
        return (spring - rest * x).toFloat()
    }

    private fun SweepSpec.oldAngleAt(elapsedMs: Long, valueAngle: Float): Float {
        val t = elapsedMs - delayMs
        return when {
            t <= 0              -> emptyAngle
            t < upMs            -> emptyAngle + (fullAngle - emptyAngle) * oldEaseInOut(t.toFloat() / upMs)
            t < upMs + settleMs -> fullAngle + (valueAngle - fullAngle) * oldSettle((t - upMs).toFloat() / settleMs)
            else                -> valueAngle
        }
    }

    private fun WobbleSpec.oldOffsetAt(elapsedMs: Long): Float {
        val t = elapsedMs - delayMs
        if (t <= 0 || t > decayMs * WobbleSpec.DECAYS) return 0f
        return (amplitude * exp(-t.toDouble() / decayMs) * sin(2 * PI * hz * (t / 1000.0))).toFloat()
    }

    // ---- helpers ----

    private fun animator(spec: WakeAnimationSpec) = LayerAnimator(spec.allLayers()).also { assertThat(it.errors).isEmpty() }

    /** What the view layers of [spec] do [ms] after the start, for views turned [rotation] and moved [offsetX]. */
    private fun effects(spec: WakeAnimationSpec, ms: Long, rotation: (String) -> Float? = { 0f }, offsetX: (String) -> Float? = { 0f }): ViewEffects =
        animator(spec).run {
            setTime(ms, 6000, 1f, 0)
            viewEffects(rotation, offsetX)
        }

    private val sweep = SweepSpec(listOf("hand"), emptyAngle = -60f, fullAngle = -20f, delayMs = 100, upMs = 600, settleMs = 900)

    /** The angle the swept hand of [sweep] is drawn at, for a value of -45. */
    private fun sweptAngle(ms: Long, s: SweepSpec = sweep): Float = -45f + (effects(WakeAnimationSpec(sweep = listOf(s)), ms, rotation = { -45f }).turns["hand"] ?: 0f)

    @Test
    fun aSweptHandWaitsGoesToFullScaleAndEndsOnItsValue() {
        assertThat(sweptAngle(0)).isEqualTo(-60f)
        assertThat(sweptAngle(100)).isEqualTo(-60f)
        assertThat(sweptAngle(400)).isWithin(0.01f).of(-40f)
        assertThat(sweptAngle(700)).isWithin(0.01f).of(-20f)
        assertThat(sweptAngle(1600)).isWithin(0.01f).of(-45f)
        assertThat(sweptAngle(5000)).isEqualTo(-45f)
        // The same as the old code at every step
        for (ms in 0L..2000L step 10) assertWithMessage("at $ms").that(sweptAngle(ms)).isWithin(1e-3f).of(sweep.oldAngleAt(ms, -45f))
    }

    @Test
    fun aSweptHandBouncesPastItsValueOnce() {
        // On the way back from -20 to -45 it goes past -45, and never further than a quarter of the way back
        val angles = (700L..1600L step 10).map { sweptAngle(it) }
        assertThat(angles.min()).isLessThan(-45f)
        assertThat(angles.min()).isGreaterThan(-45f - 0.25f * 25f)
        // The last part is close to the value, so the stop does not jump
        assertThat(abs(sweptAngle(1590) + 45f)).isLessThan(0.5f)
    }

    @Test
    fun theCurvesStartAtZeroAndEndAtOne() {
        assertThat(Formula.easeInOut(0.0)).isWithin(1e-6).of(0.0)
        assertThat(Formula.easeInOut(1.0)).isWithin(1e-6).of(1.0)
        assertThat(Formula.settle(0.0)).isWithin(1e-6).of(0.0)
        assertThat(Formula.settle(1.0)).isWithin(1e-6).of(1.0)
        for (k in 0..20) assertThat(Formula.settle(k / 20.0)).isWithin(1e-6).of(oldSettle(k / 20f).toDouble())
    }

    @Test
    fun aWobbleShakesAndDiesOut() {
        val wobble = WobbleSpec(listOf("hand"), delayMs = 200, amplitude = 16f, hz = 2.5f, decayMs = 350)
        fun offsetAt(ms: Long) = effects(WakeAnimationSpec(wobble = listOf(wobble)), ms).turns["hand"] ?: 0f
        assertThat(offsetAt(0)).isEqualTo(0f)
        assertThat(offsetAt(200)).isEqualTo(0f)
        // The first swing, a quarter turn of the wave after the start, is the biggest
        val first = offsetAt(300)
        val later = offsetAt(700)
        assertThat(first).isGreaterThan(5f)
        assertThat(abs(later)).isLessThan(abs(first))
        assertThat(offsetAt(200 + 350 * WobbleSpec.DECAYS + 1L)).isEqualTo(0f)
        for (ms in 0L..2200L step 10) assertWithMessage("at $ms").that(offsetAt(ms)).isWithin(1e-4f).of(wobble.oldOffsetAt(ms))
    }

    @Test
    fun aTracedViewIsCutAtItsEdgeAndHiddenBeforeItStarts() {
        val trace = TraceSpec(view = "chart", rect = DesignRect(20f, 0f, 100f, 50f), delayMs = 100, durationMs = 400)
        val spec = WakeAnimationSpec(trace = trace)
        fun at(ms: Long) = effects(spec, ms)
        assertThat(at(0).hidden).containsExactly("chart")
        assertThat(at(100).hidden).containsExactly("chart")
        assertThat(at(300).hidden).isEmpty()
        assertThat(at(300).clipRights.getValue("chart")).isWithin(1e-4f).of(20f + 50f)
        assertThat(at(900).clipRights.getValue("chart")).isWithin(1e-4f).of(120f)
        // The bright line is at the edge, and only while the view is being drawn in
        val layers = spec.allLayers()
        assertThat(layers.map { it.slot }).containsExactly(LayerSlot.VIEWS, LayerSlot.VIEWS).inOrder()
        val animator = animator(spec)
        fun beam(ms: Long, prop: LayerProp): Float {
            animator.setTime(ms, 6000, 1f, 0)
            return animator.propOf(1, 0, prop)!!
        }
        assertThat(beam(50, LayerProp.ALPHA)).isEqualTo(0f)
        assertThat(beam(300, LayerProp.X)).isWithin(1e-4f).of(70f)
        assertThat(beam(300, LayerProp.ALPHA)).isWithin(1e-4f).of(140 / 255f)
        // It fades over the last eighth of the way: at 15/16 it is half
        assertThat(beam(100 + 375, LayerProp.ALPHA)).isWithin(1e-4f).of(140 / 255f * 0.5f)
        assertThat(beam(500, LayerProp.ALPHA)).isEqualTo(0f)
        // No line when it has no width
        assertThat(WakeAnimationSpec(trace = trace.copy(width = 0f)).allLayers()).hasSize(1)
    }

    @Test
    fun theGlintCrossesTheFaceOnce() {
        val glint = GlintSpec(boxes = listOf(DesignRect(0f, 0f, 10f, 10f)), delayMs = 100, durationMs = 400, width = 60f, alpha = 90)
        val animator = animator(WakeAnimationSpec(glint = glint))
        fun at(ms: Long, prop: LayerProp): Float {
            animator.setTime(ms, 6000, 1f, 0)
            return animator.propOf(0, 0, prop)!!
        }
        val diagonal = 400f * sqrt(2f)
        // Not before it starts, nor after it has crossed
        assertThat(at(50, LayerProp.ALPHA)).isEqualTo(0f)
        assertThat(at(600, LayerProp.ALPHA)).isEqualTo(0f)
        assertThat(at(300, LayerProp.ALPHA)).isWithin(1e-6f).of(90 / 255f)
        // From just left of the face to just right of it, halfway in the middle
        assertThat(at(100, LayerProp.X)).isWithin(1e-3f).of(200f - diagonal / 2 - 30f)
        assertThat(at(300, LayerProp.X)).isWithin(1e-3f).of(200f)
        assertThat(at(500, LayerProp.X)).isWithin(1e-3f).of(200f + diagonal / 2 + 30f)
        assertThat(at(300, LayerProp.ROTATION)).isEqualTo(glint.angle)
        // No shapes or no light: no layer
        assertThat(WakeAnimationSpec(glint = glint.copy(boxes = emptyList())).allLayers()).isEmpty()
        assertThat(WakeAnimationSpec(glint = glint.copy(alpha = 0)).allLayers()).isEmpty()
    }

    @Test
    fun aLowValueBlinksItsViews() {
        val blink = BlinkSpec(views = listOf("a", "b"), valueKey = "battery", atOrBelow = 20.0, periodMs = 1000)
        fun hiddenAt(ms: Long, level: Double?, b: BlinkSpec = blink) = animator(WakeAnimationSpec(blink = listOf(b))).run {
            setWakeValues({ if (it == "battery") level else null }, 60)
            setTime(ms, 6000, 1f, 0)
            viewEffects({ 0f }, { 0f }).hidden
        }
        // Low: shown in the first half of each period, hidden in the second half
        assertThat(hiddenAt(0, 15.0)).isEmpty()
        assertThat(hiddenAt(499, 15.0)).isEmpty()
        assertThat(hiddenAt(500, 15.0)).containsExactly("a", "b")
        assertThat(hiddenAt(1100, 15.0)).isEmpty()
        assertThat(hiddenAt(1700, 20.0)).containsExactly("a", "b")
        // Above the limit, or with no value, never
        for (level in listOf(20.5, 100.0, null)) for (ms in listOf(0L, 600L, 1700L)) assertThat(hiddenAt(ms, level)).isEmpty()
        // An odd period: off from its half, rounded down, as before
        assertThat(hiddenAt(100, 15.0, blink.copy(periodMs = 201))).containsExactly("a", "b")
        assertThat(hiddenAt(99, 15.0, blink.copy(periodMs = 201))).isEmpty()
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
        val old = SweepSpec(listOf("month"), 0f, 220f, delayMs = 100, upMs = 600, settleMs = 900)
        fun dxAt(ms: Long) = 132f + (effects(WakeAnimationSpec(slide = listOf(slide)), ms, offsetX = { 132f }).shifts["month"] ?: 0f)
        // Waits at the design place, runs to the end, settles on the value with a bounce past it
        assertThat(dxAt(0)).isEqualTo(0f)
        assertThat(dxAt(100)).isEqualTo(0f)
        assertThat(dxAt(700)).isWithin(1e-3f).of(220f)
        assertThat((700L..1600L step 10).minOf { dxAt(it) }).isLessThan(132f)
        assertThat(dxAt(1600)).isEqualTo(132f)
        assertThat(dxAt(5000)).isEqualTo(132f)
        for (ms in 0L..2000L step 20) assertWithMessage("at $ms").that(dxAt(ms)).isWithin(1e-3f).of(old.oldAngleAt(ms, 132f))
        // Limits as for a sweep; empty views are dropped
        assertThat(SlideSpec(listOf("a", "", "a"), 10f, delayMs = -5, upMs = 1).normalized().let { Triple(it.views, it.delayMs, it.upMs) })
            .isEqualTo(Triple(listOf("a"), 0L, SweepSpec.MIN_PART_MS))
        assertThat(WakeAnimationSpec(slide = listOf(SlideSpec(emptyList(), 10f))).normalized().hasContent).isFalse()
        assertThat(LayerAnimator(WakeAnimationSpec(slide = listOf(slide)).allLayers()).viewKeys).containsExactly("month")
    }

    @Test
    fun aSlidViewIsMovedFromWhereItsValuePutsIt() {
        val spec = WakeAnimationSpec(slide = listOf(SlideSpec(listOf("month", "gone"), fullDx = 220f, upMs = 600)))
        val shifts = effects(spec, 600, rotation = { if (it == "month") 0f else null }, offsetX = { if (it == "month") 132f else null }).shifts
        // At the end of the scale: 220 from the design place, so 88 right of the value. A view the face does not have is left alone.
        assertThat(shifts.keys).containsExactly("month")
        assertThat(shifts.getValue("month")).isWithin(1e-3f).of(88f)
    }

    @Test
    fun theMovingHandsAddUp() {
        val wobble = WobbleSpec(listOf("hand"), amplitude = 10f)
        val spec = WakeAnimationSpec(sweep = listOf(sweep.copy(views = listOf("hand", "gone"))), wobble = listOf(wobble))
        val turns = effects(spec, 400, rotation = { if (it == "hand") -45f else null }).turns
        // Not there: not turned. There: the sweep (-40 for a value of -45, so +5) and the wobble
        assertThat(turns.keys).containsExactly("hand")
        assertThat(turns.getValue("hand")).isWithin(0.01f).of(5f + wobble.oldOffsetAt(400))
        assertThat(LayerAnimator(spec.allLayers()).viewKeys).containsExactly("hand", "gone")
    }

    @Test
    fun theLayersComeInPaintOrder() {
        val spec = WakeAnimationSpec(
            rain = RainSpec(), glitch = GlitchSpec(), trace = TraceSpec(rect = DesignRect(0f, 0f, 10f, 10f)),
            blink = listOf(BlinkSpec(listOf("b"), "v", 1.0)), sweep = listOf(sweep), slide = listOf(SlideSpec(listOf("s"), 5f)),
            wobble = listOf(WobbleSpec(listOf("w"))), glint = GlintSpec(rings = listOf(DesignRing(200f, 200f, 100f, 120f))),
            layers = listOf(AnimLayerSpec(LayerSource.Shape(ShapeKind.DOT), slot = LayerSlot.BACK))
        ).normalized()
        val layers = spec.allLayers()
        val kinds = layers.map { (it.source as? LayerSource.Shape)?.kind ?: (it.source as LayerSource.View).key }
        assertThat(kinds).containsExactly(ShapeKind.LINE, ShapeKind.RECT, "chart", ShapeKind.BAND, "b", "hand", "s", "w", ShapeKind.BAND, ShapeKind.DOT).inOrder()
        assertThat(layers.last().slot).isEqualTo(LayerSlot.BACK)
        assertThat(LayerAnimator(layers).errors).isEmpty()
    }
}
