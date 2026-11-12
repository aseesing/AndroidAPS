// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.health

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.clearPassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.setPassiveListenerService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.time.Instant

/** The Health Services data type behind each [HealthKind], and the permission it needs. */
internal val HealthKind.dataType: DataType<*, *>
    get() = when (this) {
        HealthKind.HEART_RATE -> DataType.HEART_RATE_BPM
        HealthKind.STEPS      -> DataType.STEPS_DAILY
    }

internal val HealthKind.permission: String
    get() = when (this) {
        HealthKind.HEART_RATE -> Manifest.permission.BODY_SENSORS
        HealthKind.STEPS      -> Manifest.permission.ACTIVITY_RECOGNITION
    }

/** The part of Health Services this package uses, so the rules can be tested without it. */
interface PassiveClient {

    suspend fun supported(): Set<DataType<*, *>>
    suspend fun register(types: Set<DataType<*, *>>)
    suspend fun clear()
}

class HealthServicesPassiveClient(context: Context) : PassiveClient {

    private val client = HealthServices.getClient(context).passiveMonitoringClient

    override suspend fun supported(): Set<DataType<*, *>> = client.getCapabilities().supportedDataTypesPassiveMonitoring

    override suspend fun register(types: Set<DataType<*, *>>) {
        client.setPassiveListenerService(HealthPassiveService::class.java, PassiveListenerConfig.builder().setDataTypes(types).build())
    }

    override suspend fun clear() {
        client.clearPassiveListenerService()
    }
}

/**
 * Asks Health Services for exactly the data a watch face shows: heart rate and steps only while a
 * complication of that kind is on a watch face and its permission is granted, and nothing at all
 * otherwise.
 *
 * Passive data costs no sensor of its own: the watch measures heart rate and steps anyway for its own
 * health tracking, and Health Services hands the values over in batches. Registrations do not survive
 * a reboot, so [update] is also called whenever the app starts.
 */
class PassiveHealthRegistration(
    private val client: PassiveClient,
    private val values: HealthValues,
    private val granted: (String) -> Boolean
) {

    /** What was asked for last, for tests and the log. Empty means nothing is registered. */
    var registered: Set<DataType<*, *>> = emptySet()
        private set

    suspend fun update() {
        val wanted = values.wanted().filter { granted(it.permission) }.map { it.dataType }.toSet()
        val types = if (wanted.isEmpty()) emptySet() else wanted intersect client.supported()
        if (types.isEmpty()) client.clear() else client.register(types)
        registered = types
    }

    companion object {

        fun of(context: Context) = PassiveHealthRegistration(
            HealthServicesPassiveClient(context.applicationContext),
            HealthValues.of(context)
        ) { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

        /** Runs [update], logging instead of failing: a watch without Health Services shows "--". */
        suspend fun updateQuietly(context: Context) {
            try {
                of(context).update()
            } catch (e: Exception) {
                Log.w(TAG, "Health Services registration failed", e)
            }
        }

        private const val TAG = "AAPS.Health"
    }
}

/**
 * Keeps the newest heart rate and today's steps out of one batch from Health Services.
 *
 * Data points carry their time as a duration since boot; [bootMs] turns that into wall time. Returns
 * the kinds that got a newer value, so only their complications are refreshed.
 */
fun HealthValues.record(points: DataPointContainer, bootMs: Long): Set<HealthKind> {
    val boot = Instant.ofEpochMilli(bootMs)
    val changed = mutableSetOf<HealthKind>()
    points.getData(DataType.HEART_RATE_BPM).maxByOrNull { it.timeDurationFromBoot }?.let {
        if (recordHeartRate(it.value.toInt(), it.getTimeInstant(boot).toEpochMilli())) changed += HealthKind.HEART_RATE
    }
    points.getData(DataType.STEPS_DAILY).maxByOrNull { it.endDurationFromBoot }?.let {
        if (recordSteps(it.value, it.getEndInstant(boot).toEpochMilli())) changed += HealthKind.STEPS
    }
    return changed
}

/** Asks the system to redraw the complications of [kinds] on every watch face that shows them. */
fun requestHealthComplicationUpdates(context: Context, kinds: Set<HealthKind>) {
    kinds.forEach { kind ->
        val service = when (kind) {
            HealthKind.HEART_RATE -> HeartRateComplication::class.java
            HealthKind.STEPS      -> StepsComplication::class.java
        }
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
    }
}

/** Receives the batches. Health Services binds it itself; the manifest allows nothing else to. */
class HealthPassiveService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val changed = HealthValues.of(this).record(dataPoints, bootMs)
        if (changed.isNotEmpty()) requestHealthComplicationUpdates(this, changed)
    }
}
