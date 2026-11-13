// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Canvas
import android.os.SystemClock
import androidx.wear.watchface.WatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The wake-up animation of the Custom watch face: rain or glitch bands, and a beating heart, for a
 * few seconds after the wrist is raised. Private patch, see [WakeAnimationSpec] for what a design can set.
 *
 * The watch face hands over the design's values ([setDesign]), the watch state ([watch]), asks for
 * each plain interactive frame whether this one draws it ([drawFrame]), and asks for the time to the
 * next frame ([updateDelay]). When the animation is not playing, all of these change nothing: the
 * face draws the same frames at the same rate as without it.
 *
 * Never plays on a headless instance (the editor and the system's preview), and the watch face does
 * not ask for an image render, the simple face, ambient, or the editor's highlight frames.
 *
 * @param heartRate the latest measured heart rate, or null; read once per wake
 * @param chosenLengthMs the length chosen in the watch setting (see [WakeLength]); read once per wake
 * @param value the value of a `dynData` value key, or null when there is none; decides which views
 *   blink ([BlinkSpec]). Read once per wake: the battery values change slowly.
 */
class WakeAnimator(
    private val heartRate: () -> Int?,
    chosenLengthMs: () -> Long? = { null },
    private val value: (String) -> Double? = { null },
    private val timer: WakeTimer = WakeTimer(chosenLengthMs) { SystemClock.elapsedRealtime() },
    private val layers: WakeFrameLayers = WakeFrameLayers(),
    private val newScope: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
) {

    private var spec: WakeAnimationSpec? = null
    private var rain: RainField? = null
    private var glitch: GlitchField? = null
    private var pulse: HeartPulse? = null
    private var bpm = PulseCurve.FALLBACK_BPM
    private var isHeadless = false
    private var scope: CoroutineScope? = null

    /** The blinks whose value is low in this wake. */
    private var lowBlinks: List<BlinkSpec> = emptyList()

    /** True while a wake plays, for tests and logging. */
    val isRunning: Boolean get() = timer.isRunning

    /** Frames drawn in the current (or last) wake. */
    val framesThisWake: Int get() = timer.framesThisWake

    /** The heart rate the current wake beats at. */
    val beatsPerMinute: Int get() = bpm

    /**
     * The animation of the design now shown, or null for none. Called each time the design is read;
     * the same values again keep a running animation going.
     */
    fun setDesign(design: WakeAnimationSpec?) {
        val s = design?.normalized()?.takeIf { it.hasContent }
        timer.spec = s
        if (s?.withoutImage() != spec?.withoutImage() || s?.pulse?.drawable !== spec?.pulse?.drawable) {
            rain = s?.rain?.let { RainField(it) }
            glitch = s?.glitch?.let { GlitchField(it) }
            pulse?.release()
            pulse = s?.pulse?.let { HeartPulse(it) }
            layers.invalidate()
        }
        spec = s
        if (s == null) layers.release()
    }

    /**
     * Follows the watch: out of ambient or visible again is a wake, into ambient or hidden stops the
     * animation at once.
     */
    fun watch(watchState: WatchState) {
        isHeadless = watchState.isHeadless
        if (isHeadless) return
        val s = scope ?: newScope().also { scope = it }
        s.launch { watchState.isAmbient.collect { ambient -> if (ambient == true) timer.stop() else if (ambient == false) timer.wake() } }
        s.launch { watchState.isVisible.collect { visible -> if (visible == false) timer.stop() else if (visible == true) timer.wake() } }
    }

    /**
     * A tap on the face: plays the animation again when it is not playing.
     *
     * @return true when it will play, so the caller should ask for a frame
     */
    fun wakeOnTap(): Boolean = !isHeadless && timer.wake()

    /** The time to the next interactive frame: fast while the animation plays, else [normal] unchanged. */
    fun updateDelay(normal: Long): Long = if (isHeadless) normal else timer.updateDelay(normal)

    /**
     * Draws this frame with the animation when it plays.
     *
     * @param allowed false where the animation must never show (an image render, the simple face);
     *   stops it
     * @param ready false while the face cannot draw its views yet; a wake waits for the first frame
     *   that can
     * @return false when the frame must be drawn the normal way
     */
    fun drawFrame(
        canvas: Canvas, width: Int, height: Int, nowMs: Long, content: Int, complications: List<Any?>,
        parts: WakeFrameParts, allowed: Boolean, ready: Boolean
    ): Boolean {
        if (!allowed || isHeadless) {
            timer.stop()
            return false
        }
        if (!ready) return false
        val elapsed = timer.frame() ?: return false
        val s = spec ?: return false
        if (timer.framesThisWake == 1) {
            // A new wake: the heart rate of now, and pictures drawn fresh, since the face may have
            // changed in ways the kept states do not show while it was drawn the normal way.
            bpm = PulseCurve.bpmOrFallback(heartRate(), s.pulse?.fallbackBpm ?: PulseCurve.FALLBACK_BPM)
            lowBlinks = s.blink.filter { it.isLow(value(it.valueKey)) }
            layers.invalidate()
        }
        val scale = width / DESIGN_SIZE
        val strength = s.envelope(elapsed, timer.lengthThisWakeMs)
        layers.draw(
            canvas, width, height, nowMs, content, complications, parts,
            rain = { c ->
                rain?.draw(c, elapsed, scale, strength)
                glitch?.draw(c, elapsed, scale, strength)
            },
            heart = { c -> pulse?.let { it.draw(c, PulseCurve.value(elapsed, bpm, s.pulse?.secondBeat ?: 0f) * strength, scale) } },
            blinkViews = s.blinkViews,
            shown = hiddenAt(elapsed).let { hidden -> if (hidden.isEmpty()) s.blinkViews else s.blinkViews - hidden }
        )
        return true
    }

    /**
     * The views that are hidden [elapsedMs] after the start of this wake: those of a low value, in the
     * off half of a blink. None when no wake plays.
     */
    internal fun hiddenAt(elapsedMs: Long): Set<String> =
        if (!timer.isRunning) emptySet() else lowBlinks.filter { it.isOff(elapsedMs) }.flatMapTo(HashSet()) { it.views }

    /** Stops watching and frees the pictures. */
    fun release() {
        scope?.cancel()
        scope = null
        timer.stop()
        layers.release()
        pulse?.release()
    }
}
