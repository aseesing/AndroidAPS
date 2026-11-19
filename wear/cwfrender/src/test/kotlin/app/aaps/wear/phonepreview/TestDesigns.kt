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

    /** A dark face with the time and glucose, a wake-up animation of 15 s, and slot 1 as a row of white text. */
    fun animated(): CwfData = load("Test_Animated", face("Test Animated", "#101828").apply {
        put("animation", JSONObject().put("durationMs", 15_000).put("fps", 24).put("fadeMs", 600).put(
            "layers", JSONArray().put(
                JSONObject().put("use", "rain").put("count", 140).put("seed", 11).put("speed", 470).put("angle", -14).put("length", 28)
                    .put("thickness", 1.6).put("color", "#5E86B8").put("alpha", 64)
            )
        ))
        put("complication1", slot(left = 256, top = 196, width = 103, height = 24, color = "#FFFFFF"))
        getJSONObject("metadata").put("key_show_complication1", "true")
    }, "#101828")

    /** A face like [animated] in other colours, without animation and without slots. */
    fun still(): CwfData = load("Test_Still", face("Test Still", "#3A1020"), "#3A1020")

    /** A face that asks for the place (`"needsPlace": true`), so it keeps the rule for when it is day, see `PlaceRuleKeeping`. */
    fun withPlace(): CwfData = load("Test_Place", face("Test Place", "#101828").put("needsPlace", true), "#101828")

    /** A light face with slot 2 at 6 o'clock (left 142, top 318, 116 x 32), in the ink [STEPS_INK]. */
    fun steps(): CwfData = load("Test_Steps", face("Test Steps", "#F2EAD8").apply {
        put("complication2", slot(left = 142, top = 318, width = 116, height = 32, color = STEPS_INK))
        getJSONObject("metadata").put("key_show_complication2", "true")
    }, "#F2EAD8")

    /** The colour of the text and icon of slot 2 in [steps]. */
    const val STEPS_INK = "#1C2A5A"

    private fun face(name: String, background: String): JSONObject = JSONObject()
        .put("metadata", JSONObject().put("name", name).put("cwf_version", "3.0").put("key_show_date", "false"))
        .put("background", JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put("visibility", "visible"))
        .put("hour", text(left = 107, top = 26, width = 76, height = 74, size = 64, color = "#00F0FF", gravity = "right"))
        .put("minute", text(left = 203, top = 26, width = 74, height = 74, size = 64, color = "#00F0FF", gravity = "left"))
        .put("sgv", text(left = 28, top = 102, width = 142, height = 102, size = 78, color = contrastTo(background), gravity = "right"))

    private fun text(left: Int, top: Int, width: Int, height: Int, size: Int, color: String, gravity: String): JSONObject = JSONObject()
        .put("width", width).put("height", height).put("topmargin", top).put("leftmargin", left).put("rotation", 0).put("visibility", "visible")
        .put("textsize", size).put("gravity", gravity).put("font", "default").put("fontStyle", "normal").put("fontColor", color)

    private fun slot(left: Int, top: Int, width: Int, height: Int, color: String): JSONObject = JSONObject()
        .put("width", width).put("height", height).put("topmargin", top).put("leftmargin", left).put("visibility", "visible")
        .put("color", "#00000000").put("borderRadius", 0).put("fontColor", color).put("iconColor", color)
        .put("ringPrimaryColor", "#00000000").put("ringSecondaryColor", "#00000000").put("textsize", 20).put("gravity", "center")

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
