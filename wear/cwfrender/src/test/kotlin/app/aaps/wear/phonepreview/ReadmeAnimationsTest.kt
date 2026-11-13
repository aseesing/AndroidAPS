// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.createBitmap
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemClock
import org.w3c.dom.Node
import java.awt.image.BufferedImage
import java.io.File
import java.time.Duration
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.PI
import kotlin.math.sin

/**
 * Makes the animated pictures of the watch faces at the top of `.github/README.md`.
 *
 * Each design is drawn by the watch's own code (the same renderer as the live preview on the phone),
 * with its wake-up animation, and written as an animated GIF to `.github/images`.
 *
 * Not part of the normal test run. Run it with:
 * `CWF_README_GIFS=1 ./gradlew :wear:cwfrender:testDebugUnitTest --tests "*ReadmeAnimationsTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ReadmeAnimationsTest {

    /**
     * One picture: a design, the weather it shows, and the phone battery (low ones blink). A design
     * without a wake-up animation ([animated] false) gets one frame: the others would be the same.
     */
    private data class Face(
        val zip: String, val gif: String, val weather: String = EventData.Weather.CLEAR, val phoneBattery: Int = 64, val animated: Boolean = true
    )

    private val faces = listOf(
        Face("Cyberpunk_Tech", "cyberpunk_tech", phoneBattery = 18),
        Face("Cyberpunk_Weather", "cyberpunk_weather", weather = EventData.Weather.PARTLY),
        Face("Cyberpunk_Yellow", "cyberpunk_yellow", phoneBattery = 15),
        Face("Wilds_Weather", "wilds_weather"),
        Face("AAPS_HR_Steps", "aaps_hr_steps", animated = false)
    )

    @Test
    fun makeTheReadmeAnimations() {
        assumeTrue("Only on request, see the class doc", System.getenv("CWF_README_GIFS") != null)
        ShadowSettings.set24HourTimeFormat(true)
        val out = File("../../.github/images").apply { mkdirs() }
        val check = File("build/readme-check").apply { mkdirs() }
        faces.forEachIndexed { index, face ->
            val frames = frames(face, "readme_$index")
            File(check, "${face.gif}.png").writeSheet(frames)
            File(out, "${face.gif}.gif").writeFrames(frames)
        }
    }

    /** A few frames side by side, to look at the animation without a GIF viewer. */
    private fun File.writeSheet(frames: List<Bitmap>) {
        val picks = listOf(0, 8, 20, 35, 50, frames.size - 1).map { it.coerceAtMost(frames.size - 1) }.distinct()
        val size = frames[0].width
        val sheet = createBitmap(size * picks.size, size)
        Canvas(sheet).apply { picks.forEachIndexed { i, f -> drawBitmap(frames[f], (i * size).toFloat(), 0f, null) } }
        outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
    }

    private fun frames(face: Face, storage: String): List<Bitmap> {
        val app = RuntimeEnvironment.getApplication()
        val renderer = CwfPreviewRenderer(app, mock(), storage)
        // A short wake, so the picture shows it start and end
        renderer.watchSettings.edit().putString(app.getString(R.string.key_wake_animation_length), WAKE_MS.toString()).commit()
        app.getSharedPreferences("${storage}_aaps_health_values", Application.MODE_PRIVATE).edit()
            .putInt("heart_rate", 72).putLong("heart_rate_at", System.currentTimeMillis())
            .putLong("steps", 6482).putLong("steps_at", System.currentTimeMillis()).commit()
        val now = System.currentTimeMillis()
        values(now, face).forEach(renderer::apply)
        val zip = File("../cwf-designs/${face.zip}.zip").readBytes()
        renderer.setDesign(ZipWatchfaceFormat.loadCustomWatchface(zip, "${face.zip}.zip", authorization = true)!!.cwfData)
        return (0 until if (face.animated) FRAMES else 1).map {
            watch(renderer.frame(SIZE, now)).also { ShadowSystemClock.advanceBy(Duration.ofMillis(FRAME_MS)) }
        }
    }

    private fun values(now: Long, face: Face): List<EventData> = listOf(
        EventData.SingleBg(
            dataset = 0, timeStamp = now - 120_000L, sgvString = "6,7", glucoseUnits = "mmol", slopeArrow = "→",
            delta = "+0,1", deltaDetailed = "+0,12", avgDelta = "+0,1", avgDeltaDetailed = "+0,08", sgvLevel = 0, sgv = 121.0,
            high = 180.0, low = 70.0, color = 0
        ),
        EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "1.25", iobDetail = "(0.90|0.35)", cob = "12g", currentBasal = "0.85U/h",
            battery = face.phoneBattery.toString(), rigBattery = "--", openApsStatus = now - 180_000L, bgi = "-0.4", batteryLevel = 1,
            tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0
        ),
        EventData.GraphData(ArrayList((0 until 36).map { i ->
            EventData.SingleBg(dataset = 0, timeStamp = now - (35 - i) * 300_000L, sgv = 125.0 + 35.0 * sin(i * PI / 14.0), high = 180.0, low = 70.0)
        })),
        EventData.Weather(now, face.weather)
    )

    /** [face] as a round watch: a dark bezel around it, and see-through outside it. */
    private fun watch(face: Bitmap): Bitmap {
        val full = SIZE + 2 * BEZEL
        val out = createBitmap(full, full)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val centre = full / 2f
        paint.color = Color.rgb(28, 30, 34)
        canvas.drawCircle(centre, centre, centre, paint)
        paint.color = Color.rgb(70, 74, 82)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(centre, centre, centre - BEZEL + 1, paint)
        // The face, cut to a circle
        val round = createBitmap(SIZE, SIZE)
        Canvas(round).apply {
            drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG))
            drawBitmap(face, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
        }
        canvas.drawBitmap(round, BEZEL.toFloat(), BEZEL.toFloat(), null)
        face.recycle()
        round.recycle()
        return out
    }

    /** Writes [frames] as a GIF that plays for ever, [FRAME_MS] per frame. */
    private fun File.writeFrames(frames: List<Bitmap>) {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(this.apply { delete() }).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            frames.forEachIndexed { index, bitmap ->
                val image = bitmap.toImage()
                val type = ImageTypeSpecifier.createFromRenderedImage(image)
                val metadata = writer.getDefaultImageMetadata(type, null)
                val root = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode
                root.child("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "restoreToBackgroundColor")
                    setAttribute("delayTime", (FRAME_MS / 10).toString())
                }
                if (index == 0) root.child("ApplicationExtensions").appendChild(IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE")
                    setAttribute("authenticationCode", "2.0")
                    userObject = byteArrayOf(1, 0, 0) // play for ever
                })
                metadata.setFromTree(metadata.nativeMetadataFormatName, root)
                writer.writeToSequence(IIOImage(image, null, metadata), null)
            }
            writer.endWriteSequence()
        }
        frames.forEach { it.recycle() }
    }

    private fun IIOMetadataNode.child(name: String): IIOMetadataNode =
        (0 until childNodes.length).map { childNodes.item(it) }.firstOrNull { it.nodeName == name } as? IIOMetadataNode
            ?: IIOMetadataNode(name).also { appendChild(it as Node) }

    /** A copy for ImageIO. Pixels outside the watch stay fully see-through, the others fully solid. */
    private fun Bitmap.toImage(): BufferedImage {
        val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
        for (i in pixels.indices) pixels[i] = if (Color.alpha(pixels[i]) < 128) 0 else pixels[i] or (0xFF shl 24)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, pixels, 0, width) }
    }

    private companion object {

        const val SIZE = 300
        const val BEZEL = 10
        const val WAKE_MS = 4000L
        const val FRAME_MS = 80L

        /** The wake, and a moment of the still face after it. */
        const val FRAMES = ((WAKE_MS + 1200L) / FRAME_MS).toInt()
    }
}
