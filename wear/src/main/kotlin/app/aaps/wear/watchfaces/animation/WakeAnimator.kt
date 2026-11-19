// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.withClip
import androidx.wear.watchface.WatchState
import app.aaps.wear.watchfaces.utils.FrameLayers
import app.aaps.wear.watchfaces.utils.FrameParts
import app.aaps.wear.watchfaces.utils.WakeLayers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The wake-up animation of the Custom watch face: rain or glitch bands, a beating heart, hands that
 * swing or shake, a view that draws itself in and a light over the shiny parts, for a few seconds
 * after the wrist is raised. Private patch, see [WakeAnimationSpec] for what a design can set. All of
 * it is drawn by one [LayerAnimator], from the layers of the design.
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
 * @param value the value of a `dynData` value key, or null when there is none: the `v_<key>` of a
 *   formula. Read once per wake: the battery values change slowly.
 * @param images a picture the app draws itself, by its name, at a clock time (epoch ms), in a square of
 *   so many pixels, for a [LayerSource.Dynamic] layer (`@moon`); null when there is none. The caller
 *   keeps the picture, see [LayerAnimator].
 * @param texts the fonts of the design and what the watch is set to, for the text layers (see [FaceTexts])
 * @param layers the kept pictures of the frame; the watch face shares them with its normal frames
 */
class WakeAnimator(
    private val heartRate: () -> Int?,
    chosenLengthMs: () -> Long? = { null },
    private val value: (String) -> Double? = { null },
    private val images: (String, Long, Int) -> Bitmap? = { _, _, _ -> null },
    private val texts: FaceTexts = object : FaceTexts {},
    private val timer: WakeTimer = WakeTimer(chosenLengthMs) { SystemClock.elapsedRealtime() },
    private val layers: FrameLayers = FrameLayers(),
    private val newScope: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
) {

    private var spec: WakeAnimationSpec? = null
    private var animator: LayerAnimator? = null

    /** False until [animator] has the values of this wake. */
    private var hasWakeValues = false
    private var bpm = PulseCurve.FALLBACK_BPM
    private var isHeadless = false
    private var scope: CoroutineScope? = null

    /** The watch state followed now, and its two collectors, so a second [watch] does not add more. */
    private var watched: WatchState? = null
    private var watchJobs: List<Job> = emptyList()

    /** True while a wake plays, for tests and logging. */
    val isRunning: Boolean get() = timer.isRunning

    /** Frames drawn in the current (or last) wake. */
    val framesThisWake: Int get() = timer.framesThisWake

    /** The heart rate the current wake beats at. */
    val beatsPerMinute: Int get() = bpm

    /** True when the animation of the design reads `bpm`, so the face needs the heart rate of the watch. */
    val readsHeartRate: Boolean get() = animator?.readsHeartRate == true

    /**
     * The animation of the design now shown, or null for none. Called each time the design is read;
     * the same values again keep a running animation going.
     */
    fun setDesign(design: WakeAnimationSpec?) {
        val s = design?.normalized()?.takeIf { it.hasContent }
        // One compare of the values, for the timer and for the animator
        val sameValues = s?.withoutImage() == spec?.withoutImage()
        if (!sameValues || !sameDrawables(s, spec)) {
            animator?.release()
            animator = s?.let { LayerAnimator(it.layers, it.lets, images = images, functions = it.functions, texts = texts) }
            hasWakeValues = false
            layers.invalidate()
        }
        // Layers that only place views at rest are no reason to play a wake
        timer.setSpec(s?.takeIf { animator?.hasWakeContent == true }, sameValues)
        spec = s
    }

    /**
     * Follows the watch: out of ambient or visible again is a wake, into ambient or hidden stops the
     * animation at once.
     */
    fun watch(watchState: WatchState) {
        isHeadless = watchState.isHeadless
        if (isHeadless || watchState === watched) return
        // A new watch state (the face was made again): the collectors of the old one stop
        watchJobs.forEach { it.cancel() }
        watched = watchState
        val s = scope ?: newScope().also { scope = it }
        watchJobs = listOf(
            s.launch { watchState.isAmbient.collect { ambient -> if (ambient == true) timer.stop() else if (ambient == false) timer.wake() } },
            s.launch { watchState.isVisible.collect { visible -> if (visible == false) timer.stop() else if (visible == true) timer.wake() } }
        )
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
     * @param content any value that changes when the drawn face changes, compared with `equals`; a new
     *   value draws the kept pictures again (see [WakeFrameLayers])
     * @param allowed false where the animation must never show (an image render, the simple face);
     *   stops it
     * @param ready false while the face cannot draw its views yet; a wake waits for the first frame
     *   that can
     * @return false when the frame must be drawn the normal way
     */
    fun drawFrame(
        canvas: Canvas, width: Int, height: Int, nowMs: Long, content: Any, complications: List<Any?>,
        parts: FrameParts, allowed: Boolean, ready: Boolean
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
            // A new wake: the heart rate and the values of now. The kept pictures follow the content
            // and the complications on their own, also those of the normal frames meanwhile.
            bpm = PulseCurve.bpmOrFallback(heartRate(), s.fallbackBpm)
            hasWakeValues = false
        }
        if (!hasWakeValues) {
            // Also when the design changed during a wake
            anim.setWakeValues(value, bpm)
            hasWakeValues = true
        }
        anim.setTime(elapsed, timer.lengthThisWakeMs, s.envelope(elapsed, timer.lengthThisWakeMs), nowMs)
        live.set(anim, width / DESIGN_SIZE, height, parts)
        layers.drawWake(canvas, width, height, nowMs, content, complications, parts, anim.viewKeys, live)
        return true
    }

    /** What a wake frame draws live, set for each frame: made once, so a frame makes no objects. */
    private val live = object : WakeLayers {

        private var anim: LayerAnimator? = null
        private var scale = 1f
        private var height = 0
        private var parts: FrameParts? = null

        fun set(anim: LayerAnimator, scale: Float, height: Int, parts: FrameParts) {
            this.anim = anim
            this.scale = scale
            this.height = height
            this.parts = parts
        }

        override fun back(canvas: Canvas) {
            anim?.drawSlot(canvas, LayerSlot.BACK, scale)
        }

        override fun views(canvas: Canvas) {
            val anim = anim ?: return
            drawLive(canvas, anim, scale, height, parts ?: return)
        }

        override fun front(canvas: Canvas) {
            anim?.drawSlot(canvas, LayerSlot.FRONT, scale)
        }
    }

    /**
     * The views slot of a wake frame: its shapes and the views that are drawn live, in the order of the
     * layers. Each group of views (view layers that follow each other) is drawn where it lies: first its
     * views that are cut (a view that draws itself in), each with its cut, then its other views in the
     * paint order of the face, moved, turned, scaled and faded. A hidden view is left out.
     */
    private fun drawLive(canvas: Canvas, anim: LayerAnimator, scale: Float, height: Int, parts: FrameParts) {
        // Read here, after the face has laid its views out for this frame
        val effects = anim.viewEffects(parts)
        anim.drawSlot(canvas, LayerSlot.VIEWS, scale) { c, members ->
            for (i in effects.keys.indices) {
                if (!members[i] || !effects.isClipped(i) || effects.isHidden(i)) continue
                val left = (effects.clipLeft(i) * scale).coerceAtLeast(0f)
                val top = (effects.clipTop(i) * scale).coerceAtLeast(0f)
                val right = (effects.clipRight(i) * scale).coerceAtMost(c.width.toFloat().coerceAtLeast(height.toFloat()))
                val bottom = (effects.clipBottom(i) * scale).coerceAtMost(height.toFloat())
                c.withClip(left, top, right, bottom) { parts.view(this, effects, i) }
            }
            parts.views(c, effects, members)
        }
    }

    /** True when [a] and [b] have the very same picture objects. */
    private fun sameDrawables(a: WakeAnimationSpec?, b: WakeAnimationSpec?): Boolean {
        val first = a?.drawables().orEmpty()
        val second = b?.drawables().orEmpty()
        return first.size == second.size && first.indices.all { first[it] === second[it] }
    }

    private fun WakeAnimationSpec.drawables(): List<Drawable?> =
        layers.flatMap { listOf((it.source as? LayerSource.Image)?.drawable, it.clip.mask?.drawable) }

    /**
     * Draws the layers of [slot] that stay on the face while no animation plays (see
     * [AnimLayerSpec.restSeconds]) onto [canvas] of [width] pixels, at the clock time [nowMs]. Called in
     * a normal frame: the back slot right after the background. Draws nothing while a wake plays: then
     * they are part of the wake frame.
     */
    fun drawRest(canvas: Canvas, width: Int, nowMs: Long, slot: LayerSlot = LayerSlot.BACK) {
        if (timer.isRunning) return
        animator?.drawRest(canvas, width / DESIGN_SIZE, nowMs, slot, value)
    }

    /**
     * Where the layers with a `rest` put the view [key] in a normal frame (see [LayerAnimator.restPlace]),
     * at the clock time [nowMs]; null when no such layer has this view. Asked when the face lays out its
     * views, also in ambient: the view keeps that place in every frame.
     */
    fun restPlace(key: String, rot0: Float, cx0: Double, cy0: Double, nowMs: Long): ViewRest? =
        animator?.restPlace(key, rot0, cx0, cy0, nowMs, value)

    /** Stops watching and frees the pictures. */
    fun release() {
        scope?.cancel()
        scope = null
        watched = null
        watchJobs = emptyList()
        timer.stop()
        layers.release()
        animator?.release()
        // Gone for good: a frame after this must not make new pictures that nobody frees
        animator = null
    }
}
