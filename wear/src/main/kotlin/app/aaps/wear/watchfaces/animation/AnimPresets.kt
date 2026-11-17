// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * All layers of the wake-up animation: the simple effects of the design (`rain`, `glitch`, `trace`,
 * `blink`, `sweep`, `slide`, `wobble`, `pulse`, `glint`) turned into [AnimLayerSpec] layers, followed by
 * the design's own [WakeAnimationSpec.layers]. So a design stays simple, and one engine
 * ([LayerAnimator]) draws everything.
 *
 * The order is the paint order inside a slot:
 * - back: rain, glitch, then the design's own
 * - views: the view that draws itself in and its bright line, the views that blink, swing, slide or
 *   shake, then the design's own
 * - front: the heart, the light over the shiny parts, then the design's own
 *
 * The formulas are text with the numbers of the design written in.
 */
fun WakeAnimationSpec.allLayers(): List<AnimLayerSpec> {
    val presets = ArrayList<AnimLayerSpec>()
    rain?.let { presets += rainLayer(it) }
    glitch?.let { presets += glitchLayer(it) }
    trace?.let { presets += traceLayers(it) }
    blink.forEach { presets += blinkLayers(it) }
    sweep.forEach { presets += sweepLayers(it) }
    slide.forEach { presets += slideLayers(it) }
    wobble.forEach { presets += wobbleLayers(it) }
    pulse?.let { presets += pulseLayer(it) }
    glint?.let { presets += glintLayers(it) }
    return presets.mapNotNull { it.normalized() } + layers
}

/**
 * Falling rain, as one line shape drawn [RainSpec.count] times. Each streak takes five random numbers,
 * in this order: where it starts (`r1`), how far it has fallen at the start (`r2`), its speed (`r3`),
 * its length (`r4`) and its alpha (`r5`). It falls along the slant and starts again at the top when it
 * has left the region at the bottom. The line starts at the tail and ends at the head.
 */
internal fun rainLayer(rain: RainSpec): AnimLayerSpec {
    val region = rain.region
    val radians = Math.toRadians(rain.angle.toDouble())
    val sinA = sin(radians).toFloat()
    val cosA = cos(radians).toFloat()
    val tanA = tan(radians).toFloat()
    val longest = rain.length * (1 + rain.lengthVariation)
    // How far a head falls before it starts again at the top
    val span = region.height + longest * cosA + 1f
    val pad = longest * abs(sinA)
    val fromX = region.left - max(0f, region.height * tanA) - pad
    val toX = region.right + max(0f, -region.height * tanA) + pad
    val fallen = "mod(r2 * ${n(span)} + ${n(rain.speed)} * (0.8 + 0.4 * r3) * ms / 1000, ${n(span)})"
    val length = "${n(rain.length)} * (1 + ${n(rain.lengthVariation)} * (2 * r4 - 1))"
    return AnimLayerSpec(
        source = LayerSource.Shape(ShapeKind.LINE, listOf(rain.color)),
        slot = LayerSlot.BACK,
        copies = LayerCopies.Count(rain.count, rain.seed),
        props = mapOf(
            LayerProp.X to "${n(fromX)} + r1 * ${n(toX - fromX)} + $fallen * ${n(tanA)} - ($length) * ${n(sinA)}",
            LayerProp.Y to "${n(region.top)} + $fallen - ($length) * ${n(cosA)}",
            LayerProp.LENGTH to length,
            LayerProp.ANGLE to n(rain.angle),
            LayerProp.THICKNESS to n(rain.thickness),
            // The colour's own alpha is added by the layer
            LayerProp.ALPHA to "env * ${n(rain.alpha / 255f)} * (0.45 + 0.55 * r5)"
        ),
        clip = LayerClip(rect = region, round = true)
    )
}

/**
 * Glitch bands, as one rectangle shape drawn [GlitchSpec.bands] times in the two colours by turns.
 * The time is cut into periods; each period has a burst or not, and a burst shows the bands for
 * [GlitchSpec.burstMs] at a random moment of the period. The bands jump sideways every
 * [GLITCH_JUMP_MS] while they show. The random numbers come from `hash` of the seed, the period and a
 * number, so the same seed always gives the same bands.
 */
internal fun glitchLayer(glitch: GlitchSpec): AnimLayerSpec {
    val region = glitch.region
    val seed = n(glitch.seed)
    val period = n(glitch.periodMs)
    fun random(k: String) = "hash($seed, floor(ms / $period), $k)"
    val start = if (glitch.periodMs > glitch.burstMs) "floor(${random("1")} * ${n(glitch.periodMs - glitch.burstMs)})" else "0"
    val inPeriod = "mod(ms, $period)"
    val step = "floor(($inPeriod - $start) / $GLITCH_JUMP_MS)"
    val height = "(${n(glitch.minHeight)} + ${random("2 + i * 4")} * ${n(glitch.maxHeight - glitch.minHeight)})"
    val width = "(${n(glitch.minWidth)} + ${random("3 + i * 4")} * ${n(glitch.maxWidth - glitch.minWidth)})"
    return AnimLayerSpec(
        source = LayerSource.Shape(ShapeKind.RECT, listOf(glitch.color1, glitch.color2)),
        slot = LayerSlot.BACK,
        copies = LayerCopies.Count(glitch.bands, glitch.seed),
        props = mapOf(
            LayerProp.X to "${n(region.left)} - $width / 2 + ${random("5 + i * 4")} * ${n(region.width)} + (mod($step + i, 3) - 1) * ${n(glitch.jump)}",
            LayerProp.Y to "${n(region.top)} + ${random("4 + i * 4")} * max(0, ${n(region.height)} - $height)",
            LayerProp.WIDTH to width,
            LayerProp.HEIGHT to height,
            LayerProp.COLOR to "mod(i, 2)",
            LayerProp.ALPHA to "if(${random("0")} < ${n(glitch.chance)} && $inPeriod >= $start && $inPeriod < $start + ${n(glitch.burstMs)}, ${n(glitch.alpha / 255f)} * env, 0)"
        ),
        clip = LayerClip(rect = region, round = true)
    )
}

/** How often the bands of a glitch burst jump to a new place, in ms. */
internal const val GLITCH_JUMP_MS = 40

/**
 * The heart that beats at the heart rate: its picture, scaled by the pulse, with a glow that follows
 * the beat. It settles to its rest size as the wake fades out.
 */
internal fun pulseLayer(pulse: PulseSpec): AnimLayerSpec {
    val beat = "pulse(ms, bpm, ${n(pulse.secondBeat)}) * env"
    val size = "1 + ${n(pulse.amplitude)} * $beat"
    return AnimLayerSpec(
        source = LayerSource.Image(pulse.image, pulse.drawable, pulse.rect),
        slot = LayerSlot.FRONT,
        props = mapOf(
            LayerProp.SCALE_X to size,
            LayerProp.SCALE_Y to size,
            LayerProp.GLOW to "${n(pulse.glowAlpha / 255f)} * $beat"
        ),
        clip = LayerClip(round = false),
        glow = if (pulse.glowRadius > 0f && pulse.glowAlpha > 0) LayerGlow(pulse.glowColor, pulse.glowRadius) else null,
        maxScale = 1 + pulse.amplitude
    )
}

/** Views that are hidden in the second half of each period while their value is low. A missing value never blinks. */
internal fun blinkLayers(blink: BlinkSpec): List<AnimLayerSpec> {
    val alpha = "if(${LayerVariables.VALUE_PREFIX}${blink.valueKey} <= ${n(blink.atOrBelow)} && mod(ms, ${n(blink.periodMs)}) >= ${n(blink.periodMs / 2)}, 0, 1)"
    return blink.views.map { view -> viewLayer(view, LayerProp.ALPHA to alpha) }
}

/** Hands that swing from the empty end of the scale up to the full end, and back to their value with a bounce. */
internal fun sweepLayers(sweep: SweepSpec): List<AnimLayerSpec> {
    val turn = swing(sweep.emptyAngle, sweep.fullAngle, sweep.delayMs, sweep.upMs, sweep.settleMs, "rot0")
    return sweep.views.map { view -> viewLayer(view, LayerProp.ROTATION to turn) }
}

/** Views that slide from their design place to the end of the scale, and back to their value with a bounce. */
internal fun slideLayers(slide: SlideSpec): List<AnimLayerSpec> {
    val move = swing(0f, slide.fullDx, slide.delayMs, slide.upMs, slide.settleMs, "dx0")
    return slide.views.map { view -> viewLayer(view, LayerProp.X to move) }
}

/** Hands that shake round their value, less and less, and stop after [WobbleSpec.DECAYS] times the decay time. */
internal fun wobbleLayers(wobble: WobbleSpec): List<AnimLayerSpec> {
    val t = "(ms - ${n(wobble.delayMs)})"
    val turn = "if($t <= 0 || $t > ${n(wobble.decayMs * WobbleSpec.DECAYS)}, 0, " +
        "${n(wobble.amplitude)} * exp(-$t / ${n(wobble.decayMs)}) * sin(2 * pi * ${n(wobble.hz)} * $t / 1000))"
    return wobble.views.map { view -> viewLayer(view, LayerProp.ROTATION to turn) }
}

/**
 * The view that draws itself in from the left: cut off right of the part done, hidden before it
 * starts. And its soft bright line at the edge, which fades out over the last eighth of the way.
 */
internal fun traceLayers(trace: TraceSpec): List<AnimLayerSpec> {
    val rect = trace.rect
    val t = "(ms - ${n(trace.delayMs)})"
    val edge = "${n(rect.left)} + ${n(rect.width)} * clamp($t / ${n(trace.durationMs)}, 0, 1)"
    val view = viewLayer(trace.view, LayerProp.CLIP_RIGHT to edge, LayerProp.ALPHA to "if($t > 0, 1, 0)")
    if (trace.width <= 0f || trace.alpha == 0) return listOf(view)
    val beam = AnimLayerSpec(
        source = LayerSource.Shape(ShapeKind.BAND, listOf(trace.color)),
        slot = LayerSlot.VIEWS,
        props = mapOf(
            LayerProp.X to edge,
            LayerProp.Y to n(rect.top),
            LayerProp.WIDTH to n(trace.width),
            LayerProp.HEIGHT to n(rect.height),
            LayerProp.ALPHA to "if($t > 0 && $t < ${n(trace.durationMs)}, ${n(trace.alpha / 255f)} * min(1, (1 - $t / ${n(trace.durationMs)}) * $TRACE_FADE_PARTS), 0)"
        ),
        clip = LayerClip(round = false)
    )
    return listOf(view, beam)
}

/** The bright line of a trace fades over the last 1 / TRACE_FADE_PARTS of the way. */
private const val TRACE_FADE_PARTS = 8

/**
 * The light that slides once over the shiny parts: a soft band, turned by the angle round the middle
 * of the face, that crosses the whole face (its diagonal), seen only on the rings and boxes.
 */
internal fun glintLayers(glint: GlintSpec): List<AnimLayerSpec> {
    if (!glint.hasShape || glint.alpha == 0) return emptyList()
    val centre = DESIGN_SIZE / 2
    val diagonal = DESIGN_SIZE * sqrt(2f)
    val half = glint.width / 2
    val t = "(ms - ${n(glint.delayMs)})"
    return listOf(
        AnimLayerSpec(
            source = LayerSource.Shape(ShapeKind.BAND, listOf(glint.color)),
            slot = LayerSlot.FRONT,
            props = mapOf(
                LayerProp.PIVOT_X to n(centre),
                LayerProp.PIVOT_Y to n(centre),
                LayerProp.ROTATION to n(glint.angle),
                LayerProp.X to "${n(centre - diagonal / 2 - half)} + ${n(diagonal + 2 * half)} * easeInOut($t / ${n(glint.durationMs)})",
                LayerProp.Y to n(centre - diagonal),
                LayerProp.WIDTH to n(glint.width),
                LayerProp.HEIGHT to n(2 * diagonal),
                LayerProp.ALPHA to "if($t >= 0 && $t <= ${n(glint.durationMs)}, ${n(glint.alpha / 255f)}, 0)"
            ),
            clip = LayerClip(round = false, rings = glint.rings, boxes = glint.boxes, cornerRadius = glint.cornerRadius)
        )
    )
}

/** A layer that moves, hides or cuts the view [key]. */
private fun viewLayer(key: String, vararg props: Pair<LayerProp, String>) =
    AnimLayerSpec(source = LayerSource.View(key), slot = LayerSlot.VIEWS, props = mapOf(*props))

/**
 * The formula of a swing, as a change of the own value [own] (`rot0` or `dx0`): waits at [from] for
 * [delayMs], goes to [to] in [upMs], and comes back to the own value in [settleMs] with a bounce.
 */
private fun swing(from: Float, to: Float, delayMs: Long, upMs: Long, settleMs: Long, own: String): String {
    val t = "(ms - ${n(delayMs)})"
    return "if($t <= 0, ${n(from)}, " +
        "if($t < ${n(upMs)}, ${n(from)} + ${n(to - from)} * easeInOut($t / ${n(upMs)}), " +
        "if($t < ${n(upMs + settleMs)}, ${n(to)} + ($own - ${n(to)}) * settle(($t - ${n(upMs)}) / ${n(settleMs)}), $own))) - $own"
}

/** A number as formula text; a negative one in brackets, so it can stand anywhere. Never NaN: that is 0. */
private fun n(value: Float): String = when {
    value.isNaN() || value.isInfinite() -> "0"
    value < 0f                          -> "($value)"
    else                                -> value.toString()
}

private fun n(value: Long): String = if (value < 0) "($value)" else value.toString()

private fun n(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "0"
    value < 0                           -> "($value)"
    else                                -> value.toString()
}
