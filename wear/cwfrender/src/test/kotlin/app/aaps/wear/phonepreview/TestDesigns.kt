package app.aaps.wear.phonepreview

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Small designs for the tests of the renderer, made here so the tests need no design zip. Each one
 * goes through a zip, as a design the phone loads. Must run under Robolectric: the pictures are made
 * with Android bitmaps.
 */
internal object TestDesigns {

    /** A dark face with the time and the glucose. */
    fun dark(): CwfData = load("Test_Dark", face("Test Dark", "#101828"), "#101828")

    /** A face like [dark] in a light colour. */
    fun light(): CwfData = load("Test_Light", face("Test Light", "#F2EAD8"), "#F2EAD8")

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
