package app.aaps.wear.watchfaces.animation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * The layers drawn by [LayerAnimator], with real pixels: shapes land where their formulas say, copies,
 * clips, colours and the variables work, and a broken layer never costs the rest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class LayerAnimatorTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    private val size = DESIGN_SIZE.toInt()
    private val noRound = LayerClip(round = false)
    private val utc: () -> ZoneId = { ZoneId.of("UTC") }

    /** A RECT shape layer with its position and size as formulas. */
    private fun rect(
        x: String, y: String, width: String = "10", height: String = "10",
        colors: List<Int> = listOf(Color.RED),
        copies: LayerCopies = LayerCopies.One,
        clip: LayerClip = noRound,
        more: Map<LayerProp, String> = emptyMap(),
        restSeconds: Float? = null
    ) = AnimLayerSpec(
        source = LayerSource.Shape(ShapeKind.RECT, colors),
        copies = copies,
        props = mapOf(LayerProp.X to x, LayerProp.Y to y, LayerProp.WIDTH to width, LayerProp.HEIGHT to height) + more,
        clip = clip,
        restSeconds = restSeconds
    )

    private val BAND = LayerGradient(LayerGradient.Type.LINEAR, listOf(LayerGradient.Stop(0f, 0f), LayerGradient.Stop(0.5f, 1f), LayerGradient.Stop(1f, 0f)))
    private val DOT = LayerGradient(LayerGradient.Type.RADIAL, listOf(LayerGradient.Stop(0f, 1f), LayerGradient.Stop(0.25f, 1f), LayerGradient.Stop(1f, 0f)))

    private fun animator(vararg specs: AnimLayerSpec) = LayerAnimator(specs.toList(), zone = utc)

    /** Draws the back slot of [animator] at scale 1 on a clear 400 x 400 bitmap. */
    private fun draw(animator: LayerAnimator, elapsedMs: Long = 0, nowMs: Long = 0, slot: LayerSlot = LayerSlot.BACK): Bitmap {
        animator.setTime(elapsedMs, 5000, 1f, nowMs)
        val bitmap = createBitmap(size, size)
        animator.drawSlot(Canvas(bitmap), slot, 1f)
        return bitmap
    }

    /** The smallest box round all pixels that are more than half covered, or null when there are none. */
    private fun bounds(bitmap: Bitmap): Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
            if (Color.alpha(bitmap.getPixel(x, y)) > 128) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        // right and bottom are just past the last pixel, like a Rect has them
        return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
    }

    private fun countDrawn(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (Color.alpha(bitmap.getPixel(x, y)) > 0) count++
        return count
    }

    private fun isClear(bitmap: Bitmap) = countDrawn(bitmap) == 0

    private fun pixel(bitmap: Bitmap, x: Int, y: Int): Int = bitmap.getPixel(x, y)

    /** A 100 x 100 picture whose every pixel is different, so a wrong cut shows. */
    private fun testPicture(): BitmapDrawable {
        val bitmap = createBitmap(100, 100)
        for (y in 0 until 100) for (x in 0 until 100) bitmap.setPixel(x, y, Color.rgb(x * 2, y * 2, (x + y) % 256))
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun assertSamePicture(a: Bitmap, b: Bitmap, tolerance: Int) {
        var worst = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            worst = maxOf(
                worst,
                abs(Color.alpha(p) - Color.alpha(q)), abs(Color.red(p) - Color.red(q)),
                abs(Color.green(p) - Color.green(q)), abs(Color.blue(p) - Color.blue(q))
            )
        }
        assertThat(worst).isAtMost(tolerance)
    }

    // ---- shapes ----

    @Test
    fun aRectLandsWhereItsFormulasSay() {
        val bitmap = draw(animator(rect(x = "100", y = "150", width = "50", height = "20")))
        assertThat(pixel(bitmap, 100, 150)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 149, 169)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 125, 160)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 99, 160)).isEqualTo(0)
        assertThat(pixel(bitmap, 150, 160)).isEqualTo(0)
        assertThat(pixel(bitmap, 125, 149)).isEqualTo(0)
        assertThat(pixel(bitmap, 125, 170)).isEqualTo(0)
        assertThat(countDrawn(bitmap)).isEqualTo(50 * 20)
    }

    @Test
    fun aRectFollowsTheTime() {
        val animator = animator(rect(x = "t * 100", y = "ms / 10"))
        val bitmap = draw(animator, elapsedMs = 1000)
        assertThat(bounds(bitmap)).isEqualTo(Rect(100, 100, 110, 110))
        assertThat(pixel(bitmap, 105, 105)).isEqualTo(Color.RED)
    }

    @Test
    fun theScaleMakesItBigger() {
        val animator = animator(rect(x = "10", y = "20", width = "30", height = "40"))
        animator.setTime(0, 5000, 1f, 0)
        val bitmap = createBitmap(size * 2, size * 2)
        animator.drawSlot(Canvas(bitmap), LayerSlot.BACK, 2f)
        assertThat(pixel(bitmap, 20, 40)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 79, 119)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 80, 60)).isEqualTo(0)
        assertThat(countDrawn(bitmap)).isEqualTo(60 * 80)
    }

    @Test
    fun alphaZeroDrawsNothing() {
        assertThat(isClear(draw(animator(rect(x = "100", y = "100", more = mapOf(LayerProp.ALPHA to "0")))))).isTrue()
        assertThat(isClear(draw(animator(rect(x = "100", y = "100", more = mapOf(LayerProp.ALPHA to "-1")))))).isTrue()
        // A missing value (NaN) is not drawn either
        assertThat(isClear(draw(animator(rect(x = "100", y = "100", more = mapOf(LayerProp.ALPHA to "v_none")))))).isTrue()
        // Half alpha draws half
        val half = draw(animator(rect(x = "100", y = "100", more = mapOf(LayerProp.ALPHA to "0.5"))))
        assertThat(Color.alpha(pixel(half, 105, 105))).isAtLeast(126)
        assertThat(Color.alpha(pixel(half, 105, 105))).isAtMost(129)
    }

    @Test
    fun onlyTheAskedSlotIsDrawn() {
        val animator = animator(rect(x = "100", y = "100").copy(slot = LayerSlot.FRONT))
        assertThat(isClear(draw(animator, slot = LayerSlot.BACK))).isTrue()
        assertThat(isClear(draw(animator, slot = LayerSlot.FRONT))).isFalse()
        assertThat(animator.hasSlot(LayerSlot.FRONT)).isTrue()
        assertThat(animator.hasSlot(LayerSlot.BACK)).isFalse()
    }

    @Test
    fun otherShapesDrawWhereTheySay() {
        fun shape(kind: ShapeKind, props: Map<LayerProp, String>, gradient: LayerGradient? = null) =
            animator(AnimLayerSpec(source = LayerSource.Shape(kind, listOf(Color.WHITE), gradient), props = props, clip = noRound))

        val circle = draw(shape(ShapeKind.CIRCLE, mapOf(LayerProp.X to "200", LayerProp.Y to "100", LayerProp.RADIUS to "20")))
        assertThat(pixel(circle, 200, 100)).isEqualTo(Color.WHITE)
        assertThat(pixel(circle, 200, 85)).isEqualTo(Color.WHITE)
        assertThat(pixel(circle, 200, 125)).isEqualTo(0)
        assertThat(pixel(circle, 216, 116)).isEqualTo(0)

        // Straight down from 50,50 over 100
        val line = draw(shape(ShapeKind.LINE, mapOf(LayerProp.X to "50", LayerProp.Y to "50", LayerProp.LENGTH to "100", LayerProp.THICKNESS to "4")))
        assertThat(Color.alpha(pixel(line, 50, 100))).isEqualTo(255)
        assertThat(pixel(line, 50, 160)).isEqualTo(0)
        assertThat(pixel(line, 60, 100)).isEqualTo(0)
        // At 90 degrees it goes right
        val right = draw(shape(ShapeKind.LINE, mapOf(LayerProp.X to "50", LayerProp.Y to "50", LayerProp.LENGTH to "100", LayerProp.THICKNESS to "4", LayerProp.ANGLE to "90")))
        assertThat(Color.alpha(pixel(right, 100, 50))).isEqualTo(255)
        assertThat(pixel(right, 50, 100)).isEqualTo(0)

        // A soft dot: bright in the middle, clear past its radius
        val dot = draw(shape(ShapeKind.CIRCLE, mapOf(LayerProp.X to "300", LayerProp.Y to "300", LayerProp.RADIUS to "20"), DOT))
        assertThat(Color.alpha(pixel(dot, 300, 300))).isGreaterThan(200)
        assertThat(Color.alpha(pixel(dot, 300, 315))).isLessThan(Color.alpha(pixel(dot, 300, 300)))
        assertThat(pixel(dot, 300, 322)).isEqualTo(0)

        // A soft band: bright in the middle, clear at the sides, from y to y + height
        val band = draw(shape(ShapeKind.RECT, mapOf(LayerProp.X to "150", LayerProp.Y to "100", LayerProp.WIDTH to "100", LayerProp.HEIGHT to "50"), BAND))
        assertThat(Color.alpha(pixel(band, 200, 120))).isGreaterThan(240)
        assertThat(Color.alpha(pixel(band, 160, 120))).isLessThan(Color.alpha(pixel(band, 200, 120)))
        assertThat(Color.alpha(pixel(band, 151, 120))).isLessThan(20)
        assertThat(pixel(band, 200, 99)).isEqualTo(0)
        assertThat(pixel(band, 200, 150)).isEqualTo(0)
        assertThat(pixel(band, 260, 120)).isEqualTo(0)
    }

    @Test
    fun rotationTurnsRoundTheMiddle() {
        // A 100 x 10 bar turned 90 degrees round its middle (200, 200) stands up
        val bitmap = draw(animator(rect(x = "150", y = "195", width = "100", height = "10", more = mapOf(LayerProp.ROTATION to "90"))))
        assertThat(pixel(bitmap, 200, 160)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 160, 200)).isEqualTo(0)
    }

    /** Draws [kind] with every size [size] (a formula), and gives the error it throws, or null. */
    private fun drawWithSize(kind: ShapeKind, size: String, gradient: LayerGradient? = null): Throwable? {
        val spec = AnimLayerSpec(
            source = LayerSource.Shape(kind, listOf(Color.WHITE), gradient),
            props = mapOf(
                LayerProp.X to "100", LayerProp.Y to "100", LayerProp.RADIUS to size, LayerProp.WIDTH to size,
                LayerProp.HEIGHT to size, LayerProp.LENGTH to size, LayerProp.THICKNESS to size
            ),
            clip = noRound
        )
        val animator = animator(spec)
        assertWithMessage("$kind").that(animator.errors).isEmpty()
        return runCatching { draw(animator) }.exceptionOrNull()
    }

    /** The shapes and sizes that throw while drawing, as "KIND size: error". */
    private fun sizesThatThrow(kinds: List<ShapeKind>, sizes: List<String>, gradient: LayerGradient? = null): List<String> =
        kinds.flatMap { kind -> sizes.mapNotNull { size -> drawWithSize(kind, size, gradient)?.let { "$kind $size: $it" } } }

    @Test
    fun aMissingOrZeroSizeDoesNotBreakTheDrawing() {
        // A formula that reads a value the watch does not have gives NaN for the size
        assertThat(sizesThatThrow(ShapeKind.entries, listOf("v_none", "0", "-5", "1e30"))).isEmpty()
        // With a gradient too
        assertThat(sizesThatThrow(ShapeKind.entries, listOf("-5", "1e30"), BAND)).isEmpty()
        assertThat(sizesThatThrow(ShapeKind.entries, listOf("-5", "1e30"), DOT)).isEmpty()
    }

    @Test
    fun aBandOfMissingOrZeroWidthDoesNotBreakTheDrawing() {
        assertThat(sizesThatThrow(listOf(ShapeKind.RECT), listOf("v_none", "0"), BAND)).isEmpty()
    }

    // ---- clips ----

    @Test
    fun theRoundClipKeepsTheCornersClear() {
        val full = rect(x = "0", y = "0", width = "400", height = "400", clip = LayerClip(round = true))
        val bitmap = draw(animator(full))
        for ((x, y) in listOf(2 to 2, 397 to 2, 2 to 397, 397 to 397, 30 to 30, 370 to 370))
            assertWithMessage("corner $x,$y").that(pixel(bitmap, x, y)).isEqualTo(0)
        for ((x, y) in listOf(200 to 200, 200 to 3, 3 to 200, 396 to 200, 200 to 396))
            assertWithMessage("inside $x,$y").that(pixel(bitmap, x, y)).isEqualTo(Color.RED)
        // Without the round clip the corners are drawn
        val square = draw(animator(full.copy(clip = noRound)))
        assertThat(pixel(square, 2, 2)).isEqualTo(Color.RED)
        assertThat(countDrawn(square)).isEqualTo(400 * 400)
    }

    @Test
    fun theClipRectCuts() {
        val bitmap = draw(animator(rect(x = "0", y = "0", width = "400", height = "400", clip = LayerClip(rect = DesignRect(50f, 60f, 100f, 80f), round = false))))
        assertThat(pixel(bitmap, 50, 60)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 149, 139)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 49, 100)).isEqualTo(0)
        assertThat(pixel(bitmap, 150, 100)).isEqualTo(0)
        assertThat(pixel(bitmap, 100, 59)).isEqualTo(0)
        assertThat(pixel(bitmap, 100, 140)).isEqualTo(0)
        assertThat(countDrawn(bitmap)).isEqualTo(100 * 80)
    }

    @Test
    fun ringsAndBoxesCut() {
        val clip = LayerClip(round = false, rings = listOf(DesignRing(200f, 200f, 50f, 100f)), boxes = listOf(DesignRect(0f, 0f, 40f, 40f)))
        val bitmap = draw(animator(rect(x = "0", y = "0", width = "400", height = "400", clip = clip)))
        assertThat(pixel(bitmap, 200, 125)).isEqualTo(Color.RED)    // in the ring
        assertThat(pixel(bitmap, 200, 200)).isEqualTo(0)            // in its hole
        assertThat(pixel(bitmap, 200, 20)).isEqualTo(0)             // outside
        assertThat(pixel(bitmap, 20, 20)).isEqualTo(Color.RED)      // in the box
    }

    // ---- masks ----

    /** A 100 x 100 mask picture: [left] on the left half, [right] on the right half. */
    private fun maskPicture(left: Int, right: Int): BitmapDrawable {
        val bitmap = createBitmap(100, 100)
        for (y in 0 until 100) for (x in 0 until 100) bitmap.setPixel(x, y, if (x < 50) left else right)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun maskClip(drawable: BitmapDrawable?, rect: DesignRect = DesignRect.FULL) = LayerClip(round = false, mask = LayerMask("mask", drawable, rect))

    /** A red rect over the whole face, masked by [clip]. */
    private fun maskedRect(clip: LayerClip, blend: LayerBlend = LayerBlend.NORMAL) =
        rect(x = "0", y = "0", width = "400", height = "400", clip = clip).copy(blend = blend)

    @Test
    fun aMaskShowsTheLayerOnlyWhereItIsOpaque() {
        val bitmap = draw(animator(maskedRect(maskClip(maskPicture(Color.WHITE, Color.TRANSPARENT)))))
        for ((x, y) in listOf(5 to 5, 100 to 200, 190 to 395))
            assertWithMessage("left $x,$y").that(pixel(bitmap, x, y)).isEqualTo(Color.RED)
        for ((x, y) in listOf(210 to 5, 300 to 200, 395 to 395))
            assertWithMessage("right $x,$y").that(pixel(bitmap, x, y)).isEqualTo(0)
    }

    @Test
    fun aHalfClearMaskGivesHalfStrength() {
        val bitmap = draw(animator(maskedRect(maskClip(maskPicture(Color.argb(128, 255, 255, 255), Color.WHITE)))))
        assertThat(Color.alpha(pixel(bitmap, 100, 200))).isIn(120..136)
        assertThat(Color.alpha(pixel(bitmap, 300, 200))).isEqualTo(255)
    }

    @Test
    fun outsideTheMaskRectTheLayerDoesNotShow() {
        // An opaque mask over the top half only
        val bitmap = draw(animator(maskedRect(maskClip(maskPicture(Color.WHITE, Color.WHITE), DesignRect(0f, 0f, 400f, 200f)))))
        assertThat(pixel(bitmap, 300, 100)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 300, 300)).isEqualTo(0)
        assertThat(countDrawn(bitmap)).isEqualTo(400 * 200)
    }

    @Test
    fun aScreenLayerWithAMaskLightensOnlyInsideTheMask() {
        val background = Color.rgb(0, 0, 128)
        val animator = animator(maskedRect(maskClip(maskPicture(Color.WHITE, Color.TRANSPARENT)), LayerBlend.SCREEN))
        animator.setTime(0, 5000, 1f, 0)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        animator.drawSlot(canvas, LayerSlot.BACK, 1f)
        // Screen of red over dark blue: red and blue
        assertThat(pixel(bitmap, 100, 200)).isEqualTo(Color.rgb(255, 0, 128))
        // Outside the mask the background is kept as it was, not cut away
        assertThat(pixel(bitmap, 300, 200)).isEqualTo(background)
    }

    @Test
    fun aMaskThatIsNotInTheZipDropsTheLayer() {
        val spec = maskedRect(maskClip(null))
        assertThat(spec.normalized()).isNull()
        // Not normalized: still never drawn without its mask
        assertThat(isClear(draw(animator(spec)))).isTrue()
        // With its picture it is kept
        assertThat(maskedRect(maskClip(maskPicture(Color.WHITE, Color.WHITE))).normalized()).isNotNull()
    }

    @Test
    fun withoutImageDropsTheMaskPicture() {
        val a = maskedRect(maskClip(maskPicture(Color.WHITE, Color.WHITE)))
        val b = maskedRect(maskClip(maskPicture(Color.WHITE, Color.WHITE)))
        assertThat(a).isNotEqualTo(b)
        assertThat(a.withoutImage()).isEqualTo(b.withoutImage())
        assertThat(a.withoutImage().clip.mask).isEqualTo(LayerMask("mask", null, DesignRect.FULL))
    }

    @Test
    fun aMaskIsMadeAgainAfterRelease() {
        val animator = animator(maskedRect(maskClip(maskPicture(Color.WHITE, Color.TRANSPARENT))))
        val first = draw(animator)
        animator.release()
        assertThat(draw(animator).sameAs(first)).isTrue()
        // and at another scale
        animator.setTime(0, 5000, 1f, 0)
        val big = createBitmap(size * 2, size * 2)
        animator.drawSlot(Canvas(big), LayerSlot.BACK, 2f)
        assertThat(pixel(big, 300, 400)).isEqualTo(Color.RED)
        assertThat(pixel(big, 500, 400)).isEqualTo(0)
    }

    // ---- copies ----

    private fun randomDots(seed: Long) =
        rect(x = "r1 * 390", y = "r2 * 390", copies = LayerCopies.Count(30, seed))

    @Test
    fun copiesWithTheSameSeedLookTheSame() {
        val a = draw(animator(randomDots(7)))
        val b = draw(animator(randomDots(7)))
        assertThat(a.sameAs(b)).isTrue()
        // Many copies, so many places
        assertThat(countDrawn(a)).isGreaterThan(10 * 10 * 10)
    }

    @Test
    fun copiesWithAnotherSeedLookDifferent() {
        val a = draw(animator(randomDots(7)))
        val b = draw(animator(randomDots(8)))
        assertThat(a.sameAs(b)).isFalse()
    }

    @Test
    fun eachCopyHasItsNumber() {
        // Copy i at x = i * 50; u goes from 0 to 1 over the copies
        val bitmap = draw(animator(rect(x = "i * 50", y = "u * 300 + n", copies = LayerCopies.Count(4))))
        assertThat(pixel(bitmap, 5, 9)).isEqualTo(Color.RED)          // i 0, u 0, n 4
        assertThat(pixel(bitmap, 55, 109)).isEqualTo(Color.RED)       // i 1, u 1/3
        assertThat(pixel(bitmap, 105, 209)).isEqualTo(Color.RED)      // i 2, u 2/3
        assertThat(pixel(bitmap, 155, 309)).isEqualTo(Color.RED)      // i 3, u 1
        assertThat(countDrawn(bitmap)).isEqualTo(4 * 100)
    }

    @Test
    fun pointCopiesAreDrawnAtTheirPoints() {
        val points = LayerCopies.Points(listOf(DesignPoint(50f, 60f), DesignPoint(300f, 200f), DesignPoint(120f, 330f)))
        val bitmap = draw(animator(rect(x = "px - 5", y = "py - 5", copies = points)))
        assertThat(pixel(bitmap, 50, 60)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 300, 200)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 120, 330)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 200, 200)).isEqualTo(0)
        assertThat(countDrawn(bitmap)).isEqualTo(3 * 100)
    }

    @Test
    fun colorIndexPicksTheColour() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val bitmap = draw(animator(rect(x = "i * 60", y = "100", colors = colors, copies = LayerCopies.Count(6), more = mapOf(LayerProp.COLOR to "i - 1"))))
        // i - 1 = -1, 0, 1, 2, 3, 4: it goes round, also below 0
        assertThat(pixel(bitmap, 5, 105)).isEqualTo(Color.BLUE)
        assertThat(pixel(bitmap, 65, 105)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 125, 105)).isEqualTo(Color.GREEN)
        assertThat(pixel(bitmap, 185, 105)).isEqualTo(Color.BLUE)
        assertThat(pixel(bitmap, 245, 105)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 305, 105)).isEqualTo(Color.GREEN)
    }

    // ---- pictures ----

    private fun picture(copies: LayerCopies, props: Map<LayerProp, String> = emptyMap()) = AnimLayerSpec(
        source = LayerSource.Image("pic", testPicture(), DesignRect(100f, 120f, 100f, 100f)),
        copies = copies,
        props = props,
        clip = noRound
    )

    @Test
    fun aPictureIsDrawnInItsRect() {
        val bitmap = draw(animator(picture(LayerCopies.One)))
        assertThat(bounds(bitmap)).isNotNull()
        val box = bounds(bitmap)!!
        assertThat(box.left).isEqualTo(100)
        assertThat(box.top).isEqualTo(120)
        assertThat(box.width()).isEqualTo(100)
        assertThat(box.height()).isEqualTo(100)
        // Moved by x and y
        val moved = bounds(draw(animator(picture(LayerCopies.One, mapOf(LayerProp.X to "30", LayerProp.Y to "-20")))))!!
        assertThat(moved.left).isEqualTo(130)
        assertThat(moved.top).isEqualTo(100)
    }

    @Test
    fun slicesWithoutOffsetsKeepThePicture() {
        val whole = draw(animator(picture(LayerCopies.One)))
        assertSamePicture(draw(animator(picture(LayerCopies.Slices(4)))), whole, 2)
        assertSamePicture(draw(animator(picture(LayerCopies.Slices(4, vertical = false)))), whole, 2)
        assertSamePicture(draw(animator(picture(LayerCopies.Slices(10)))), whole, 2)
    }

    @Test
    fun slicesMoveOnTheirOwn() {
        // Every other strip 10 down: the picture is no longer the same
        val whole = draw(animator(picture(LayerCopies.One)))
        val waved = draw(animator(picture(LayerCopies.Slices(4), mapOf(LayerProp.Y to "if(i % 2, 10, 0)"))))
        assertThat(waved.sameAs(whole)).isFalse()
        // Strip 0 (x 100..124) did not move, strip 1 (x 125..149) did
        assertThat(waved.getPixel(110, 125)).isEqualTo(whole.getPixel(110, 125))
        assertThat(Color.alpha(waved.getPixel(135, 125))).isEqualTo(0)
        assertThat(Color.alpha(waved.getPixel(135, 135))).isEqualTo(255)
    }

    // ---- the moon ----

    /** A moon layer in [rect]; the moon of the tests is a plain red square. */
    private fun moonLayer(rect: DesignRect = DesignRect(100f, 120f, 60f, 40f), restSeconds: Float? = null, maxScale: Float = 1f) =
        AnimLayerSpec(source = LayerSource.Dynamic("moon", rect), clip = noRound, restSeconds = restSeconds, maxScale = maxScale)

    /** Gives a red square of the asked size and keeps what was asked, as (clock time, size). */
    private class TestMoon {

        val asked = ArrayList<Pair<Long, Int>>()
        val made = ArrayList<Bitmap>()

        fun picture(name: String, epochMs: Long, size: Int): Bitmap {
            check(name == "moon")
            asked += epochMs to size
            return createBitmap(size, size).apply { eraseColor(Color.RED) }.also { made += it }
        }
    }

    @Test
    fun aMoonLayerDrawsTheMoonInTheMiddleOfItsRect() {
        val moon = TestMoon()
        val now = 1_700_000_000_000L
        val bitmap = draw(LayerAnimator(listOf(moonLayer()), zone = utc, images = moon::picture), nowMs = now)
        // The square of the shorter side (40), in the middle of the 60 wide rect
        assertThat(bounds(bitmap)).isEqualTo(Rect(110, 120, 150, 160))
        assertThat(pixel(bitmap, 130, 140)).isEqualTo(Color.RED)
        // The moon of the frame's clock time, as big as it is drawn
        assertThat(moon.asked).containsExactly(now to 40)
        // Moved by x and y, like a picture
        val moved = draw(LayerAnimator(listOf(moonLayer().copy(props = mapOf(LayerProp.X to "30", LayerProp.Y to "-20"))), zone = utc, images = moon::picture), nowMs = now)
        assertThat(bounds(moved)).isEqualTo(Rect(140, 100, 180, 140))
    }

    @Test
    fun aMoonLayerIsDrawnInItsPlaceAmongTheLayers() {
        val moon = TestMoon()
        val below = rect(x = "100", y = "130", width = "60", height = "20", colors = listOf(Color.BLUE))
        val above = rect(x = "100", y = "150", width = "60", height = "20", colors = listOf(Color.GREEN))
        val bitmap = draw(LayerAnimator(listOf(below, moonLayer(), above), zone = utc, images = moon::picture))
        assertThat(pixel(bitmap, 105, 140)).isEqualTo(Color.BLUE)  // only the layer below
        assertThat(pixel(bitmap, 130, 140)).isEqualTo(Color.RED)   // the moon over the layer below
        assertThat(pixel(bitmap, 130, 155)).isEqualTo(Color.GREEN) // the layer above over the moon
        assertThat(pixel(bitmap, 130, 125)).isEqualTo(Color.RED)
    }

    @Test
    fun withoutAPictureOfTheAppNothingIsDrawnAndTheOtherLayersStay() {
        val other = rect(x = "10", y = "10")
        // No moon at all (the default), none to be had, and one that fails
        for (animator in listOf(
            LayerAnimator(listOf(moonLayer(), other), zone = utc),
            LayerAnimator(listOf(moonLayer(), other), zone = utc) { _, _, _ -> null },
            LayerAnimator(listOf(moonLayer(), other), zone = utc) { _, _, _ -> error("no moon") }
        )) {
            val bitmap = draw(animator)
            assertThat(pixel(bitmap, 15, 15)).isEqualTo(Color.RED)
            assertThat(countDrawn(bitmap)).isEqualTo(100)
            // And again, while it is not asked again
            assertThat(countDrawn(draw(animator))).isEqualTo(100)
        }
        // A picture freed by its owner is not drawn either
        val freed = LayerAnimator(listOf(moonLayer()), zone = utc) { _, _, size -> createBitmap(size, size).apply { recycle() } }
        assertThat(isClear(draw(freed))).isTrue()
    }

    @Test
    fun theMoonIsAskedForOncePerMinuteAndSize() {
        val moon = TestMoon()
        val animator = LayerAnimator(listOf(moonLayer()), zone = utc, images = moon::picture)
        val minute = 1_700_000_040_000L // a whole minute
        for (frame in 0 until 24) {
            animator.setTime(frame * 40L, 5000, 1f, minute + frame * 40L)
            animator.drawSlot(Canvas(createBitmap(size, size)), LayerSlot.BACK, 1f)
        }
        assertThat(moon.asked).hasSize(1)
        // The next minute, and another size of the screen, ask again
        draw(animator, nowMs = minute + 60_000)
        assertThat(moon.asked).hasSize(2)
        animator.setTime(0, 5000, 1f, minute + 60_000)
        animator.drawSlot(Canvas(createBitmap(2 * size, 2 * size)), LayerSlot.BACK, 2f)
        assertThat(moon.asked.last()).isEqualTo(minute + 60_000 to 80)
        // Freeing the pictures never frees the moon: it belongs to the caller
        animator.release()
        assertThat(moon.made.none { it.isRecycled }).isTrue()
    }

    @Test
    fun theMoonSizeHasALimit() {
        val moon = TestMoon()
        draw(LayerAnimator(listOf(moonLayer(DesignRect.FULL, maxScale = 4f)), zone = utc, images = moon::picture))
        assertThat(moon.asked.single().second).isEqualTo(512)
    }

    @Test
    fun aRestMoonLayerStaysOnTheFace() {
        val moon = TestMoon()
        val animator = LayerAnimator(listOf(moonLayer(restSeconds = 0f)), zone = utc, images = moon::picture)
        assertThat(animator.hasRest).isTrue()
        val bitmap = createBitmap(size, size)
        val now = 1_700_000_000_000L
        animator.drawRest(Canvas(bitmap), 1f, now) { null }
        assertThat(bounds(bitmap)).isEqualTo(Rect(110, 120, 150, 160))
        assertThat(moon.asked).containsExactly(now to 40)
    }

    // ---- views ----

    @Test
    fun aViewLayerGivesItsEffects() {
        val chart = AnimLayerSpec(
            source = LayerSource.View("chart"),
            props = mapOf(LayerProp.ROTATION to "rot0 + 10", LayerProp.X to "dx0 * 2", LayerProp.ALPHA to "0", LayerProp.CLIP_RIGHT to "300")
        )
        val bg = AnimLayerSpec(source = LayerSource.View("bg"), props = mapOf(LayerProp.ALPHA to "1", LayerProp.X to "5"))
        val gone = AnimLayerSpec(source = LayerSource.View("gone"), props = mapOf(LayerProp.ROTATION to "45"))
        val animator = animator(chart, bg, gone)
        assertThat(animator.viewKeys).containsExactly("chart", "bg", "gone")
        val effects = animator.viewEffects(
            rotation = { key -> mapOf("chart" to 5f, "bg" to 0f)[key] },
            offsetX = { key -> if (key == "chart") 3f else null }
        )
        assertThat(effects.turns).containsExactly("chart", 15f)
        assertThat(effects.shifts).containsExactly("chart", 6f, "bg", 5f)
        assertThat(effects.hidden).containsExactly("chart")
        assertThat(effects.clipRights).containsExactly("chart", 300f)
        // A view layer draws nothing itself
        assertThat(animator.hasSlot(LayerSlot.VIEWS)).isFalse()
        assertThat(isClear(draw(animator, slot = LayerSlot.VIEWS))).isTrue()
        assertThat(isClear(draw(animator, slot = LayerSlot.BACK))).isTrue()
    }

    @Test
    fun twoLayersOnOneViewAddUp() {
        val a = AnimLayerSpec(source = LayerSource.View("chart"), props = mapOf(LayerProp.ROTATION to "10", LayerProp.X to "1", LayerProp.CLIP_RIGHT to "300"))
        val b = AnimLayerSpec(source = LayerSource.View("chart"), props = mapOf(LayerProp.ROTATION to "t", LayerProp.X to "2", LayerProp.CLIP_RIGHT to "250"))
        val animator = animator(a, b)
        animator.setTime(3000, 5000, 1f, 0)
        val effects = animator.viewEffects({ 0f }, { 0f })
        assertThat(effects.turns).containsExactly("chart", 13f)
        assertThat(effects.shifts).containsExactly("chart", 3f)
        assertThat(effects.clipRights).containsExactly("chart", 250f)
        assertThat(effects.hidden).isEmpty()
    }

    // ---- variables ----

    @Test
    fun valueKeysComeFromTheWakeValues() {
        // Drawn when the battery is known and low; a missing value is NaN, and both comparisons are false then
        val animator = animator(rect(x = "100", y = "100", more = mapOf(LayerProp.ALPHA to "v_watch_battery <= 20 || v_watch_battery > 1000")))
        assertThat(animator.valueKeys).containsExactly("watch_battery")
        assertThat(isClear(draw(animator))).isTrue()          // not set yet: NaN
        animator.setWakeValues({ key -> if (key == "watch_battery") 15.0 else null }, 60)
        assertThat(isClear(draw(animator))).isFalse()
        animator.setWakeValues({ 50.0 }, 60)
        assertThat(isClear(draw(animator))).isTrue()
        animator.setWakeValues({ null }, 60)
        assertThat(isClear(draw(animator))).isTrue()
    }

    @Test
    fun aMissingValueIsNaN() {
        // NaN is not equal to itself: x is 100 for a number and 200 for a missing value
        val animator = animator(rect(x = "if(v_a == v_a, 100, 200)", y = "100"))
        animator.setWakeValues({ null }, 60)
        assertThat(pixel(draw(animator), 205, 105)).isEqualTo(Color.RED)
        animator.setWakeValues({ 1.0 }, 60)
        assertThat(pixel(draw(animator), 105, 105)).isEqualTo(Color.RED)
    }

    @Test
    fun bpmComesFromTheWakeValues() {
        val animator = animator(rect(x = "bpm", y = "100"))
        // Before the wake values: the fallback heart rate
        assertThat(bounds(draw(animator))!!.left).isEqualTo(PulseCurve.FALLBACK_BPM)
        animator.setWakeValues({ null }, 90)
        assertThat(bounds(draw(animator))!!.left).isEqualTo(90)
    }

    @Test
    fun envAndLenComeFromSetTime() {
        val animator = animator(rect(x = "env * 100", y = "len / 100"))
        animator.setTime(0, 15000, 0.5f, 0)
        val bitmap = createBitmap(size, size)
        animator.drawSlot(Canvas(bitmap), LayerSlot.BACK, 1f)
        val box = bounds(bitmap)!!
        assertThat(box.left).isEqualTo(50)
        assertThat(box.top).isEqualTo(150)
    }

    @Test
    fun theTimeOfDayComesFromNowInTheZone() {
        val now = ZonedDateTime.of(2026, 1, 15, 10, 20, 30, 500_000_000, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val spec = rect(x = "sec * 10", y = "min * 5", width = "hour", height = "10")
        val box = bounds(draw(LayerAnimator(listOf(spec), zone = utc), nowMs = now))!!
        assertThat(box.left).isEqualTo(305)    // 30.5 seconds
        assertThat(box.top).isEqualTo(100)     // 20 minutes
        assertThat(box.width()).isEqualTo(10)  // 10 hours
        // The same moment in Amsterdam (winter: one hour later)
        val amsterdam = bounds(draw(LayerAnimator(listOf(spec), zone = { ZoneId.of("Europe/Amsterdam") }), nowMs = now))!!
        assertThat(amsterdam.left).isEqualTo(305)
        assertThat(amsterdam.width()).isEqualTo(11)
    }

    // ---- rest ----

    @Test
    fun drawRestDrawsOnlyTheRestLayers() {
        val rest = rect(x = "50", y = "50", more = mapOf(LayerProp.ALPHA to "if(t > 1.5, 1, 0)"), restSeconds = 2f)
        val other = rect(x = "200", y = "200", colors = listOf(Color.BLUE))
        val animator = animator(rest, other)
        assertThat(animator.hasRest).isTrue()
        val bitmap = createBitmap(size, size)
        animator.drawRest(Canvas(bitmap), 1f, 0) { null }
        // Drawn as it is 2 seconds into a wake, where its alpha is 1
        assertThat(pixel(bitmap, 55, 55)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 205, 205)).isEqualTo(0)
        assertThat(countDrawn(bitmap)).isEqualTo(100)
    }

    @Test
    fun drawRestReadsTheValuesAgain() {
        val rest = rect(x = "v_level", y = "50", restSeconds = 0f)
        val animator = animator(rest)
        val bitmap = createBitmap(size, size)
        animator.drawRest(Canvas(bitmap), 1f, 0) { 120.0 }
        assertThat(bounds(bitmap)!!.left).isEqualTo(120)
    }

    @Test
    fun withoutRestLayersDrawRestDrawsNothing() {
        val animator = animator(rect(x = "50", y = "50"))
        assertThat(animator.hasRest).isFalse()
        val bitmap = createBitmap(size, size)
        animator.drawRest(Canvas(bitmap), 1f, 0) { null }
        assertThat(isClear(bitmap)).isTrue()
    }

    // ---- errors and limits ----

    @Test
    fun aBrokenLayerIsLeftOutAndTheOthersDraw() {
        val broken = rect(x = "1 +", y = "50")
        val unknown = rect(x = "foo", y = "50")
        val good = rect(x = "200", y = "200")
        val animator = animator(broken, good, unknown)
        assertThat(animator.errors).hasSize(2)
        assertThat(animator.errors[0]).startsWith("layer 0:")
        assertThat(animator.errors[1]).startsWith("layer 2:")
        assertThat(animator.errors[1]).contains("foo")
        assertThat(animator.isEmpty).isFalse()
        val bitmap = draw(animator)
        assertThat(pixel(bitmap, 205, 205)).isEqualTo(Color.RED)
        assertThat(countDrawn(bitmap)).isEqualTo(100)
    }

    @Test
    fun onlyBrokenLayersLeaveAnEmptyAnimator() {
        val animator = animator(rect(x = "(", y = "50"))
        assertThat(animator.isEmpty).isTrue()
        assertThat(animator.errors).hasSize(1)
        assertThat(isClear(draw(animator))).isTrue()
    }

    @Test
    fun tooManyCopiesInAllAreLeftOut() {
        val big = AnimLayerSpec.MAX_COPIES
        val layers = List(AnimLayerSpec.MAX_TOTAL_COPIES / big) { rect(x = "0", y = "0", width = "1", height = "1", copies = LayerCopies.Count(big)) } +
            rect(x = "0", y = "0", copies = LayerCopies.Count(big)) +                      // this one is too many
            rect(x = "200", y = "200", copies = LayerCopies.Count(AnimLayerSpec.MAX_TOTAL_COPIES % big))  // this one still fits
        val animator = LayerAnimator(layers, zone = utc)
        val tooMany = AnimLayerSpec.MAX_TOTAL_COPIES / big
        assertThat(animator.errors).containsExactly("layer $tooMany: more than ${AnimLayerSpec.MAX_TOTAL_COPIES} copies in all")
        val bitmap = draw(animator)
        assertThat(pixel(bitmap, 205, 205)).isEqualTo(Color.RED)
        // The left out layer would have drawn a 10 x 10 rect at 0,0
        assertThat(pixel(bitmap, 5, 5)).isEqualTo(0)
    }

    @Test
    fun oneLayerWithTooManyCopiesIsLeftOut() {
        val animator = animator(rect(x = "0", y = "0", copies = LayerCopies.Count(AnimLayerSpec.MAX_TOTAL_COPIES + 1)))
        assertThat(animator.errors).hasSize(1)
        assertThat(animator.isEmpty).isTrue()
    }

    @Test
    fun noMoreThanMaxLayersAreDrawn() {
        // One 10 x 10 rect per layer, side by side; the one past the limit is not drawn
        val layers = List(AnimLayerSpec.MAX_LAYERS + 1) { k -> rect(x = "${(k % 20) * 10}", y = "${(k / 20) * 10}") }
        val bitmap = draw(LayerAnimator(layers, zone = utc))
        assertThat(countDrawn(bitmap)).isEqualTo(AnimLayerSpec.MAX_LAYERS * 100)
        assertThat(pixel(bitmap, 5, 25)).isEqualTo(0)
        // and the design tests can see why
        assertThat(LayerAnimator(layers, zone = utc).errors).containsExactly("layer ${AnimLayerSpec.MAX_LAYERS}: more than ${AnimLayerSpec.MAX_LAYERS} layers")
    }

    @Test
    fun aBrokenLayerDoesNotKeepItsValueKeys() {
        val broken = rect(x = "v_broken_key + nothere", y = "0")
        val good = rect(x = "v_watch_battery", y = "0")
        val animator = animator(broken, good)
        assertThat(animator.errors).hasSize(1)
        assertThat(animator.valueKeys).containsExactly("watch_battery")
    }

    @Test
    fun aNegativeCountDoesNotBreakTheAnimator() {
        val bad = rect(x = "r1 * 100", y = "0", copies = LayerCopies.Count(-3))
        val good = rect(x = "200", y = "200")
        val animator = animator(bad, good)
        assertThat(pixel(draw(animator), 205, 205)).isEqualTo(Color.RED)
    }

    @Test
    fun theShaderCachesStaySmall() {
        val twinkle = AnimLayerSpec(
            source = LayerSource.Shape(ShapeKind.CIRCLE, listOf(Color.WHITE), DOT),
            copies = LayerCopies.Count(50),
            props = mapOf(LayerProp.X to "r1 * 400", LayerProp.Y to "r2 * 400", LayerProp.RADIUS to "3 + noise(t * 10 + i) * 6"),
            clip = noRound
        )
        val animator = animator(twinkle)
        val canvas = Canvas(createBitmap(size, size))
        for (frame in 0 until 200) {
            animator.setTime(frame * 16L, 5000, 1f, 0)
            animator.drawSlot(canvas, LayerSlot.BACK, 1f)
        }
        // Every map the animator keeps (found by type, so a rename does not break this test)
        val sizes = LayerAnimator::class.java.declaredFields
            .filter { Map::class.java.isAssignableFrom(it.type) }
            .associate { field -> field.isAccessible = true; field.name to (field.get(animator) as Map<*, *>).size }
        for ((name, entries) in sizes) assertWithMessage(name).that(entries).isAtMost(256)
    }

    // ---- how often a formula is worked out ----

    @Test
    fun aFormulaOfTheWakeFollowsNewValuesAndANewLength() {
        val animator = animator(rect(x = "v_a + len / 1000", y = "100"))
        animator.setWakeValues({ 10.0 }, 60)
        animator.setTime(0, 5000, 1f, 0)
        assertThat(animator.propOf(0, 0, LayerProp.X)).isEqualTo(15f)
        animator.setTime(1000, 5000, 0.5f, 0)
        assertThat(animator.propOf(0, 0, LayerProp.X)).isEqualTo(15f)
        animator.setTime(1000, 8000, 0.5f, 0)
        assertThat(animator.propOf(0, 0, LayerProp.X)).isEqualTo(18f)
        animator.setWakeValues({ 20.0 }, 60)
        assertThat(animator.propOf(0, 0, LayerProp.X)).isEqualTo(28f)
        animator.setWakeValues({ null }, 90)
        assertThat(animator.propOf(0, 0, LayerProp.X)!!.isNaN()).isTrue()
    }

    @Test
    fun aFormulaOfTheFrameFollowsTheTimeForEveryCopy() {
        val animator = animator(rect(x = "t * 10 + env", y = "i * 20 + t", copies = LayerCopies.Count(3)))
        animator.setTime(2000, 5000, 1f, 0)
        for (i in 0..2) {
            assertThat(animator.propOf(0, i, LayerProp.X)).isEqualTo(21f)
            assertThat(animator.propOf(0, i, LayerProp.Y)).isEqualTo(i * 20f + 2f)
        }
        animator.setTime(3000, 5000, 0f, 0)
        for (i in 0..2) assertThat(animator.propOf(0, i, LayerProp.X)).isEqualTo(30f)
        // Drawn: three boxes in a column, all at the x of this frame
        val box = bounds(draw(animator, elapsedMs = 4000))!!
        assertThat(box.left).isEqualTo(41)
        assertThat(box.top).isEqualTo(4)
        assertThat(box.bottom).isEqualTo(54)
    }

    @Test
    fun aFixedNumberIsKept() {
        val animator = animator(rect(x = "2 * 50", y = "100"))
        animator.setTime(0, 5000, 1f, 0)
        assertThat(animator.propOf(0, 0, LayerProp.X)).isEqualTo(100f)
        assertThat(animator.propOf(0, 0, LayerProp.ROTATION)).isNull()
    }

    @Test
    fun theZoneIsReadOncePerRestFrameAndNotPerLayer() {
        var asked = 0
        val now = ZonedDateTime.of(2026, 1, 15, 10, 20, 30, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val animator = LayerAnimator(
            listOf(rect(x = "sec", y = "10", restSeconds = 1f), rect(x = "min", y = "30", restSeconds = 2f), rect(x = "hour", y = "50", restSeconds = 3f)),
            zone = { asked++; ZoneId.of("UTC") }
        )
        val bitmap = createBitmap(size, size)
        animator.drawRest(Canvas(bitmap), 1f, now) { null }
        assertThat(asked).isEqualTo(1)
        assertThat(pixel(bitmap, 35, 15)).isEqualTo(Color.RED)   // 30 seconds
        assertThat(pixel(bitmap, 25, 35)).isEqualTo(Color.RED)   // 20 minutes
        assertThat(pixel(bitmap, 15, 55)).isEqualTo(Color.RED)   // 10 hours
        animator.drawRest(Canvas(bitmap), 1f, now + 60_000) { null }
        assertThat(asked).isEqualTo(2)
    }

    // ---- groups ----

    @Test
    fun eraseTakesAwayOnlyWhatItsGroupDrew() {
        val below = rect("0", "0", "100", "100", colors = listOf(Color.BLUE))
        val red = rect("20", "20", "60", "60").copy(group = "g")
        val hole = rect("40", "40", "20", "20").copy(group = "g", blend = LayerBlend.ERASE)
        val bitmap = draw(animator(below, red, hole))
        assertThat(pixel(bitmap, 30, 30)).isEqualTo(Color.RED)
        // The hole shows the layer below the group, which is not cut
        assertThat(pixel(bitmap, 50, 50)).isEqualTo(Color.BLUE)
        assertThat(pixel(bitmap, 10, 10)).isEqualTo(Color.BLUE)
    }

    @Test
    fun insideIsDrawnOnlyWhereItsGroupDrewBefore() {
        val below = rect("0", "0", "100", "100", colors = listOf(Color.BLUE))
        val shape = rect("20", "20", "30", "60", colors = listOf(Color.WHITE)).copy(group = "g")
        val fill = rect("20", "20", "60", "60", colors = listOf(Color.GREEN)).copy(group = "g", blend = LayerBlend.INSIDE)
        val bitmap = draw(animator(below, shape, fill))
        // Green where the white shape was, and nothing of the fill beside it
        assertThat(pixel(bitmap, 30, 50)).isEqualTo(Color.GREEN)
        assertThat(pixel(bitmap, 60, 50)).isEqualTo(Color.BLUE)
    }

    @Test
    fun aGroupEndsWhereALayerOfAnotherGroupOrOfNoneFollows() {
        val red = rect("20", "20", "60", "60").copy(group = "a")
        val between = rect("0", "0", "10", "10", colors = listOf(Color.GREEN))
        // Another group: its erase does not reach the red of the first one
        val other = rect("30", "30", "10", "10", colors = listOf(Color.WHITE)).copy(group = "b")
        val hole = rect("20", "20", "60", "60").copy(group = "b", blend = LayerBlend.ERASE)
        val bitmap = draw(animator(red, between, other, hole))
        assertThat(pixel(bitmap, 35, 35)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 60, 60)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 5, 5)).isEqualTo(Color.GREEN)
        // The same without the layer between, with the same name: one group, the erase cuts the red too
        val one = draw(animator(red, other.copy(group = "a"), hole.copy(group = "a")))
        assertThat(isClear(one)).isTrue()
    }

    @Test
    fun aGroupIsAsBigAsTheClipRectOfItsFirstLayer() {
        val first = rect("20", "20", "10", "10", clip = LayerClip(rect = DesignRect(0f, 0f, 50f, 50f), round = false)).copy(group = "g")
        val second = rect("40", "40", "40", "40", colors = listOf(Color.GREEN)).copy(group = "g")
        val bitmap = draw(animator(first, second))
        assertThat(pixel(bitmap, 25, 25)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 45, 45)).isEqualTo(Color.GREEN)
        // Outside the sheet of the group nothing of it shows
        assertThat(Color.alpha(pixel(bitmap, 60, 60))).isEqualTo(0)
    }

    @Test
    fun eraseAndInsideNeedAGroup() {
        assertThat(rect("0", "0").copy(blend = LayerBlend.ERASE).normalized()).isNull()
        assertThat(rect("0", "0").copy(blend = LayerBlend.INSIDE).normalized()).isNull()
        assertThat(rect("0", "0").copy(blend = LayerBlend.ERASE, group = "g").normalized()).isNotNull()
    }

    @Test
    fun aRestGroupStaysOnTheFace() {
        val red = rect("20", "20", "60", "60", restSeconds = 0f).copy(group = "g")
        val hole = rect("40", "40", "20", "20", restSeconds = 0f).copy(group = "g", blend = LayerBlend.ERASE)
        val bitmap = createBitmap(size, size).apply { eraseColor(Color.BLUE) }
        animator(red, hole).drawRest(Canvas(bitmap), 1f, 0) { null }
        assertThat(pixel(bitmap, 30, 30)).isEqualTo(Color.RED)
        assertThat(pixel(bitmap, 50, 50)).isEqualTo(Color.BLUE)
    }
}
