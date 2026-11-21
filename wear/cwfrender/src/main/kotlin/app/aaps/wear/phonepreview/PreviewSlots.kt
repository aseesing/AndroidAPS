// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.graphics.Canvas
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.core.graphics.withRotation
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import app.aaps.wear.watchfaces.CustomSlotDefault
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.customSlotDefault
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * What a complication slot of the preview shows. On the watch the wearer picks a source per slot, and
 * the source runs on the watch. The preview runs none: it makes the same short text from the values it
 * has, see [PreviewSlots].
 */
enum class PreviewSlotSource {

    /** Nothing: an empty slot. */
    NONE,

    /** Carbs on board, as the AAPS source "COB" with its icon. */
    COB,

    /** Insulin on board, as the AAPS source "IOB" with its icon. */
    IOB,

    /** The glucose with its arrow, and below it the age and the delta, as the AAPS source "SGV". */
    BG,

    /** The basal rate, as the AAPS source "BR". */
    BASAL,

    /** The battery of the phone, as the AAPS source "Uploader battery". */
    PHONE_BATTERY,

    /** A stand-in for the heart rate source of the watch itself. Not AAPS code: a number with a heart. */
    HEART_RATE,

    /** A stand-in for the steps source of the watch itself. Not AAPS code: a number with footprints. */
    STEPS
}

/**
 * Draws the complication slots of the Custom watch face in the preview. On the watch the system draws
 * them, with data from complication sources that run on the watch; the preview runs none.
 *
 * So each slot shows a source of [PreviewSlotSource], made here from the values the preview has:
 * - the one [chosen] for the slot, by its number (1 to 5);
 * - else what the slot shows on the watch before the wearer picks anything (see
 *   [customSlotDefault]): the heart rate and the steps of the watch itself in slots 1 and 2, carbs on
 *   board and insulin on board in slots 3 and 4, nothing in slot 5.
 *
 * The texts of the AAPS sources are made as the sources of the watch make them (`CobIconComplication`,
 * `IobIconComplication`, `SgvComplication`, `BrComplication`, `UploaderBatteryComplication`). Those are
 * services of the watch and cannot run here, so their few lines are written again in this file: keep
 * them the same. The heart rate and the steps come from sources of the watch itself, whose code we do
 * not have: they are plain stand-ins.
 *
 * Where a slot is and how it looks comes from the watch's own code ([CustomWatchface.slotLook]); it is
 * drawn with the library's [ComplicationDrawable], as on the watch.
 *
 * A slot that fails is left out and logged: it can not stop the preview.
 */
internal class PreviewSlots(private val host: PreviewHost, private val logger: AAPSLogger) {

    /** The source of a slot, by its number (1 to 5). A slot that is not here shows its default, see [sourceOf]. */
    var chosen: Map<Int, PreviewSlotSource> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    /** The steps of today for [PreviewSlotSource.STEPS], or null for none. The watch does not send them. */
    var steps: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    /** One drawable per slot, kept: making it loads the library's resources. */
    private val drawables = HashMap<Int, ComplicationDrawable>()

    /** What each slot shows, by its number, made once for the values of now; a slot with nothing has null. */
    private val made = HashMap<Int, ComplicationData?>()

    /** The values changed: the next frame makes the texts again. */
    fun invalidate() = made.clear()

    /** What slot [number] (1 to 5) shows: the [chosen] source, else the default of the watch. */
    fun sourceOf(number: Int): PreviewSlotSource = chosen[number] ?: defaultOf(number)

    /**
     * What slot [number] (1 to 5) shows on the watch before the wearer picks a source, see
     * [customSlotDefault]. The heart rate and the steps of the watch itself are the stand-ins here.
     */
    fun defaultOf(number: Int): PreviewSlotSource {
        val slotId = CustomWatchface.complicationSlots.getOrNull(number - 1)?.id ?: return PreviewSlotSource.NONE
        return when (customSlotDefault(slotId)) {
            CustomSlotDefault.HEART_RATE -> PreviewSlotSource.HEART_RATE
            CustomSlotDefault.STEPS      -> PreviewSlotSource.STEPS
            CustomSlotDefault.COB        -> PreviewSlotSource.COB
            CustomSlotDefault.IOB        -> PreviewSlotSource.IOB
            CustomSlotDefault.NONE       -> PreviewSlotSource.NONE
        }
    }

    /** Draws every slot that [face] shows, at [nowMs]. */
    fun draw(canvas: Canvas, face: CustomWatchface, nowMs: Long) {
        CustomWatchface.complicationSlots.forEachIndexed { index, slot ->
            try {
                drawSlot(canvas, face, slot.id, index + 1, nowMs)
            } catch (e: Exception) {
                logger.error(LTag.WEAR, "Preview: slot ${index + 1} not drawn", e)
            }
        }
    }

    private fun drawSlot(canvas: Canvas, face: CustomWatchface, slotId: Int, number: Int, nowMs: Long) {
        val (at, style) = face.slotLook(slotId) ?: return
        val data = dataFor(number) ?: return
        val drawable = drawables.getOrPut(slotId) { ComplicationDrawable(host) }
        style.applyTo(drawable)
        drawable.setComplicationData(data, false)
        drawable.currentTime = Instant.ofEpochMilli(nowMs)
        drawable.bounds = at.bounds
        canvas.withRotation(at.rotation, at.bounds.exactCenterX(), at.bounds.exactCenterY()) { drawable.draw(this) }
    }

    /** What slot [number] (1 to 5) shows, or null for an empty slot. */
    fun dataFor(number: Int): ComplicationData? = made.getOrPut(number) { make(sourceOf(number)) }

    private fun make(source: PreviewSlotSource): ComplicationData? {
        if (source == PreviewSlotSource.NONE) return null
        val values = runBlocking { host.repository.current() }
        val status = values.statusData
        return when (source) {
            PreviewSlotSource.NONE          -> null
            // CobIconComplication
            PreviewSlotSource.COB           -> shortText(status.cob, "COB ${status.cob}", R.drawable.ic_carbs)
            // IobIconComplication
            PreviewSlotSource.IOB           -> (status.iobSum + host.getString(R.string.insulin_unit_short)).let { shortText(it, "IOB $it", R.drawable.ic_ins) }
            PreviewSlotSource.BG            -> glucose(values.bgData)
            // BrComplication, with the sign of `DisplayFormat.basalRateSymbol`
            PreviewSlotSource.BASAL         -> shortText((if (host.sp.getBoolean(UNICODE_KEY, true)) BASAL_SYMBOL else "") + status.currentBasal, "Basal Rate", null)
            PreviewSlotSource.PHONE_BATTERY -> phoneBattery(status.battery)
            PreviewSlotSource.HEART_RATE    -> standIn(host.healthValues.heartRate()?.toLong(), "Heart rate", R.drawable.ic_health_heart)
            PreviewSlotSource.STEPS         -> standIn(steps, "Steps", R.drawable.ic_preview_steps)
        }
    }

    private fun plain(text: String) = PlainComplicationText.Builder(text).build()

    private fun icon(@DrawableRes id: Int) = MonochromaticImage.Builder(Icon.createWithResource(host, id)).build()

    private fun shortText(text: String, description: String, @DrawableRes icon: Int?): ComplicationData =
        ShortTextComplicationData.Builder(plain(text), plain(description)).apply { icon?.let { setMonochromaticImage(icon(it)) } }.build()

    /** `SgvComplication`: the glucose and its arrow, and a title that counts the minutes since the reading, with the delta. */
    private fun glucose(bg: EventData.SingleBg): ComplicationData {
        val text = bg.sgvString + bg.slopeArrow
        // As `ModernBaseComplicationProviderService.buildCountUpText`: half a minute later, so the age rounds down
        val title = TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountUpTimeReference(Instant.ofEpochMilli(bg.timeStamp + 30_000L)))
            .setMinimumTimeUnit(TimeUnit.MINUTES).setText("^1 ${bg.delta}").build()
        return ShortTextComplicationData.Builder(plain(text), plain("Glucose $text")).setTitle(title).build()
    }

    /**
     * `UploaderBatteryComplication`, in the type a slot asks for first (a value in a ring): the level
     * as a text with the battery icon of that level, and "???" when the phone sent no number.
     */
    private fun phoneBattery(battery: String): ComplicationData {
        val level = battery.takeIf { it.matches(Regex("^[0-9]+$")) }?.toIntOrNull()?.coerceIn(0, 100)
        val text = level?.let { host.getString(R.string.loop_status_profile_percentage_value, it) } ?: "???"
        val image = when {
            level == null -> R.drawable.ic_battery_unknown
            level > 95    -> R.drawable.ic_battery_charging_wireless
            else          -> BATTERY_ICONS[level / 10]
        }
        return RangedValueComplicationData.Builder((level ?: 0).toFloat(), 0f, 100f, plain("Battery $text"))
            .setText(plain(text)).setMonochromaticImage(icon(image)).build()
    }

    /** A stand-in for a health source of the watch itself: the number with an icon, "--" without a value. */
    private fun standIn(value: Long?, name: String, @DrawableRes icon: Int): ComplicationData =
        (value?.toString() ?: NO_VALUE).let { shortText(it, "$name $it", icon) }

    private companion object {

        /** The watch setting "Unicode in complications", as `DisplayFormat` reads it. */
        const val UNICODE_KEY = "complication_unicode"

        /** The sign before a basal rate, as `DisplayFormat.basalRateSymbol`. */
        const val BASAL_SYMBOL = "⎍ "

        const val NO_VALUE = "--"

        /** The battery icons of `UploaderBatteryComplication` for a level of 0 to 9 tenths. */
        val BATTERY_ICONS = intArrayOf(
            R.drawable.ic_battery_alert_variant_outline, R.drawable.ic_battery_charging_wireless_10, R.drawable.ic_battery_charging_wireless_20,
            R.drawable.ic_battery_charging_wireless_30, R.drawable.ic_battery_charging_wireless_40, R.drawable.ic_battery_charging_wireless_50,
            R.drawable.ic_battery_charging_wireless_60, R.drawable.ic_battery_charging_wireless_70, R.drawable.ic_battery_charging_wireless_80,
            R.drawable.ic_battery_charging_wireless_90
        )
    }
}
