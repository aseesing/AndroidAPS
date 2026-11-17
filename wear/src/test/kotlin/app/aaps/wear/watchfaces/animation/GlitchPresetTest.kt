// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.app.Application
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot
import kotlin.math.max

/** The glitch preset: short bursts of bands, the same for the same seed, inside the round screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class GlitchPresetTest {

    private val spec = GlitchSpec().normalized()

    private data class Band(val left: Float, val top: Float, val width: Float, val height: Float, val colorIndex: Int, val alpha: Float)

    private fun animator(glitch: GlitchSpec): LayerAnimator {
        val layers = WakeAnimationSpec(glitch = glitch).normalized().allLayers()
        assertThat(layers).hasSize(1)
        return LayerAnimator(layers).also { assertThat(it.errors).isEmpty() }
    }

    /** The bands that show [ms] after the start, at the fade [env]. */
    private fun bands(animator: LayerAnimator, ms: Long, env: Float = 1f) = mutableListOf<Band>().apply {
        animator.setTime(ms, 30_000, env, 0)
        for (i in 0 until spec.bands) {
            val alpha = animator.propOf(0, i, LayerProp.ALPHA)!!
            if (alpha <= 0f) continue
            add(
                Band(
                    animator.propOf(0, i, LayerProp.X)!!, animator.propOf(0, i, LayerProp.Y)!!,
                    animator.propOf(0, i, LayerProp.WIDTH)!!, animator.propOf(0, i, LayerProp.HEIGHT)!!,
                    animator.propOf(0, i, LayerProp.COLOR)!!.toInt(), alpha
                )
            )
        }
    }

    @Test
    fun theSameSeedGivesTheSameBands() {
        val a = animator(spec)
        val b = animator(spec)
        for (ms in 0L until 5000L step 10) assertThat(bands(a, ms)).isEqualTo(bands(b, ms))
        val other = animator(spec.copy(seed = 6))
        assertThat((0L until 5000L step 10).any { bands(a, it) != bands(other, it) }).isTrue()
    }

    @Test
    fun burstsAreShortAndNotAllTheTime() {
        val animator = animator(spec)
        val frames = (0L until 20_000L step 10).map { bands(animator, it).isNotEmpty() }
        val shown = frames.count { it }
        // Some bursts, but bands show only a small part of the time
        assertThat(shown).isGreaterThan(0)
        assertThat(shown.toDouble() / frames.size).isLessThan(0.4)
        // No burst is longer than burstMs
        var run = 0
        var longest = 0
        frames.forEach { if (it) run++ else run = 0; longest = max(longest, run) }
        assertThat(longest * 10L).isAtMost(spec.burstMs + 10)
    }

    @Test
    fun theBandsKeepTheirSizesAndColoursAndJumpSideways() {
        val animator = animator(spec)
        var bursts = 0
        for (ms in 0L until 20_000L step 10) {
            val now = bands(animator, ms)
            if (now.isEmpty()) continue
            bursts++
            assertThat(now).hasSize(spec.bands)
            now.forEachIndexed { i, band ->
                assertThat(band.width).isAtLeast(spec.minWidth)
                assertThat(band.width).isAtMost(spec.maxWidth)
                assertThat(band.height).isAtLeast(spec.minHeight)
                assertThat(band.height).isAtMost(spec.maxHeight)
                assertThat(band.top).isAtLeast(spec.region.top)
                assertThat(band.top + band.height).isAtMost(spec.region.bottom + 0.01f)
                assertThat(band.colorIndex).isEqualTo(i % 2)
                assertThat(band.alpha).isWithin(1e-6f).of(spec.alpha / 255f)
            }
            // 40 ms later, in the same burst, a band is moved by a whole number of jumps and is the same size
            val later = bands(animator, ms + GLITCH_JUMP_MS)
            val samePeriod = ms / spec.periodMs == (ms + GLITCH_JUMP_MS) / spec.periodMs
            if (samePeriod && later.isNotEmpty()) for (i in now.indices) {
                val jumps = (later[i].left - now[i].left) / spec.jump
                assertThat(jumps).isWithin(1e-3f).of(Math.round(jumps).toFloat())
                assertThat(later[i].width).isEqualTo(now[i].width)
            }
        }
        assertThat(bursts).isGreaterThan(0)
        // The fade of the wake
        val shownAt = (0L until 20_000L step 10).first { bands(animator, it).isNotEmpty() }
        assertThat(bands(animator, shownAt, env = 0.5f)[0].alpha).isWithin(1e-6f).of(spec.alpha / 255f / 2)
    }

    @Test
    fun everyBandStaysInsideTheRoundScreen() {
        val wide = spec.copy(minWidth = 300f, maxWidth = 400f, maxHeight = 40f, color1 = Color.WHITE, color2 = Color.WHITE, alpha = 255)
        assertThat(glitchLayer(wide).clip).isEqualTo(LayerClip(rect = DesignRect.FULL, round = true))
        val bitmap = createBitmap(400, 400).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(bitmap)
        val animator = animator(wide)
        var drawn = 0
        for (ms in 0L until 30_000L step 5) {
            animator.setTime(ms, 30_000, 1f, 0)
            if ((0 until wide.bands).any { animator.propOf(0, it, LayerProp.ALPHA)!! > 0f }) drawn++
            animator.drawSlot(canvas, LayerSlot.BACK, 1f)
        }
        assertThat(drawn).isGreaterThan(0)
        var lit = 0
        for (y in 0 until 400) for (x in 0 until 400) if (Color.red(bitmap.getPixel(x, y)) > 8) {
            lit++
            assertWithMessage("lit at $x,$y").that(hypot(x + 0.5f - 200f, y + 0.5f - 200f)).isAtMost(201f)
        }
        assertThat(lit).isGreaterThan(0)
    }

    @Test
    fun noChanceNoBands() {
        val off = WakeAnimationSpec(glitch = GlitchSpec(chance = 0f)).normalized()
        assertThat(off.glitch).isNull()
        assertThat(off.hasContent).isFalse()
    }
}
