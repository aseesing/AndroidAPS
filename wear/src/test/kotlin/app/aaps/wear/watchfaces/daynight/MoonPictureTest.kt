// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.graphics.createBitmap
import app.aaps.wear.R
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MoonPictureTest {

    private val size = 64

    /** A photo that is white, with a red top half: red marks the moon's north. */
    private fun photo(): Bitmap = createBitmap(size, size).apply {
        for (y in 0 until size) for (x in 0 until size) setPixel(x, y, if (y < size / 2) Color.RED else Color.WHITE)
    }

    private fun brightness(pixel: Int) = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))

    /** The part of the disc that is lit: pixels brighter than half way. */
    private fun litPart(image: Bitmap): Double {
        var disc = 0
        var lit = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (hypot(x + 0.5 - size / 2.0, y + 0.5 - size / 2.0) > size / 2.0 - 1) continue
            val pixel = image.getPixel(x, y)
            disc++
            if (brightness(pixel) > 128) lit++
        }
        return lit.toDouble() / disc
    }

    @Test
    fun aQuarterIsHalfLitOnTheSideOfTheSun() {
        // The lit side to the right (270: a quarter turn with the clock from up)
        val image = MoonPicture.draw(photo(), size, MoonLight.Look(terminator = 0.0, limbAngle = 270.0, northAngle = 0.0))
        assertThat(litPart(image)).isWithin(0.03).of(0.5)
        assertThat(brightness(image.getPixel(48, 40))).isGreaterThan(200)
        // The dark side is dim, not black, so the moon can still be found; and partly see-through, so a day sky shows through it
        val darkPixel = image.getPixel(16, 40)
        val onBlack = brightness(darkPixel) * Color.alpha(darkPixel) / 255
        assertThat(onBlack).isGreaterThan(20)
        assertThat(onBlack).isLessThan(60)
        assertThat(Color.alpha(darkPixel)).isLessThan(160)
        // Round: the corners are see-through
        assertThat(Color.alpha(image.getPixel(0, 0))).isEqualTo(0)
        assertThat(Color.alpha(image.getPixel(size / 2, size / 2))).isEqualTo(255)
    }

    @Test
    fun theLitPartIsTheRightSize() {
        for (lit in listOf(0.05, 0.25, 0.431, 0.75, 0.95)) {
            val look = MoonLight.Look(terminator = 1 - 2 * lit, limbAngle = 90.0, northAngle = 0.0)
            assertThat(litPart(MoonPicture.draw(photo(), size, look))).isWithin(0.03).of(lit)
        }
    }

    @Test
    fun theMoonTurnsWithTheSky() {
        // North to the left: the red half is now on the left
        val image = MoonPicture.draw(photo(), size, MoonLight.Look(terminator = -1.0, limbAngle = 0.0, northAngle = 90.0))
        val left = image.getPixel(12, size / 2)
        val right = image.getPixel(size - 12, size / 2)
        assertThat(Color.red(left)).isGreaterThan(200)
        assertThat(Color.green(left)).isLessThan(60)
        assertThat(Color.green(right)).isGreaterThan(200)
    }

    @Test
    fun thePictureIsMadeAgainOnlyAfterAWhile() {
        var loads = 0
        val picture = MoonPicture { loads++; photo() }
        val now = 1_763_000_000_000L - 1_763_000_000_000L % MoonPicture.REDRAW_MS
        val first = picture.bitmap(now, null, size)
        assertThat(picture.bitmap(now + MoonPicture.REDRAW_MS - 1, null, size)).isSameInstanceAs(first)
        assertThat(picture.bitmap(now + MoonPicture.REDRAW_MS, null, size)).isNotSameInstanceAs(first)
        assertThat(picture.bitmap(now, null, size + 2)!!.width).isEqualTo(size + 2)
        assertThat(loads).isEqualTo(1)
        assertThat(picture.bitmap(now, null, 0)).isNull()
    }

    @Test
    fun theWatchCarriesTheMoonPhoto() {
        val photo = BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources, R.drawable.personal_moon_full)
        assertThat(photo.width).isEqualTo(photo.height)
        assertThat(photo.width).isAtLeast(128)
        // The middle is moon, the corners are sky
        assertThat(brightness(photo.getPixel(photo.width / 2, photo.height / 2))).isGreaterThan(80)
        assertThat(brightness(photo.getPixel(1, 1))).isLessThan(30)
    }

    @Test
    fun aViewAsksForTheMoon() {
        assertThat(MoonPicture.isOn(JSONObject().put(MoonPicture.JSON_KEY, true))).isTrue()
        assertThat(MoonPicture.isOn(JSONObject())).isFalse()
        assertThat(MoonPicture.isOn(null)).isFalse()
    }
}
