package app.aaps.wear.phonepreview

import android.app.Application
import android.graphics.Bitmap
import app.aaps.core.interfaces.R
import app.aaps.core.interfaces.rx.weardata.CUSTOM_VERSION
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ResFileMap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * The watch's renderer, built for the phone, draws a design with values and moves the clock. Runs
 * with a plain [Application]: not the wear app, as on the phone. The designs are small ones made by
 * [TestDesigns]. Writes the frames to `build/cwf-preview` for a look by eye.
 *
 * One renderer per test: the face's storage allows one owner per file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CwfPreviewRendererTest {

    private val out = File("build/cwf-preview").apply { mkdirs() }

    private fun renderer(design: CwfData?, now: Long): CwfPreviewRenderer =
        CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock()).apply {
            values(now).forEach(::apply)
            design?.let(::setDesign)
        }

    @Test
    fun drawsADesignWithValues() {
        val now = System.currentTimeMillis()
        val renderer = renderer(TestDesigns.dark(), now)
        val frame = renderer.frame(SIZE, now)
        save(frame, "dark")
        assertThat(frame.width).isEqualTo(SIZE)
        assertThat(frame.height).isEqualTo(SIZE)
        // Another design gives another picture
        renderer.setDesign(TestDesigns.light())
        val light = renderer.frame(SIZE, now)
        save(light, "light")
        assertThat(light.sameAs(frame)).isFalse()
    }

    @Test
    fun drawsTheDefaultDesignWithoutAZip() {
        val now = System.currentTimeMillis()
        val renderer = renderer(null, now)
        val frame = renderer.frame(SIZE, now)
        save(frame, "default")
        assertThat(frame.width).isEqualTo(SIZE)
    }

    @Test
    fun knowsTheDefaultDesignOfTheWatch() {
        val now = System.currentTimeMillis()
        val renderer = renderer(null, now)
        assertThat(renderer.isDefaultDesign(TestDesigns.dark())).isFalse()
        // What the watch sends for its default design: the same name, author and version
        val default = CwfData(
            "{}",
            mutableMapOf(
                CwfMetadataKey.CWF_NAME to RuntimeEnvironment.getApplication().getString(R.string.wear_default_watchface),
                CwfMetadataKey.CWF_AUTHOR to "Philoul",
                CwfMetadataKey.CWF_AUTHOR_VERSION to CUSTOM_VERSION
            ),
            mutableMapOf()
        )
        assertThat(renderer.isDefaultDesign(default)).isTrue()
    }

    @Test
    fun drawsOnThePreviewThreadAsOnThePhone() {
        // The phone draws on its own thread, not on the main thread the other tests run on. On a
        // plain thread the face's chart view could not be built.
        val frame = FutureTask {
            val now = System.currentTimeMillis()
            renderer(TestDesigns.dark(), now).frame(SIZE, now)
        }
        PreviewThread().executor.execute(frame)
        assertThat(frame.get(30, TimeUnit.SECONDS).width).isEqualTo(SIZE)
    }

    @Test
    fun theShortCopyOfTheWatchMissesThePictures() {
        // What the watch sends to the phone: the json, the metadata and the preview picture only.
        // Drawn from it, the face lacks its background: that is why the phone looks for the whole zip.
        val whole = TestDesigns.dark()
        val preview = ResFileMap.CUSTOM_WATCHFACE.fileName
        val shortCopy = CwfData(whole.json, whole.metadata, mutableMapOf(preview to whole.resData.getValue(preview)))
        val now = System.currentTimeMillis()
        val renderer = renderer(whole, now)
        val withWhole = renderer.frame(SIZE, now)
        renderer.setDesign(shortCopy)
        val withShort = renderer.frame(SIZE, now)
        save(withShort, "short_copy")
        assertThat(withShort.sameAs(withWhole)).isFalse()
    }

    @Test
    fun aNewSizeIsDrawnAsAFaceOfThatSize() {
        val now = System.currentTimeMillis()
        val renderer = renderer(TestDesigns.dark(), now)
        val big = renderer.frame(SIZE, now)
        val small = renderer.frame(SMALL, now)
        save(small, "small")
        assertThat(small.width).isEqualTo(SMALL)
        // The same as a face built for that size from the start: the scale is not the one of the first size
        renderer.release()
        assertThat(renderer.frame(SMALL, now).sameAs(small)).isTrue()
        // And back
        assertThat(renderer.frame(SIZE, now).sameAs(big)).isTrue()
    }

    @Test
    fun afterReleaseTheSameFaceIsDrawnAgain() {
        val now = System.currentTimeMillis()
        val renderer = renderer(TestDesigns.dark(), now)
        val before = renderer.frame(SIZE, now)
        renderer.release()
        assertThat(renderer.frame(SIZE, now).sameAs(before)).isTrue()
    }

    @Test
    fun aNewValueIsDrawn() {
        val now = System.currentTimeMillis()
        val renderer = renderer(TestDesigns.dark(), now)
        // A setting change makes the next frame read the values
        renderer.applyWatchSettings(mapOf("show_iob" to "false"))
        val before = renderer.frame(SIZE, now)
        // A value right after that read: the face reads the values at most once in half a second, so it
        // is kept for a later frame, not lost until the next minute
        renderer.apply(bg(now, "9,4", 170.0))
        assertThat(renderer.frame(SIZE, now).sameAs(before)).isTrue()
        Thread.sleep(1_500)
        val after = renderer.frame(SIZE, now)
        save(after, "new_value")
        assertThat(after.sameAs(before)).isFalse()
    }

    @Test
    fun aChangedSettingIsDrawn() {
        val now = System.currentTimeMillis()
        val renderer = renderer(TestDesigns.dark(), now)
        val before = renderer.frame(SIZE, now)
        assertThat(renderer.applyWatchSettings(mapOf("showBG" to "false"))).isTrue()
        val after = renderer.frame(SIZE, now)
        save(after, "no_bg")
        assertThat(after.sameAs(before)).isFalse()
    }

    private fun bg(now: Long, text: String, mgdl: Double) = EventData.SingleBg(
        dataset = 0, timeStamp = now - 120_000L, sgvString = text, glucoseUnits = "mmol", slopeArrow = "→",
        delta = "+0,2", deltaDetailed = "+0,2", avgDelta = "+0,1", avgDeltaDetailed = "+0,1", sgvLevel = 0, sgv = mgdl,
        high = 180.0, low = 70.0, color = 0
    )

    private fun values(now: Long): List<EventData> = listOf(
        bg(now, "6,7", 120.0),
        EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "1.25", iobDetail = "(0.90|0.35)", cob = "12g", currentBasal = "0.85U/h",
            battery = "78", rigBattery = "--", openApsStatus = now - 180_000L, bgi = "-0.4", batteryLevel = 1,
            tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0
        ),
        EventData.GraphData(ArrayList((0 until 36).map { i ->
            EventData.SingleBg(dataset = 0, timeStamp = now - (35 - i) * 300_000L, sgv = 125.0 + 35.0 * sin(i * PI / 14.0), high = 180.0, low = 70.0)
        }))
    )

    private fun save(bitmap: Bitmap, name: String) = File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private companion object {

        const val SIZE = 400
        const val SMALL = 300
    }
}
