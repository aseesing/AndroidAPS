package app.aaps.wear.health

import android.Manifest
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

/** The Android permission that heart rate from Health Services needs. */
const val HEART_RATE_PERMISSION = Manifest.permission.BODY_SENSORS

/** The part of Health Services this package uses, so the rules can be tested without it. */
interface PassiveClient {

    suspend fun supported(): Set<DataType<*, *>>
    suspend fun register(types: Set<DataType<*, *>>)
    suspend fun clear()
}

class HealthServicesPassiveClient(private val context: Context) : PassiveClient {

    /** Made on first use only, so a watch whose face never uses `bpm` never binds to Health Services. */
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
 * Asks Health Services for the heart rate only while the design of the Custom watch face uses `bpm`
 * ([HealthValues.faceWantsHeartRate]) and the permission is granted, and for nothing otherwise.
 *
 * Passive data costs no sensor of its own: the watch measures the heart rate anyway for its own health
 * tracking, and Health Services hands the values over in batches. Registrations do not survive a
 * reboot, so [start] registers again whenever the app starts.
 *
 * One instance for the whole app (see `HealthModule`): its lock makes sure two updates never run at
 * the same time, so the last update always wins with the newest wish of the face.
 *
 * @param askPermission shows the wearer a way to allow the heart rate; called when the face starts to
 *   want it and the permission is missing
 * @param granted whether the app has this Android permission now
 */
class PassiveHealthRegistration(
    private val client: PassiveClient,
    private val values: HealthValues,
    private val aapsLogger: AAPSLogger,
    private val scope: CoroutineScope,
    private val askPermission: () -> Unit,
    private val granted: (String) -> Boolean
) {

    private val lock = Mutex()

    /** Follows the wish of the face from now on, and registers what it wants now. Called once, when the app starts. */
    fun start() {
        values.onFaceWishChanged = { wanted ->
            updateInBackground()
            if (wanted && !isGranted()) askPermission()
        }
        updateInBackground()
    }

    fun isGranted(): Boolean = granted(HEART_RATE_PERMISSION)

    /** Registers the heart rate when the face wants it, or clears the registration otherwise. Throws when Health Services fails. */
    suspend fun update() = lock.withLock {
        val types = if (values.faceWantsHeartRate && isGranted()) setOf(DataType.HEART_RATE_BPM) intersect client.supported() else emptySet()
        if (types.isNotEmpty()) {
            client.register(types)
            values.passiveRegistered = true
        } else if (values.passiveRegistered) {
            client.clear()
            values.passiveRegistered = false
        }
        aapsLogger.debug(LTag.WEAR, "Health Services passive data: ${types.map { it.name }}")
    }

    /** Runs [update], logging instead of failing: without Health Services the face beats at its `fallbackBpm`. */
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
}

/**
 * Keeps the newest heart rate out of one batch from Health Services, and returns true when it was
 * newer than the one kept.
 *
 * Data points carry their time as a duration since boot; [bootMs] turns that into wall time.
 */
fun HealthValues.record(points: DataPointContainer, bootMs: Long): Boolean {
    val boot = Instant.ofEpochMilli(bootMs)
    val newest = points.getData(DataType.HEART_RATE_BPM).maxByOrNull { it.timeDurationFromBoot } ?: return false
    return recordHeartRate(newest.value.toInt(), newest.getTimeInstant(boot).toEpochMilli())
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
        healthValues.record(dataPoints, bootMs)
    }
}
