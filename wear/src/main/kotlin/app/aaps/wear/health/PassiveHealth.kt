package app.aaps.wear.health

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.clearPassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.setPassiveListenerService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.aaps.core.interfaces.di.injectMetroMembers
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

class HealthServicesPassiveClient(private val context: Context) : PassiveClient {

    /** Made on first use only, so a watch that never shows a health complication never binds to Health Services. */
    private val client: PassiveMonitoringClient by lazy { HealthServices.getClient(context).passiveMonitoringClient }

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
 * a reboot, so [updateInBackground] is also called whenever the app starts.
 *
 * One instance for the whole app (see `HealthModule`): its lock makes sure two updates never run at
 * the same time, so the last update always wins with the newest list of shown complications.
 *
 * @param granted whether the app has this Android permission now
 */
class PassiveHealthRegistration(
    private val client: PassiveClient,
    private val values: HealthValues,
    private val aapsLogger: AAPSLogger,
    private val scope: CoroutineScope,
    private val granted: (String) -> Boolean
) {

    private val lock = Mutex()

    /**
     * The permissions that were granted at the last update, to notice when they change. Null before the
     * first update. Written under [lock], read without it from the complication requests.
     */
    @Volatile private var grantedAtLastUpdate: Set<String>? = null

    fun isGranted(kind: HealthKind): Boolean = granted(kind.permission)

    /**
     * The permissions to ask for after a tap on a complication of [tapped]: the missing ones of every
     * kind a watch face shows, and always the one of [tapped] when it is missing, even if the list of
     * shown complications was lost.
     */
    fun missingPermissions(tapped: HealthKind?): List<String> =
        (values.wanted() + listOfNotNull(tapped)).filterNot { isGranted(it) }.map { it.permission }.distinct()

    /** Registers what is shown now, or clears the registration when nothing is shown. Throws when Health Services fails. */
    suspend fun update() = lock.withLock {
        val grantedNow = grantedPermissions()
        val wanted = values.wanted().filter { it.permission in grantedNow }.map { it.dataType }.toSet()
        val types = if (wanted.isEmpty()) emptySet() else wanted intersect client.supported()
        if (types.isNotEmpty()) {
            client.register(types)
            values.passiveRegistered = true
        } else if (values.passiveRegistered) {
            client.clear()
            values.passiveRegistered = false
        }
        grantedAtLastUpdate = grantedNow
        aapsLogger.debug(LTag.WEAR, "Health Services passive data: ${types.map { it.name }}")
    }

    /** Runs [update], logging instead of failing: a watch without Health Services shows "--". */
    suspend fun updateQuietly() {
        try {
            update()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Health Services registration failed", e)
        }
    }

    /** [updateQuietly] without waiting for it. */
    fun updateInBackground() {
        scope.launch { updateQuietly() }
    }

    /**
     * Updates when a permission was granted or taken away since the last update. The permissions are
     * shared with the heart rate and steps sampling for the phone, so the user may grant them there.
     */
    fun updateIfPermissionsChanged() {
        if (grantedPermissions() != grantedAtLastUpdate) updateInBackground()
    }

    private fun grantedPermissions(): Set<String> = HealthKind.entries.map { it.permission }.filter(granted).toSet()
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

    @Inject lateinit var healthValues: HealthValues

    override fun onCreate() {
        injectMetroMembers(this)
        super.onCreate()
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val changed = healthValues.record(dataPoints, bootMs)
        if (changed.isNotEmpty()) requestHealthComplicationUpdates(this, changed)
    }
}
