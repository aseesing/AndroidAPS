package app.aaps.wear.watchfaces

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.PulseSpec
import app.aaps.wear.watchfaces.animation.RainSpec
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The optional `animation` key of a design: absent means no animation, missing keys get the defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WakeAnimationJsonTest {

    private val face = CustomWatchface()
    private val heart: Drawable = ColorDrawable(Color.RED)
    private val images = mapOf("heart" to heart)

    private fun read(json: String): WakeAnimationSpec? = face.readWakeAnimation(JSONObject(json)) { images[it] }

    @Test
    fun noAnimationKeyMeansNoAnimation() {
        assertThat(read("""{"metadata": {}, "time": {"width": 100}}""")).isNull()
    }

    @Test
    fun anEmptyAnimationHasTheDefaultsAndNothingToDraw() {
        val spec = read("""{"animation": {}}""")
        assertThat(spec).isEqualTo(WakeAnimationSpec())
        assertThat(spec!!.normalized().hasContent).isFalse()
    }

    @Test
    fun missingKeysGetTheDefaults() {
        val spec = read(
            """{"animation": {"fps": 20, "rain": {"count": 50, "color": "#80FF0000"}, "pulse": {"image": "heart", "top": 30}}}"""
        )!!
        assertThat(spec.fps).isEqualTo(20)
        assertThat(spec.durationMs).isEqualTo(WakeAnimationSpec().durationMs)
        assertThat(spec.fadeMs).isEqualTo(WakeAnimationSpec().fadeMs)
        assertThat(spec.rain).isEqualTo(RainSpec(count = 50, color = Color.argb(0x80, 0xFF, 0, 0)))
        val pulse = spec.pulse!!
        assertThat(pulse.drawable).isSameInstanceAs(heart)
        assertThat(pulse.copy(drawable = null)).isEqualTo(PulseSpec(image = "heart", rect = PulseSpec().rect.copy(top = 30f)))
    }

    @Test
    fun everyKeyIsRead() {
        val spec = read(
            """{"animation": {"durationMs": 4000, "fps": 30, "fadeMs": 300,
               "rain": {"count": 70, "seed": 3, "speed": 400, "angle": -10, "length": 30, "lengthVariation": 0.2,
                        "thickness": 2.5, "color": "#FFFFFF", "alpha": 100,
                        "region": {"left": 10, "top": 20, "width": 300, "height": 200}},
               "pulse": {"image": "heart", "left": 180, "top": 60, "width": 40, "height": 36, "amplitude": 0.2,
                         "secondBeat": 0.5, "glowColor": "#00FF00", "glowAlpha": 90, "glowRadius": 6, "fallbackBpm": 70}}}"""
        )!!
        assertThat(spec.withoutImage()).isEqualTo(
            WakeAnimationSpec(
                durationMs = 4000, fps = 30, fadeMs = 300,
                rain = RainSpec(70, 3, 400f, -10f, 30f, 0.2f, 2.5f, Color.WHITE, 100, DesignRect(10f, 20f, 300f, 200f)),
                pulse = PulseSpec("heart", null, DesignRect(180f, 60f, 40f, 36f), 0.2f, 0.5f, Color.GREEN, 90, 6f, 70)
            )
        )
    }

    @Test
    fun brokenValuesFallBackInsteadOfBreakingTheDesign() {
        val spec = read("""{"animation": {"fps": "fast", "rain": {"color": "blue-ish", "count": "many"}, "pulse": {"image": "nothere"}}}""")!!
        assertThat(spec.fps).isEqualTo(WakeAnimationSpec().fps)
        assertThat(spec.rain).isEqualTo(RainSpec())
        // an image that is not in the zip gives no heart, but the rain still plays
        val normalized = spec.normalized()
        assertThat(normalized.pulse).isNull()
        assertThat(normalized.hasContent).isTrue()
        // not an object at all
        assertThat(read("""{"animation": 5}""")).isNull()
    }
}
