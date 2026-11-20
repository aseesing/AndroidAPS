// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.wffconvert

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import app.aaps.wear.watchfaces.wff.WffPictures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The picture rules of the converter ([WffPictures]) on real bitmaps: the file format and the 1 pixel backgrounds. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class WffPicturesBitmapTest {

    private fun filled(colour: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }

    private fun ascii(bytes: ByteArray, from: Int, to: Int) = String(bytes.copyOfRange(from, to), Charsets.US_ASCII)

    @Test
    fun anOpaqueBitmapIsWrittenAsWebp() {
        val encoded = WffPictures.encode(filled(Color.rgb(10, 120, 200)))
        assertThat(encoded.format).isEqualTo(WffPictures.Format.WEBP)
        assertThat(ascii(encoded.bytes, 0, 4)).isEqualTo("RIFF")
        assertThat(ascii(encoded.bytes, 8, 12)).isEqualTo("WEBP")
    }

    @Test
    fun aSeeThroughBitmapIsWrittenAsPng() {
        val bitmap = filled(Color.rgb(10, 120, 200)).apply { setPixel(3, 3, Color.argb(200, 10, 120, 200)) }
        val encoded = WffPictures.encode(bitmap)
        assertThat(encoded.format).isEqualTo(WffPictures.Format.PNG)
        assertThat(ascii(encoded.bytes, 1, 4)).isEqualTo("PNG")
        // A PNG keeps every pixel
        val back = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)
        assertThat(back.getPixel(3, 3)).isEqualTo(Color.argb(200, 10, 120, 200))
    }

    @Test
    fun pixelsGoToABitmapAndBack() {
        val pixels = WffPictures.Pixels(2, 1, intArrayOf(Color.argb(128, 255, 0, 0), Color.BLUE))
        assertThat(WffPictures.pixelsOf(WffPictures.bitmapOf(pixels)).argb).isEqualTo(pixels.argb)
    }

    @Test
    fun theBackgroundsAreOnePixelOfTheirColour() {
        fun pixelOf(png: ByteArray): Int = BitmapFactory.decodeByteArray(png, 0, png.size).also { assertThat(it.width to it.height).isEqualTo(1 to 1) }.getPixel(0, 0)
        assertThat(pixelOf(WffPictures.BLACK_PNG)).isEqualTo(Color.BLACK)
        assertThat(pixelOf(WffPictures.WHITE_PNG)).isEqualTo(Color.WHITE)
        assertThat(pixelOf(WffPictures.CLEAR_PNG)).isEqualTo(Color.TRANSPARENT)
    }
}
