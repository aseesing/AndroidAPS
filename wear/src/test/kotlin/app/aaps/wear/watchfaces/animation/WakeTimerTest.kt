package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * When the wake-up animation plays, how fast frames come while it plays, and that the face goes back
 * to exactly its normal rate afterwards.
 */
class WakeTimerTest {

    private var now = 1_000_000L
    private val timer = WakeTimer { now }
    private val spec = WakeAnimationSpec(durationMs = 6000, fps = 24, rain = RainSpec())

    @Test
    fun withoutAnAnimationNothingChanges() {
        assertThat(timer.frame()).isNull()
        assertThat(timer.updateDelay(60_000)).isEqualTo(60_000)
        assertThat(timer.updateDelay(1000)).isEqualTo(1000)
        assertThat(timer.wake()).isFalse()
        assertThat(timer.wantsFrames).isFalse()
    }

    @Test
    fun startsWithTheFirstFrameAfterTheDesignIsSet() {
        timer.spec = spec
        assertThat(timer.wantsFrames).isTrue()
        // fast frames are asked for already, so the first frame comes soon
        assertThat(timer.updateDelay(60_000)).isEqualTo(41)
        now += 500
        assertThat(timer.frame()).isEqualTo(0)
        assertThat(timer.isRunning).isTrue()
    }

    @Test
    fun stopsAfterTheDurationAndTheRateGoesBack() {
        timer.spec = spec
        timer.frame()
        now += 5999
        assertThat(timer.frame()).isEqualTo(5999)
        assertThat(timer.updateDelay(60_000)).isEqualTo(41)
        now += 1
        assertThat(timer.frame()).isNull()
        assertThat(timer.isRunning).isFalse()
        assertThat(timer.updateDelay(60_000)).isEqualTo(60_000)
        assertThat(timer.updateDelay(1000)).isEqualTo(1000)
        // and it stays off: later frames are normal frames
        now += 10_000
        assertThat(timer.frame()).isNull()
    }

    @Test
    fun rateIsOneFramePerFpsWhilePlaying() {
        timer.spec = spec.copy(fps = 20)
        timer.frame()
        assertThat(timer.updateDelay(60_000)).isEqualTo(50)
        assertThat(timer.updateDelay(1000)).isEqualTo(50)
        // never slower than the face is anyway
        assertThat(timer.updateDelay(16)).isEqualTo(16)
    }

    @Test
    fun ambientOrHiddenStopsAtOnce() {
        timer.spec = spec
        timer.frame()
        now += 1000
        timer.stop()
        assertThat(timer.isRunning).isFalse()
        assertThat(timer.wantsFrames).isFalse()
        assertThat(timer.frame()).isNull()
        assertThat(timer.updateDelay(60_000)).isEqualTo(60_000)
    }

    @Test
    fun aWakePlaysFromTheStartAgain() {
        timer.spec = spec
        timer.frame()
        now += 2000
        timer.stop()
        now += 30_000            // ambient for a while
        assertThat(timer.wake()).isTrue()
        now += 80                // the first interactive frame
        assertThat(timer.frame()).isEqualTo(0)
        assertThat(timer.framesThisWake).isEqualTo(1)
    }

    @Test
    fun aWakeWhilePlayingDoesNotRestart() {
        timer.spec = spec
        timer.frame()
        now += 300
        // the second signal of the same wrist raise, or a tap during the animation
        assertThat(timer.wake()).isFalse()
        assertThat(timer.frame()).isEqualTo(300)
    }

    @Test
    fun theSameDesignReadAgainDoesNotRestart() {
        timer.spec = spec
        timer.frame()
        now += 1000
        timer.spec = spec.copy()
        assertThat(timer.frame()).isEqualTo(1000)
        // but a different design plays from the start
        timer.spec = spec.copy(fps = 12)
        now += 100
        assertThat(timer.frame()).isEqualTo(0)
    }

    @Test
    fun removingTheAnimationStopsIt() {
        timer.spec = spec
        timer.frame()
        timer.spec = null
        assertThat(timer.frame()).isNull()
        assertThat(timer.updateDelay(60_000)).isEqualTo(60_000)
    }

    /** Runs the frames the renderer would draw for one wake, each at the delay asked for. */
    private fun framesOfOneWake(extraDelay: Long = 0): Int {
        var frames = 0
        repeat(10_000) {
            if (timer.frame() != null) frames++
            now += timer.updateDelay(60_000) + extraDelay
        }
        return frames
    }

    @Test
    fun oneWakeDrawsAtMostFpsTimesDurationFrames() {
        timer.spec = spec
        val frames = framesOfOneWake()
        println("PERF wake animation: one wake drew $frames frames (budget ${spec.maxFrames})")
        assertThat(spec.maxFrames).isEqualTo(144)
        assertThat(frames).isAtMost(144)
        assertThat(frames).isAtLeast(140)
    }

    @Test
    fun extraFramesFromDataDoNotRaiseTheBudget() {
        timer.spec = spec
        var frames = 0
        repeat(1000) {
            if (timer.frame() != null) frames++
            now += 10          // invalidations come much faster than the frame rate
        }
        assertThat(frames).isEqualTo(144)
        assertThat(timer.updateDelay(60_000)).isEqualTo(60_000)
    }

    @Test
    fun aSlowWatchDrawsFewerFramesInTheSameTime() {
        // the library draws at most 10 frames a second when the battery is low
        timer.spec = spec
        val frames = framesOfOneWake(extraDelay = 59)
        assertThat(frames).isEqualTo(60)
    }

    @Test
    fun specLimitsAreKept() {
        val wild = WakeAnimationSpec(durationMs = 100_000, fps = 120, fadeMs = 99_000, rain = RainSpec(count = 5000)).normalized()
        assertThat(wild.durationMs).isEqualTo(WakeAnimationSpec.MAX_DURATION_MS)
        assertThat(wild.fps).isEqualTo(WakeAnimationSpec.MAX_FPS)
        assertThat(wild.fadeMs).isEqualTo(WakeAnimationSpec.MAX_DURATION_MS / 2)
        assertThat(wild.rain?.count).isEqualTo(RainSpec.MAX_COUNT)
        assertThat(wild.maxFrames).isEqualTo(450)
        assertThat(WakeAnimationSpec(rain = RainSpec(count = 0)).normalized().hasContent).isFalse()
        // a heart without its image in the zip is no heart
        assertThat(WakeAnimationSpec(pulse = PulseSpec(image = "missing")).normalized().pulse).isNull()
    }

    @Test
    fun envelopeFadesInAndOut() {
        val s = WakeAnimationSpec(durationMs = 6000, fadeMs = 600)
        assertThat(s.envelope(0)).isEqualTo(0f)
        assertThat(s.envelope(300)).isWithin(0.001f).of(0.5f)
        assertThat(s.envelope(3000)).isEqualTo(1f)
        assertThat(s.envelope(5700)).isWithin(0.001f).of(0.5f)
        assertThat(s.envelope(6000)).isEqualTo(0f)
        assertThat(s.copy(fadeMs = 0).envelope(0)).isEqualTo(1f)
    }
}
