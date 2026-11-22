package app.aaps.wear.complications.wff

import android.app.PendingIntent
import android.os.Build
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicFloat
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import app.aaps.wear.complications.ComplicationAction
import app.aaps.wear.complications.ModernBaseComplicationProviderService
import app.aaps.wear.data.ComplicationData as ComplicationStore
import app.aaps.wear.watchfaces.wff.WffFaceShown
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import java.time.Instant

/**
 * The age of the last glucose reading for a Watch Face Format (WFF) face, as one `RANGED_VALUE`
 * in minutes (0 to [MAX_MINUTES]).
 *
 * The value is a dynamic value made from the platform's clock, so the watch keeps it right by
 * itself while our process is frozen, also in always-on. A face uses it for its "old reading" mark
 * (a `Condition` on the value). Checked on the Wear OS 6 emulator: it keeps counting with our
 * process stopped and drives a `Condition`.
 *
 * The text is "5m", which also counts by itself.
 *
 * Below Android 13 there are no dynamic values: the value is then a fixed number, right when it is sent
 * and old until the next update (a new reading, or the 300 s refresh of the complication). The API has no
 * ranged value that counts by itself there; only the text does. This does not matter in practice: the
 * faces that read this value are Watch Face Format faces, and those run on Wear OS 5 and later (Android 14).
 */
@HasMemberInjections
class WffGlucoseAgeComplication : ModernBaseComplicationProviderService() {

    // The phone names the face only while the watch shows it, see WffFaceShown
    @Inject lateinit var wffFaceShown: WffFaceShown

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) = wffFaceShown.activated(javaClass, complicationInstanceId)

    override fun onComplicationDeactivated(complicationInstanceId: Int) = wffFaceShown.deactivated(javaClass, complicationInstanceId)

    override fun buildComplicationData(type: ComplicationType, data: ComplicationStore, complicationPendingIntent: PendingIntent): ComplicationData? {
        if (type != ComplicationType.RANGED_VALUE) return null
        val bg = data.bgData
        if (!GlucoseRange.hasReading(bg)) return NoDataComplicationData()
        val now = System.currentTimeMillis()
        val description = PlainComplicationText.Builder("Glucose age").build()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            RangedValueComplicationData.Builder(
                dynamicValue = minutesSince(bg.timeStamp),
                fallbackValue = ageMinutes(bg.timeStamp, now),
                min = 0f,
                max = MAX_MINUTES,
                contentDescription = description
            )
        else
            RangedValueComplicationData.Builder(ageMinutes(bg.timeStamp, now), 0f, MAX_MINUTES, description)
        return builder
            .setText(buildCountUpText(bg.timeStamp, "^1"))
            .setTapAction(complicationPendingIntent)
            .build()
    }

    override fun getComplicationAction(): ComplicationAction = ComplicationAction.MENU

    companion object {

        /** The highest age the value shows. Older readings show this. */
        const val MAX_MINUTES = 60f

        /** Minutes from [timeStamp] to [now], inside 0 to [MAX_MINUTES]. */
        fun ageMinutes(timeStamp: Long, now: Long): Float = ((now - timeStamp) / 60_000f).coerceIn(0f, MAX_MINUTES)

        /**
         * Minutes from [timeStamp] to the platform's "now", worked out by the system itself, inside 0 to
         * [MAX_MINUTES] like [ageMinutes]: a face may draw the value as a bar or an arc, and a value past
         * the range draws wrong.
         */
        internal fun minutesSince(timeStamp: Long): DynamicFloat {
            val minutes = DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(timeStamp))
                .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
                .toIntSeconds()
                .asFloat()
                .div(60f)
            val atMost = DynamicFloat.onCondition(minutes.gt(MAX_MINUTES)).use(MAX_MINUTES).elseUse(minutes)
            return DynamicFloat.onCondition(minutes.lt(0f)).use(0f).elseUse(atMost)
        }
    }
}
