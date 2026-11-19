// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The text layers drawn by [LayerAnimator], with real pixels: a text lands where its formulas say, two
 * texts at the same place cover each other exactly, and a text is worked out only when an input changed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class TextLayerTest {

    private val size = DESIGN_SIZE.toInt()
    private val noRound = LayerClip(round = false)
    private val utc: () -> ZoneId = { ZoneId.of("UTC") }

    /** What the watch is set to in a test; a change shows at the next wake. */
    private class Texts(override var locale: Locale = Locale.US, override var is24Hour: Boolean = true, override var unitsMgdl: Boolean = true) : FaceTexts {

        val asked = ArrayList<String>()
        override fun font(key: String): Typeface? = when (key) {
            "mono" -> Typeface.MONOSPACE
            else   -> null
        }.also { asked += key }
    }

    private fun text(
        formula: String, x: String = "100", y: String = "200", size: Float = 40f,
        align: TextAlign = TextAlign.LEFT, anchor: TextAnchor = TextAnchor.BASELINE,
        colors: List<Int> = listOf(Color.RED), copies: LayerCopies = LayerCopies.One, font: String = "",
        gradient: LayerGradient? = null, glow: LayerGlow? = null, more: Map<LayerProp, String> = emptyMap(), lets: List<Pair<String, String>> = emptyList()
    ) = AnimLayerSpec(
        source = LayerSource.Text(formula, font, size, align, anchor, 0f, colors, gradient),
        copies = copies, props = mapOf(LayerProp.X to x, LayerProp.Y to y) + more, lets = lets, clip = noRound, glow = glow
    )

    private fun animator(vararg specs: AnimLayerSpec, texts: FaceTexts = Texts(), functions: List<DesignFunction> = emptyList(), lets: List<Pair<String, String>> = emptyList()) =
        LayerAnimator(specs.toList(), lets, zone = utc, functions = functions, texts = texts).also {
            assertThat(it.errors).isEmpty()
            it.setWakeValues({ null }, 60)
        }

    private fun draw(animator: LayerAnimator, elapsedMs: Long = 0, nowMs: Long = 0, scale: Float = 1f): Bitmap {
        animator.setTime(elapsedMs, 5000, 1f, nowMs)
        val bitmap = createBitmap((size * scale).toInt(), (size * scale).toInt())
        animator.drawSlot(Canvas(bitmap), LayerSlot.BACK, scale)
        return bitmap
    }

    /** The smallest box round all pixels that are drawn at all, or null when there are none. */
    private fun bounds(bitmap: Bitmap, minAlpha: Int = 1): Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
            if (Color.alpha(bitmap.getPixel(x, y)) >= minAlpha) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
    }

    private fun countDrawn(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (Color.alpha(bitmap.getPixel(x, y)) > 0) count++
        return count
    }

    // ---- where a text lands ----

    @Test
    fun aTextIsDrawnAtItsPlace() {
        val box = bounds(draw(animator(text("'HHH'"))))!!
        // From x on to the right, standing on y
        assertThat(box.left).isIn(98..104)
        assertThat(box.bottom).isIn(198..202)
        assertThat(box.height()).isIn(20..40)
        assertThat(box.width()).isGreaterThan(40)
    }

    @Test
    fun anEmptyTextDrawsNothing() {
        assertThat(bounds(draw(animator(text("''"))))).isNull()
        assertThat(bounds(draw(animator(text("fixed(v_nothing, 1)"))))).isNull()
    }

    @Test
    fun alignSaysWhichPointIsAtX() {
        val left = bounds(draw(animator(text("'HHH'", x = "200", align = TextAlign.LEFT))))!!
        val centre = bounds(draw(animator(text("'HHH'", x = "200", align = TextAlign.CENTER))))!!
        val right = bounds(draw(animator(text("'HHH'", x = "200", align = TextAlign.RIGHT))))!!
        assertThat(left.left).isIn(198..204)
        assertThat(right.right).isIn(196..202)
        assertThat((centre.left + centre.right) / 2).isIn(198..202)
        assertThat(centre.width()).isEqualTo(left.width())
    }

    @Test
    fun anchorSaysWhichHeightIsAtY() {
        val base = bounds(draw(animator(text("'HHH'", anchor = TextAnchor.BASELINE))))!!
        val top = bounds(draw(animator(text("'HHH'", anchor = TextAnchor.TOP))))!!
        val middle = bounds(draw(animator(text("'HHH'", anchor = TextAnchor.MIDDLE))))!!
        val bottom = bounds(draw(animator(text("'HHH'", anchor = TextAnchor.BOTTOM))))!!
        // The letters stand on y; below the top of the font; round y; above the bottom of the font
        assertThat(base.bottom).isIn(198..202)
        assertThat(top.top).isIn(200..215)
        assertThat(middle.top).isLessThan(200)
        assertThat(middle.bottom).isGreaterThan(200)
        assertThat(bottom.bottom).isIn(185..200)
        assertThat(top.top).isGreaterThan(middle.top)
        assertThat(middle.top).isGreaterThan(bottom.top)
    }

    @Test
    fun theSizeIsInDesignPixels() {
        val small = bounds(draw(animator(text("'H'", size = 20f))))!!
        val big = bounds(draw(animator(text("'H'", size = 80f))))!!
        assertThat(big.height().toFloat() / small.height()).isWithin(0.5f).of(4f)
        // A bigger screen draws the same design bigger
        val twice = bounds(draw(animator(text("'H'", size = 40f)), scale = 2f))!!
        assertThat(twice.height()).isEqualTo(big.height())
        assertThat(twice.left).isIn(198..206)
    }

    @Test
    fun twoTextsAtTheSamePlaceCoverEachOtherAtAnyPartOfAPixel() {
        // The reason text layers exist: the same text at the same place is the same pixels, wherever that place is
        for (x in listOf("100", "100.25", "100.5", "133.37")) {
            val red = draw(animator(text("'12:34'", x = x, y = "200.4", align = TextAlign.CENTER, colors = listOf(Color.RED))))
            val both = draw(
                animator(
                    text("'12:34'", x = x, y = "200.4", align = TextAlign.CENTER, colors = listOf(Color.RED)),
                    text("'12:34'", x = x, y = "200.4", align = TextAlign.CENTER, colors = listOf(Color.BLUE))
                )
            )
            assertWithMessage("x = $x").that(bounds(both)).isEqualTo(bounds(red))
            assertWithMessage("x = $x").that(countDrawn(both)).isEqualTo(countDrawn(red))
        }
    }

    @Test
    fun aPartOfAPixelMovesTheText() {
        val at = draw(animator(text("'H'", x = "100")))
        val half = draw(animator(text("'H'", x = "100.5")))
        // Not snapped to whole pixels: the edge pixels differ
        assertThat(half.sameAs(at)).isFalse()
    }

    @Test
    fun aTextTurnsAndScalesRoundItsPoint() {
        val plain = bounds(draw(animator(text("'HHH'", x = "200", y = "200"))))!!
        val turned = bounds(draw(animator(text("'HHH'", x = "200", y = "200", more = mapOf(LayerProp.ROTATION to "90")))))!!
        // A quarter turn with the clock round (200, 200): the line now runs down from there
        assertThat(turned.height()).isEqualTo(plain.width())
        assertThat(turned.top).isIn(198..204)
        assertThat(turned.left).isIn(198..202)
        val scaled = bounds(draw(animator(text("'HHH'", x = "200", y = "200", more = mapOf(LayerProp.SCALE_X to "2", LayerProp.SCALE_Y to "2")))))!!
        assertThat(scaled.height().toFloat() / plain.height()).isWithin(0.15f).of(2f)
        assertThat(scaled.bottom).isIn(198..202)
    }

    @Test
    fun alphaVisibleAndColourWorkAsForShapes() {
        assertThat(bounds(draw(animator(text("'H'", more = mapOf(LayerProp.VISIBLE to "0")))))).isNull()
        assertThat(bounds(draw(animator(text("'H'", more = mapOf(LayerProp.ALPHA to "0")))))).isNull()
        val half = draw(animator(text("'HHH'", more = mapOf(LayerProp.ALPHA to "0.5"))))
        val box = bounds(half)!!
        var most = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) most = maxOf(most, Color.alpha(half.getPixel(x, y)))
        assertThat(most).isIn(120..135)
        val second = draw(animator(text("'HHH'", colors = listOf(Color.RED, Color.BLUE), more = mapOf(LayerProp.COLOR to "1"))))
        val inside = bounds(second, minAlpha = 250)!!
        assertThat(second.getPixel(inside.left, inside.centerY()) and 0x00FFFFFF).isEqualTo(Color.BLUE and 0x00FFFFFF)
    }

    @Test
    fun aGlowDrawsRoundTheText() {
        val plain = bounds(draw(animator(text("'H'"))))!!
        val glowing = bounds(draw(animator(text("'H'", glow = LayerGlow(Color.WHITE, 8f), more = mapOf(LayerProp.GLOW to "1")))))!!
        assertThat(glowing.width()).isGreaterThan(plain.width() + 6)
        // No glow strength, no glow
        val off = bounds(draw(animator(text("'H'", glow = LayerGlow(Color.WHITE, 8f)))))!!
        assertThat(off).isEqualTo(plain)
    }

    @Test
    fun aGradientFadesOverTheLine() {
        val fade = LayerGradient(LayerGradient.Type.LINEAR, listOf(LayerGradient.Stop(0f, 1f), LayerGradient.Stop(1f, 0f)))
        val bitmap = draw(animator(text("'HHHHHH'", gradient = fade)))
        val plain = bounds(draw(animator(text("'HHHHHH'"))), minAlpha = 200)!!
        var leftMost = 0
        var rightMost = 0
        for (y in plain.top until plain.bottom) {
            for (x in plain.left until plain.left + 6) leftMost = maxOf(leftMost, Color.alpha(bitmap.getPixel(x, y)))
            for (x in plain.right - 6 until plain.right) rightMost = maxOf(rightMost, Color.alpha(bitmap.getPixel(x, y)))
        }
        assertThat(leftMost).isGreaterThan(200)
        assertThat(rightMost).isLessThan(40)
    }

    @Test
    fun aFontOfTheDesignIsAskedByItsKey() {
        val texts = Texts()
        val mono = bounds(draw(animator(text("'iiii'", font = "mono"), texts = texts)))!!
        val plain = bounds(draw(animator(text("'iiii'"), texts = texts)))!!
        assertThat(texts.asked).containsExactly("mono")
        assertThat(mono.width()).isGreaterThan(plain.width())
        // A font that is not known is the default font, not a mistake
        val unknown = bounds(draw(animator(text("'iiii'", font = "nothing"), texts = texts)))!!
        assertThat(unknown).isEqualTo(plain)
    }

    // ---- copies ----

    @Test
    fun everyCopyHasItsOwnText() {
        val animator = animator(text("text(i + 1, '0')", x = "50 + i * 100", copies = LayerCopies.Count(3)))
        animator.setTime(0, 5000, 1f, 0)
        assertThat((0..2).map { animator.textOf(0, it) }).containsExactly("1", "2", "3").inOrder()
        val bitmap = draw(animator)
        // Three signs, one near each x
        for (x in listOf(50, 150, 250)) {
            var drawn = false
            for (px in x until x + 30) for (y in 160..200) if (Color.alpha(bitmap.getPixel(px, y)) > 128) drawn = true
            assertWithMessage("a sign at $x").that(drawn).isTrue()
        }
    }

    // ---- recompute only when an input changed ----

    @Test
    fun aFixedTextIsWorkedOutOnce() {
        val animator = animator(text("'hello' & ' ' & 'you'"))
        for (ms in 0L..2000L step 40) draw(animator, elapsedMs = ms)
        assertThat(animator.textEvals(0)).isEqualTo(1)
    }

    @Test
    fun aTextOfAValueIsWorkedOutWhenTheValueChanges() {
        var battery = 80.0
        val animator = animator(text("fixed(v_watch_battery, 0) & '%'"))
        animator.setWakeValues({ battery }, 60)
        for (ms in 0L..2000L step 40) draw(animator, elapsedMs = ms)
        assertThat(animator.textOf(0, 0)).isEqualTo("80%")
        assertThat(animator.textEvals(0)).isEqualTo(1)
        // The next wake with the same value: nothing to do
        animator.setWakeValues({ battery }, 60)
        draw(animator)
        assertThat(animator.textEvals(0)).isEqualTo(1)
        battery = 79.0
        animator.setWakeValues({ battery }, 60)
        draw(animator)
        assertThat(animator.textOf(0, 0)).isEqualTo("79%")
        assertThat(animator.textEvals(0)).isEqualTo(2)
    }

    @Test
    fun aTextOfTheTimeGivesANewTextOnlyWhenItShowsSomethingElse() {
        val animator = animator(text("text(floor(t), '0')"))
        val seen = HashSet<Int>()
        var last: String? = null
        var changes = 0
        for (ms in 0L..2900L step 40) {
            animator.setTime(ms, 5000, 1f, 0)
            val now = animator.textOf(0, 0)
            if (now !== last) changes++
            last = now
            seen += System.identityHashCode(now)
        }
        // 0, 1 and 2: three texts in 73 frames
        assertThat(changes).isEqualTo(3)
        assertThat(last).isEqualTo("2")
    }

    @Test
    fun aTextPerCopyIsWorkedOutOncePerCopy() {
        val animator = animator(text("text(i, '0')", x = "20 + i * 30 + t", copies = LayerCopies.Count(10)))
        for (ms in 0L..2000L step 40) draw(animator, elapsedMs = ms)
        // Ten copies, 51 frames: ten texts, not 510
        assertThat(animator.textEvals(0)).isEqualTo(10)
    }

    @Test
    fun theClockTextIsWorkedOutOnceAMinute() {
        val start = ZonedDateTime.of(2026, 11, 19, 13, 5, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val animator = animator(text("clockHour() & ':' & clockMinute()"))
        for (ms in 0L..90_000L step 1000) draw(animator, elapsedMs = ms % 5000, nowMs = start + ms)
        assertThat(animator.textOf(0, 0)).isEqualTo("13:06")
        assertThat(animator.textEvals(0)).isEqualTo(2)
    }

    @Test
    fun aNewLanguageShowsAtTheNextWake() {
        val texts = Texts(Locale.US)
        val animator = animator(text("fixed(2.5, 1)"), texts = texts)
        animator.setTime(0, 5000, 1f, 0)
        assertThat(animator.textOf(0, 0)).isEqualTo("2.5")
        texts.locale = Locale.GERMANY
        animator.setWakeValues({ null }, 60)
        animator.setTime(0, 5000, 1f, 0)
        assertThat(animator.textOf(0, 0)).isEqualTo("2,5")
        assertThat(animator.textEvals(0)).isEqualTo(2)
    }

    @Test
    fun theUnitAndTheHoursOfTheWatchAreUsed() {
        val start = ZonedDateTime.of(2026, 11, 19, 13, 5, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val animator = animator(text("glucose(v_sgv)"), text("clock() & ampm()"), texts = Texts(is24Hour = false, unitsMgdl = false))
        animator.setWakeValues({ if (it == "sgv") 100.0 else null }, 60)
        animator.setTime(0, 5000, 1f, start)
        assertThat(animator.textOf(0, 0)).isEqualTo("5.6")
        assertThat(animator.textOf(1, 0)).isEqualTo("01:05PM")
    }

    // ---- own functions and let ----

    @Test
    fun aLayerUsesTheOwnFunctionsAndItsLet() {
        val functions = listOf(DesignFunction.read("two(n)", "text(n, '00')"), DesignFunction.read("swing(a)", "a * base"))
        val animator = animator(
            text("two(k) & '/' & two(base)", x = "swing(10)", lets = listOf("k" to "3 + i")),
            functions = functions, lets = listOf("base" to "7")
        )
        animator.setTime(0, 5000, 1f, 0)
        assertThat(animator.textOf(0, 0)).isEqualTo("03/07")
        assertThat(animator.propOf(0, 0, LayerProp.X)).isEqualTo(70f)
    }

    @Test
    fun aBrokenTextCostsOnlyItsLayer() {
        val animator = LayerAnimator(listOf(text("'a' +"), text("nothing(1)"), text("'ok'")), zone = utc)
        assertThat(animator.size).isEqualTo(1)
        assertThat(animator.errors).hasSize(2)
        animator.setTime(0, 5000, 1f, 0)
        assertThat(animator.textOf(0, 0)).isEqualTo("ok")
    }

    // ---- the design json ----

    @Test
    fun aTextLayerIsReadFromTheDesign() {
        val spec = readAnimation(
            JSONObject(
                """{"functions": {"two(n)": "text(n, '00')", "bad": "1", "sin(a)": "a"},
                    "layers": [
                      {"text": "two(7) & ' min'", "font": "mono", "fontSize": 32, "align": "center", "valign": "middle", "letterSpacing": 0.1,
                       "x": 200, "y": "100 + t", "colors": ["#FF0000", "#0000FF"], "colorIndex": 1,
                       "gradient": {"type": "linear", "stops": [[0, 1], [1, 0]]}},
                      {"text": 42},
                      {"text": "'a'", "shape": "rect"},
                      {"text": ""}
                    ]}"""
            )
        ).normalized()
        assertThat(spec.functions).containsExactly(DesignFunction("two", listOf("n"), "text(n, '00')"))
        assertThat(spec.errors.filter { it.startsWith("functions") }).hasSize(2)
        assertThat(spec.layers).hasSize(2)
        val source = spec.layers[0].source as LayerSource.Text
        assertThat(source.text).isEqualTo("two(7) & ' min'")
        assertThat(source.font).isEqualTo("mono")
        assertThat(source.size).isEqualTo(32f)
        assertThat(source.align).isEqualTo(TextAlign.CENTER)
        assertThat(source.anchor).isEqualTo(TextAnchor.MIDDLE)
        assertThat(source.letterSpacing).isEqualTo(0.1f)
        assertThat(source.colors).containsExactly(Color.RED, Color.BLUE).inOrder()
        assertThat(source.gradient!!.type).isEqualTo(LayerGradient.Type.LINEAR)
        assertThat(spec.layers[0].props[LayerProp.Y]).isEqualTo("100 + t")
        // A number as the text is that number
        assertThat((spec.layers[1].source as LayerSource.Text).text).isEqualTo("42")
        val animator = LayerAnimator(spec.layers, spec.lets, zone = utc, functions = spec.functions, texts = Texts())
        assertThat(animator.errors).isEmpty()
        animator.setWakeValues({ null }, 60)
        animator.setTime(0, 5000, 1f, 0)
        assertThat(animator.textOf(0, 0)).isEqualTo("07 min")
        assertThat(animator.textOf(1, 0)).isEqualTo("42")
    }

    @Test
    fun theSizeOfATextIsCutToSafeLimits() {
        assertThat((text("'a'", size = 5000f).normalized()!!.source as LayerSource.Text).size).isEqualTo(LayerSource.Text.MAX_SIZE)
        assertThat((text("'a'", size = 0f).normalized()!!.source as LayerSource.Text).size).isEqualTo(LayerSource.Text.MIN_SIZE)
        assertThat(text("").normalized()).isNull()
    }
}
