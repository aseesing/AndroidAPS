// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.LayerBlend
import app.aaps.wear.watchfaces.wff.WffPictures.Box
import app.aaps.wear.watchfaces.wff.WffPictures.Format
import app.aaps.wear.watchfaces.wff.WffPictures.Pixels
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class WffPicturesTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** A picture [w] x [h] of [fill], with [at] (x, y to colour) set. */
    private fun pixels(w: Int, h: Int, fill: Int, at: Map<Pair<Int, Int>, Int> = emptyMap()) =
        Pixels(w, h, IntArray(w * h) { fill }.also { p -> at.forEach { (xy, c) -> p[xy.second * w + xy.first] = c } })

    @Test
    fun anOpaquePictureIsLossyWebp() {
        assertThat(WffPictures.formatOf(pixels(4, 4, argb(255, 10, 20, 30)))).isEqualTo(Format.WEBP)
        assertThat(WffPictures.WEBP_QUALITY).isEqualTo(80)
    }

    @Test
    fun framesOfLightThatAddsAreLosslessThoughOpaque() {
        // The noise of a lossy picture in their black would add light under PLUS
        assertThat(WffPictures.formatOf(pixels(4, 4, argb(255, 0, 0, 0)), addsLight = true)).isEqualTo(Format.PNG)
    }

    @Test
    fun oneSeeThroughPixelMakesItPng() {
        // The push validator crashes on a lossy WebP with transparency
        assertThat(WffPictures.formatOf(pixels(4, 4, argb(255, 10, 20, 30), mapOf((3 to 3) to argb(254, 10, 20, 30))))).isEqualTo(Format.PNG)
        assertThat(WffPictures.formatOf(pixels(2, 2, 0))).isEqualTo(Format.PNG)
    }

    @Test
    fun matteGivesTheAlphaAndTheColourOfTheFrame() {
        val black = argb(255, 0, 0, 0)
        val white = argb(255, 255, 255, 255)
        // Pixel 0: the background shows fully. Pixel 1: opaque red. Pixel 2: half see-through white.
        // Pixel 3: half see-through red, on black (128, 0, 0) and on white (255, 127, 127)
        val onBlack = Pixels(4, 1, intArrayOf(black, argb(255, 255, 0, 0), argb(255, 128, 128, 128), argb(255, 128, 0, 0)))
        val onWhite = Pixels(4, 1, intArrayOf(white, argb(255, 255, 0, 0), argb(255, 255, 255, 255), argb(255, 255, 127, 127)))
        val out = WffPictures.matte(onBlack, onWhite).argb
        assertThat(out[0]).isEqualTo(0)
        assertThat(out[1]).isEqualTo(argb(255, 255, 0, 0))
        assertThat(out[2]).isEqualTo(argb(128, 255, 255, 255))
        assertThat(out[3]).isEqualTo(argb(128, 255, 0, 0))
    }

    @Test
    fun aFrameThatIsDarkerOnWhiteIsOpaque() {
        // It cannot be, but rounding in the engine can do it: the background does not show at all
        val out = WffPictures.matte(Pixels(1, 1, intArrayOf(argb(255, 200, 200, 200))), Pixels(1, 1, intArrayOf(argb(255, 100, 100, 100)))).argb
        assertThat(out[0]).isEqualTo(argb(255, 200, 200, 200))
    }

    @Test
    fun onlyLayersThatAddLightAreFramedOnBlack() {
        assertThat(WffPictures.lightBlend(LayerBlend.ADD)).isEqualTo("PLUS")
        assertThat(WffPictures.lightBlend(LayerBlend.SCREEN)).isEqualTo("SCREEN")
        assertThat(WffPictures.lightBlend(LayerBlend.NORMAL)).isNull()
        assertThat(WffPictures.lightBlend(LayerBlend.ERASE)).isNull()
        assertThat(WffPictures.lightBlend(null)).isNull()
    }

    @Test
    fun inkBoxIsSnappedOutToTheGrid() {
        val p = pixels(20, 20, 0, mapOf((10 to 4) to argb(1, 0, 0, 0), (12 to 9) to argb(255, 0, 0, 0)))
        assertThat(WffPictures.inkBox(p, 1)).isEqualTo(Box(10, 4, 13, 10))
        assertThat(WffPictures.inkBox(p, 9)).isEqualTo(Box(9, 0, 18, 18))
        // Never past the edge
        assertThat(WffPictures.inkBox(pixels(20, 20, 0, mapOf((19 to 19) to -1)), 9)).isEqualTo(Box(18, 18, 20, 20))
        assertThat(WffPictures.inkBox(pixels(20, 20, 0), 9)).isNull()
    }

    @Test
    fun lightBoxLooksForWhatIsNotBlack() {
        val black = argb(255, 0, 0, 0)
        val p = pixels(10, 10, black, mapOf((3 to 5) to argb(255, 0, 0, 1)))
        assertThat(WffPictures.lightBox(p, 1)).isEqualTo(Box(3, 5, 4, 6))
        assertThat(WffPictures.lightBox(pixels(10, 10, black), 1)).isNull()
        assertThat(WffPictures.inkBox(pixels(10, 10, black), 1)).isEqualTo(Box(0, 0, 10, 10))
        assertThat(WffPictures.shownBox(p, 1, onBlack = true)).isEqualTo(Box(3, 5, 4, 6))
        assertThat(WffPictures.shownBox(p, 1, onBlack = false)).isEqualTo(Box(0, 0, 10, 10))
    }

    @Test
    fun diffBoxIsWhereTwoPicturesDiffer() {
        val a = pixels(10, 10, -1)
        val b = pixels(10, 10, -1, mapOf((2 to 3) to 0, (6 to 7) to argb(255, 1, 1, 1)))
        assertThat(WffPictures.diffBox(a, b, 1)).isEqualTo(Box(2, 3, 7, 8))
        assertThat(WffPictures.diffBox(a, a, 1)).isNull()
    }

    @Test
    fun unionHoldsBothBoxes() {
        assertThat(WffPictures.union(Box(0, 5, 10, 10), Box(5, 0, 20, 8))).isEqualTo(Box(0, 0, 20, 10))
        assertThat(WffPictures.union(null, Box(1, 2, 3, 4))).isEqualTo(Box(1, 2, 3, 4))
        assertThat(WffPictures.union(Box(1, 2, 3, 4), null)).isEqualTo(Box(1, 2, 3, 4))
        assertThat(WffPictures.union(null, null)).isNull()
    }

    @Test
    fun aSnappedBoxOf450PixelsIsWholeInDesignPixels() {
        assertThat(WffPictures.designRect(Box(9, 18, 450, 108), 450)).isEqualTo(DesignRect(8f, 16f, 392f, 80f))
        assertThat(WffPictures.SNAP).isEqualTo(9)
    }
}
