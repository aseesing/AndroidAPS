// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.withClip
import androidx.wear.watchface.WatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The wake-up animation of the Custom watch face: rain or glitch bands, a beating heart, hands that
 * swing or shake, a view that draws itself in and a light over the shiny parts, for a few seconds
 * after the wrist is raised. Private patch, see [WakeAnimationSpec] for what a design can set. All of
 * it is drawn by one [LayerAnimator], from the layers of [allLayers].
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
 *   blink ([BlinkSpec]) and is the `v_<key>` of a formula. Read once per wake: the battery values
 *   change slowly.
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
    private var animator: LayerAnimator? = null

    /** False until [animator] has the values of this wake. */
    private var hasWakeValues = false
    private var bpm = PulseCurve.FALLBACK_BPM
    private var isHeadless = false
    private var scope: CoroutineScope? = null

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
        if (s?.withoutImage() != spec?.withoutImage() || !sameDrawables(s, spec)) {
            animator?.release()
            animator = s?.let { LayerAnimator(it.allLayers()) }
            hasWakeValues = false
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
        val anim = animator ?: return false
        if (timer.framesThisWake == 1) {
            // A new wake: the heart rate and the values of now, and pictures drawn fresh, since the face
            // may have changed in ways the kept states do not show while it was drawn the normal way.
            bpm = PulseCurve.bpmOrFallback(heartRate(), s.pulse?.fallbackBpm ?: PulseCurve.FALLBACK_BPM)
            hasWakeValues = false
            layers.invalidate()
        }
        if (!hasWakeValues) {
            // Also when the design changed during a wake
            anim.setWakeValues(value, bpm)
            hasWakeValues = true
        }
        val scale = width / DESIGN_SIZE
        anim.setTime(elapsed, timer.lengthThisWakeMs, s.envelope(elapsed, timer.lengthThisWakeMs), nowMs)
        layers.draw(
            canvas, width, height, nowMs, content, complications, parts,
            rain = { c -> anim.drawSlot(c, LayerSlot.BACK, scale) },
            heart = { c -> anim.drawSlot(c, LayerSlot.FRONT, scale) },
            leaveOut = anim.viewKeys,
            live = { c -> drawLive(c, anim, scale, height, parts) }
        )
        return true
    }

    /**
     * The views that are drawn live, as the view layers have them now: first the views that are cut at
     * the right (a view that draws itself in), then the shapes of the views slot (the bright line of
     * that view), then the other views, turned and moved. A hidden view is left out.
     */
    private fun drawLive(canvas: Canvas, anim: LayerAnimator, scale: Float, height: Int, parts: WakeFrameParts) {
        // Read here, after the face has laid its views out for this frame
        val effects = anim.viewEffects(parts::rotation, parts::offsetX)
        for ((key, right) in effects.clipRights) {
            if (key in effects.hidden) continue
            canvas.withClip(0f, 0f, right * scale, height.toFloat()) {
                parts.views(this, setOf(key), effects.turns.filterKeys { it == key }, effects.shifts.filterKeys { it == key })
            }
        }
        anim.drawSlot(canvas, LayerSlot.VIEWS, scale)
        val views = anim.viewKeys - effects.clipRights.keys - effects.hidden
        if (views.isNotEmpty()) parts.views(canvas, views, effects.turns, effects.shifts)
    }

    /** True when [a] and [b] have the very same picture objects. */
    private fun sameDrawables(a: WakeAnimationSpec?, b: WakeAnimationSpec?): Boolean {
        val first = a?.drawables().orEmpty()
        val second = b?.drawables().orEmpty()
        return first.size == second.size && first.indices.all { first[it] === second[it] }
    }

    private fun WakeAnimationSpec.drawables(): List<Drawable?> =
        listOf(pulse?.drawable) + layers.flatMap { listOf((it.source as? LayerSource.Image)?.drawable, it.clip.mask?.drawable) }

    /**
     * Draws the layers that stay on the face while no animation plays (see [AnimLayerSpec.restSeconds])
     * onto [canvas] of [width] pixels, at the clock time [nowMs]. Called right after the background of a
     * normal frame. Draws nothing while a wake plays: then they are part of the wake frame.
     */
    fun drawRest(canvas: Canvas, width: Int, nowMs: Long) {
        if (timer.isRunning) return
        animator?.drawRest(canvas, width / DESIGN_SIZE, nowMs, value)
    }

    /** Stops watching and frees the pictures. */
    fun release() {
        scope?.cancel()
        scope = null
        timer.stop()
        layers.release()
        animator?.release()
        // Gone for good: a frame after this must not make new pictures that nobody frees
        animator = null
    }
}
