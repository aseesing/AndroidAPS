// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.wffconvert

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.shared.impl.weardata.toDrawable
import app.aaps.wear.R
import app.aaps.wear.phonepreview.CwfPreviewRenderer
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.wff.WffFaceWriter
import app.aaps.wear.watchfaces.wff.WffPlan
import app.aaps.wear.watchfaces.wff.WffPlanner
import app.aaps.wear.watchfaces.wff.WffStaticDesign
import org.json.JSONObject
import org.mockito.kotlin.mock
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Turns a design zip into a Watch Face Format face on a dev machine (design doc section 2.2, steps 1
 * to 7, without the phone): read, plan, bake with the watch's own renderer, write `watchface.xml`,
 * build the APK with Pack, and check it with the Watch Face Push validator.
 *
 * Runs under Robolectric, like the phone preview tests: the engine draws exactly as on the watch.
 *
 * @param client the application id of the wear app that installs the face and feeds its slots
 * @param packCli the `pack-cli` binary, see `tools/build-pack.sh`
 * @param key the PEM file with the signing certificate and key, see `tools/build-pack.sh`
 */
class WffDevConverter(
    private val app: Application,
    private val client: String,
    private val packCli: File,
    private val key: File
) {

    /**
     * What came out for one design.
     *
     * @param apk the face, or null when it was not built (see [lint])
     * @param token the validator's token, or null when the face did not pass
     */
    data class Result(val lint: String, val apk: File?, val token: String?)

    fun convert(zip: File, out: File): Result {
        val cwf = ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)?.cwfData ?: error("${zip.name} is not a design zip")
        val design = JSONObject(cwf.json)
        val animation = WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }) { null }.read(design)
        val plan = WffPlanner().plan(design, animation)
        val face = WffFaceWriter(providerApp = client).write(design, animation, plan)
        val lint = plan.lint() + face.notWritten.joinToString("") { " ! not written: $it\n" }
        val dir = File(out, zip.nameWithoutExtension).apply { deleteRecursively(); mkdirs() }
        File(dir, "lint.txt").writeText(lint)
        if (!plan.canBuild) return Result(lint, null, null)
        val static = WffStaticDesign.of(design, animation, plan) ?: return Result(lint + "!! the animation has errors, so the static picture cannot be made\n", null, null)

        // The face folder that Pack reads
        val res = File(dir, "face/res")
        File(res, "raw").mkdirs()
        File(res, "raw/watchface.xml").writeText(face.xml)
        File(res, "xml").mkdirs()
        File(res, "xml/watch_face_info.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<WatchFaceInfo><Preview value="@drawable/preview" /><MultipleInstancesAllowed value="false" /><Editable value="true" /></WatchFaceInfo>
"""
        )
        File(res, "values").mkdirs()
        File(res, "values/strings.xml").writeText(
            "<resources>\n" + face.strings.entries.joinToString("") { (name, text) -> "    <string name=\"$name\">${escape(text)}</string>\n" } + "</resources>\n"
        )
        File(res, "drawable").mkdirs()
        val now = System.currentTimeMillis()
        bake(CwfData(static.toString(), cwf.metadata, cwf.resData), now).save(File(res, "drawable/background.png"))
        bake(cwf, now).save(File(res, "drawable/preview.png"))
        for ((file, resource) in face.hands) {
            val drawable = cwf.resData[file]?.toDrawable(app.resources) ?: continue
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { b ->
                drawable.setBounds(0, 0, SIZE, SIZE)
                drawable.draw(Canvas(b))
            }.save(File(res, "drawable/$resource.png"))
        }
        if (face.fonts.isNotEmpty()) File(res, "font").mkdirs()
        for ((file, resource) in face.fonts) {
            val font = cwf.resData[file] ?: continue
            File(res, "font/$resource.${font.format.extension}").writeBytes(font.value)
        }
        File(dir, "face/AndroidManifest.xml").writeText(manifest())

        // Pack: aligned and signed APK. It says "Error:" and still ends with 0 on a mistake.
        val apkBase = File(dir, "face")
        val pack = run(listOf(packCli.path, File(dir, "face").path, apkBase.path, key.path))
        val apk = File(dir, "face.apk")
        if (!pack.contains("Compiled, aligned & signed successfully") || !apk.isFile) return Result(lint + "!! Pack failed:\n$pack\n", null, null)
        File(dir, "face.aab").delete()

        // The validator of Watch Face Push, as the wear build runs it for its own faces
        val validator = run(listOf(javaBin(), "-cp", validatorJar(), "com.google.android.wearable.watchface.validator.cli.DwfValidation", "-a", apk.path, "-p", client))
        val token = TOKEN.find(validator)?.groupValues?.get(1)
        File(dir, "validator.txt").writeText(validator)
        token?.let { File(dir, "token.txt").writeText(it) }
        return Result(lint, apk, token)
    }

    /** The design [cwf] at rest, as the watch draws it, [SIZE] pixels wide, without the wake-up animation. */
    private fun bake(cwf: CwfData, now: Long): Bitmap {
        val renderer = CwfPreviewRenderer(app, mock<AAPSLogger>(), "wff${counter++}")
        try {
            renderer.applyWatchSettings(mapOf(app.getString(R.string.key_wake_animation_length) to "0"))
            values(now).forEach(renderer::apply)
            renderer.setDesign(cwf)
            return renderer.frame(SIZE, now).copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            renderer.release()
        }
    }

    private fun manifest() = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$client.watchfacepush.design" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36" />
    <uses-feature android:name="android.hardware.type.watch" />
    <application android:label="@string/app_name" android:hasCode="false">
        <property android:name="com.google.wear.watchface.format.version" android:value="4" />
        <property android:name="com.google.wear.watchface.format.publisher" android:value="AAPS" />
    </application>
</manifest>
"""

    /** Sample values, so the preview shows a face with data. The static picture holds none of them. */
    private fun values(now: Long): List<EventData> = listOf(
        EventData.SingleBg(
            dataset = 0, timeStamp = now - 120_000L, sgvString = "120", glucoseUnits = "mg/dl", slopeArrow = "→",
            delta = "+2", deltaDetailed = "+2", avgDelta = "+1", avgDeltaDetailed = "+1", sgvLevel = 0, sgv = 120.0, high = 180.0, low = 70.0, color = 0
        ),
        EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "1.25", iobDetail = "(0.90|0.35)", cob = "12g", currentBasal = "0.85U/h",
            battery = "78", rigBattery = "--", openApsStatus = now - 180_000L, bgi = "-0.4", batteryLevel = 1,
            tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0
        ),
        EventData.GraphData(ArrayList((0 until 36).map { i ->
            EventData.SingleBg(dataset = 0, timeStamp = now - (35 - i) * 300_000L, sgv = 125.0 + 35.0 * sin(i * PI / 14.0), high = 180.0, low = 70.0)
        }))
    )

    private fun Bitmap.save(file: File) = file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.MINUTES)
        return output
    }

    private fun javaBin() = File(System.getProperty("java.home"), "bin/java").path

    /** The validator jar is on the test class path (a test dependency of this module). */
    private fun validatorJar(): String =
        System.getProperty("java.class.path").split(File.pathSeparator).firstOrNull { "validator-push-cli" in it } ?: error("the validator is not on the class path")

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")

    private companion object {

        const val SIZE = 400
        val TOKEN = Regex("generated token: (\\S+)")
        var counter = 0
    }
}
