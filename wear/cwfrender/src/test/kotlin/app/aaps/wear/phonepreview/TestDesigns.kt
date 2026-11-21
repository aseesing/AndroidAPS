// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Small designs for the tests of the renderer. They are made here, so the tests need no design zip:
 * the real designs live in their own repository. Each one goes through a zip, as a design the phone loads.
 * Must run under Robolectric: the pictures are made with Android bitmaps.
 */
internal object TestDesigns {

    /**
     * A dark face with the time and glucose, and a wake-up animation of 15 s.
     * The animation is falling streaks from a template of the design: the app has no templates of its own.
     */
    fun animated(): CwfData = load("Test_Animated", face("Test Animated", "#101828").apply {
        put("animation", JSONObject().put("durationMs", 15_000).put("fps", 24).put("fadeMs", 600).put("templates", JSONObject(STREAKS)).put(
            "layers", JSONArray().put(
                JSONObject().put("use", "streaks").put("count", 140).put("seed", 11).put("speed", 470).put("color", "#5E86B8").put("alpha", 0.25)
            )
        ))
    }, "#101828")

    /** A template of falling streaks, as a design writes it. */
    private const val STREAKS = $$"""
        {"streaks": {
          "params": {"count": 40, "seed": 1, "speed": 400, "color": "#A6C8FF", "alpha": 0.8},
          "layers": [{
            "shape": "line", "colors": ["$color"], "slot": "back", "count": "$count", "seed": "$seed",
            "x": "r1 * 400", "y": "mod(r2 * 440 + t * $speed * (0.8 + 0.4 * r3), 440) - 20",
            "length": 28, "thickness": 1.6, "alpha": "$alpha * env"
          }]
        }}
    """

    /**
     * A dark face with a thick line in [REST_INK] that stays at rest after the animation (`"rest"`),
     * around the middle of the lower half. The watch leaves rest layers out of the always-on face.
     */
    fun withRestLayer(): CwfData = load("Test_Rest", face("Test Rest", "#101828").apply {
        put("animation", JSONObject().put("durationMs", 2_000).put("layers", JSONArray().put(
            JSONObject().put("shape", "line").put("colors", JSONArray().put(REST_INK)).put("x", 200).put("y", 300).put("length", 80).put("thickness", 24).put("rest", 1)
        )))
    }, "#101828")

    const val REST_INK = "#FF2020"

    /**
     * A dark face that shows the glucose as text layers of its animation, as the design "BG Reactor"
     * does: a line in [GLUCOSE_INK] in the upper half while there is a reading (`v_sgv`), and a line in
     * [NO_DATA_INK] in the lower half while there is none (`v_timestamp`). No glucose view.
     */
    fun withGlucoseLayers(): CwfData = load("Test_Glucose_Layers", face("Test Glucose Layers", "#101828").apply {
        remove("sgv")
        put("animation", JSONObject().put("durationMs", 1_000).put("layers", JSONArray()
            .put(textLayer("if(missing(v_sgv), '', 'HHHH')", y = 150, color = GLUCOSE_INK))
            .put(textLayer("if(missing(v_timestamp), 'HHHH', '')", y = 300, color = NO_DATA_INK))
        ))
    }, "#101828")

    const val GLUCOSE_INK = "#20FF20"
    const val NO_DATA_INK = "#FF2020"

    private fun textLayer(text: String, y: Int, color: String): JSONObject = JSONObject()
        .put("text", text).put("fontSize", 60).put("align", "center").put("valign", "middle").put("x", 200).put("y", y).put("color", color).put("slot", "front").put("rest", 0)

    /** A face like [animated] in other colours, without animation. */
    fun still(): CwfData = load("Test_Still", face("Test Still", "#3A1020"), "#3A1020")

    /** A face that asks for the place (`"needsPlace": true`), so it keeps the rule for when it is day, see `PlaceRuleKeeping`. */
    fun withPlace(): CwfData = load("Test_Place", face("Test Place", "#101828").put("needsPlace", true), "#101828")

    /**
     * A dark face whose glucose text is switched by the design. [show] is the value of `key_show_bg`
     * in the metadata: a text (`"true"`) or a JSON boolean (`true`).
     */
    fun withBgSwitch(show: Any): CwfData = load("Test_Bg_Switch", face("Test Bg Switch", "#101828").apply {
        getJSONObject("metadata").put("key_show_bg", show)
    }, "#101828")

    /**
     * A dark face that shows all five complication slots as rows of [SLOT_INK], one below the other:
     * slot n is at [slotBox] of n.
     */
    fun withSlots(): CwfData = load("Test_Slots", face("Test Slots", "#101828").apply {
        for (n in 1..5) {
            val box = slotBox(n)
            put("complication$n", slot(left = box[0], top = box[1], width = box[2] - box[0], height = box[3] - box[1], color = SLOT_INK))
            getJSONObject("metadata").put("key_show_complication$n", "true")
        }
    }, "#101828")

    /** The colour of the text and the icon of the slots of [withSlots]. */
    const val SLOT_INK = "#FFE600"

    /** Where slot [n] (1 to 5) of [withSlots] is: left, top, right, bottom in the 400 design space. */
    fun slotBox(n: Int): IntArray = intArrayOf(240, 110 + (n - 1) * 50, 380, 150 + (n - 1) * 50)

    private fun slot(left: Int, top: Int, width: Int, height: Int, color: String): JSONObject = JSONObject()
        .put("width", width).put("height", height).put("topmargin", top).put("leftmargin", left).put("visibility", "visible")
        .put("color", "#00000000").put("borderRadius", 0).put("fontColor", color).put("iconColor", color)
        .put("ringPrimaryColor", "#00000000").put("ringSecondaryColor", "#00000000").put("textsize", 20).put("gravity", "center")

    private fun face(name: String, background: String): JSONObject = JSONObject()
        .put("metadata", JSONObject().put("name", name).put("cwf_version", "3.0").put("key_show_date", "false"))
        .put("background", JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put("visibility", "visible"))
        .put("hour", text(left = 107, top = 26, width = 76, height = 74, size = 64, color = "#00F0FF", gravity = "right"))
        .put("minute", text(left = 203, top = 26, width = 74, height = 74, size = 64, color = "#00F0FF", gravity = "left"))
        .put("sgv", text(left = 28, top = 102, width = 142, height = 102, size = 78, color = contrastTo(background), gravity = "right"))

    private fun text(left: Int, top: Int, width: Int, height: Int, size: Int, color: String, gravity: String): JSONObject = JSONObject()
        .put("width", width).put("height", height).put("topmargin", top).put("leftmargin", left).put("rotation", 0).put("visibility", "visible")
        .put("textsize", size).put("gravity", gravity).put("font", "default").put("fontStyle", "normal").put("fontColor", color)

    private fun contrastTo(background: String): String =
        if (Color.luminance(Color.parseColor(background)) > 0.5f) "#202020" else "#E0E0E0"

    /** [json] with a plain [background] picture, zipped and loaded as the phone loads a design. */
    private fun load(fileName: String, json: JSONObject, background: String): CwfData {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("CustomWatchface.json"))
                zip.write(json.toString(4).toByteArray())
                zip.putNextEntry(ZipEntry("${ResFileMap.CUSTOM_WATCHFACE.fileName}.png"))
                zip.write(png(40, background))
                zip.putNextEntry(ZipEntry("${ResFileMap.BACKGROUND.fileName}.png"))
                zip.write(png(400, background))
                zip.closeEntry()
            }
        }.toByteArray()
        return ZipWatchfaceFormat.loadCustomWatchface(zip, "$fileName.zip", authorization = true)!!.cwfData
    }

    private fun png(size: Int, color: String): ByteArray = ByteArrayOutputStream().also {
        createBitmap(size, size).apply { eraseColor(Color.parseColor(color)) }.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()
}
