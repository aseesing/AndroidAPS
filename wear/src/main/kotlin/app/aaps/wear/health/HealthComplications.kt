package app.aaps.wear.health

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.aaps.core.interfaces.di.injectMetroMembers
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject

/**
 * A complication with the latest value of one [kind] from Health Services.
 *
 * Being shown is what switches the data on: activation registers the data type with Health Services,
 * and when the last complication of a kind leaves the watch face it is unregistered again.
 */
@HasMemberInjections
abstract class HealthComplication(private val kind: HealthKind) : SuspendingComplicationDataSourceService() {

    @Inject lateinit var healthValues: HealthValues
    @Inject lateinit var registration: PassiveHealthRegistration

    // Each subclass needs its own entry in WearMemberInjectors, because the injector is found by the
    // runtime class.
    override fun onCreate() {
        injectMetroMembers(this)
        super.onCreate()
    }

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        healthValues.setShown(kind, complicationInstanceId, true)
        registration.updateInBackground()
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        healthValues.setShown(kind, complicationInstanceId, false)
        registration.updateInBackground()
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // Normally noted on activation already, but not after app data was cleared or restored
        if (healthValues.markShown(kind, request.complicationInstanceId)) registration.updateInBackground()
        else registration.updateIfPermissionsChanged()
        val missing = !registration.isGranted(kind)
        val reading = when (kind) {
            HealthKind.HEART_RATE -> HealthReading(kind, healthValues.heartRate()?.toLong(), missing, healthValues.heartRateShownUntil())
            HealthKind.STEPS      -> HealthReading(kind, healthValues.stepsToday(), missing)
        }
        val tap = if (missing) permissionIntent() else null
        return healthComplicationData(this, reading, request.complicationType, tap)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        healthComplicationData(this, HealthReading(kind, if (kind == HealthKind.HEART_RATE) 72 else 6482, false), type, null)

    private fun permissionIntent(): PendingIntent = PendingIntent.getActivity(
        this, kind.ordinal,
        Intent(this, HealthPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(HealthPermissionActivity.EXTRA_KIND, kind.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

class HeartRateComplication : HealthComplication(HealthKind.HEART_RATE)

class StepsComplication : HealthComplication(HealthKind.STEPS)
