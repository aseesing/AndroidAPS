// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

/**
 * Decides when the wake-up animation plays and how many frames it may draw.
 *
 * - A wake (the face starts, comes out of ambient, becomes visible again, or is tapped) asks for a
 *   start. The animation starts with the next frame that can draw it, so its first frame is at 0 ms.
 * - It stops after its length, or after the most frames for that length, whichever comes first.
 *   [stop] (ambient, hidden) ends it at once. The length is read at the start of each wake: the
 *   watch setting when the user chose one, else the design's own ([WakeAnimationSpec.lengthOf]).
 * - With the watch setting "off" no wake starts.
 * - While it plays, [updateDelay] asks for a frame every `1000 / fps` ms; otherwise it gives back the
 *   normal rate unchanged, so the face draws exactly as many frames as without the animation.
 *
 * All calls come from the main thread, where the watch face renders.
 *
 * @param chosenLengthMs the length the user chose on the watch (see [WakeLength]): null to keep the
 *   design's own, 0 for off
 * @param clock a time that never jumps, in ms (`SystemClock.elapsedRealtime` on the watch); last, so
 *   it can be passed as a trailing lambda
 */
class WakeTimer(private val chosenLengthMs: () -> Long? = { null }, private val clock: () -> Long) {

    /**
     * The animation of the design, or null when it has none. A different one plays from the start
     * with the next frame (a new design is shown); the same one again changes nothing, because the
     * design is read again for reasons that are not a wake.
     */
    var spec: WakeAnimationSpec? = null
        set(value) {
            val same = value?.withoutImage() == field?.withoutImage()
            field = value
            if (same) return
            startedAt = NOT_RUNNING
            pending = value != null && !isOff
        }

    /** True when the user turned the animation off on the watch. */
    private val isOff: Boolean get() = chosenLengthMs() == 0L

    /** How long the current (or last) wake plays, in ms. */
    var lengthThisWakeMs = 0L
        private set

    private var pending = false
    private var startedAt = NOT_RUNNING

    /** Frames drawn in the current (or last) wake. */
    var framesThisWake = 0
        private set

    /** True while a wake has started and not ended. */
    val isRunning: Boolean get() = startedAt != NOT_RUNNING

    /** True while frames should come fast: playing, or waiting for the first frame of a wake. */
    val wantsFrames: Boolean get() = spec != null && (pending || isRunning)

    /**
     * Asks for the animation to play from the start with the next frame. Ignored while it already
     * plays, so the two signals of one wrist raise (out of ambient, visible) cannot restart it.
     *
     * @return true when a new start was asked for, so the caller should ask for a frame
     */
    fun wake(): Boolean {
        if (spec == null || isRunning || pending || isOff) return false
        pending = true
        return true
    }

    /** Ends the animation at once and forgets a start that was asked for. */
    fun stop() {
        pending = false
        startedAt = NOT_RUNNING
    }

    /**
     * Called for a frame that could draw the animation. Returns how long the animation has played,
     * in ms, when this frame is part of it, or null when the frame must be drawn the normal way.
     * Counts the frame.
     */
    fun frame(): Long? {
        val s = spec ?: return null
        val now = clock()
        if (pending) {
            pending = false
            if (isOff) return null
            startedAt = now
            framesThisWake = 0
            lengthThisWakeMs = s.lengthOf(chosenLengthMs())
        }
        if (!isRunning) return null
        val elapsed = now - startedAt
        if (elapsed >= lengthThisWakeMs || framesThisWake >= s.maxFrames(lengthThisWakeMs)) {
            startedAt = NOT_RUNNING
            return null
        }
        framesThisWake++
        return elapsed
    }

    /** The time to the next frame: fast while the animation plays, else [normal]. */
    fun updateDelay(normal: Long): Long {
        val s = spec ?: return normal
        return if (wantsFrames) minOf(s.frameDelayMs, normal) else normal
    }

    private companion object {

        const val NOT_RUNNING = Long.MIN_VALUE
    }
}
