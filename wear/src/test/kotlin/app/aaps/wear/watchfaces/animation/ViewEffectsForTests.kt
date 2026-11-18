// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

/** What [ViewEffects] says for one frame, as maps by view key, so a test can compare it whole. */
data class ViewEffectMaps(
    val turns: Map<String, Float>,
    val shifts: Map<String, Float>,
    val hidden: Set<String>,
    val clipRights: Map<String, Float>,
    val shiftsY: Map<String, Float> = emptyMap(),
    val scales: Map<String, Pair<Float, Float>> = emptyMap(),
    val alphas: Map<String, Float> = emptyMap()
)

fun ViewEffects.asMaps(): ViewEffectMaps {
    val turns = LinkedHashMap<String, Float>()
    val shifts = LinkedHashMap<String, Float>()
    val hidden = LinkedHashSet<String>()
    val clipRights = LinkedHashMap<String, Float>()
    val shiftsY = LinkedHashMap<String, Float>()
    val scales = LinkedHashMap<String, Pair<Float, Float>>()
    val alphas = LinkedHashMap<String, Float>()
    for ((i, key) in keys.withIndex()) {
        if (isTurned(i)) turns[key] = turn(i)
        if (isMoved(i) && dx(i) != 0f) shifts[key] = dx(i)
        if (isMoved(i) && dy(i) != 0f) shiftsY[key] = dy(i)
        if (isScaled(i)) scales[key] = scaleX(i) to scaleY(i)
        if (isHidden(i)) hidden += key
        else if (alpha(i) < 1f) alphas[key] = alpha(i)
        if (isClipped(i) && clipRight(i).isFinite()) clipRights[key] = clipRight(i)
    }
    return ViewEffectMaps(turns, shifts, hidden, clipRights, shiftsY, scales, alphas)
}

/** The view places of a test face: [rotation] and [offsetX] give null for a view the face does not have; [centre] its middle. */
fun viewPlaces(rotation: (String) -> Float?, offsetX: (String) -> Float?, centre: (String) -> Pair<Float, Float>? = { null }): ViewPlaces = object : ViewPlaces {
    override fun rotation(key: String): Float = rotation(key) ?: Float.NaN
    override fun offsetX(key: String): Float = offsetX(key) ?: Float.NaN
    override fun centreX(key: String): Float = centre(key)?.first ?: Float.NaN
    override fun centreY(key: String): Float = centre(key)?.second ?: Float.NaN
}

/** [LayerAnimator.viewEffects] for the views of [rotation] and [offsetX], as maps. */
fun LayerAnimator.viewEffects(rotation: (String) -> Float?, offsetX: (String) -> Float?): ViewEffectMaps =
    viewEffects(viewPlaces(rotation, offsetX)).asMaps()
