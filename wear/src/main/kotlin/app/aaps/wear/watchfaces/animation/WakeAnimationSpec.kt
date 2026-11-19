// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import app.aaps.shared.impl.weardata.CwfPlaceNeeds
import kotlin.math.ceil

/**
 * The wake-up animation of a Custom watch face design: for a few seconds after the wrist is raised,
 * and the layers that stay on the face while no wake plays.
 *
 * Everything is a layer of the one layer system ([AnimLayerSpec]), drawn by [LayerAnimator]. An effect
 * a design uses often (rain, a heart that beats, hands that swing, ...) is a template of the design
 * that expands to such layers when the design is read (see [Templates]).
 *
 * Plain values only. `CustomWatchface` reads them from the optional `animation` key of the design
 * json (see [WakeAnimationJson]) and hands them over. All sizes and positions are in the 400 x 400
 * design space, like every other view of a design.
 *
 * @param durationMs how long one wake plays, unless the watch setting `WakeLength` chooses another length
 * @param fps frames per second while it plays
 * @param fadeMs the variable `env` rises over this time at the start and falls over it at the end
 * @param fallbackBpm the heart rate (`bpm`) when the watch has measured none
 * @param lets named formulas of the animation, in order, worked out once per frame and usable in
 *   every layer (the `let` of the animation)
 * @param layers the layers, in paint order inside each slot
 * @param errors what could not be read (an unknown template, a wrong parameter), for the design tests
 */
data class WakeAnimationSpec(
    val durationMs: Long = 6000,
    val fps: Int = 24,
    val fadeMs: Long = 600,
    val fallbackBpm: Int = PulseCurve.FALLBACK_BPM,
    val lets: List<Pair<String, String>> = emptyList(),
    val layers: List<AnimLayerSpec> = emptyList(),
    val errors: List<String> = emptyList()
) {

    /** Values cut to safe limits, so a mistake in a design cannot cost a lot of battery. */
    fun normalized(): WakeAnimationSpec = copy(
        durationMs = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
        fps = fps.coerceIn(1, MAX_FPS),
        fadeMs = fadeMs.coerceIn(0, durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS) / 2),
        fallbackBpm = PulseCurve.clampBpm(fallbackBpm),
        lets = lets.take(AnimLayerSpec.MAX_LETS),
        layers = layers.mapNotNull { it.normalized() }.take(AnimLayerSpec.MAX_LAYERS)
    )

    /** The same values without the image objects, which are made new each time the design is read. */
    fun withoutImage(): WakeAnimationSpec = copy(layers = layers.map { it.withoutImage() })

    /**
     * How strong the animation is [elapsedMs] after the start of a wake that plays [lengthMs]: rises
     * from 0 to 1 over [fadeMs], and falls back to 0 over the last [fadeMs].
     */
    fun envelope(elapsedMs: Long, lengthMs: Long = durationMs): Float {
        if (fadeMs <= 0) return 1f
        val fadeIn = elapsedMs.toFloat() / fadeMs
        val fadeOut = (lengthMs - elapsedMs).toFloat() / fadeMs
        return minOf(fadeIn, fadeOut, 1f).coerceIn(0f, 1f)
    }

    /** True when there is something to draw at all. */
    val hasContent: Boolean get() = layers.isNotEmpty()

    /** Time between two frames while the animation plays. */
    val frameDelayMs: Long get() = (1000L / fps).coerceAtLeast(1)

    /** The most frames one wake may draw. */
    val maxFrames: Int get() = maxFrames(durationMs)

    /** The most frames a wake that plays [lengthMs] may draw. */
    fun maxFrames(lengthMs: Long): Int = ceil(lengthMs * fps / 1000.0).toInt()

    /**
     * How long a wake plays: [chosenMs] (the watch setting) when there is one, else [durationMs], and
     * always inside the safe limits. Never 0: "off" is handled before a wake starts.
     */
    fun lengthOf(chosenMs: Long?): Long = (chosenMs?.takeIf { it > 0 } ?: durationMs).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

    companion object {

        /** The key of the animation in the design json. */
        const val JSON_KEY = CwfPlaceNeeds.ANIMATION

        const val MIN_DURATION_MS = 500L
        const val MAX_DURATION_MS = 30_000L
        const val MAX_FPS = 30
    }
}
