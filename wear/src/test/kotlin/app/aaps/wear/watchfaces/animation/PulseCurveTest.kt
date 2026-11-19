// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The heart rate of `bpm`: kept between 40 and 180, with a fallback. The shape of a beat is tested in [TemplatesForTestsTest]. */
class PulseCurveTest {

    @Test
    fun heartRateIsKeptBetween40And180() {
        assertThat(PulseCurve.bpmOrFallback(200)).isEqualTo(180)
        assertThat(PulseCurve.bpmOrFallback(30)).isEqualTo(40)
    }

    @Test
    fun withoutAHeartRateTheFallbackIsUsed() {
        assertThat(PulseCurve.bpmOrFallback(null)).isEqualTo(60)
        assertThat(PulseCurve.bpmOrFallback(0)).isEqualTo(60)
        assertThat(PulseCurve.bpmOrFallback(null, fallback = 72)).isEqualTo(72)
        assertThat(PulseCurve.bpmOrFallback(85, fallback = 72)).isEqualTo(85)
    }
}
