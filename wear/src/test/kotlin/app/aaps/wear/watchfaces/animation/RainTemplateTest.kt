package app.aaps.wear.watchfaces.animation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
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
 * The template `rain` of the manual ([TemplatesForTests]): the same streaks as the old `RainField` drew, the same for the same
 * seed, never outside the round screen or its region, and it never runs out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class RainTemplateTest {

    /** The parameters of the template, with its defaults. */
    private data class Rain(
        val count: Int = 90,
        val seed: Long = 7,
        val speed: Float = 520f,
        val angle: Float = 12f,
        val length: Float = 22f,
        val lengthVariation: Float = 0.4f,
        val thickness: Float = 1.6f,
        val color: Int = Color.rgb(166, 200, 255),
        val alpha: Int = 150,
        val region: DesignRect = DesignRect.FULL
    ) {

        fun json(): JSONObject = use(
            "rain", "count" to count, "seed" to seed, "speed" to speed.toDouble(), "angle" to angle.toDouble(), "length" to length.toDouble(),
            "lengthVariation" to lengthVariation.toDouble(), "thickness" to thickness.toDouble(), "color" to colorText(color), "alpha" to alpha,
            "region" to rectJson(region)
        )
    }

    /** The streak math of the old `RainField`, kept here as the reference the template must match. */
    private class OldRain(spec: Rain) {

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

    private fun animator(spec: Rain): LayerAnimator = animatorOf(animationOf(spec.json())).also { assertThat(it.size).isEqualTo(1) }

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
            Rain(),
            Rain(seed = 8, angle = -30f, lengthVariation = 0f, region = DesignRect(100f, 50f, 150f, 120f)),
            Rain(count = 300, seed = 1234567, speed = 4000f, angle = 45f, length = 80f, lengthVariation = 0.9f, alpha = 77)
        )
        for (spec in specs) {
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
        val a = animator(Rain())
        val b = animator(Rain())
        for (t in listOf(0L, 41L, 1234L, 5999L)) assertThat(streaks(a, 90, t)).isEqualTo(streaks(b, 90, t))
        assertThat(streaks(animator(Rain(seed = 8)), 90, 1234)).isNotEqualTo(streaks(a, 90, 1234))
    }

    @Test
    fun theRainFallsAndSlantsAndFades() {
        val animator = animator(Rain(count = 1, seed = 3, angle = 20f, lengthVariation = 0f))
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
        assertThat(readAnimation(animationOf(Rain(region = region).json())).layers.single().clip).isEqualTo(LayerClip(rect = region, round = true))
        // Drawn: nothing outside the region, and something inside it
        val inRegion = lit(Rain(count = 120, angle = -30f, alpha = 255, color = Color.WHITE, region = region))
        var seen = 0
        for (y in 0 until 400) for (x in 0 until 400) if (inRegion.isLit(x, y)) {
            seen++
            assertWithMessage("lit at $x,$y").that(x >= region.left - 1 && x <= region.right && y >= region.top - 1 && y <= region.bottom).isTrue()
        }
        assertThat(seen).isGreaterThan(0)
        // Nothing outside the round screen
        val all = lit(Rain(count = 300, thickness = 4f, alpha = 255, color = Color.WHITE))
        for (y in 0 until 400) for (x in 0 until 400) if (all.isLit(x, y))
            assertWithMessage("lit at $x,$y").that(hypot(x + 0.5f - 200f, y + 0.5f - 200f)).isAtMost(201f)
    }

    @Test
    fun theRainNeverRunsOut() {
        val spec = Rain(count = 90)
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
    private fun lit(spec: Rain): Bitmap {
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

    @Test
    fun theLimitsOfTheOldPresetStay() {
        val layer = readAnimation(animationOf(Rain(count = 1000, speed = 1f, angle = 80f, thickness = 0f, alpha = 900).json())).layers.single()
        assertThat(layer.copies).isEqualTo(LayerCopies.Count(300, 7))
        assertThat(layer.props[LayerProp.ANGLE]).isEqualTo("45")
        assertThat(layer.props[LayerProp.THICKNESS]).isEqualTo("0.5")
        assertThat(layer.props[LayerProp.ALPHA]).contains("env * 255 / 255 *")
        // No streaks, no layer
        assertThat(readAnimation(animationOf(Rain(count = 0).json())).layers).isEmpty()
    }
}
