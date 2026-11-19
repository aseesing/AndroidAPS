package app.aaps.wear.watchfaces.animation

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
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
 * The templates of the manual ([TemplatesForTests]) of the views that move (`sweep`, `slide`, `wobble`), draw themselves in
 * (`trace`), blink (`blink`), and of the light (`glint`): the layer formulas give the same numbers as
 * the old code, which is kept here as the reference.
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

    /** The parameters of a `sweep`, and the old maths of it. */
    private data class Sweep(val view: String, val emptyAngle: Float, val fullAngle: Float, val delayMs: Long = 0, val upMs: Long = 600, val settleMs: Long = 900) {

        fun json(template: String = "sweep"): JSONObject =
            if (template == "slide") use("slide", "view" to view, "fullDx" to fullAngle.toDouble(), "delayMs" to delayMs, "upMs" to upMs, "settleMs" to settleMs)
            else use(template, "view" to view, "emptyAngle" to emptyAngle.toDouble(), "fullAngle" to fullAngle.toDouble(), "delayMs" to delayMs, "upMs" to upMs, "settleMs" to settleMs)
    }

    private fun Sweep.oldAngleAt(elapsedMs: Long, valueAngle: Float): Float {
        val t = elapsedMs - delayMs
        return when {
            t <= 0              -> emptyAngle
            t < upMs            -> emptyAngle + (fullAngle - emptyAngle) * oldEaseInOut(t.toFloat() / upMs)
            t < upMs + settleMs -> fullAngle + (valueAngle - fullAngle) * oldSettle((t - upMs).toFloat() / settleMs)
            else                -> valueAngle
        }
    }

    private data class Wobble(val view: String, val delayMs: Long = 0, val amplitude: Float = 16f, val hz: Float = 2.5f, val decayMs: Long = 350) {

        fun json(): JSONObject = use("wobble", "view" to view, "delayMs" to delayMs, "amplitude" to amplitude.toDouble(), "hz" to hz.toDouble(), "decayMs" to decayMs)

        fun oldOffsetAt(elapsedMs: Long): Float {
            val t = elapsedMs - delayMs
            if (t <= 0 || t > decayMs * 5) return 0f
            return (amplitude * exp(-t.toDouble() / decayMs) * sin(2 * PI * hz * (t / 1000.0))).toFloat()
        }
    }

    // ---- helpers ----

    /** What the view layers of [animation] do [ms] after the start, for views turned [rotation] and moved [offsetX]. */
    private fun effects(animation: JSONObject, ms: Long, rotation: (String) -> Float? = { 0f }, offsetX: (String) -> Float? = { 0f }): ViewEffectMaps =
        animatorOf(animation).run {
            setTime(ms, 6000, 1f, 0)
            viewEffects(rotation, offsetX)
        }

    private val sweep = Sweep("hand", emptyAngle = -60f, fullAngle = -20f, delayMs = 100, upMs = 600, settleMs = 900)

    /** The angle the swept hand of [sweep] is drawn at, for a value of -45. */
    private fun sweptAngle(ms: Long, s: Sweep = sweep): Float = -45f + (effects(animationOf(s.json()), ms, rotation = { -45f }).turns["hand"] ?: 0f)

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
    }

    @Test
    fun aWobbleShakesAndDiesOut() {
        val wobble = Wobble("hand", delayMs = 200, amplitude = 16f, hz = 2.5f, decayMs = 350)
        fun offsetAt(ms: Long) = effects(animationOf(wobble.json()), ms).turns["hand"] ?: 0f
        assertThat(offsetAt(0)).isEqualTo(0f)
        assertThat(offsetAt(200)).isEqualTo(0f)
        // The first swing, a quarter turn of the wave after the start, is the biggest
        val first = offsetAt(300)
        val later = offsetAt(700)
        assertThat(first).isGreaterThan(5f)
        assertThat(abs(later)).isLessThan(abs(first))
        assertThat(offsetAt(200 + 350 * 5 + 1L)).isEqualTo(0f)
        for (ms in 0L..2200L step 10) assertWithMessage("at $ms").that(offsetAt(ms)).isWithin(1e-4f).of(wobble.oldOffsetAt(ms))
    }

    @Test
    fun aTracedViewIsCutAtItsEdgeAndHiddenBeforeItStarts() {
        fun trace(width: Double = 8.0) = use(
            "trace", "view" to "chart", "rect" to rectJson(DesignRect(20f, 0f, 100f, 50f)), "delayMs" to 100, "durationMs" to 400, "width" to width
        )
        val animation = animationOf(trace())
        fun at(ms: Long) = effects(animation, ms)
        assertThat(at(0).hidden).containsExactly("chart")
        assertThat(at(100).hidden).containsExactly("chart")
        assertThat(at(300).hidden).isEmpty()
        assertThat(at(300).clipRights.getValue("chart")).isWithin(1e-4f).of(20f + 50f)
        assertThat(at(900).clipRights.getValue("chart")).isWithin(1e-4f).of(120f)
        // The bright line is at the edge, and only while the view is being drawn in
        val layers = readAnimation(animation).layers
        assertThat(layers.map { it.slot }).containsExactly(LayerSlot.VIEWS, LayerSlot.VIEWS).inOrder()
        assertThat((layers[1].source as LayerSource.Shape).gradient).isEqualTo(
            LayerGradient(LayerGradient.Type.LINEAR, listOf(LayerGradient.Stop(0f, 0f), LayerGradient.Stop(0.5f, 1f), LayerGradient.Stop(1f, 0f)))
        )
        val animator = animatorOf(animation)
        fun beam(ms: Long, prop: LayerProp): Float {
            animator.setTime(ms, 6000, 1f, 0)
            return animator.propOf(1, 0, prop)!!
        }
        assertThat(beam(50, LayerProp.ALPHA)).isEqualTo(0f)
        // The middle of the line on the edge: its left side half its width before it
        assertThat(beam(300, LayerProp.X)).isWithin(1e-4f).of(70f - 4f)
        assertThat(beam(300, LayerProp.ALPHA)).isWithin(1e-4f).of(140 / 255f)
        // It fades over the last eighth of the way: at 15/16 it is half
        assertThat(beam(100 + 375, LayerProp.ALPHA)).isWithin(1e-4f).of(140 / 255f * 0.5f)
        assertThat(beam(500, LayerProp.ALPHA)).isEqualTo(0f)
        // No line when it has no width
        assertThat(readAnimation(animationOf(trace(width = 0.0))).layers).hasSize(1)
    }

    @Test
    fun theGlintCrossesTheFaceOnce() {
        fun glint(boxes: JSONArray = JSONArray().put(rectJson(DesignRect(0f, 0f, 10f, 10f))), alpha: Int = 90) =
            use("glint", "boxes" to boxes, "delayMs" to 100, "durationMs" to 400, "width" to 60, "alpha" to alpha)
        val animator = animatorOf(animationOf(glint()))
        fun at(ms: Long, prop: LayerProp): Float {
            animator.setTime(ms, 6000, 1f, 0)
            return animator.propOf(0, 0, prop)!!
        }
        val diagonal = 400f * sqrt(2f)
        // Not before it starts, nor after it has crossed
        assertThat(at(50, LayerProp.ALPHA)).isEqualTo(0f)
        assertThat(at(600, LayerProp.ALPHA)).isEqualTo(0f)
        assertThat(at(300, LayerProp.ALPHA)).isWithin(1e-6f).of(90 / 255f)
        // The middle of the band from just left of the face to just right of it, halfway in the middle
        assertThat(at(100, LayerProp.X) + 30f).isWithin(1e-3f).of(200f - diagonal / 2 - 30f)
        assertThat(at(300, LayerProp.X) + 30f).isWithin(1e-3f).of(200f)
        assertThat(at(500, LayerProp.X) + 30f).isWithin(1e-3f).of(200f + diagonal / 2 + 30f)
        assertThat(at(300, LayerProp.ROTATION)).isEqualTo(30f)
        // No shapes: nothing is drawn; no light: no layer
        assertThat(readAnimation(animationOf(glint(boxes = JSONArray()))).layers.single().clip.isEmpty).isTrue()
        assertThat(readAnimation(animationOf(glint(alpha = 0))).layers).isEmpty()
    }

    @Test
    fun aLowValueBlinksItsView() {
        fun hiddenAt(ms: Long, level: Double?, periodMs: Long = 1000) = animatorOf(
            animationOf(use("blink", "view" to "a", "valueKey" to "battery", "atOrBelow" to 20, "periodMs" to periodMs))
        ).run {
            setWakeValues({ if (it == "battery") level else null }, 60)
            setTime(ms, 6000, 1f, 0)
            viewEffects({ 0f }, { 0f }).hidden
        }
        // Low: shown in the first half of each period, hidden in the second half
        assertThat(hiddenAt(0, 15.0)).isEmpty()
        assertThat(hiddenAt(499, 15.0)).isEmpty()
        assertThat(hiddenAt(500, 15.0)).containsExactly("a")
        assertThat(hiddenAt(1100, 15.0)).isEmpty()
        assertThat(hiddenAt(1700, 20.0)).containsExactly("a")
        // Above the limit, or with no value, never
        for (level in listOf(20.5, 100.0, null)) for (ms in listOf(0L, 600L, 1700L)) assertThat(hiddenAt(ms, level)).isEmpty()
        // An odd period: off from its half, rounded down, as before
        assertThat(hiddenAt(100, 15.0, periodMs = 201)).containsExactly("a")
        assertThat(hiddenAt(99, 15.0, periodMs = 201)).isEmpty()
        // The period is kept inside its limits
        assertThat(readAnimation(animationOf(use("blink", "view" to "a", "valueKey" to "v", "periodMs" to 60_000))).layers.single().props.getValue(LayerProp.ALPHA))
            .contains("mod(ms, 5000) >= floor(5000 / 2)")
    }

    @Test
    fun valuesAreKeptSafe() {
        val wild = readAnimation(animationOf(Sweep("a", 0f, 10f, delayMs = -5, upMs = 0, settleMs = 1_000_000).json())).layers.single()
        val rotation = wild.props.getValue(LayerProp.ROTATION)
        assertThat(wild.lets.toMap()).containsEntry("w", "ms - 0")
        assertThat(wild.lets.toMap()).containsEntry("s", "clamp((w - 50) / 5000, 0, 1)")
        assertThat(rotation).contains("easeInOut(w / 50)")
        val wobble = readAnimation(animationOf(Wobble("a", amplitude = 500f, hz = 100f).json())).layers.single().props.getValue(LayerProp.ROTATION)
        assertThat(wobble).contains("90 * exp(")
        assertThat(wobble).contains("2 * pi * 10 * w")
    }

    @Test
    fun aSlideGoesToTheEndOfItsScaleAndBackToItsValue() {
        val slide = Sweep("month", 0f, 220f, delayMs = 100, upMs = 600, settleMs = 900)
        fun dxAt(ms: Long) = 132f + (effects(animationOf(slide.json("slide")), ms, offsetX = { 132f }).shifts["month"] ?: 0f)
        // Waits at the design place, runs to the end, settles on the value with a bounce past it
        assertThat(dxAt(0)).isEqualTo(0f)
        assertThat(dxAt(100)).isEqualTo(0f)
        assertThat(dxAt(700)).isWithin(1e-3f).of(220f)
        assertThat((700L..1600L step 10).minOf { dxAt(it) }).isLessThan(132f)
        assertThat(dxAt(1600)).isEqualTo(132f)
        assertThat(dxAt(5000)).isEqualTo(132f)
        for (ms in 0L..2000L step 20) assertWithMessage("at $ms").that(dxAt(ms)).isWithin(1e-3f).of(slide.oldAngleAt(ms, 132f))
        assertThat(animatorOf(animationOf(slide.json("slide"))).viewKeys).containsExactly("month")
    }

    @Test
    fun aSlidViewIsMovedFromWhereItsValuePutsIt() {
        val animation = animationOf(Sweep("month", 0f, 220f, upMs = 600).json("slide"), Sweep("gone", 0f, 220f, upMs = 600).json("slide"))
        val shifts = effects(animation, 600, rotation = { if (it == "month") 0f else null }, offsetX = { if (it == "month") 132f else null }).shifts
        // At the end of the scale: 220 from the design place, so 88 right of the value. A view the face does not have is left alone.
        assertThat(shifts.keys).containsExactly("month")
        assertThat(shifts.getValue("month")).isWithin(1e-3f).of(88f)
    }

    @Test
    fun theMovingHandsAddUp() {
        val wobble = Wobble("hand", amplitude = 10f)
        val animation = animationOf(sweep.json(), sweep.copy(view = "gone").json(), wobble.json())
        val turns = effects(animation, 400, rotation = { if (it == "hand") -45f else null }).turns
        // Not there: not turned. There: the sweep (-40 for a value of -45, so +5) and the wobble
        assertThat(turns.keys).containsExactly("hand")
        assertThat(turns.getValue("hand")).isWithin(0.01f).of(5f + wobble.oldOffsetAt(400))
        assertThat(animatorOf(animation).viewKeys).containsExactly("hand", "gone")
    }
}
