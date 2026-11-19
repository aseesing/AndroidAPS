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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every built-in template expands and its formulas can be read, with its defaults and with values given. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BuiltInTemplatesTest {

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
    fun everyBuiltInTemplateExpandsAndCompiles() {
        val names = BuiltInTemplates.all.keys().asSequence().toList()
        assertThat(names).containsExactly("rain", "glitch", "pulse", "blink", "sweep", "slide", "wobble", "trace", "glint", "moon")
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
        val all = BuiltInTemplates.all
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
    fun f32GivesTheNumbersOfTheOldFloatMaths() {
        assertThat(Formula.f32(0.1)).isEqualTo(0.1f.toString().toDouble())
        assertThat(Formula.f32(400 * Math.sqrt(2.0))).isEqualTo((400f * kotlin.math.sqrt(2f)).toString().toDouble())
        // Fixed numbers only: folded when the formula is read
        assertThat(Formula.parse("f32(1 / 3)") { null }.isConstant).isTrue()
    }
}
