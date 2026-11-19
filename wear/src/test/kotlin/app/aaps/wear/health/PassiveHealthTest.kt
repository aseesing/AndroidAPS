package app.aaps.wear.health

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.health.services.client.data.DataPoint
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.SampleDataPoint
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.testing.mocks.SharedPreferencesMock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Health Services is asked for the heart rate only while the design of the face uses `bpm`, and its
 * batches end up in the values the face reads. Robolectric, because the data points are Android types.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PassiveHealthTest {

    private class FakeClient(var supported: Set<DataType<*, *>> = setOf(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY)) : PassiveClient {

        val calls = mutableListOf<String>()

        /** When set, the next call of [supported] waits for it, so a test can hold one update in the middle. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun supported(): Set<DataType<*, *>> {
            val waitFor = gate
            gate = null
            waitFor?.await()
            return supported
        }

        override suspend fun register(types: Set<DataType<*, *>>) {
            calls += "register ${types.map { it.name }.sorted()}"
        }

        override suspend fun clear() {
            calls += "clear"
        }
    }

    private val heartRateOnly = "register [${DataType.HEART_RATE_BPM.name}]"

    private val values = HealthValues(SharedPreferencesMock())
    private val client = FakeClient()
    private val granted = mutableSetOf(Manifest.permission.BODY_SENSORS)
    private var asked = 0
    private val registration = PassiveHealthRegistration(client, values, AAPSLoggerTest(), CoroutineScope(Dispatchers.Unconfined), { asked++ }) { it in granted }

    @Test
    fun aFaceWithoutBpmNeverTalksToHealthServices(): Unit = runBlocking<Unit> {
        registration.start()
        registration.update()
        assertThat(client.calls).isEmpty()
        assertThat(asked).isEqualTo(0)
    }

    @Test
    fun onlyTheHeartRateIsRegisteredWhileTheFaceWantsIt(): Unit = runBlocking<Unit> {
        registration.start()
        values.faceWantsHeartRate = true
        assertThat(client.calls).containsExactly(heartRateOnly)
        values.faceWantsHeartRate = false
        assertThat(client.calls).containsExactly(heartRateOnly, "clear").inOrder()
        // Nothing registered any more: a second update leaves Health Services alone
        registration.update()
        assertThat(client.calls).hasSize(2)
    }

    @Test
    fun startRegistersAgainAfterARestart(): Unit = runBlocking<Unit> {
        values.faceWantsHeartRate = true    // kept from before the restart, no one listened yet
        assertThat(client.calls).isEmpty()
        registration.start()
        assertThat(client.calls).containsExactly(heartRateOnly)
    }

    @Test
    fun withoutPermissionNothingIsRegisteredAndTheWearerIsAskedOnce(): Unit = runBlocking<Unit> {
        granted.clear()
        registration.start()
        values.faceWantsHeartRate = true
        values.faceWantsHeartRate = true    // the face reads its design again: no second notification
        assertThat(client.calls).isEmpty()
        assertThat(asked).isEqualTo(1)
        // Allowed later (the notification opens HealthPermissionActivity, which updates)
        granted += Manifest.permission.BODY_SENSORS
        registration.update()
        assertThat(client.calls).containsExactly(heartRateOnly)
    }

    @Test
    fun aWatchThatCannotMeasureTheHeartRateRegistersNothing(): Unit = runBlocking<Unit> {
        client.supported = setOf(DataType.STEPS_DAILY)
        values.faceWantsHeartRate = true
        registration.update()
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun twoUpdatesAtOnceEndWithTheNewestWish(): Unit = runBlocking<Unit> {
        // The first update reads "wanted" and is held inside Health Services
        values.faceWantsHeartRate = true
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        val first = launch { registration.update() }
        yield()
        // Meanwhile a design without bpm is loaded, and its update starts
        values.faceWantsHeartRate = false
        val second = launch { registration.update() }
        yield()
        gate.complete(Unit)
        first.join()
        second.join()
        // Without the lock the older wish could win, and the heart rate would stay registered
        assertThat(client.calls.last()).isEqualTo("clear")
    }

    // ---- batches ----

    private val bootMs = 1_700_000_000_000L

    private fun heartRate(bpm: Double, secondsAfterBoot: Long): DataPoint<*> =
        SampleDataPoint(DataType.HEART_RATE_BPM, bpm, Duration.ofSeconds(secondsAfterBoot))

    @Test
    fun aBatchKeepsTheNewestHeartRate() {
        val now = bootMs + 3_600_000
        val fresh = HealthValues(SharedPreferencesMock()) { now }
        assertThat(fresh.record(DataPointContainer(listOf(heartRate(80.0, 3000), heartRate(72.0, 3500), heartRate(95.0, 3400))), bootMs)).isTrue()
        assertThat(fresh.heartRate()).isEqualTo(72)
    }

    @Test
    fun aBatchWithNothingNewChangesNothing() {
        val now = bootMs + 3_600_000
        val fresh = HealthValues(SharedPreferencesMock()) { now }
        fresh.record(DataPointContainer(listOf(heartRate(72.0, 3500))), bootMs)
        assertThat(fresh.record(DataPointContainer(listOf(heartRate(60.0, 3000))), bootMs)).isFalse()
        assertThat(fresh.record(DataPointContainer(emptyList()), bootMs)).isFalse()
        assertThat(fresh.heartRate()).isEqualTo(72)
    }

    // ---- manifest ----

    @Test
    fun onlyHealthServicesMayBindTheReceiverAndNoHealthComplicationIsLeft() {
        val context = RuntimeEnvironment.getApplication() as Context
        val pm = context.packageManager
        val receiver = pm.getServiceInfo(ComponentName(context, HealthPassiveService::class.java), 0)
        assertThat(receiver.permission).isEqualTo("com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING")
        val services = pm.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty().map { it.name }
        assertThat(services.filter { it.startsWith("app.aaps.wear.health.") }).containsExactly(HealthPassiveService::class.java.name)
    }
}
