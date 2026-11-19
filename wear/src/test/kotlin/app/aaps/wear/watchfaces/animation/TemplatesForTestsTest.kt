// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The templates of the manual ([TemplatesForTests]) expand and their formulas can be read, with their
 * defaults and with values given. The app itself knows no template: a `use` of a name the design does
 * not define is left out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TemplatesForTestsTest {

    /** The parameters a template needs to draw anything; the others keep their defaults. */
    private val needed = mapOf(
        "pulse" to listOf("image" to "heart"),
        "blink" to listOf("view" to "battery", "valueKey" to "watch_battery", "atOrBelow" to 20),
        "sweep" to listOf("view" to "hand", "fullAngle" to 30),
        "slide" to listOf("view" to "month", "fullDx" to 20),
        "wobble" to listOf("view" to "hand"),
        "glint" to listOf("rings" to JSONArray("""[{"cx": 200, "cy": 200, "inner": 180, "outer": 200}]"""))
    )

    private val images = mapOf("heart" to ColorDrawable(Color.RED))

    @Test
    fun everyTemplateExpandsAndCompiles() {
        val names = TemplatesForTests.all().keys().asSequence().toList()
        assertThat(names).containsExactly("rain", "glitch", "pulse", "blink", "sweep", "slide", "wobble", "trace", "glint")
        for (name in names) {
            val spec = readAnimation(animationOf(use(name, *needed[name].orEmpty().toTypedArray())), images)
            assertWithMessage("$name: ${spec.errors}").that(spec.errors).isEmpty()
            val normalized = spec.normalized()
            assertWithMessage(name).that(normalized.layers).isNotEmpty()
            val animator = LayerAnimator(normalized.layers, normalized.lets)
            assertWithMessage("$name: ${animator.errors}").that(animator.errors).isEmpty()
            assertWithMessage(name).that(animator.size).isEqualTo(normalized.layers.size)
            assertWithMessage(name).that(animator.hasWakeContent).isTrue()
            // Worked out for a frame without trouble
            animator.setTime(500, 6000, 1f, 0)
            for (layer in 0 until animator.size) for (prop in LayerProp.entries) animator.propOf(layer, 0, prop)
        }
    }

    @Test
    fun everyParameterHasADefaultThatIsInsideItsLimits() {
        val all = TemplatesForTests.all()
        for (name in all.keys()) {
            val params = all.getJSONObject(name).getJSONObject(Templates.PARAMS)
            for (key in params.keys()) {
                val limits = params.optJSONObject(key)?.takeIf { it.has(Templates.DEFAULT) } ?: continue
                val default = limits.getDouble(Templates.DEFAULT)
                limits.opt(Templates.MIN).let { if (it is Number) assertWithMessage("$name.$key").that(default).isAtLeast(it.toDouble()) }
                limits.opt(Templates.MAX).let { if (it is Number) assertWithMessage("$name.$key").that(default).isAtMost(it.toDouble()) }
            }
        }
    }

    @Test
    fun theAppHasNoTemplatesOfItsOwn() {
        for (name in TemplatesForTests.all().keys()) {
            val animation = JSONObject().put("layers", JSONArray().put(use(name, *needed[name].orEmpty().toTypedArray())))
            val spec = readAnimation(animation, images)
            assertWithMessage(name).that(spec.layers).isEmpty()
            assertWithMessage(name).that(spec.errors).containsExactly("layer 0: no template '$name' in the templates of the design")
        }
    }

    @Test
    fun theBeatOfThePulseTemplateIsTheOldHeartBeatCurve() {
        for (bpm in listOf(40, 60, 72, 110, 150, 180)) for (secondBeat in listOf(0.0, 0.35, 1.0)) {
            val spec = readAnimation(animationOf(use("pulse", "image" to "heart", "amplitude" to 0.5, "secondBeat" to secondBeat)), images).normalized()
            val animator = LayerAnimator(spec.layers, spec.lets)
            animator.setWakeValues({ null }, bpm)
            for (ms in 0L until 3000L step 7) {
                animator.setTime(ms, 6000, 1f, 0)
                val beat = (animator.propOf(0, 0, LayerProp.SCALE_X)!! - 1f) / 0.5f
                assertWithMessage("bpm $bpm, second beat $secondBeat, $ms ms").that(beat).isWithin(1e-5f).of(oldBeat(ms, bpm, secondBeat.toFloat()))
            }
        }
    }

    /** The heart beat curve the app had as the formula function `pulse` (`PulseCurve.value`), in 32-bit floats. */
    private fun oldBeat(elapsedMs: Long, bpm: Int, secondBeat: Float): Float {
        val periodMs = 60_000L / bpm
        val period = periodMs.toFloat()
        val t = (elapsedMs % periodMs).toFloat()
        val rise = min(120f, period * 0.3f)
        val decay = max(50f, period * 0.16f)
        val main = if (t < rise) sin(PI / 2 * t / rise).let { (it * it).toFloat() } else exp(-(t - rise) / decay)
        val secondAt = rise + min(250f, period * 0.3f)
        val secondWidth = max(25f, period * 0.06f)
        val d = (t - secondAt) / secondWidth
        return (main + secondBeat * exp(-d * d)).coerceIn(0f, 1f)
    }
}
