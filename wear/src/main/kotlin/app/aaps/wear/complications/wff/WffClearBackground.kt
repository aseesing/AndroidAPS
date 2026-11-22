// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.complications.wff

import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.shared.impl.weardata.ResFileMap
import java.util.Base64

/**
 * Makes sure the data picture of a Watch Face Format face is see-through behind its parts.
 *
 * The face tool gives `data.zip` a clear background picture, but the contract
 * (`_docs/WFF_FACE_ZIP.md`) does not promise it. Without a background picture the Custom watch face
 * draws its own default background, which is black, and the picture would then hide the whole art
 * of the face. So the watch puts a clear picture in its place, whatever the zip holds.
 */
internal object WffClearBackground {

    /** The background pictures of a design (`ResFileMap`): the normal one, and the ones for a high and a low glucose. */
    private val BACKGROUNDS = listOf(ResFileMap.BACKGROUND, ResFileMap.BACKGROUND_HIGH, ResFileMap.BACKGROUND_LOW).map { it.fileName }

    /** A PNG of one clear pixel (RGBA 0, 0, 0, 0), the same picture the face tool writes. */
    val CLEAR_PNG: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNgAAIAAAUAAen63NgAAAAASUVORK5CYII=")

    /** A copy of [design] with [CLEAR_PNG] as its only background picture. */
    fun of(design: CwfData): CwfData {
        val res = design.resData.toMutableMap()
        BACKGROUNDS.forEach { res.remove(it) }
        res[BACKGROUNDS.first()] = ResData(CLEAR_PNG, ResFormat.PNG)
        return CwfData(design.json, design.metadata, res)
    }
}
