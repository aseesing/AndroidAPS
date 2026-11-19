package app.aaps.wear.watchfaces.daynight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import app.aaps.shared.impl.weardata.CwfPlaceNeeds
import app.aaps.wear.R
import app.aaps.wear.watchfaces.animation.DESIGN_SIZE
import app.aaps.wear.watchfaces.animation.LayerAnimator
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.utils.DynamicImages
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The moon template of the manual ([ManualMoon]: plain layers, the photo `@moon`, a few shapes, a group)
 * drawn by the layer engine: as much lit as the values say, on the right side, turned with the sky, with
 * a dark part that lets the sky through.
 *
 * Also writes the moons as a picture, for the eye: `wear/build/moon-template/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MoonTemplateTest {

    private val photo: Bitmap by lazy { BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources, R.drawable.moon_full) }

    private class Case(val name: String, val look: MoonLight.Look)

    private fun at(name: String, time: String, place: GeoPoint?) = Case(name, MoonLight.at(ZonedDateTime.parse(time).toInstant().toEpochMilli(), place))

    /** Every phase with the lit side at some angle, then real moments seen from the north, from the south and with no place. */
    private val cases = listOf(
        Case("new moon", MoonLight.Look(1.0, 270.0, 0.0)),
        Case("thin crescent, 3 % lit", MoonLight.Look(0.94, 250.0, 20.0)),
        Case("crescent, 20 % lit", MoonLight.Look(0.6, 300.0, 340.0)),
        Case("first quarter", MoonLight.Look(0.0, 270.0, 0.0)),
        Case("gibbous, 80 % lit", MoonLight.Look(-0.6, 240.0, 25.0)),
        Case("nearly full, 97 % lit", MoonLight.Look(-0.94, 200.0, 0.0)),
        Case("full moon", MoonLight.Look(-1.0, 90.0, 330.0)),
        Case("last quarter, lit side left", MoonLight.Look(0.0, 90.0, 0.0)),
        Case("waning crescent, 20 % lit", MoonLight.Look(0.6, 110.0, 15.0)),
        at("17 Nov 2026 19:30, Amsterdam", "2026-11-17T19:30:00+01:00", AMSTERDAM),
        at("17 Nov 2026 19:30, Sydney", "2026-11-17T19:30:00+11:00", SYDNEY),
        at("17 Nov 2026 19:30 UTC, no place", "2026-11-17T19:30:00Z", null),
        at("13 Nov 2026 18:00, Amsterdam", "2026-11-13T18:00:00+01:00", AMSTERDAM),
        at("13 Nov 2026 20:00, Sydney", "2026-11-13T20:00:00+11:00", SYDNEY),
        at("24 Nov 2026 23:00, Amsterdam", "2026-11-24T23:00:00+01:00", AMSTERDAM),
        at("24 Nov 2026 23:00, Sydney", "2026-11-24T23:00:00+11:00", SYDNEY),
        at("2 Dec 2026 06:00, Amsterdam", "2026-12-02T06:00:00+01:00", AMSTERDAM),
        at("2 Dec 2026 04:00, Sydney", "2026-12-02T04:00:00+11:00", SYDNEY)
    )

    /** The moon: the layers of the template, drawn by the layer engine with the three moon values. */
    private fun moon(look: MoonLight.Look, size: Int, use: String = """{"use": "moon"}"""): Bitmap {
        val json = ManualMoon.design(use)
        val spec = WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }, { null }).read(json)!!.normalized()
        assertThat(spec.errors).isEmpty()
        val animator = LayerAnimator(spec.layers, spec.lets, images = { name, _, side -> if (name == MoonPhoto.NAME) MoonPhoto.round(photo, side) else null })
        assertThat(animator.errors).isEmpty()
        val values = mapOf(CwfPlaceNeeds.MOON_LIT to look.litFraction, CwfPlaceNeeds.MOON_LIMB to look.limbTurn, CwfPlaceNeeds.MOON_NORTH to look.northTurn)
        animator.setWakeValues({ values[it] }, 60)
        animator.setTime(0, 6000, 0f, 0)
        val image = createBitmap(size, size)
        val canvas = Canvas(image)
        for (slot in LayerSlot.entries) animator.drawSlot(canvas, slot, size / DESIGN_SIZE)
        animator.release()
        return image
    }

    /** [moon] laid over [sky], as the face shows it. */
    private fun over(sky: Int, moon: Bitmap): Bitmap = createBitmap(moon.width, moon.height).also {
        val canvas = Canvas(it)
        canvas.drawColor(sky)
        canvas.drawBitmap(moon, 0f, 0f, null)
    }

    /** The part of the disc that is covered fully: the lit part. */
    private fun litPart(image: Bitmap): Double {
        var lit = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (Color.alpha(image.getPixel(x, y)) > 200) lit++
        return lit / (Math.PI * image.width * image.width / 4)
    }

    @Test
    fun asMuchIsLitAsTheValueSays() {
        for (case in cases) assertWithMessage(case.name).that(litPart(moon(case.look, 96))).isWithin(0.03).of(case.look.litFraction)
    }

    @Test
    fun theLitSideAndTheNorthPointWhereTheValuesSay() {
        // A quarter with the lit side to the right, then to the top
        val right = moon(MoonLight.Look(0.0, 270.0, 0.0), 64)
        assertThat(Color.alpha(right.getPixel(48, 32))).isEqualTo(255)
        assertThat(Color.alpha(right.getPixel(16, 32))).isLessThan(130)
        val up = moon(MoonLight.Look(0.0, 0.0, 0.0), 64)
        assertThat(Color.alpha(up.getPixel(32, 16))).isEqualTo(255)
        assertThat(Color.alpha(up.getPixel(32, 48))).isLessThan(130)
        // The full moon turned half round is the photo upside down
        val straight = moon(MoonLight.Look(-1.0, 0.0, 0.0), 64)
        val turned = moon(MoonLight.Look(-1.0, 0.0, 180.0), 64)
        var difference = 0L
        for (y in 8 until 56) for (x in 8 until 56) difference += abs(Color.red(straight.getPixel(x, y)) - Color.red(turned.getPixel(63 - x, 63 - y)))
        assertThat(difference / (48.0 * 48.0)).isLessThan(8.0)
    }

    @Test
    fun thePhotoOfTheAppIsThePlainFullMoon() {
        val round = MoonPhoto.round(photo, 64)
        // All of the disc, nothing of the corners, and as bright on the left as on the right: no phase
        assertThat(Color.alpha(round.getPixel(32, 32))).isEqualTo(255)
        assertThat(Color.alpha(round.getPixel(3, 32))).isEqualTo(255)
        assertThat(Color.alpha(round.getPixel(60, 32))).isEqualTo(255)
        assertThat(Color.alpha(round.getPixel(2, 2))).isEqualTo(0)
        assertThat(Color.red(round.getPixel(10, 32))).isGreaterThan(90)
        assertThat(Color.red(round.getPixel(54, 32))).isGreaterThan(90)
        // It never changes: one picture for every time
        var loads = 0
        val images = DynamicImages(mapOf(MoonPhoto.NAME to MoonPhoto { loads++; photo }))
        val first = images.bitmap(MoonPhoto.NAME, 0, 64)
        assertThat(images.bitmap(MoonPhoto.NAME, 1_800_000_000_000L, 64)).isSameInstanceAs(first)
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun theTurnsOfALookAreWithTheClock() {
        // The lit side to the right is a quarter turn with the clock; to the left a quarter turn against it
        assertThat(MoonLight.Look(0.0, 270.0, 0.0).limbTurn).isEqualTo(90.0)
        assertThat(MoonLight.Look(0.0, 90.0, 0.0).limbTurn).isEqualTo(-90.0)
        assertThat(MoonLight.Look(0.0, 0.0, 340.0).northTurn).isEqualTo(20.0)
        assertThat(MoonLight.Look(0.0, 180.0, 0.0).limbTurn).isEqualTo(180.0)
        // Without a place north is up
        assertThat(MoonLight.at(1_763_000_000_000L, null).northTurn).isEqualTo(0.0)
    }

    @Test
    fun theDarkPartIsSeeThroughAndTheRestIsClear() {
        val moon = moon(MoonLight.Look(0.0, 270.0, 0.0), 64)
        // The lit side (right) covers all, the dark side lets the sky through, the corners are clear
        assertThat(Color.alpha(moon.getPixel(48, 32))).isEqualTo(255)
        assertThat(Color.alpha(moon.getPixel(16, 32))).isIn(105..125)
        assertThat(Color.alpha(moon.getPixel(1, 1))).isEqualTo(0)
        assertThat(Color.alpha(moon.getPixel(62, 62))).isEqualTo(0)
    }

    @Test
    fun theTemplateFillsTheSquareItIsGiven() {
        // A moon of 100 design pixels at 250, 50: nothing is drawn outside that square
        val image = moon(MoonLight.Look(-1.0, 270.0, 0.0), 400, """{"use": "moon", "left": 250, "top": 50, "size": 100}""")
        for (y in 0 until 400) for (x in 0 until 400) {
            val inDisc = hypot(x + 0.5 - 300, y + 0.5 - 100) < 49
            val outside = hypot(x + 0.5 - 300, y + 0.5 - 100) > 51
            if (inDisc) assertWithMessage("$x, $y").that(Color.alpha(image.getPixel(x, y))).isEqualTo(255)
            if (outside) assertWithMessage("$x, $y").that(Color.alpha(image.getPixel(x, y))).isEqualTo(0)
        }
    }

    @Test
    fun theWatchCarriesTheMoonPhoto() {
        assertThat(photo.width).isEqualTo(photo.height)
        assertThat(photo.width).isAtLeast(128)
    }

    /** For the eye: every case on a night sky and on a day sky. */
    @Test
    fun writeThePictures() {
        val out = File(System.getProperty("moon.template.out") ?: "build/moon-template").apply { mkdirs() }
        val size = 96
        val cell = size + 8
        val labelWidth = 260
        val sheet = createBitmap(labelWidth + 2 * cell, cases.size * cell)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(40, 40, 40))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f }
        for ((row, case) in cases.withIndex()) {
            val top = row * cell
            canvas.drawText(case.name, 6f, top + 20f, text)
            canvas.drawText("lit %.2f  limb %.0f  north %.0f".format(case.look.litFraction, case.look.limbTurn, case.look.northTurn), 6f, top + 38f, text)
            val picture = moon(case.look, size)
            for ((column, sky) in listOf(NIGHT_SKY, DAY_SKY).withIndex()) {
                val left = labelWidth + column * cell + 4
                canvas.drawBitmap(over(sky, picture), null, Rect(left, top + 4, left + size, top + 4 + size), null)
            }
        }
        FileOutputStream(File(out, "moon-template.png")).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {

        val AMSTERDAM = GeoPoint(52.37, 4.90)
        val SYDNEY = GeoPoint(-33.87, 151.21)

        const val NIGHT_SKY = Color.BLACK
        val DAY_SKY = Color.rgb(90, 150, 220)
    }
}
