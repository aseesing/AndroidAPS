// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.max

class GlitchFieldTest {

    private val spec = GlitchSpec().normalized()

    private data class Band(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int)

    private fun bands(field: GlitchField, ms: Long) = mutableListOf<Band>().apply {
        field.forEachBand(ms) { l, t, r, b, c -> add(Band(l, t, r, b, c)) }
    }

    @Test
    fun theSameSeedGivesTheSameBands() {
        val a = GlitchField(spec)
        val b = GlitchField(spec)
        for (ms in 0L until 5000L step 10) assertThat(bands(a, ms)).isEqualTo(bands(b, ms))
    }

    @Test
    fun burstsAreShortAndNotAllTheTime() {
        val field = GlitchField(spec)
        val frames = (0L until 20_000L step 10).map { bands(field, it).isNotEmpty() }
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
    fun everyBandStaysInsideTheRoundScreen() {
        val field = GlitchField(spec.copy(minWidth = 300f, maxWidth = 400f, maxHeight = 40f))
        for (ms in 0L until 30_000L step 5) {
            for (band in bands(field, ms)) {
                assertThat(band.right).isGreaterThan(band.left)
                for ((x, y) in listOf(band.left to band.top, band.right to band.top, band.left to band.bottom, band.right to band.bottom))
                    assertThat(hypot(x - 200f, y - 200f)).isAtMost(200.01f)
            }
        }
    }

    @Test
    fun noChanceNoBands() {
        val off = WakeAnimationSpec(glitch = GlitchSpec(chance = 0f)).normalized()
        assertThat(off.glitch).isNull()
        assertThat(off.hasContent).isFalse()
    }
}
