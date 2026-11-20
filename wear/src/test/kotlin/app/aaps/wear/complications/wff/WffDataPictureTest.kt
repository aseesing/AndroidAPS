// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.complications.wff

import android.graphics.Bitmap
import android.graphics.Color
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.R
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.daynight.DayNightChoice
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The data picture of the D2 face, drawn from the `data.zip` that the face tool wrote for it
 * (`wff/D2-data.zip`), on a 466 x 466 screen like the OnePlus watch.
 *
 * The wearer's own Custom watch face and night face are stored too, and both have art (the Default
 * design, with its black background). The picture must show none of it: only the data parts, on a
 * see-through square of the size of the face's data slot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w466dp-h466dp-mdpi")
class WffDataPictureTest {

    private val app = RuntimeEnvironment.getApplication()
    private val helper = CustomWatchface().apply { prepareForRendering(app) }
    private lateinit var data: CwfData

    @Before
    fun setUp() {
        helper.renderToBitmap(SCREEN, SCREEN).recycle()     // stores the Default design
        val default = runBlocking { helper.complicationDataRepository.getCustomWatchface(true) }!!
        val now = System.currentTimeMillis()
        runBlocking {
            helper.complicationDataRepository.storeCustomWatchface(default)
            helper.complicationDataRepository.storeNightWatchface(default)
            helper.complicationDataRepository.updateBgData(
                EventData.SingleBg(
                    dataset = 0, timeStamp = now - 2 * MINUTE, sgvString = "6,7", glucoseUnits = "mmol", slopeArrow = "→",
                    delta = "+0,1", deltaDetailed = "+0,12", avgDelta = "+0,1", avgDeltaDetailed = "+0,08", sgvLevel = 0, sgv = 121.0,
                    high = 180.0, low = 70.0, color = 0, deltaMgdl = 1.8, avgDeltaMgdl = 1.4
                )
            )
            helper.complicationDataRepository.updateStatusData(
                EventData.Status(
                    dataset = 0, externalStatus = "", iobSum = "1.25", iobDetail = "(0.90|0.35)", cob = "12g", currentBasal = "0.85U/h",
                    battery = "78", rigBattery = "--", openApsStatus = now - 3 * MINUTE, bgi = "-0.4", batteryLevel = 1,
                    tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0,
                    cobValue = 12.0, iobValue = 1.25, iobBolusValue = 0.9, iobBasalValue = 0.35, basalRateValue = 1.02, bgiMgdl = -7.2
                )
            )
        }
        val bytes = javaClass.getResourceAsStream("/wff/D2-data.zip")!!.use { it.readBytes() }
        data = ZipWatchfaceFormat.loadCustomWatchface(bytes, "data.zip", authorization = false)!!.cwfData
    }

    private fun choose(look: String) {
        helper.sp.putString(R.string.key_day_night_look, look)
        helper.sp.putString(R.string.key_day_night_always_on, DayNightChoice.AS_ACTIVE)
    }

    /** The screen pixel of the design pixel [design]: the design is 400 wide, the slot is the whole face. */
    private fun px(design: Int): Int = design * SCREEN / DESIGN

    private fun Bitmap.alphaAt(x: Int, y: Int): Int = Color.alpha(getPixel(x, y))

    /** The share of pixels that are not fully clear, every second pixel in both directions. */
    private fun Bitmap.painted(): Double {
        var n = 0
        var all = 0
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            all++
            if (Color.alpha(getPixel(x, y)) > 0) n++
        }
        return n.toDouble() / all
    }

    /** True when some pixel in the design box ([left], [top], [width], [height]) is not fully clear. */
    private fun Bitmap.paintsIn(left: Int, top: Int, width: Int, height: Int): Boolean {
        for (y in px(top) until px(top + height)) for (x in px(left) until px(left + width)) if (alphaAt(x, y) > 0) return true
        return false
    }

    private fun Bitmap.save(name: String) {
        val dir = File("build/wff-data-picture").apply { mkdirs() }
        File(dir, name).outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The checks every data picture of D2 must pass. */
    private fun assertOnlyData(picture: Bitmap) {
        // The data slot of the D2 face is x 0, y 0, 450 x 450 of a face of 450 x 450: the whole screen
        assertThat(picture.width).isEqualTo(SCREEN * SLOT / FACE)
        assertThat(picture.height).isEqualTo(SCREEN * SLOT / FACE)
        // Art only: the corners, the bezel at the top and on the left, the plain background at the top and below the graph
        val artOnly = listOf(10 to 10, SCREEN - 11 to 10, 10 to SCREEN - 11, SCREEN - 11 to SCREEN - 11, px(200) to px(6), px(6) to px(200), px(200) to px(86), px(300) to px(360))
        for ((x, y) in artOnly) assertWithMessage("alpha at $x, $y").that(picture.alphaAt(x, y)).isEqualTo(0)
        // The art covers the whole face; the data parts only a small part of it
        assertThat(picture.painted()).isLessThan(0.15)
        // The data parts: the IOB, the COB and the basal rate in the bar, and the phone battery
        assertWithMessage("IOB").that(picture.paintsIn(108, 126, 52, 28)).isTrue()
        assertWithMessage("COB").that(picture.paintsIn(190, 126, 38, 28)).isTrue()
        assertWithMessage("basal rate").that(picture.paintsIn(236, 126, 68, 28)).isTrue()
        assertWithMessage("phone battery").that(picture.paintsIn(240, 21, 44, 29)).isTrue()
    }

    @Test
    fun atNightThePictureHasOnlyTheDataPartsNotTheNightFace() {
        choose(DayNightChoice.NIGHT)
        val picture = WffDataPicture(app, AAPSLoggerTest()).draw(data, System.currentTimeMillis())
        picture.save("D2-night.png")
        assertOnlyData(picture)
    }

    @Test
    fun inTheDayThePictureHasOnlyTheDataPartsNotTheWearersFace() {
        choose(DayNightChoice.DAY)
        val picture = WffDataPicture(app, AAPSLoggerTest()).draw(data, System.currentTimeMillis())
        picture.save("D2-day.png")
        assertOnlyData(picture)
    }

    @Test
    fun theSamePainterFollowsTheWearersSwitchFromDayToNight() {
        val painter = WffDataPicture(app, AAPSLoggerTest())
        choose(DayNightChoice.DAY)
        assertOnlyData(painter.draw(data, System.currentTimeMillis()))
        choose(DayNightChoice.NIGHT)
        Thread.sleep(600)   // past the render data share window, so the face reads its style again
        assertOnlyData(painter.draw(data, System.currentTimeMillis()))
    }

    @Test
    fun aDataZipWithoutAClearBackgroundStillGivesASeeThroughPicture() {
        choose(DayNightChoice.DAY)
        val res = data.resData.toMutableMap().apply { remove(ResFileMap.BACKGROUND.fileName) }
        val picture = WffDataPicture(app, AAPSLoggerTest()).draw(CwfData(data.json, data.metadata, res), System.currentTimeMillis())
        assertOnlyData(picture)
    }

    private companion object {

        const val SCREEN = 466
        const val DESIGN = 400

        /** The face and its data slot, as in the face XML of D2 (`<WatchFace width="450"`, slotId 20). */
        const val FACE = 450
        const val SLOT = 450

        const val MINUTE = 60_000L
    }
}
