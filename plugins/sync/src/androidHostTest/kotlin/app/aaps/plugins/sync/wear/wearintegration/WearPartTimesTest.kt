package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.keys.IntKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WearPartTimesTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer

    private lateinit var sut: WearPartTimes
    private val hour = 60 * 60 * 1000L
    private val changed = 1_000_000_000_000L

    @BeforeEach
    fun setUp() {
        sut = WearPartTimes(persistenceLayer, activePlugin, preferences)
        whenever(preferences.get(IntKey.OverviewSageCritical)).thenReturn(216)
        whenever(preferences.get(IntKey.OverviewCageCritical)).thenReturn(72)
    }

    private fun change(type: TE.Type) = TE(timestamp = changed, type = type, glucoseUnit = GlucoseUnit.MGDL)

    private fun reading(sensor: SourceSensor) = GV(timestamp = changed, raw = null, value = 120.0, trendArrow = TrendArrow.FLAT, noise = null, sourceSensor = sensor)

    @Test
    fun aKnownSensorEndsAfterItsLifetimeAndGrace() = runBlocking {
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(change(TE.Type.SENSOR_CHANGE))
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(reading(SourceSensor.DEXCOM_G7))
        // A G7: 10 days and 12 hours
        assertThat(sut.sensorEnd()).isEqualTo(changed + 252 * hour)
    }

    @Test
    fun anUnknownSensorEndsAtTheCriticalThreshold() = runBlocking {
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(change(TE.Type.SENSOR_CHANGE))
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(null)
        assertThat(sut.sensorEnd()).isEqualTo(changed + 216 * hour)
    }

    @Test
    fun aPodEndsAfterEightyHours() = runBlocking {
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)).thenReturn(change(TE.Type.CANNULA_CHANGE))
        val pod: PumpWithConcentration = mock()
        whenever(pod.model()).thenReturn(PumpType.OMNIPOD_DASH)
        whenever(activePlugin.activePump).thenReturn(pod)
        assertThat(sut.pumpEnd()).isEqualTo(changed + 80 * hour)
    }

    @Test
    fun withoutAChangeThereIsNoTime() = runBlocking {
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(null)
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)).thenReturn(null)
        assertThat(sut.sensorEnd()).isNull()
        assertThat(sut.pumpEnd()).isNull()
    }
}
