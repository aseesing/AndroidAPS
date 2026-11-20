// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.DesignRect

/**
 * The pictures that were baked for a converted face, as [WffFaceWriter] needs to know them: their
 * resource names and where they lie, in design pixels.
 *
 * @param statics the static pictures by their run ([WffPlanItem.run]); a run with nothing to see has none
 * @param loops the frame sequences of the wake
 */
data class WffAssets(val statics: Map<Int, Picture> = emptyMap(), val loops: List<Loop> = emptyList()) {

    /** One picture: its resource name and its box. */
    data class Picture(val resource: String, val box: DesignRect)

    /**
     * The frames of some wake layers.
     *
     * @param layers the layers the frames show ([WffPlanItem.layer])
     * @param frames the resource names of the frames, in order
     * @param fps frames per second
     * @param box where the frames lie
     * @param loopCount how often the frames play in one wake
     * @param delaySeconds how long after the wake they start
     * @param blend the WFF `blendMode` of layers that add light (`PLUS`, `SCREEN`): their frames are on
     *   black and mix with what lies below; null for normal see-through frames
     */
    data class Loop(val layers: Set<Int>, val frames: List<String>, val fps: Int, val box: DesignRect, val loopCount: Int = 1, val delaySeconds: Float = 0f, val blend: String? = null)
}
