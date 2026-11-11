package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The watch setting for the length of the wake-up animation, and how the timer follows it. */
class WakeLengthTest {

    private var now = 1_000_000L
    private var chosen: Long? = null
    private val timer = WakeTimer({ chosen }) { now }
    private val spec = WakeAnimationSpec(durationMs = 6000, fps = 24, fadeMs = 600, rain = RainSpec())

    @Test
    fun theStoredValueIsALengthOffOrTheDesign() {
        assertThat(WakeLength.parse(WakeLength.AS_DESIGN)).isNull()
        assertThat(WakeLength.parse(null)).isNull()
        assertThat(WakeLength.parse("0")).isEqualTo(0L)
        assertThat(WakeLength.parse("15000")).isEqualTo(15_000L)
        // anything else keeps the design's own length
        assertThat(WakeLength.parse("abc")).isNull()
        assertThat(WakeLength.parse("-5")).isNull()
    }

    @Test
    fun everyChoiceOfTheSettingIsKnown() {
        for (value in listOf("design", "0", "3000", "6000", "10000", "15000", "20000", "30000")) {
            val length = WakeLength.parse(value)
            if (value == "design") assertThat(length).isNull() else assertThat(length).isEqualTo(value.toLong())
        }
    }

    @Test
    fun withoutAChoiceTheDesignLengthPlays() {
        timer.spec = spec
        assertThat(timer.frame()).isEqualTo(0)
        assertThat(timer.lengthThisWakeMs).isEqualTo(6000)
        now += 6000
        assertThat(timer.frame()).isNull()
    }

    @Test
    fun aLongerChoicePlaysLongerWithEnoughFrames() {
        chosen = 20_000
        timer.spec = spec
        timer.frame()
        assertThat(timer.lengthThisWakeMs).isEqualTo(20_000)
        // one frame every 1000 / 24 ms over the whole 20 s: none is refused for the frame limit
        var frames = 1
        while (true) {
            now += 42
            if (timer.frame() == null) break
            frames++
        }
        assertThat(frames).isEqualTo(20_000 / 42 + 1)
        assertThat(spec.maxFrames(20_000)).isEqualTo(480)
    }

    @Test
    fun theLengthStaysInsideTheSafeLimit() {
        chosen = 600_000
        timer.spec = spec
        timer.frame()
        assertThat(timer.lengthThisWakeMs).isEqualTo(WakeAnimationSpec.MAX_DURATION_MS)
        assertThat(WakeAnimationSpec.MAX_DURATION_MS).isEqualTo(30_000)
        now += 30_000
        assertThat(timer.frame()).isNull()
    }

    @Test
    fun offPlaysNothingAndKeepsTheNormalRate() {
        chosen = 0
        timer.spec = spec
        assertThat(timer.wantsFrames).isFalse()
        assertThat(timer.frame()).isNull()
        assertThat(timer.wake()).isFalse()
        assertThat(timer.updateDelay(60_000)).isEqualTo(60_000)
    }

    @Test
    fun aChangeTakesEffectWithTheNextWake() {
        timer.spec = spec
        timer.frame()
        // changed while a wake plays: this wake keeps its length
        chosen = 15_000
        now += 5999
        assertThat(timer.frame()).isEqualTo(5999)
        now += 1
        assertThat(timer.frame()).isNull()
        // the next wake plays the new length
        assertThat(timer.wake()).isTrue()
        timer.frame()
        assertThat(timer.lengthThisWakeMs).isEqualTo(15_000)
        // turned off between two wakes: no new wake starts
        now += 15_000
        timer.frame()
        chosen = 0
        assertThat(timer.wake()).isFalse()
    }

    @Test
    fun theFadeOutFollowsTheChosenLength() {
        assertThat(spec.envelope(19_700, 20_000)).isWithin(0.001f).of(0.5f)
        assertThat(spec.envelope(10_000, 20_000)).isEqualTo(1f)
        assertThat(spec.envelope(20_000, 20_000)).isEqualTo(0f)
        // without a length the design's own is used
        assertThat(spec.envelope(5700)).isWithin(0.001f).of(0.5f)
    }
}
