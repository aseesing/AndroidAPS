// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.daynight.ManualMoon
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A view that shows a layer template of the design as its picture: `"image": {"use": "moon"}`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ViewTemplatesTest {

    private val values = HashMap<String, Double>().apply {
        put("moon_lit", 0.5)
        put("moon_limb", 90.0)
        put("moon_north", 0.0)
    }

    /** The photo of the tests is a plain white disc. */
    private fun photo(size: Int): Bitmap = createBitmap(size, size).apply {
        Canvas(this).drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private val templates = ViewTemplates(
        RuntimeEnvironment.getApplication().resources,
        templates = { ManualMoon.templates },
        read = { json -> WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }, { null }).read(json) },
        images = { _, _, size -> photo(size) },
        value = { values[it] }
    )
    private val zip = ViewImages(RuntimeEnvironment.getApplication().resources, DynamicImages(emptyMap()), templates)

    private fun picture(view: String, width: Int = 40, height: Int = 40): Bitmap? =
        (zip.drawable("freetext1", JSONObject(view), width, height, 0, mutableMapOf()) as? BitmapDrawable)?.bitmap

    @Test
    fun theMoonFillsTheViewAndFollowsItsValues() {
        val half = picture("""{"image": {"use": "moon"}}""")!!
        assertThat(half.width).isEqualTo(40)
        // Lit on the right, dark and see-through on the left, clear in the corners
        assertThat(half.getPixel(30, 20)).isEqualTo(Color.WHITE)
        assertThat(Color.alpha(half.getPixel(10, 20))).isIn(100..130)
        assertThat(Color.alpha(half.getPixel(0, 0))).isEqualTo(0)
        // The same values: the same picture, not drawn again
        assertThat(picture("""{"image": {"use": "moon"}}""")).isSameInstanceAs(half)
        // The moon grows to full: a new picture, lit on the left too
        values["moon_lit"] = 1.0
        val full = picture("""{"image": {"use": "moon"}}""")!!
        assertThat(full).isNotSameInstanceAs(half)
        assertThat(full.getPixel(10, 20)).isEqualTo(Color.WHITE)
    }

    @Test
    fun aViewThatIsNotSquareGetsASquareOfItsShorterSide() {
        assertThat(picture("""{"image": {"use": "moon"}}""", 60, 24)!!.let { it.width to it.height }).isEqualTo(24 to 24)
    }

    @Test
    fun theParametersOfTheTemplateCount() {
        // A dark part that covers all: black where the moon is not lit
        val covered = picture("""{"image": {"use": "moon", "dark": 0, "cover": 1}}""")!!
        assertThat(covered.getPixel(10, 20)).isEqualTo(Color.BLACK)
    }

    @Test
    fun whatCannotBeDrawnGivesNoPicture() {
        assertThat(picture("""{"image": {"use": "no_such_template"}}""")).isNull()
        assertThat(picture("""{"image": {"use": "moon"}}""", 0, 40)).isNull()
        // A view layer cannot be the picture of a view
        assertThat(picture("""{"image": {"use": "blink", "view": "sgv", "valueKey": "sgv"}}""")).isNull()
    }
}
