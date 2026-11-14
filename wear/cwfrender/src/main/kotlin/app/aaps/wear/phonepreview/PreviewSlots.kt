// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.content.Context
import android.graphics.Canvas
import androidx.core.graphics.withRotation
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.health.HealthKind
import app.aaps.wear.health.HealthReading
import app.aaps.wear.health.HealthValues
import app.aaps.wear.health.HeartRateComplication
import app.aaps.wear.health.StepsComplication
import app.aaps.wear.health.healthComplicationData
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.customSlotDefaultSource
import java.time.Instant

/**
 * Draws the complication slots of the Custom watch face on the phone. On the watch the system draws
 * them, with data from complication sources that run on the watch; the phone runs none.
 *
 * So a slot shows what it shows on the watch before the user picks anything else (see
 * [customSlotDefaultSource]): AAPS heart rate in slot 1 and AAPS steps in slot 2, with the values kept
 * in [health]. The watch measures these and does not send them to the phone, so in the app the slot
 * shows the source's own "no value" text, never a made-up number. A slot whose default source is not
 * one of these stays empty, as before.
 *
 * Where a slot is and how it looks comes from the watch's own code ([CustomWatchface.slotLook]); it is
 * drawn with the library's [ComplicationDrawable], as on the watch.
 *
 * A slot that fails is left out and logged: it can not stop the preview.
 */
internal class PreviewSlots(private val context: Context, private val health: HealthValues, private val logger: AAPSLogger) {

    /** One drawable per slot, kept: making it loads the library's resources. */
    private val drawables = HashMap<Int, ComplicationDrawable>()

    /** Draws every slot that [face] shows, at [nowMs]. */
    fun draw(canvas: Canvas, face: CustomWatchface, nowMs: Long) {
        for (slot in CustomWatchface.complicationSlots) {
            try {
                drawSlot(canvas, face, slot.id, nowMs)
            } catch (e: Exception) {
                logger.error(LTag.WEAR, "Preview: slot ${slot.id} not drawn", e)
            }
        }
    }

    private fun drawSlot(canvas: Canvas, face: CustomWatchface, slotId: Int, nowMs: Long) {
        val (at, style) = face.slotLook(slotId) ?: return
        val data = dataFor(slotId) ?: return
        val drawable = drawables.getOrPut(slotId) { ComplicationDrawable(context) }
        style.applyTo(drawable)
        drawable.setComplicationData(data, false)
        drawable.currentTime = Instant.ofEpochMilli(nowMs)
        drawable.bounds = at.bounds
        canvas.withRotation(at.rotation, at.bounds.exactCenterX(), at.bounds.exactCenterY()) { drawable.draw(this) }
    }

    /** What slot [slotId] shows with its default source, in the type that source sends, or null for a source the phone can not stand in for. */
    fun dataFor(slotId: Int): ComplicationData? {
        val policy = customSlotDefaultSource(context, slotId)
        val kind = when (policy.primaryDataSource?.className) {
            HeartRateComplication::class.java.name -> HealthKind.HEART_RATE
            StepsComplication::class.java.name     -> HealthKind.STEPS
            else                                   -> return null
        }
        val value = when (kind) {
            HealthKind.HEART_RATE -> health.heartRate()?.toLong()
            HealthKind.STEPS      -> health.stepsToday()
        }
        val type = policy.primaryDataSourceDefaultType ?: ComplicationType.SHORT_TEXT
        return healthComplicationData(context, HealthReading(kind, value, missingPermission = false), type, null)
    }
}
