// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.wffconvert

import android.app.Application
import android.graphics.Color
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.wff.WffAssets
import app.aaps.wear.watchfaces.wff.WffDesignParts
import app.aaps.wear.watchfaces.wff.WffFaceWriter
import app.aaps.wear.watchfaces.wff.WffPlanner
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.samsung.watchface.WatchFaceXmlValidator
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The writer and the design copies of the converter to Watch Face Format faces, on a small design.
 * The whole converter (baking, Pack, the push validator, the emulator) runs in the watch face designs
 * repository, see its `docs/wff-apk.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WffConvertTest {

    private fun view(left: Int, top: Int, width: Int, height: Int, extra: String = "") =
        """{"leftmargin": $left, "topmargin": $top, "width": $width, "height": $height, "visibility": "visible"$extra}"""

    private val design = JSONObject(
        """{"metadata": {"name": "Small", "key_show_date": "true", "key_show_complication1": "true"}, "highColor": "#FFFF00", "midColor": "#00FF00", "lowColor": "#FF0000",
            "background": ${view(0, 0, 400, 400)},
            "sgv": ${view(40, 150, 160, 80, ", \"textsize\": 60, \"gravity\": \"right\"")},
            "direction": ${view(210, 160, 60, 60)},
            "delta": ${view(270, 160, 60, 30, ", \"textsize\": 20")},
            "timestamp": ${view(270, 190, 60, 30, ", \"textsize\": 20, \"dynData\": \"age\"")},
            "time": ${view(100, 60, 200, 70, ", \"textsize\": 60, \"background\": \"Frame\"")},
            "day_name": ${view(150, 30, 100, 24, ", \"textsize\": 18, \"allCaps\": true")},
            "complication1": ${view(150, 300, 100, 40, ", \"color\": \"#40000000\"")},
            "iob1": ${view(20, 240, 60, 30)},
            "chart": ${view(40, 340, 320, 50)},
            "cover_plate": ${view(0, 0, 400, 400)},
            "hour_hand": ${view(0, 0, 400, 400)},
            "dynData": {"age": {"valueKey": "timestamp", "minData": 0, "maxData": 20, "textvalue1": "%.0f'", "invalidTextvalue": "--", "fontColor1": "#FFFFFF", "fontColor2": "#FF0000"}},
            "animation": {"layers": [
                {"text": "clock()", "x": "200", "y": "260", "size": 30, "align": "center"},
                {"text": "glucose(v_sgv)", "x": "200", "y": "290", "align": "center", "colors": ["#FF0000", "#00FF00", "#FFFF00"], "colorIndex": "v_sgvLevel + 1"},
                {"shape": "circle", "count": 20, "x": "u * 400", "y": "100 + 10 * sin(t)", "slot": "front"},
                {"shape": "circle", "x": "100 + v_iob", "y": "100"}
            ]}}"""
    )

    private val animation = WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }) { null }.read(design)
    private val plan = WffPlanner().plan(design, animation)

    @Test
    fun aSmallDesignGivesAValidFace() {
        val assets = WffAssets(
            statics = mapOf(0 to WffAssets.Picture("static_0", DesignRect.FULL), 1 to WffAssets.Picture("static_1", DesignRect(10f, 10f, 380f, 380f))),
            loops = listOf(
                WffAssets.Loop(layers = setOf(2), frames = listOf("loop0_000", "loop0_001"), fps = 15, box = DesignRect(0f, 80f, 400f, 40f), loopCount = 3, blend = "PLUS", condition = "[MONTH] == 12 && [DAY] >= 24"),
                WffAssets.Loop(layers = emptySet(), frames = listOf("sky_000", "sky_001"), fps = 6, box = DesignRect(0f, 0f, 400f, 200f), overRun = 0)
            )
        )
        val face = WffFaceWriter(providerApp = "info.nightscout.androidaps").write(design, animation, plan, assets)
        val file = File.createTempFile("watchface", ".xml").apply { writeText(face.xml) }
        assertWithMessage("%s", face.xml).that(WatchFaceXmlValidator().validate(file.path, "4")).isTrue()
        val xml = face.xml
        // The pictures in paint order: the bottom one, the data picture, the one over it, and the loop on top
        val order = listOf("\"static_0\"", "WffDataComplication", "\"static_1\"", "\"loop0_000\"").map { xml.indexOf(it) }
        assertThat(order).isInOrder()
        assertThat(order.first()).isAtLeast(0)
        assertThat(xml).contains("""<PartAnimatedImage x="0" y="80" width="400" height="40" blendMode="PLUS">""")
        assertThat(xml).contains("""<Expression name="days">[MONTH] == 12 &amp;&amp; [DAY] &gt;= 24</Expression>""")
        // The bottom picture in motion lies right over it, under the data picture
        assertThat(xml.indexOf("\"sky_000\"")).isGreaterThan(xml.indexOf("\"static_0\""))
        assertThat(xml.indexOf("\"sky_000\"")).isLessThan(xml.indexOf("WffDataComplication"))
        // The glucose in its slot, coloured by the thresholds, twice (the view and the text layer); the arrow, the delta
        assertThat(xml).contains("""slotId="10"""")
        assertThat(xml).contains("info.nightscout.androidaps/app.aaps.wear.complications.wff.WffGlucoseComplication")
        assertThat(xml).contains("[COMPLICATION.MONOCHROMATIC_IMAGE]")
        assertThat(xml).contains("[COMPLICATION.TITLE]")
        assertThat(xml).contains("[COMPLICATION.RANGED_VALUE_VALUE] &gt; 180")
        assertThat(Regex("COMPLICATION.TEXT").findAll(xml).count()).isAtLeast(6)
        // The age: whole minutes with the design's sign, white below 10 minutes and red from there
        assertThat(xml).contains("WffGlucoseAgeComplication")
        assertThat(xml).contains("<Template>%s'<Parameter expression=\"floor([COMPLICATION.RANGED_VALUE_VALUE])\" />")
        assertThat(xml).contains("[COMPLICATION.RANGED_VALUE_VALUE] &lt; 10</Expression>")
        assertThat(xml).contains("<![CDATA[--]]>")
        // The live parts
        assertThat(Regex("<TimeText").findAll(xml).count()).isEqualTo(2)
        // The wearer's slot starts with the heart rate of AAPS, as on the code-based face, because the design turns it on
        assertThat(xml).contains("""slotId="1"""")
        assertThat(xml).contains("info.nightscout.androidaps/app.aaps.wear.health.HeartRateComplication")
        assertThat(xml).contains("<Upper>")
        assertThat(xml).contains("<HourHand")
        assertThat(face.strings.keys).containsAtLeast("app_name", "slot_1", "glucose", "glucose_age", "data")
        assertThat(face.notWritten).isEmpty()
    }

    @Test
    fun eachCopyOfTheDesignShowsOnlyItsParts() {
        val parts = WffDesignParts(design, animation, plan)
        assertThat(parts.isUsable).isTrue()
        fun shown(copy: JSONObject) = copy.keys().asSequence().filter { copy.optJSONObject(it)?.optString("visibility") == "visible" }.toSet()
        fun visibleLayers(copy: JSONObject) = copy.getJSONObject("animation").getJSONArray("layers").let { l -> (0 until l.length()).filter { l.getJSONObject(it).optString("visible") != "0" } }

        val bottom = parts.static(0)
        // The time stays for its frame picture, without its text
        assertThat(shown(bottom)).containsExactly("background", "time")
        assertThat(bottom.getJSONObject("time").getString("fontColor")).isEqualTo("#00000000")
        assertThat(visibleLayers(bottom)).isEmpty()

        assertThat(shown(parts.static(1))).containsExactly("background", "cover_plate")
        assertThat(parts.static(1).getJSONObject("background").has("color")).isFalse()

        val data = parts.data()
        assertThat(shown(data)).containsExactly("background", "iob1", "chart")
        assertThat(visibleLayers(data)).containsExactly(3)

        assertThat(visibleLayers(parts.layers(setOf(2)))).containsExactly(2)
        assertThat(shown(parts.layers(setOf(2)))).containsExactly("background")
    }

    @Test
    fun aSeeThroughCopyGetsAClearBackgroundPicture() {
        val source = CwfData(design.toString(), mutableMapOf(), mutableMapOf())
        val data = WffDesignParts.dataOf(source)!!
        assertThat(data.resData.keys).contains("Background")
        // The picture is really clear: a wrong pixel here once put a blue wash over the whole face
        val pixel = ImageIO.read(ByteArrayInputStream(data.resData.getValue("Background").value))
        assertThat(pixel.width).isEqualTo(1)
        assertThat(pixel.getRGB(0, 0) ushr 24).isEqualTo(0)
        assertThat(JSONObject(data.json).getJSONObject("sgv").getString("visibility")).isEqualTo("gone")
        // A design with nothing for the data picture has none
        val plain = JSONObject("""{"background": ${view(0, 0, 400, 400)}, "time": ${view(100, 60, 200, 70)}}""")
        assertThat(WffDesignParts.dataOf(CwfData(plain.toString(), mutableMapOf(), mutableMapOf()))).isNull()
    }
}
