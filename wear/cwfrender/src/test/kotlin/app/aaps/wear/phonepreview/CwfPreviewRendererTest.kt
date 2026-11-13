// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.app.Application
import android.graphics.Bitmap
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * The watch's renderer, built for the phone, draws a real design with values, moves the clock, and
 * plays the wake-up animation. Runs with a plain [Application]: not the wear app, as on the phone.
 * Writes the frames to `build/cwf-preview` for a look by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CwfPreviewRendererTest {

    private val out = File("build/cwf-preview").apply { mkdirs() }
    private val lengthKey: String get() = RuntimeEnvironment.getApplication().getString(R.string.key_wake_animation_length)

    private fun design(name: String) =
        ZipWatchfaceFormat.loadCustomWatchface(File("../cwf-designs/$name.zip").readBytes(), "$name.zip", authorization = true)!!.cwfData

    @Test
    fun drawsADesignWithValuesTheClockAndTheAnimation() {
        val logger: AAPSLogger = mock()
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), logger)
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)

        renderer.setDesign(design("Cyberpunk_Tech"))
        // A new design plays its animation once, as on the watch when the face starts
        val first = renderer.frame(SIZE, now)
        assertThat(renderer.isAnimating).isTrue()
        assertThat(renderer.frameDelayMs(1000L)).isLessThan(1000L)
        save(first, "anim_0")
        ShadowSystemClock.advanceBy(Duration.ofMillis(400)) // the animation runs on the system clock
        val later = renderer.frame(SIZE, now + 400)
        save(later, "anim_1")
        assertThat(later.sameAs(first)).isFalse()

        // After its length (15 s for this design) the face is still again
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        val still = renderer.frame(SIZE, now)
        assertThat(renderer.isAnimating).isFalse()
        assertThat(renderer.frameDelayMs(1000L)).isEqualTo(1000L)
        save(still, "still")

        // A tap plays it again
        assertThat(renderer.wake()).isTrue()
        renderer.frame(SIZE, now)
        assertThat(renderer.isAnimating).isTrue()
    }

    @Test
    fun drawsOnThePreviewThreadAsOnThePhone() {
        // The phone draws on its own thread, not on the main thread the other tests run on. On a
        // plain thread the face's chart view could not be built, and the first preview on a phone stopped.
        val frame = FutureTask {
            val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock())
            val now = System.currentTimeMillis()
            values(now).forEach(renderer::apply)
            renderer.setDesign(design("Cyberpunk_Tech"))
            renderer.frame(SIZE, now)
        }
        PreviewThread().executor.execute(frame)
        assertThat(frame.get(30, TimeUnit.SECONDS).width).isEqualTo(SIZE)
    }

    @Test
    fun aNewSizeIsDrawnWhole() {
        val renderer = stillRenderer("Cyberpunk_Yellow")
        val now = System.currentTimeMillis()
        val before = renderer.frame(SIZE, now)
        renderer.frame(SIZE * 3 / 4, now)
        // The layers kept from the smaller frame must not be reused at the old size
        val after = renderer.frame(SIZE, now)
        assertThat(after.sameAs(before)).isTrue()
    }

    @Test
    fun afterReleaseTheSameFaceIsDrawnAgainAndItsAnimationPlays() {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock())
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)
        renderer.setDesign(design("Cyberpunk_Tech"))
        renderer.frame(SIZE, now)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        val before = renderer.frame(SIZE, now)
        assertThat(renderer.isAnimating).isFalse()

        renderer.release()
        assertThat(renderer.isAnimating).isFalse()
        assertThat(renderer.wake()).isFalse()
        // Built again: the same values and design, and the animation plays as when a face becomes visible
        renderer.frame(SIZE, now)
        assertThat(renderer.isAnimating).isTrue()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        val after = renderer.frame(SIZE, now)
        assertThat(after.sameAs(before)).isTrue()
    }

    /** A renderer with values and [designName], and the wake-up animation switched off. */
    private fun stillRenderer(designName: String): CwfPreviewRenderer {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock())
        renderer.watchSettings.edit().putString(lengthKey, "0").commit()
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)
        renderer.setDesign(design(designName))
        return renderer
    }

    @Test
    fun aNewValueIsDrawn() {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock())
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)
        renderer.setDesign(design("Cyberpunk_Yellow"))
        renderer.watchSettings.edit().putString(lengthKey, "0").commit()
        renderer.settingsChanged()
        val before = renderer.frame(SIZE, now)
        renderer.apply(bg(now, "9,4", 170.0))
        val after = renderer.frame(SIZE, now)
        save(after, "new_value")
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
    }
}
