// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.Color
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.wear.R
import app.aaps.wear.watchfaces.daynight.DayNightChoice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The render-only face (the one that draws the picture for the Watch Face Push face) shows the night
 * face at night and the main face in the day, and switches on the same instance. See `CwfNight`.
 *
 * The main face is the Default design; the night face is the Default design with a big blue text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfNightFaceRenderTest {

    private fun newFace() = CustomWatchface().apply { prepareForRendering(RuntimeEnvironment.getApplication()) }

    /** [default] with a big blue text at the bottom, named "night". */
    private fun nightFace(default: CwfData): CwfData {
        val json = JSONObject(default.json).apply {
            getJSONObject("freetext1")
                .put("leftmargin", 0).put("topmargin", 280).put("width", 400).put("height", 120).put("visibility", "visible")
                .put("textsize", 100).put("fontColor", "#0000FF").put("textvalue", "WWWW")
        }
        val metadata = default.metadata.toMutableMap()
        metadata[CwfMetadataKey.CWF_NAME] = "night"
        return CwfData(json.toString(4), metadata, default.resData)
    }

    /** Sets both settings, so the active and the always-on screen agree. */
    private fun CustomWatchface.choose(look: String) {
        sp.putString(R.string.key_day_night_look, look)
        sp.putString(R.string.key_day_night_always_on, DayNightChoice.AS_ACTIVE)
    }

    /** Renders [face] once its stored data is read again. */
    private fun render(face: CustomWatchface): Bitmap {
        Thread.sleep(600)   // past the render data share window
        face.refreshRenderData()
        return face.renderToBitmap(400, 400)
    }

    /** The number of clearly blue pixels, every fourth pixel in both directions. */
    private fun Bitmap.blue(): Int {
        var n = 0
        for (y in 0 until height step 4) for (x in 0 until width step 4) {
            val c = getPixel(x, y)
            if (Color.blue(c) > 200 && Color.red(c) < 60 && Color.green(c) < 60) n++
        }
        return n
    }

    @Test
    fun theNightFaceShowsAtNightAndTheMainFaceInTheDay() {
        val face = newFace()
        face.renderToBitmap(400, 400)     // stores the Default design on first render
        val default = runBlocking { face.complicationDataRepository.getCustomWatchface(true) }!!
        runBlocking {
            face.complicationDataRepository.storeCustomWatchface(default)
            face.complicationDataRepository.storeNightWatchface(nightFace(default))
        }

        face.choose(DayNightChoice.DAY)
        assertThat(render(face).blue()).isLessThan(10)
        val dayStyle = face.styleId

        // At sunset the same instance rebuilds with the night face
        face.choose(DayNightChoice.NIGHT)
        assertThat(render(face).blue()).isGreaterThan(100)
        assertThat(face.styleId).isNotEqualTo(dayStyle)

        // And back in the morning
        face.choose(DayNightChoice.DAY)
        assertThat(render(face).blue()).isLessThan(10)

        // Without a night face the main face shows at night too, as before
        runBlocking { face.complicationDataRepository.clearNightWatchface() }
        face.choose(DayNightChoice.NIGHT)
        assertThat(render(face).blue()).isLessThan(10)
    }
}
