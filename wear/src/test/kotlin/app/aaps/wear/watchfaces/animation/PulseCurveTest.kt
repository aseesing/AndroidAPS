package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The heart beat curve: its period follows the heart rate, and its shape looks like a pulse. */
class PulseCurveTest {

    @Test
    fun periodFollowsTheHeartRate() {
        assertThat(PulseCurve.periodMs(60)).isEqualTo(1000)
        assertThat(PulseCurve.periodMs(120)).isEqualTo(500)
        assertThat(PulseCurve.periodMs(75)).isEqualTo(800)
    }

    @Test
    fun heartRateIsKeptBetween40And180() {
        assertThat(PulseCurve.periodMs(20)).isEqualTo(1500)
        assertThat(PulseCurve.periodMs(250)).isEqualTo(333)
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

    @Test
    fun aBeatSwellsQuicklyAndFallsSlowly() {
        val bpm = 60
        val rise = PulseCurve.riseMs(bpm).toLong()
        assertThat(rise).isEqualTo(120)
        assertThat(PulseCurve.value(0, bpm)).isLessThan(0.02f)
        assertThat(PulseCurve.value(60, bpm)).isWithin(0.01f).of(0.5f)
        assertThat(PulseCurve.value(rise, bpm)).isWithin(0.01f).of(1f)
        // falls slower than it rose: 120 ms after the top it is still well above half way down
        assertThat(PulseCurve.value(rise + 60, bpm)).isGreaterThan(0.6f)
        // and it is back near rest before the next beat
        assertThat(PulseCurve.value(950, bpm)).isLessThan(0.03f)
    }

    @Test
    fun theSecondBeatIsASmallBumpAfterTheFirst() {
        val bpm = 60
        val before = PulseCurve.value(290, bpm)
        val bump = PulseCurve.value(370, bpm)
        val after = PulseCurve.value(450, bpm)
        assertThat(bump).isGreaterThan(before)
        assertThat(bump).isGreaterThan(after)
        assertThat(bump).isLessThan(0.7f)
        // without it the fall is smooth
        assertThat(PulseCurve.value(370, bpm, secondBeat = 0f)).isLessThan(PulseCurve.value(290, bpm, secondBeat = 0f))
    }

    @Test
    fun theCurveRepeatsEveryPeriodAndStaysBetween0And1() {
        for (bpm in listOf(40, 60, 90, 140, 180)) {
            val period = PulseCurve.periodMs(bpm)
            for (t in 0L until 3000L step 7) {
                val v = PulseCurve.value(t, bpm)
                assertThat(v).isAtLeast(0f)
                assertThat(v).isAtMost(1f)
                assertThat(PulseCurve.value(t + period, bpm)).isWithin(0.0001f).of(v)
            }
            // every beat reaches its top, even at 180 bpm
            assertThat(PulseCurve.value(PulseCurve.riseMs(bpm).toLong(), bpm)).isGreaterThan(0.95f)
        }
    }
}
