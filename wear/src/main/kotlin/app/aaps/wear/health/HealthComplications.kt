// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.health

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
