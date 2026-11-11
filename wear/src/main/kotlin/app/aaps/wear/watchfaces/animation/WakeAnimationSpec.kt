package app.aaps.wear.watchfaces.animation

import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlin.math.ceil

/**
 * The wake-up animation of a Custom watch face design: falling rain and a beating heart for a few
 * seconds after the wrist is raised.
 *
 * Plain values only. `CustomWatchface` reads them from the optional `animation` key of the design
 * json (it is the only class that may read that json) and hands them over. All sizes and positions
 * are in the 400 x 400 design space, like every other view of a design.
 *
 * @param durationMs how long one wake plays, unless the watch setting `WakeLength` chooses another length
 * @param fps frames per second while it plays
 * @param fadeMs the rain fades in and out over this time, and the heart settles to its rest size
 */
data class WakeAnimationSpec(
    val durationMs: Long = 6000,
    val fps: Int = 24,
    val fadeMs: Long = 600,
    val rain: RainSpec? = null,
    val pulse: PulseSpec? = null
) {

    /** Values cut to safe limits, so a mistake in a design cannot cost a lot of battery. */
    fun normalized(): WakeAnimationSpec = copy(
        durationMs = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
        fps = fps.coerceIn(1, MAX_FPS),
        fadeMs = fadeMs.coerceIn(0, durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS) / 2),
        rain = rain?.normalized()?.takeIf { it.count > 0 },
        pulse = pulse?.normalized()?.takeIf { it.drawable != null }    // no image in the zip, no heart
    )

    /** The same values without the image object, which is made new each time the design is read. */
    fun withoutImage(): WakeAnimationSpec = copy(pulse = pulse?.copy(drawable = null))

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
    val hasContent: Boolean get() = rain != null || pulse != null

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
        const val JSON_KEY = "animation"

        const val MIN_DURATION_MS = 500L
        const val MAX_DURATION_MS = 30_000L
        const val MAX_FPS = 30
    }
}

/** A rectangle in the 400 x 400 design space. */
data class DesignRect(val left: Float, val top: Float, val width: Float, val height: Float) {

    val right: Float get() = left + width
    val bottom: Float get() = top + height

    companion object {

        /** The whole face. */
        val FULL = DesignRect(0f, 0f, DESIGN_SIZE, DESIGN_SIZE)
    }
}

/** The size of the design space every design is drawn in, scaled to the screen. */
const val DESIGN_SIZE = 400f

/**
 * Falling rain: straight streaks, drawn above the background and below every data view.
 *
 * @param count number of streaks
 * @param seed the same seed always gives the same rain
 * @param speed fall speed in design pixels per second
 * @param angle slant in degrees from straight down; positive falls to the right
 * @param length streak length in design pixels
 * @param lengthVariation each streak is up to this part longer or shorter (0 = all the same)
 * @param thickness line width in design pixels
 * @param color the colour; its own alpha is multiplied with [alpha]
 * @param alpha 0..255
 * @param region where the rain falls; it is also always kept inside the round screen
 */
data class RainSpec(
    val count: Int = 90,
    val seed: Long = 7,
    val speed: Float = 520f,
    val angle: Float = 12f,
    val length: Float = 22f,
    val lengthVariation: Float = 0.4f,
    val thickness: Float = 1.6f,
    val color: Int = Color.rgb(166, 200, 255),
    val alpha: Int = 150,
    val region: DesignRect = DesignRect.FULL
) {

    fun normalized(): RainSpec = copy(
        count = count.coerceIn(0, MAX_COUNT),
        speed = speed.coerceIn(10f, 4000f),
        angle = angle.coerceIn(-45f, 45f),
        length = length.coerceIn(1f, 200f),
        lengthVariation = lengthVariation.coerceIn(0f, 0.9f),
        thickness = thickness.coerceIn(0.5f, 10f),
        alpha = alpha.coerceIn(0, 255)
    )

    companion object {

        const val MAX_COUNT = 300
    }
}

/**
 * A heart that beats at the measured heart rate: the image is drawn in [rect], scaled by the pulse.
 *
 * Tip for the design: keep the heart in the still logo too. The animated heart is never smaller than
 * the still one, so it covers it while it beats, and at the end it settles exactly on top of it.
 *
 * @param image the key of the image in the design zip (the file name without extension)
 * @param drawable the image itself, found by `CustomWatchface` in the zip
 * @param rect where the heart sits at rest
 * @param amplitude how much larger the heart gets at the top of a beat (0.12 = 12 %)
 * @param secondBeat the size of the small second beat, as a part of the first one (0 = none)
 * @param glowColor colour of the glow around the heart
 * @param glowAlpha 0..255, at the top of a beat; the glow follows the beat
 * @param glowRadius blur radius in design pixels, 0 for no glow
 * @param fallbackBpm used when there is no recent heart rate
 */
data class PulseSpec(
    val image: String = "",
    val drawable: Drawable? = null,
    val rect: DesignRect = DesignRect(175f, 40f, 50f, 50f),
    val amplitude: Float = 0.12f,
    val secondBeat: Float = 0.35f,
    val glowColor: Int = Color.rgb(255, 48, 80),
    val glowAlpha: Int = 160,
    val glowRadius: Float = 10f,
    val fallbackBpm: Int = PulseCurve.FALLBACK_BPM
) {

    fun normalized(): PulseSpec = copy(
        amplitude = amplitude.coerceIn(0f, 0.5f),
        secondBeat = secondBeat.coerceIn(0f, 1f),
        glowAlpha = glowAlpha.coerceIn(0, 255),
        glowRadius = glowRadius.coerceIn(0f, 40f),
        fallbackBpm = PulseCurve.clampBpm(fallbackBpm)
    )
}
