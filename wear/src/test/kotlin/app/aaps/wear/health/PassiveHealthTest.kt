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
 * Health Services is asked for exactly what a watch face shows, and its batches end up in the values
 * the complications read. Robolectric, because the data points are Android types.
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
            calls += registerCall(types)
        }

        override suspend fun clear() {
            calls += "clear"
        }
    }

    private companion object {

        fun registerCall(types: Set<DataType<*, *>>) = "register ${types.map { it.name }.sorted()}"
    }

    private val values = HealthValues(SharedPreferencesMock())
    private val client = FakeClient()
    private val granted = mutableSetOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
    private val registration = PassiveHealthRegistration(client, values, AAPSLoggerTest(), CoroutineScope(Dispatchers.Unconfined)) { it in granted }

    @Test
    fun nothingEverShownNeverTalksToHealthServices(): Unit = runBlocking<Unit> {
        registration.update()
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun onlyTheShownKindIsRegistered(): Unit = runBlocking<Unit> {
        values.setShown(HealthKind.HEART_RATE, 1, true)
        registration.update()
        assertThat(client.calls).containsExactly(registerCall(setOf(DataType.HEART_RATE_BPM)))
    }

    @Test
    fun bothShownRegistersBoth_andTheLastOneLeavingClearsAll(): Unit = runBlocking<Unit> {
        values.setShown(HealthKind.HEART_RATE, 1, true)
        values.setShown(HealthKind.STEPS, 2, true)
        registration.update()
        assertThat(client.calls).containsExactly(registerCall(setOf(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY)))

        values.setShown(HealthKind.HEART_RATE, 1, false)
        values.setShown(HealthKind.STEPS, 2, false)
        registration.update()
        assertThat(client.calls.last()).isEqualTo("clear")
        assertThat(values.passiveRegistered).isFalse()

        // Cleared once is enough
        registration.update()
        assertThat(client.calls.filter { it == "clear" }).hasSize(1)
    }

    @Test
    fun aKindWithoutPermissionIsNotRegistered(): Unit = runBlocking<Unit> {
        values.setShown(HealthKind.HEART_RATE, 1, true)
        values.setShown(HealthKind.STEPS, 2, true)
        granted -= Manifest.permission.BODY_SENSORS
        registration.update()
        assertThat(client.calls).containsExactly(registerCall(setOf(DataType.STEPS_DAILY)))
        assertThat(registration.isGranted(HealthKind.HEART_RATE)).isFalse()
    }

    @Test
    fun aKindTheWatchCannotMeasureIsNotRegistered(): Unit = runBlocking<Unit> {
        client.supported = setOf(DataType.STEPS_DAILY)
        values.setShown(HealthKind.HEART_RATE, 1, true)
        registration.update()
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun aPermissionGrantedElsewhereIsNoticedOnTheNextRequest(): Unit = runBlocking<Unit> {
        values.setShown(HealthKind.HEART_RATE, 1, true)
        granted -= Manifest.permission.BODY_SENSORS
        registration.update()
        assertThat(client.calls).isEmpty()

        // Nothing changed: no new update
        registration.updateIfPermissionsChanged()
        assertThat(client.calls).isEmpty()

        // Granted from the heart rate setting for the phone, not from the complication
        granted += Manifest.permission.BODY_SENSORS
        registration.updateIfPermissionsChanged()
        assertThat(client.calls).containsExactly(registerCall(setOf(DataType.HEART_RATE_BPM)))
    }

    @Test
    fun aTapAsksForTheShownKindsAndAlwaysForTheTappedOne() {
        granted.clear()
        // Nothing noted as shown, for example after app data was cleared: the tapped kind is still asked for
        assertThat(registration.missingPermissions(HealthKind.STEPS)).containsExactly(Manifest.permission.ACTIVITY_RECOGNITION)
        assertThat(registration.missingPermissions(null)).isEmpty()

        values.setShown(HealthKind.HEART_RATE, 1, true)
        assertThat(registration.missingPermissions(HealthKind.HEART_RATE)).containsExactly(Manifest.permission.BODY_SENSORS)
        assertThat(registration.missingPermissions(HealthKind.STEPS))
            .containsExactly(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)

        // Granted ones are not asked again
        granted += Manifest.permission.BODY_SENSORS
        assertThat(registration.missingPermissions(HealthKind.HEART_RATE)).isEmpty()
    }

    @Test
    fun aComplicationNoticedOnlyOnARequestIsRegistered(): Unit = runBlocking<Unit> {
        // App data was cleared: the system does not activate the complication again, it only requests data
        assertThat(values.markShown(HealthKind.HEART_RATE, 7)).isTrue()
        assertThat(values.markShown(HealthKind.HEART_RATE, 7)).isFalse()
        registration.update()
        assertThat(client.calls).containsExactly(registerCall(setOf(DataType.HEART_RATE_BPM)))
    }

    @Test
    fun twoUpdatesAtOnceEndWithTheNewestList(): Unit = runBlocking<Unit> {
        // The first update reads "heart rate only" and is held inside Health Services
        values.setShown(HealthKind.HEART_RATE, 1, true)
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        val first = launch { registration.update() }
        yield()
        // Meanwhile a steps complication is added, and its update starts
        values.setShown(HealthKind.STEPS, 2, true)
        val second = launch { registration.update() }
        yield()
        gate.complete(Unit)
        first.join()
        second.join()
        // Without the lock the older list could be registered last and steps would be lost
        assertThat(client.calls.last()).isEqualTo(registerCall(setOf(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY)))
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
        // Asked again now and then, so an old heart rate disappears even when no new batch comes in
        assertThat(hr.metaData.getInt("android.support.wearable.complications.UPDATE_PERIOD_SECONDS")).isEqualTo(600)
        val steps = pm.getServiceInfo(ComponentName(context, StepsComplication::class.java), PackageManager.GET_META_DATA)
        assertThat(steps.metaData.getString("android.support.wearable.complications.SUPPORTED_TYPES")).isEqualTo("RANGED_VALUE,GOAL_PROGRESS,SHORT_TEXT")
        assertThat(steps.metaData.getInt("android.support.wearable.complications.UPDATE_PERIOD_SECONDS")).isEqualTo(600)
        val receiver = pm.getServiceInfo(ComponentName(context, HealthPassiveService::class.java), 0)
        assertThat(receiver.permission).isEqualTo("com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING")
    }
}
