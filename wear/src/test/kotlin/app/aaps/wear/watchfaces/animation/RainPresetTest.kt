// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.app.Application
import android.graphics.Bitmap
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * The rain preset: the same streaks as the old `RainField` drew, the same for the same seed, never
 * outside the round screen or its region, and it never runs out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class RainPresetTest {

    /** The streak math of the old `RainField`, kept here as the reference the preset must match. */
    private class OldRain(spec: RainSpec) {

        private val region = spec.region
        private val sinA = sin(Math.toRadians(spec.angle.toDouble())).toFloat()
        private val cosA = cos(Math.toRadians(spec.angle.toDouble())).toFloat()
        private val tanA = tan(Math.toRadians(spec.angle.toDouble())).toFloat()
        private val startX = FloatArray(spec.count)
        private val phase = FloatArray(spec.count)
        private val speed = FloatArray(spec.count)
        private val length = FloatArray(spec.count)
        val alpha = FloatArray(spec.count)
        private val span: Float

        init {
            val random = Random(spec.seed)
            val longest = spec.length * (1 + spec.lengthVariation)
            span = region.height + longest * cosA + 1f
            val pad = longest * abs(sinA)
            val fromX = region.left - max(0f, region.height * tanA) - pad
            val toX = region.right + max(0f, -region.height * tanA) + pad
            for (i in 0 until spec.count) {
                startX[i] = fromX + random.nextFloat() * (toX - fromX)
                phase[i] = random.nextFloat() * span
                speed[i] = spec.speed * (0.8f + 0.4f * random.nextFloat())
                length[i] = spec.length * (1 + spec.lengthVariation * (2 * random.nextFloat() - 1))
                alpha[i] = 0.45f + 0.55f * random.nextFloat()
            }
        }

        /** Tail x, tail y, head x, head y of streak [i], not cut. */
        fun streak(i: Int, elapsedMs: Long): List<Float> {
            val fallen = (phase[i] + speed[i] * elapsedMs / 1000f) % span
            val headY = region.top + fallen
            val headX = startX[i] + fallen * tanA
            return listOf(headX - length[i] * sinA, headY - length[i] * cosA, headX, headY)
        }
    }

    private data class Streak(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val alpha: Float)

    private fun animator(spec: RainSpec): LayerAnimator {
        val layers = WakeAnimationSpec(rain = spec).normalized().allLayers()
        assertThat(layers).hasSize(1)
        return LayerAnimator(layers).also { assertThat(it.errors).isEmpty() }
    }

    /** Every streak of the preset at [elapsedMs]: from where the line starts to where it ends, not cut. */
    private fun streaks(animator: LayerAnimator, count: Int, elapsedMs: Long, env: Float = 1f): List<Streak> {
        animator.setTime(elapsedMs, 6000, env, 0)
        return (0 until count).map { i ->
            val x = animator.propOf(0, i, LayerProp.X)!!
            val y = animator.propOf(0, i, LayerProp.Y)!!
            val length = animator.propOf(0, i, LayerProp.LENGTH)!!
            val angle = Math.toRadians(animator.propOf(0, i, LayerProp.ANGLE)!!.toDouble())
            Streak(x, y, x + length * sin(angle).toFloat(), y + length * cos(angle).toFloat(), animator.propOf(0, i, LayerProp.ALPHA)!!)
        }
    }

    @Test
    fun thePresetDrawsTheSameStreaksAsTheOldRain() {
        val specs = listOf(
            RainSpec(),
            RainSpec(seed = 8, angle = -30f, lengthVariation = 0f, region = DesignRect(100f, 50f, 150f, 120f)),
            RainSpec(count = 300, seed = 1234567, speed = 4000f, angle = 45f, length = 80f, lengthVariation = 0.9f, alpha = 77)
        )
        for (raw in specs) {
            val spec = raw.normalized()
            val old = OldRain(spec)
            val animator = animator(spec)
            for (t in listOf(0L, 41L, 1234L, 3000L, 5999L)) streaks(animator, spec.count, t).forEachIndexed { i, s ->
                val expected = old.streak(i, t)
                val actual = listOf(s.x0, s.y0, s.x1, s.y1)
                for (k in 0..3) assertWithMessage("seed ${spec.seed} streak $i at $t ms, value $k").that(actual[k]).isWithin(0.01f).of(expected[k])
                assertWithMessage("alpha of streak $i").that(s.alpha).isWithin(1e-5f).of(old.alpha[i] * spec.alpha / 255f)
            }
        }
    }

    @Test
    fun theSameSeedGivesTheSameRain() {
        val a = animator(RainSpec())
        val b = animator(RainSpec())
        for (t in listOf(0L, 41L, 1234L, 5999L)) assertThat(streaks(a, 90, t)).isEqualTo(streaks(b, 90, t))
        assertThat(streaks(animator(RainSpec(seed = 8)), 90, 1234)).isNotEqualTo(streaks(a, 90, 1234))
    }

    @Test
    fun theRainFallsAndSlantsAndFades() {
        val animator = animator(RainSpec(count = 1, seed = 3, angle = 20f, lengthVariation = 0f))
        // A moment where the streak does not start again at the top in the next 40 ms
        val t = (0L until 3000L step 10).first { streaks(animator, 1, it + 40)[0].y1 > streaks(animator, 1, it)[0].y1 }
        val before = streaks(animator, 1, t)[0]
        val after = streaks(animator, 1, t + 40)[0]
        assertThat(after.y1).isGreaterThan(before.y1)                  // down
        assertThat(after.x1).isGreaterThan(before.x1)                  // and to the right
        assertThat(before.x1 - before.x0).isGreaterThan(0f)            // head right of the tail
        assertThat(before.y1 - before.y0).isGreaterThan(0f)            // and below it
        // The fade of the wake
        assertThat(streaks(animator, 1, t, env = 0.5f)[0].alpha).isWithin(1e-6f).of(before.alpha / 2)
    }

    @Test
    fun theRainIsCutToItsRegionAndTheRoundScreen() {
        val region = DesignRect(100f, 50f, 150f, 120f)
        assertThat(rainLayer(RainSpec(region = region)).clip).isEqualTo(LayerClip(rect = region, round = true))
        // Drawn: nothing outside the region, and something inside it
        val inRegion = lit(RainSpec(count = 120, angle = -30f, alpha = 255, color = Color.WHITE, region = region))
        var seen = 0
        for (y in 0 until 400) for (x in 0 until 400) if (inRegion.isLit(x, y)) {
            seen++
            assertWithMessage("lit at $x,$y").that(x >= region.left - 1 && x <= region.right && y >= region.top - 1 && y <= region.bottom).isTrue()
        }
        assertThat(seen).isGreaterThan(0)
        // Nothing outside the round screen
        val all = lit(RainSpec(count = 300, thickness = 4f, alpha = 255, color = Color.WHITE))
        for (y in 0 until 400) for (x in 0 until 400) if (all.isLit(x, y))
            assertWithMessage("lit at $x,$y").that(hypot(x + 0.5f - 200f, y + 0.5f - 200f)).isAtMost(201f)
    }

    @Test
    fun theRainNeverRunsOut() {
        val spec = RainSpec(count = 90)
        val animator = animator(spec)
        val counts = (0L until 6000L step 41).map { t -> streaks(animator, spec.count, t).count { it.isSeen() } }
        println("RAIN visible streaks per frame: min ${counts.min()}, max ${counts.max()} of 90")
        assertThat(counts.min()).isAtLeast(30)
    }

    /** True when some part of the streak is on the round screen. */
    private fun Streak.isSeen(): Boolean = (0..10).any { k ->
        val u = k / 10f
        hypot(x0 + (x1 - x0) * u - 200f, y0 + (y1 - y0) * u - 200f) < 200f
    }

    /** The rain of [spec] drawn on black at 1 screen pixel per design pixel, many frames on top of each other. */
    private fun lit(spec: RainSpec): Bitmap {
        val bitmap = createBitmap(400, 400).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(bitmap)
        val animator = animator(spec)
        for (t in 0L until 6000L step 250) {
            animator.setTime(t, 6000, 1f, 0)
            animator.drawSlot(canvas, LayerSlot.BACK, 1f)
        }
        return bitmap
    }

    private fun Bitmap.isLit(x: Int, y: Int): Boolean = Color.red(getPixel(x, y)) > 8
}
