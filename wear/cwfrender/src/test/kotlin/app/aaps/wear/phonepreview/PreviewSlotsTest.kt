// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import kotlin.math.abs

/**
 * The complication slots of the preview: what a slot shows with no choice (the default of the watch),
 * the text each source makes, and that the renderer draws it where the design puts the slot.
 * Writes the frames to `build/cwf-preview` for a look by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PreviewSlotsTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val out = File("build/cwf-preview").apply { mkdirs() }

    /** A host named [name] with the values of the tests, and the heart rate kept in its health values when given. */
    private fun host(name: String, heartRate: Int? = 72): PreviewHost {
        val now = System.currentTimeMillis()
        heartRate?.let {
            app.getSharedPreferences("${name}_aaps_health_values", Application.MODE_PRIVATE).edit().putInt("heart_rate", it).putLong("heart_rate_at", now).commit()
        }
        return PreviewHost(app, mock(), name).also { host -> runBlocking { values(now).forEach { host.take(it) } } }
    }

    private suspend fun PreviewHost.take(event: EventData) = when (event) {
        is EventData.SingleBg -> repository.updateBgData(event)
        is EventData.Status   -> repository.updateStatusData(event)
        else                  -> error("not a value of this test")
    }

    private fun values(now: Long): List<EventData> = listOf(
        EventData.SingleBg(
            dataset = 0, timeStamp = now - 120_000L, sgvString = "6,7", glucoseUnits = "mmol", slopeArrow = "→", delta = "+0,2", deltaDetailed = "+0,2",
            avgDelta = "+0,1", avgDeltaDetailed = "+0,1", sgvLevel = 0, sgv = 120.0, high = 180.0, low = 70.0, color = 0
        ),
        EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "1.25", iobDetail = "(0.90|0.35)", cob = "12g", currentBasal = "0.85U/h", battery = "78",
            rigBattery = "--", openApsStatus = now - 180_000L, bgi = "-0.4", batteryLevel = 1, tempTarget = "100", tempTargetLevel = 0,
            reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0
        )
    )

    private fun text(data: ComplicationData?): String = when (data) {
        is ShortTextComplicationData   -> data.text.getTextAt(app.resources, Instant.now()).toString()
        is RangedValueComplicationData -> data.text!!.getTextAt(app.resources, Instant.now()).toString()
        else                           -> error("no text in $data")
    }

    @Test
    fun aSlotWithNoChoiceShowsTheDefaultOfTheWatch() {
        val slots = PreviewSlots(host("slots_default"), mock())
        // As `customSlotDefault` says: the stand-ins for the heart rate and the steps of the watch in slots 1 and 2,
        // carbs on board in slot 3, insulin on board in slot 4, nothing in slot 5
        assertThat((1..5).map(slots::sourceOf)).containsExactly(
            PreviewSlotSource.HEART_RATE, PreviewSlotSource.STEPS, PreviewSlotSource.COB, PreviewSlotSource.IOB, PreviewSlotSource.NONE
        ).inOrder()
        assertThat(text(slots.dataFor(1))).isEqualTo("72")
        // The watch does not send the steps: no value until the caller gives one
        assertThat(text(slots.dataFor(2))).isEqualTo("--")
        slots.steps = 6482
        assertThat(text(slots.dataFor(2))).isEqualTo("6482")
        assertThat(text(slots.dataFor(3))).isEqualTo("12g")
        assertThat(text(slots.dataFor(4))).isEqualTo("1.25U")
        assertThat(slots.dataFor(5)).isNull()
        // No slot 6
        assertThat(slots.sourceOf(6)).isEqualTo(PreviewSlotSource.NONE)
    }

    @Test
    fun eachSourceMakesTheTextOfItsComplication() {
        val slots = PreviewSlots(host("slots_sources"), mock())
        fun shown(source: PreviewSlotSource): ComplicationData? {
            slots.chosen = mapOf(5 to source)
            return slots.dataFor(5)
        }
        assertThat(shown(PreviewSlotSource.NONE)).isNull()
        // The texts and the icons of CobIconComplication and IobIconComplication
        (shown(PreviewSlotSource.COB) as ShortTextComplicationData).let {
            assertThat(text(it)).isEqualTo("12g")
            assertThat(it.monochromaticImage).isNotNull()
        }
        (shown(PreviewSlotSource.IOB) as ShortTextComplicationData).let {
            assertThat(text(it)).isEqualTo("1.25" + app.getString(R.string.insulin_unit_short))
            assertThat(it.monochromaticImage).isNotNull()
        }
        // SgvComplication: the glucose with its arrow, and the age with the delta as the title
        (shown(PreviewSlotSource.BG) as ShortTextComplicationData).let {
            assertThat(text(it)).isEqualTo("6,7→")
            assertThat(it.title!!.getTextAt(app.resources, Instant.now()).toString()).endsWith(" +0,2")
            assertThat(it.monochromaticImage).isNull()
        }
        // BrComplication: the basal sign of the watch setting "Unicode in complications" (on by default), then the rate
        assertThat(text(shown(PreviewSlotSource.BASAL))).isEqualTo("⎍ 0.85U/h")
        // UploaderBatteryComplication: a value in a ring with the level as its text
        (shown(PreviewSlotSource.PHONE_BATTERY) as RangedValueComplicationData).let {
            assertThat(it.value).isEqualTo(78f)
            assertThat(it.max).isEqualTo(100f)
            assertThat(text(it)).isEqualTo("78%")
        }
        // The stand-ins for the sources of the watch itself
        assertThat(text(shown(PreviewSlotSource.HEART_RATE))).isEqualTo("72")
        assertThat(text(shown(PreviewSlotSource.STEPS))).isEqualTo("--")
        slots.steps = 6482
        assertThat(text(slots.dataFor(5))).isEqualTo("6482")
    }

    @Test
    fun aValueThatIsMissingIsShownAsMissing() {
        // A phone that sent nothing, and a watch with no heart rate
        val slots = PreviewSlots(PreviewHost(app, mock(), "slots_empty"), mock())
        slots.chosen = mapOf(1 to PreviewSlotSource.HEART_RATE, 2 to PreviewSlotSource.PHONE_BATTERY, 3 to PreviewSlotSource.BASAL)
        assertThat(text(slots.dataFor(1))).isEqualTo("--")
        assertThat(text(slots.dataFor(2))).isEqualTo("???")
        assertThat((slots.dataFor(2) as RangedValueComplicationData).value).isEqualTo(0f)
        // Without the unicode setting there is no sign before the rate
        val host = host("slots_plain")
        host.sp.putBoolean("complication_unicode", false)
        assertThat(text(PreviewSlots(host, mock()).apply { chosen = mapOf(1 to PreviewSlotSource.BASAL) }.dataFor(1))).isEqualTo("0.85U/h")
    }

    @Test
    fun newValuesMakeNewTexts() {
        val host = host("slots_new_values")
        val slots = PreviewSlots(host, mock())
        assertThat(text(slots.dataFor(3))).isEqualTo("12g")
        runBlocking { host.take((values(System.currentTimeMillis())[1] as EventData.Status).copy(cob = "30g")) }
        // The text is kept until the renderer says the values changed
        assertThat(text(slots.dataFor(3))).isEqualTo("12g")
        slots.invalidate()
        assertThat(text(slots.dataFor(3))).isEqualTo("30g")
    }

    /** A still renderer named [name] with [design] and the values of the tests. */
    private fun renderer(name: String, design: CwfData): CwfPreviewRenderer {
        val now = System.currentTimeMillis()
        app.getSharedPreferences("${name}_aaps_health_values", Application.MODE_PRIVATE).edit().putInt("heart_rate", 72).putLong("heart_rate_at", now).commit()
        val renderer = CwfPreviewRenderer(app, mock(), name)
        renderer.watchSettings.edit().putString(app.getString(R.string.key_wake_animation_length), "0").commit()
        values(now).forEach(renderer::apply)
        renderer.setDesign(design)
        return renderer
    }

    /** How many pixels of slot [n] of `TestDesigns.withSlots` in [frame] have about the ink of the slots. */
    private fun ink(frame: Bitmap, n: Int): Int {
        val box = TestDesigns.slotBox(n)
        val c = Color.parseColor(TestDesigns.SLOT_INK)
        var count = 0
        for (y in box[1] * SIZE / 400 until box[3] * SIZE / 400) for (x in box[0] * SIZE / 400 until box[2] * SIZE / 400) {
            val p = frame.getPixel(x, y)
            if (abs(Color.red(p) - Color.red(c)) < 50 && abs(Color.green(p) - Color.green(c)) < 50 && abs(Color.blue(p) - Color.blue(c)) < 50) count++
        }
        return count
    }

    private fun Bitmap.kept(): Bitmap = copy(Bitmap.Config.ARGB_8888, false)

    private fun save(bitmap: Bitmap, name: String) = File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test
    fun withNoChoiceTheRendererDrawsTheDefaultsOfTheWatch() {
        // As the phone app uses the renderer: it sets no source
        val frame = renderer("cwf_preview_slots_default", TestDesigns.withSlots()).frame(SIZE, System.currentTimeMillis())
        save(frame, "slots_default")
        for (n in 1..4) assertThat(ink(frame, n)).isGreaterThan(20)
        assertThat(ink(frame, 5)).isEqualTo(0)
    }

    @Test
    fun theRendererDrawsTheChosenSourceOfEachSlot() {
        val renderer = renderer("cwf_preview_slots_chosen", TestDesigns.withSlots())
        val now = System.currentTimeMillis()
        val defaults = renderer.frame(SIZE, now).kept()
        renderer.stepsToday = 6482
        renderer.slotSources = mapOf(
            1 to PreviewSlotSource.NONE, 2 to PreviewSlotSource.BG, 3 to PreviewSlotSource.PHONE_BATTERY, 4 to PreviewSlotSource.HEART_RATE, 5 to PreviewSlotSource.STEPS
        )
        val chosen = renderer.frame(SIZE, now).kept()
        save(chosen, "slots_chosen")
        // Slot 1 is empty now, slot 2 shows something else, and the others show something
        assertThat(ink(chosen, 1)).isEqualTo(0)
        assertThat(ink(chosen, 2)).isNotEqualTo(ink(defaults, 2))
        for (n in 2..5) assertThat(ink(chosen, n)).isGreaterThan(20)
        // More digits, more ink: the steps of the renderer are drawn
        renderer.stepsToday = 1
        assertThat(ink(renderer.frame(SIZE, now), 5)).isLessThan(ink(chosen, 5))
        // A choice does not change what the default is
        assertThat((1..5).map(renderer::defaultSlotSource)).containsExactly(
            PreviewSlotSource.HEART_RATE, PreviewSlotSource.STEPS, PreviewSlotSource.COB, PreviewSlotSource.IOB, PreviewSlotSource.NONE
        ).inOrder()
        // Back to no choice and no steps: the defaults of the watch again
        renderer.slotSources = emptyMap()
        renderer.stepsToday = null
        assertThat(renderer.frame(SIZE, now).sameAs(defaults)).isTrue()
    }

    @Test
    fun aDesignWithoutSlotsDrawsNone() {
        val renderer = renderer("cwf_preview_slots_none", TestDesigns.still())
        val now = System.currentTimeMillis()
        val before = renderer.frame(SIZE, now).kept()
        renderer.slotSources = (1..5).associateWith { PreviewSlotSource.BG }
        assertThat(renderer.frame(SIZE, now).sameAs(before)).isTrue()
    }

    private companion object {

        const val SIZE = 400
    }
}
