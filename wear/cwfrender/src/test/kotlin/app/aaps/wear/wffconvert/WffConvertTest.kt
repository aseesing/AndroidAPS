// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.wffconvert

import android.app.Application
import android.graphics.Color
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.wff.WffFaceWriter
import app.aaps.wear.watchfaces.wff.WffPlanner
import app.aaps.wear.watchfaces.wff.WffStaticDesign
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.samsung.watchface.WatchFaceXmlValidator
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The converter of designs to Watch Face Format faces.
 *
 * [aSmallDesignGivesAValidFace] runs always. [convertTheZips] runs only when asked, on a dev machine
 * with Pack built (`sh wear/cwfrender/tools/build-pack.sh`):
 *
 * `env WFF_ZIPS=<a zip or a folder of zips> sh ~/.claude/bin/gradle-locked.sh <aaps> <log> :wear:cwfrender:testDebugUnitTest --tests '*WffConvertTest*'`
 *
 * Optional: `WFF_OUT` (default `build/wff`), `WFF_CLIENT` (the wear app's id, default the full app),
 * `PACK_CLI` and `WFF_KEY` (default: what build-pack.sh made in `~/.cache/aaps-wff`). Per design it
 * writes `lint.txt`, the face folder, `face.apk`, `validator.txt` and `token.txt` when it passed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class WffConvertTest {

    private fun view(left: Int, top: Int, width: Int, height: Int, extra: String = "") =
        """{"leftmargin": $left, "topmargin": $top, "width": $width, "height": $height, "visibility": "visible"$extra}"""

    private val design = JSONObject(
        """{"metadata": {"name": "Small"}, "highColor": "#FFFF00", "midColor": "#00FF00", "lowColor": "#FF0000",
            "background": ${view(0, 0, 400, 400)},
            "sgv": ${view(40, 150, 160, 80, ", \"textsize\": 60, \"gravity\": \"right\"")},
            "direction": ${view(210, 160, 60, 60)},
            "delta": ${view(270, 160, 60, 30, ", \"textsize\": 20")},
            "timestamp": ${view(270, 190, 60, 30, ", \"textsize\": 20")},
            "time": ${view(100, 60, 200, 70, ", \"textsize\": 60")},
            "day_name": ${view(150, 30, 100, 24, ", \"textsize\": 18, \"allCaps\": true")},
            "complication1": ${view(150, 300, 100, 40, ", \"color\": \"#40000000\"")},
            "hour_hand": ${view(0, 0, 400, 400)},
            "animation": {"layers": [
                {"text": "clock()", "x": "200", "y": "260", "size": 30, "align": "center"},
                {"shape": "circle", "count": 20, "x": "u * 400", "y": "100 + 10 * sin(t)"}
            ]}}"""
    )

    private fun read() = WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }) { null }.read(design)

    @Test
    fun aSmallDesignGivesAValidFace() {
        val animation = read()
        val plan = WffPlanner().plan(design, animation)
        val face = WffFaceWriter(providerApp = "info.nightscout.androidaps").write(design, animation, plan)
        val file = File.createTempFile("watchface", ".xml").apply { writeText(face.xml) }
        assertWithMessage("%s", face.xml).that(WatchFaceXmlValidator().validate(file.path, "4")).isTrue()
        // The glucose in its slot, coloured by the thresholds; the arrow; the age; the clock and the user slot
        assertThat(face.xml).contains("""slotId="10"""")
        assertThat(face.xml).contains("info.nightscout.androidaps/app.aaps.wear.complications.wff.WffGlucoseComplication")
        assertThat(face.xml).contains("[COMPLICATION.MONOCHROMATIC_IMAGE]")
        assertThat(face.xml).contains("[COMPLICATION.RANGED_VALUE_VALUE] &gt; 180")
        assertThat(face.xml).contains("WffGlucoseAgeComplication")
        assertThat(face.xml).contains("<TimeText")
        assertThat(face.xml).contains("""slotId="1"""")
        assertThat(face.xml).contains("<Upper>")
        assertThat(face.xml).contains("<HourHand")
        assertThat(face.strings).containsKey("slot_1")
        assertThat(face.notWritten).isEmpty()
    }

    @Test
    fun theStaticCopyLeavesOutWhatIsLive() {
        val animation = read()
        val plan = WffPlanner().plan(design, animation)
        val static = WffStaticDesign.of(design, animation, plan)!!
        assertThat(static.getJSONObject("sgv").getString("visibility")).isEqualTo("gone")
        assertThat(static.getJSONObject("time").getString("visibility")).isEqualTo("gone")
        assertThat(static.getJSONObject("background").getString("visibility")).isEqualTo("visible")
        val layers = static.getJSONObject("animation").getJSONArray("layers")
        assertThat(layers.getJSONObject(0).getString("visible")).isEqualTo("0")
        assertThat(layers.getJSONObject(1).has("visible")).isFalse()
    }

    @Test
    fun convertTheZips() {
        val zips = System.getenv("WFF_ZIPS")
        assumeTrue("Runs only with WFF_ZIPS set, see the class doc", zips != null)
        val cache = File(System.getProperty("user.home"), ".cache/aaps-wff")
        val converter = WffDevConverter(
            RuntimeEnvironment.getApplication(),
            client = System.getenv("WFF_CLIENT") ?: "info.nightscout.androidaps",
            packCli = File(System.getenv("PACK_CLI") ?: File(cache, "pack/target/release/pack-cli").path),
            key = File(System.getenv("WFF_KEY") ?: File(cache, "dev-key.pem").path)
        )
        val out = File(System.getenv("WFF_OUT") ?: "build/wff").apply { mkdirs() }
        val source = File(zips!!)
        val list = if (source.isDirectory) source.listFiles { f -> f.extension == "zip" }!!.sorted() else listOf(source)
        val summary = StringBuilder()
        for (zip in list) {
            val result = runCatching { converter.convert(zip, out) }
            summary.append(zip.name).append(": ").append(
                result.fold({ r -> if (r.token != null) "valid, ${r.apk!!.length() / 1024} KB" else if (r.apk != null) "built, NOT valid" else "not built" }, { e -> "failed: $e" })
            ).append('\n')
        }
        File(out, "summary.txt").writeText(summary.toString())
        println(summary)
    }
}
