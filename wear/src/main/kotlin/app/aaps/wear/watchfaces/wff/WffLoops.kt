// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import kotlin.math.ceil

/**
 * Which frames of the wake a converted face gets, and how they play ([WffAssets.Loop]). The baker
 * only draws the moments this class asks for.
 *
 * All frames of one face share one budget ([MAX_FRAMES]), because every frame is a picture in the
 * APK and the face must stay small enough to push to the watch. One instance plans one face: it
 * keeps what is left of the budget.
 *
 * All times are in ms after the start of the wake. Note for whoever draws the frames: the wake timer
 * of the engine starts at the first frame drawn, not when the design is shown. So draw one frame at
 * the start of the wake first, or every frame comes out too early (the harness of the designs
 * repository does this in `WffBaker.wakeOn`).
 *
 * @param wakeMs how long one wake plays
 * @param fadeMs how long the wake fades in
 */
class WffLoops(private val wakeMs: Long, private val fadeMs: Long) {

    /** From when to when a layer shows something in the wake. */
    data class Span(val fromMs: Long, val toMs: Long)

    /** Layers whose frames are made together: [span] is null for layers that show for the whole wake. */
    data class Group(val layers: Set<Int>, val span: Span?)

    /**
     * The moments to draw for one loop.
     *
     * @param fps the frames per second it plays at
     * @param atMs the moments to draw, after the start of the wake
     * @param span when the layers show, or null for a short loop that plays over the whole wake
     * @param delayMs how long after the start of the wake the frames start to play
     * @param once true when the frames play once, false for a short loop that repeats
     */
    data class Take(
        val fps: Int, val atMs: List<Long>, val span: Span?, val cut: Boolean = false,
        val delayMs: Long = span?.fromMs ?: 0L, val once: Boolean = span != null
    ) {

        /** How long after the start of the wake the frames start to play. */
        val delaySeconds: Float get() = delayMs / 1000f
    }

    /**
     * A loop of the whole wake with the fade of the wake: [fadeIn] plays once at the start, then [loop]
     * repeats, then [fadeOut] plays once.
     */
    data class Faded(val fadeIn: Take, val loop: Take, val fadeOut: Take)

    /** How many frames are left for this face. */
    var budget: Int = MAX_FRAMES
        private set

    /** Takes [frames] from the budget. */
    fun spend(frames: Int) {
        budget -= frames
    }

    /**
     * The moments of a static picture in motion: from the start of the wake to its end, so it starts
     * and ends at rest, at [RUN_FPS] or slower so that it takes at most [share] of the budget; null
     * when fewer than 2 frames fit.
     */
    fun runTake(share: Double = RUN_SHARE): Take? {
        val allowed = (budget * share).toInt()
        val fps = minOf(RUN_FPS.toDouble(), (allowed - 1) * 1000.0 / maxOf(wakeMs, 1L))
        if (fps <= 0.0 || allowed < 2) return null
        val count = (wakeMs * fps / 1000).toInt() + 1
        if (count < 2) return null
        val stepMs = wakeMs.toDouble() / (count - 1)
        return Take(fps = maxOf(1, Math.round(fps).toInt()), atMs = (0 until count).map { Math.round(it * stepMs) }, span = null)
    }

    /** The moments to look at, to find when a layer shows ([span]). */
    fun spanTimes(): List<Long> = (0..wakeMs / SPAN_STEP_MS).map { it * SPAN_STEP_MS }

    /**
     * When a layer shows, from the moments of [spanTimes] at which it showed something ([seenMs]);
     * null when it never does. A step is added on each side: the layer may show a little before and
     * after the moments that were looked at.
     */
    fun span(seenMs: List<Long>): Span? {
        if (seenMs.isEmpty()) return null
        return Span(maxOf(0L, seenMs.first() - SPAN_STEP_MS), minOf(wakeMs, seenMs.last() + SPAN_STEP_MS))
    }

    /** True when [span] is so long that the layer counts as showing for the whole wake. */
    fun isWholeWake(span: Span): Boolean = span.toMs - span.fromMs >= wakeMs * WHOLE_WAKE

    /**
     * The loops for the layers that show in the wake ([shown]: the layer and its span), in the order
     * to make them. Layers that show for the whole wake share one short loop per slot and blend
     * ([blends], see [WffPictures.lightBlend]): layers in one loop are one picture, so they must lie
     * at the same depth and mix in the same way. A layer that shows for only a part of the wake (Santa)
     * plays once, in a loop of its own. The loops of the whole wake come first: they are short and seen
     * at every wake, so they get the budget first.
     */
    fun groups(shown: Map<Int, Span>, plan: WffPlan, blends: Map<Int, String?>): List<Group> {
        val whole = shown.filterValues { isWholeWake(it) }.keys
        val wholeGroups = whole.groupBy { layer -> plan.items.first { it.layer == layer }.slot to blends[layer] }.values.map { Group(it.toSet(), null) }
        val partGroups = (shown.keys - whole).map { Group(setOf(it), shown.getValue(it)) }
        return (wholeGroups + partGroups).sortedWith(compareBy({ it.span != null }, { it.layers.min() }))
    }

    /**
     * The moments to draw for layers that show during [span], or for the whole wake when it is null;
     * null when the budget has fewer than 2 frames left.
     *
     * A loop of the whole wake is at most [LOOP_MS] long at [LOOP_FPS], and is taken after the fade in,
     * so it has the full strength. When the wake fades, its length fits a whole number of times between
     * the fade in and the fade out (see [faded]). A layer that shows for a part of the wake gets as many
     * frames as the budget allows, at most [PART_FPS].
     */
    fun take(span: Span?): Take? {
        val seconds = if (span == null) loopMs() / 1000.0 else (span.toMs - span.fromMs) / 1000.0
        val fps = if (span == null) LOOP_FPS else (budget / maxOf(seconds, 0.1)).toInt().coerceIn(1, PART_FPS)
        val wanted = ceil(seconds * fps).toInt()
        val count = minOf(budget, wanted)
        if (count < 2) return null
        val start = span?.fromMs ?: fade()
        return Take(fps, (0 until count).map { start + it * 1000L / fps }, span, cut = count < wanted)
    }

    /**
     * The fade of the wake for [loop], a loop of the whole wake from [take]. Our engine fades with `env`
     * at the start and the end of the wake; WFF has no time of the wake, so the frames of the fade in
     * play once before the loop and the frames of the fade out once after it, at [LOOP_FPS]. Null when
     * the wake does not fade, or when the budget has no room for the fade next to the loop: the loop
     * then shows at full strength from the start of the wake to its end.
     */
    fun faded(loop: Take): Faded? {
        val fade = fade()
        if (loop.span != null || fade <= 0L) return null
        val count = ceil(fade * LOOP_FPS / 1000.0).toInt()
        if (budget - loop.atMs.size < 2 * count) return null
        val repeating = loop.copy(delayMs = fade)
        val endMs = fade + loopCount(repeating, loop.atMs.size) * loop.atMs.size * 1000L / loop.fps
        return Faded(
            fadeIn = Take(LOOP_FPS, (0 until count).map { it * 1000L / LOOP_FPS }, null, delayMs = 0L, once = true),
            loop = repeating,
            fadeOut = Take(LOOP_FPS, (0 until count).map { wakeMs - fade + it * 1000L / LOOP_FPS }, null, delayMs = endMs, once = true)
        )
    }

    /**
     * How often the [frames] of [take] play in one wake: a part of the wake plays once, a short loop
     * repeats until the wake ends, and a short loop after a fade in ([faded]) until the fade out starts.
     */
    fun loopCount(take: Take, frames: Int): Int {
        if (take.once) return 1
        val loopMs = frames * 1000.0 / take.fps
        return if (take.delayMs > 0L) Math.round((wakeMs - 2 * take.delayMs) / loopMs).toInt().coerceAtLeast(1)
        else ceil(wakeMs / loopMs).toInt().coerceAtLeast(1)
    }

    /** How long the wake fades in and out: at most a quarter of the wake each. */
    private fun fade(): Long = minOf(fadeMs, wakeMs / 4).coerceAtLeast(0L)

    /** The length of a loop of the whole wake: at most [LOOP_MS], and a whole number of times between the fade in and the fade out. */
    private fun loopMs(): Long {
        val between = wakeMs - 2 * fade()
        if (fade() <= 0L || between <= 0L) return minOf(LOOP_MS, wakeMs)
        return between / ceil(between.toDouble() / LOOP_MS).toLong()
    }

    companion object {

        /** All frames of the wake loops of one face together. */
        const val MAX_FRAMES = 200

        /** The rate of a static picture in motion, for the whole wake. Low, because these frames are large. */
        const val RUN_FPS = 4

        /** The highest rate of a layer that shows for a part of the wake (Santa). */
        const val PART_FPS = 8

        /** The length of a loop of layers that show for the whole wake. */
        const val LOOP_MS = 2000L

        /** The rate of a loop of layers that show for the whole wake. */
        const val LOOP_FPS = 15

        /** A layer that shows for at least this part of the wake counts as showing for the whole wake. */
        const val WHOLE_WAKE = 0.8

        /** The most of the budget that one static picture in motion may take: its frames are large. */
        const val RUN_SHARE = 0.4

        /** How closely the wake is looked at to find when a layer shows. */
        const val SPAN_STEP_MS = 500L

        /**
         * True when the static picture [run] can be shown in motion in the wake, with [items] the
         * layers of it that move.
         *
         * Only picture 0: it holds the background, so it is opaque, and its frames hide it fully while
         * they play. The frames of a later, see-through picture cannot hide the picture under them:
         * where a moved part leaves a hole, the rest pose would show through, so the part would be seen
         * twice. Such a picture keeps its rest pose.
         */
        fun runCanMove(run: Int): Boolean = run == 0
    }
}
