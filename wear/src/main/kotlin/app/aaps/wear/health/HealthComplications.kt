package app.aaps.wear.health

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.aaps.wear.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What one health complication shows: the [value] (null when there is none), whether the permission
 * is still [missingPermission], and how a gauge spans it. Pure, so it can be tested without a service.
 */
data class HealthReading(val kind: HealthKind, val value: Long?, val missingPermission: Boolean) {

    /** The gauge range: a heart rate between rest and effort, steps towards the daily goal. */
    val min: Float get() = if (kind == HealthKind.HEART_RATE) HEART_RATE_MIN else 0f
    val max: Float get() = if (kind == HealthKind.HEART_RATE) HEART_RATE_MAX else STEPS_GOAL

    /** The gauge position, kept inside the range so the drawable always draws. */
    val gauge: Float get() = (value?.toFloat() ?: min).coerceIn(min, max)

    companion object {

        const val HEART_RATE_MIN = 40f
        const val HEART_RATE_MAX = 180f
        const val STEPS_GOAL = 10_000f
    }
}

/**
 * The complication data for [reading] in the [type] the slot asked for. A slot always gets the type it
 * asked for: with no value the text is "--", and with the permission missing it says so and a tap asks
 * for it.
 */
fun healthComplicationData(context: Context, reading: HealthReading, type: ComplicationType, tapAction: PendingIntent?): ComplicationData? {
    val shown = when {
        reading.missingPermission -> context.getString(R.string.health_allow)
        reading.value == null     -> context.getString(R.string.health_no_value)
        else                      -> reading.value.toString()
    }
    val text = PlainComplicationText.Builder(shown).build()
    val description = PlainComplicationText.Builder(
        when {
            reading.missingPermission             -> context.getString(R.string.health_allow_description)
            reading.kind == HealthKind.HEART_RATE -> context.getString(R.string.health_heart_rate_description, shown)
            else                                  -> context.getString(R.string.health_steps_description, shown)
        }
    ).build()
    val image = MonochromaticImage.Builder(Icon.createWithResource(context, iconOf(reading))).build()
    return when (type) {
        ComplicationType.RANGED_VALUE  ->
            RangedValueComplicationData.Builder(reading.gauge, reading.min, reading.max, description)
                .setText(text).setMonochromaticImage(image).setTapAction(tapAction).build()

        ComplicationType.GOAL_PROGRESS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                GoalProgressComplicationData.Builder(reading.value?.toFloat() ?: 0f, reading.max, description)
                    .setText(text).setMonochromaticImage(image).setTapAction(tapAction).build()
            else null

        ComplicationType.SHORT_TEXT    ->
            ShortTextComplicationData.Builder(text, description).setMonochromaticImage(image).setTapAction(tapAction).build()

        else                           -> null
    }
}

@DrawableRes
private fun iconOf(reading: HealthReading): Int = when {
    reading.missingPermission             -> R.drawable.ic_health_lock
    reading.kind == HealthKind.HEART_RATE -> R.drawable.ic_health_heart
    else                                  -> R.drawable.ic_health_steps
}

/**
 * A complication with the latest value of one [kind] from Health Services.
 *
 * Being shown is what switches the measuring on: activation registers the data type with Health
 * Services, and when the last complication of a kind leaves the watch face it is unregistered again.
 */
abstract class HealthComplication(private val kind: HealthKind) : SuspendingComplicationDataSourceService() {

    /** Registration is a short call to Health Services that may outlive this service instance. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        HealthValues.of(this).setShown(kind, complicationInstanceId, true)
        scope.launch { PassiveHealthRegistration.updateQuietly(applicationContext) }
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        HealthValues.of(this).setShown(kind, complicationInstanceId, false)
        scope.launch { PassiveHealthRegistration.updateQuietly(applicationContext) }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val values = HealthValues.of(this)
        val missing = ContextCompat.checkSelfPermission(this, kind.permission) != PackageManager.PERMISSION_GRANTED
        val value = when (kind) {
            HealthKind.HEART_RATE -> values.heartRate()?.toLong()
            HealthKind.STEPS      -> values.stepsToday()
        }
        val tap = if (missing) permissionIntent() else null
        return healthComplicationData(this, HealthReading(kind, value, missing), request.complicationType, tap)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        healthComplicationData(this, HealthReading(kind, if (kind == HealthKind.HEART_RATE) 72 else 6482, false), type, null)

    private fun permissionIntent(): PendingIntent = PendingIntent.getActivity(
        this, kind.ordinal, Intent(this, HealthPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

class HeartRateComplication : HealthComplication(HealthKind.HEART_RATE)

class StepsComplication : HealthComplication(HealthKind.STEPS)
