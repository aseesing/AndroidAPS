// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.health

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.health.services.client.data.DataPoint
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.SampleDataPoint
import org.robolectric.RuntimeEnvironment
import app.aaps.wear.testing.mocks.SharedPreferencesMock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Health Services is asked for exactly what a watch face shows, and its batches end up in the values
 * the complications read. Robolectric, because the data points are Android types.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PassiveHealthTest {

    private class FakeClient(var supported: Set<DataType<*, *>> = setOf(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY)) : PassiveClient {

        val calls = mutableListOf<String>()
        override suspend fun supported() = supported
        override suspend fun register(types: Set<DataType<*, *>>) {
            calls += "register ${types.map { it.name }.sorted()}"
        }

        override suspend fun clear() {
            calls += "clear"
        }
    }

    private val values = HealthValues(SharedPreferencesMock())
    private val client = FakeClient()
    private val granted = mutableSetOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
    private val registration = PassiveHealthRegistration(client, values) { it in granted }

    @Test
    fun nothingShownRegistersNothing(): Unit = runBlocking<Unit> {
        registration.update()
        assertThat(client.calls).containsExactly("clear")
        assertThat(registration.registered).isEmpty()
    }

    @Test
    fun onlyTheShownKindIsRegistered(): Unit = runBlocking<Unit> {
        values.setShown(HealthKind.HEART_RATE, 1, true)
        registration.update()
        assertThat(registration.registered).containsExactly(DataType.HEART_RATE_BPM)
    }

    @Test
    fun bothShownRegistersBoth_andTheLastOneLeavingClearsAll(): Unit = runBlocking<Unit> {
        values.setShown(HealthKind.HEART_RATE, 1, true)
        values.setShown(HealthKind.STEPS, 2, true)
        registration.update()
        assertThat(registration.registered).containsExactly(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY)

        values.setShown(HealthKind.HEART_RATE, 1, false)
        values.setShown(HealthKind.STEPS, 2, false)
        registration.update()
        assertThat(client.calls.last()).isEqualTo("clear")
    }

    @Test
    fun aKindWithoutPermissionIsNotRegistered(): Unit = runBlocking<Unit> {
        values.setShown(HealthKind.HEART_RATE, 1, true)
        values.setShown(HealthKind.STEPS, 2, true)
        granted -= Manifest.permission.BODY_SENSORS
        registration.update()
        assertThat(registration.registered).containsExactly(DataType.STEPS_DAILY)
    }

    @Test
    fun aKindTheWatchCannotMeasureIsNotRegistered(): Unit = runBlocking<Unit> {
        client.supported = setOf(DataType.STEPS_DAILY)
        values.setShown(HealthKind.HEART_RATE, 1, true)
        registration.update()
        assertThat(client.calls).containsExactly("clear")
    }

    // ---- batches ----

    private val bootMs = 1_700_000_000_000L

    private fun heartRate(bpm: Double, secondsAfterBoot: Long): DataPoint<*> =
        SampleDataPoint(DataType.HEART_RATE_BPM, bpm, Duration.ofSeconds(secondsAfterBoot))

    private fun steps(count: Long, endSecondsAfterBoot: Long): DataPoint<*> =
        IntervalDataPoint(DataType.STEPS_DAILY, count, Duration.ofSeconds(endSecondsAfterBoot - 60), Duration.ofSeconds(endSecondsAfterBoot))

    @Test
    fun aBatchKeepsTheNewestOfEachKind() {
        val now = bootMs + 3_600_000
        val fresh = HealthValues(SharedPreferencesMock(), { now })
        val changed = fresh.record(
            DataPointContainer(listOf(heartRate(80.0, 3000), heartRate(72.0, 3500), heartRate(95.0, 3400), steps(6400, 3000), steps(6482, 3500))),
            bootMs
        )
        assertThat(changed).containsExactly(HealthKind.HEART_RATE, HealthKind.STEPS)
        assertThat(fresh.heartRate()).isEqualTo(72)
        assertThat(fresh.stepsToday()).isEqualTo(6482)
    }

    @Test
    fun aBatchWithNothingNewChangesNothing() {
        val now = bootMs + 3_600_000
        val fresh = HealthValues(SharedPreferencesMock(), { now })
        fresh.record(DataPointContainer(listOf(heartRate(72.0, 3500))), bootMs)
        val changed = fresh.record(DataPointContainer(listOf(heartRate(60.0, 3000))), bootMs)
        assertThat(changed).isEmpty()
        assertThat(fresh.heartRate()).isEqualTo(72)
    }

    // ---- manifest ----

    @Test
    fun theManifestDeclaresTheSourcesAndOnlyHealthServicesMayBindTheReceiver() {
        val context = RuntimeEnvironment.getApplication() as Context
        val pm = context.packageManager
        val hr = pm.getServiceInfo(ComponentName(context, HeartRateComplication::class.java), PackageManager.GET_META_DATA)
        assertThat(hr.metaData.getString("android.support.wearable.complications.SUPPORTED_TYPES")).isEqualTo("RANGED_VALUE,SHORT_TEXT")
        assertThat(hr.permission).isEqualTo("com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER")
        val steps = pm.getServiceInfo(ComponentName(context, StepsComplication::class.java), PackageManager.GET_META_DATA)
        assertThat(steps.metaData.getString("android.support.wearable.complications.SUPPORTED_TYPES")).isEqualTo("RANGED_VALUE,GOAL_PROGRESS,SHORT_TEXT")
        // Pushed, never polled
        assertThat(steps.metaData.getInt("android.support.wearable.complications.UPDATE_PERIOD_SECONDS")).isEqualTo(0)
        val receiver = pm.getServiceInfo(ComponentName(context, HealthPassiveService::class.java), 0)
        assertThat(receiver.permission).isEqualTo("com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING")
    }
}
