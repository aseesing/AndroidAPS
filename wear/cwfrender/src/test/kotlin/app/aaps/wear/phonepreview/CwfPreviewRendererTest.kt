package app.aaps.wear.phonepreview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.CUSTOM_VERSION
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.wear.R
import app.aaps.wear.watchfaces.COB_SLOT_ID
import app.aaps.wear.watchfaces.HEART_RATE_SLOT_ID
import app.aaps.wear.watchfaces.IOB_SLOT_ID
import app.aaps.wear.watchfaces.STEPS_SLOT_ID
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
import kotlin.math.abs
import kotlin.math.sin
import app.aaps.core.interfaces.R as InterfacesR

/**
 * The watch's renderer, built for the phone, draws a design with values, moves the clock, and
 * plays the wake-up animation. Runs with a plain [Application]: not the wear app, as on the phone.
 * The designs are small ones made by [TestDesigns]. Writes the frames to `build/cwf-preview` for a
 * look by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CwfPreviewRendererTest {

    private val out = File("build/cwf-preview").apply { mkdirs() }
    private val lengthKey: String get() = RuntimeEnvironment.getApplication().getString(R.string.key_wake_animation_length)

    @Test
    fun drawsADesignWithValuesTheClockAndTheAnimation() {
        val logger: AAPSLogger = mock()
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), logger)
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)

        renderer.setDesign(TestDesigns.animated())
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
            renderer.setDesign(TestDesigns.animated())
            renderer.frame(SIZE, now)
        }
        PreviewThread().executor.execute(frame)
        assertThat(frame.get(30, TimeUnit.SECONDS).width).isEqualTo(SIZE)
    }

    @Test
    fun twoPreviewsWithTheirOwnStorageDrawTheirOwnFaceAtTheSameTime() {
        // The day face and the night face: the face's storage allows one owner per file
        val day = stillRenderer(TestDesigns.animated())
        val night = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock(), "cwf_preview_night")
        night.watchSettings.edit().putString(lengthKey, "0").commit()
        val now = System.currentTimeMillis()
        values(now).forEach(night::apply)
        night.setDesign(TestDesigns.still())
        val dayFrame = day.frame(SIZE, now)
        val nightFrame = night.frame(SIZE, now)
        save(nightFrame, "night")
        assertThat(nightFrame.sameAs(dayFrame)).isFalse()
        // Each keeps its own design
        assertThat(day.frame(SIZE, now).sameAs(dayFrame)).isTrue()
    }

    @Test
    fun theShortCopyOfTheWatchMissesThePictures() {
        // What the watch sends to the phone: the json, the metadata and the preview picture only.
        // Drawn from it, the face lacks its background, as first seen on the phone.
        val whole = TestDesigns.animated()
        val preview = ResFileMap.CUSTOM_WATCHFACE.fileName
        val shortCopy = CwfData(whole.json, whole.metadata, mutableMapOf(preview to whole.resData.getValue(preview)))
        val now = System.currentTimeMillis()
        val withWhole = stillRenderer(whole).frame(SIZE, now)
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock(), "cwf_preview_short")
        renderer.watchSettings.edit().putString(lengthKey, "0").commit()
        values(now).forEach(renderer::apply)
        renderer.setDesign(shortCopy)
        val withShort = renderer.frame(SIZE, now)
        save(withShort, "short_copy")
        assertThat(withShort.sameAs(withWhole)).isFalse()
    }

    @Test
    fun aNewSizeIsDrawnAsAFaceOfThatSize() {
        val renderer = stillRenderer(TestDesigns.still())
        val now = System.currentTimeMillis()
        val big = renderer.frame(SIZE, now).kept()
        val small = renderer.frame(SMALL, now).kept()
        save(small, "small")
        assertThat(small.width).isEqualTo(SMALL)
        // The same as a face built for that size from the start: the scale is not the one of the first size
        renderer.release()
        assertThat(renderer.frame(SMALL, now).sameAs(small)).isTrue()
        // And back
        assertThat(renderer.frame(SIZE, now).sameAs(big)).isTrue()
    }

    @Test
    fun drawsTheDefaultDesignWithoutAZip() {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock(), "cwf_preview_default")
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)
        val frame = renderer.frame(SIZE, now)
        save(frame, "default")
        assertThat(frame.width).isEqualTo(SIZE)
    }

    @Test
    fun knowsTheDefaultDesignOfTheWatch() {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock(), "cwf_preview_knows_default")
        values(System.currentTimeMillis()).forEach(renderer::apply)
        assertThat(renderer.isDefaultDesign(TestDesigns.still())).isFalse()
        // What the watch sends for its default design: the same name, author and version
        val default = CwfData(
            "{}",
            mutableMapOf(
                CwfMetadataKey.CWF_NAME to RuntimeEnvironment.getApplication().getString(InterfacesR.string.wear_default_watchface),
                CwfMetadataKey.CWF_AUTHOR to "Philoul",
                CwfMetadataKey.CWF_AUTHOR_VERSION to CUSTOM_VERSION
            ),
            mutableMapOf()
        )
        assertThat(renderer.isDefaultDesign(default)).isTrue()
    }

    @Test
    fun aSmallFirstFrameDoesNotKeepTheFaceSmall() {
        // Seen in the snapshot test: after a first frame of 32 px, a frame of 400 px drew the face at
        // 32 px in the top left corner, because the views kept the sizes of the first layout
        val now = System.currentTimeMillis()
        val renderer = stillRenderer(TestDesigns.still())
        renderer.frame(32, now)
        val after = renderer.frame(SIZE, now)
        save(after, "after_small_frame")
        val fresh = stillRenderer(TestDesigns.still(), "cwf_preview_fresh").frame(SIZE, now)
        assertThat(after.sameAs(fresh)).isTrue()
        // And back to small: the small face again, not the corner of the large one
        val small = renderer.frame(32, now).kept()
        assertThat(small.sameAs(stillRenderer(TestDesigns.still(), "cwf_preview_fresh_small").frame(32, now))).isTrue()
    }

    @Test
    fun framesAreDrawnIntoThreeBitmapsInTurn() {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock(), "cwf_preview_buffers")
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)
        renderer.setDesign(TestDesigns.animated())
        // While the animation plays and after it: the frame shown last is never drawn into
        val frames = (0 until 6).map { i ->
            ShadowSystemClock.advanceBy(Duration.ofMillis(if (i == 3) 16_000 else 40))
            renderer.frame(SIZE, now + i * 40)
        }
        assertThat(renderer.isAnimating).isFalse()
        for (i in 1 until frames.size) assertThat(frames[i]).isNotSameInstanceAs(frames[i - 1])
        assertThat(frames.distinct()).hasSize(3)
        assertThat(frames.none { it.isRecycled }).isTrue()
    }

    @Test
    fun aBitmapTheCallerStillShowsIsNeverDrawnInto() {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock(), "cwf_preview_busy")
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)
        renderer.setDesign(TestDesigns.animated())
        val busy = HashSet<Bitmap>()
        // The screen shows none of the frames yet: three are handed out, then there is no free bitmap
        repeat(3) { busy += renderer.frame(SIZE, now + it * 40) { bitmap -> bitmap !in busy }!! }
        assertThat(busy).hasSize(3)
        assertThat(renderer.frame(SIZE, now + 120) { bitmap -> bitmap !in busy }).isNull()
        // One is free again: that one is drawn into
        val free = busy.first()
        busy -= free
        assertThat(renderer.frame(SIZE, now + 160) { bitmap -> bitmap !in busy }).isSameInstanceAs(free)
    }

    @Test
    fun afterReleaseTheSameFaceIsDrawnAgainAndItsAnimationPlays() {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock())
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)
        renderer.setDesign(TestDesigns.animated())
        renderer.frame(SIZE, now)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        val before = renderer.frame(SIZE, now).kept()
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

    /** A renderer named [name] with values and [design], and the wake-up animation switched off. */
    private fun stillRenderer(design: CwfData, name: String = PreviewHost.DEFAULT_NAME): CwfPreviewRenderer {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock(), name)
        renderer.watchSettings.edit().putString(lengthKey, "0").commit()
        val now = System.currentTimeMillis()
        values(now).forEach(renderer::apply)
        renderer.setDesign(design)
        return renderer
    }

    @Test
    fun aNewValueIsDrawn() {
        val renderer = stillRenderer(TestDesigns.still())
        val now = System.currentTimeMillis()
        // A setting change makes the next frame read the values
        renderer.settingsChanged()
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
        val renderer = stillRenderer(TestDesigns.still(), "cwf_preview_setting")
        val now = System.currentTimeMillis()
        val before = renderer.frame(SIZE, now).kept()
        renderer.applyWatchSettings(mapOf("showBG" to "false"))
        val after = renderer.frame(SIZE, now)
        save(after, "no_bg")
        assertThat(after.sameAs(before)).isFalse()
    }

    /** A still renderer named [name] with [design], and the heart rate and steps kept in its health values when given. */
    private fun slotRenderer(name: String, design: CwfData, heartRate: Int?, steps: Long?): CwfPreviewRenderer {
        val now = System.currentTimeMillis()
        RuntimeEnvironment.getApplication().getSharedPreferences("${name}_aaps_health_values", Application.MODE_PRIVATE).edit().apply {
            heartRate?.let { putInt("heart_rate", it).putLong("heart_rate_at", now) }
            steps?.let { putLong("steps", it).putLong("steps_at", now) }
        }.commit()
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock(), name)
        renderer.watchSettings.edit().putString(lengthKey, "0").commit()
        values(now).forEach(renderer::apply)
        renderer.setDesign(design)
        return renderer
    }

    /** How many pixels in the box (left, top, right, bottom in the 400 design space) of [frame] have about the colour [hex]. */
    private fun count(frame: Bitmap, box: IntArray, hex: String): Int {
        val c = Color.parseColor(hex)
        var n = 0
        for (y in box[1] * SIZE / 400 until box[3] * SIZE / 400) for (x in box[0] * SIZE / 400 until box[2] * SIZE / 400) {
            val p = frame.getPixel(x, y)
            if (abs(Color.red(p) - Color.red(c)) < 50 && abs(Color.green(p) - Color.green(c)) < 50 && abs(Color.blue(p) - Color.blue(c)) < 50) n++
        }
        return n
    }

    @Test
    fun theStepsSlotShowsTheKeptValueOrNoValue() {
        // The steps window at 6 o'clock is slot 2 of the design, in its ink
        val window = intArrayOf(144, 320, 256, 348)
        val ink = TestDesigns.STEPS_INK
        val now = System.currentTimeMillis()
        val withSteps = slotRenderer("cwf_preview_steps", TestDesigns.steps(), heartRate = null, steps = 6482).frame(SIZE, now)
        val without = slotRenderer("cwf_preview_no_steps", TestDesigns.steps(), heartRate = null, steps = null).frame(SIZE, now)
        save(withSteps, "slot_steps")
        save(without, "slot_no_steps")
        // The number and the icon of the source are drawn; without a value only the icon and the source's "no value" text
        assertThat(count(withSteps, window, ink)).isGreaterThan(count(without, window, ink) + 40)
        assertThat(count(without, window, ink)).isGreaterThan(0)
    }

    @Test
    fun theHeartRateRowIsDrawn() {
        // Heart rate is slot 1, a row of white text
        val row = intArrayOf(254, 204, 359, 230)
        val now = System.currentTimeMillis()
        val withHeart = slotRenderer("cwf_preview_heart", TestDesigns.animated(), heartRate = 72, steps = null).frame(SIZE, now)
        val without = slotRenderer("cwf_preview_no_heart", TestDesigns.animated(), heartRate = null, steps = null).frame(SIZE, now)
        save(withHeart, "slot_heart")
        assertThat(count(withHeart, row, "#FFFFFF")).isGreaterThan(count(without, row, "#FFFFFF") + 20)
    }

    @Test
    fun eachSlotShowsItsDefaultSource() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("slots_test_aaps_health_values", Application.MODE_PRIVATE).edit()
            .putInt("heart_rate", 72).putLong("heart_rate_at", System.currentTimeMillis()).commit()
        val host = PreviewHost(app, mock(), "slots_test")
        val slots = PreviewSlots(host, host.healthValues, mock())
        // Heart rate and steps, in the type the source sends by default
        val heart = slots.dataFor(HEART_RATE_SLOT_ID) as RangedValueComplicationData
        assertThat(heart.value).isEqualTo(72f)
        assertThat(slots.dataFor(STEPS_SLOT_ID)).isInstanceOf(RangedValueComplicationData::class.java)
        // Carbs and insulin come from other sources: the phone does not stand in for them, as before
        assertThat(slots.dataFor(COB_SLOT_ID)).isNull()
        assertThat(slots.dataFor(IOB_SLOT_ID)).isNull()
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

    /** A copy of a frame: the renderer draws into its frames again two frames later. */
    private fun Bitmap.kept(): Bitmap = copy(Bitmap.Config.ARGB_8888, false)

    private fun save(bitmap: Bitmap, name: String) = File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private companion object {

        const val SIZE = 400
        const val SMALL = 300
    }
}
