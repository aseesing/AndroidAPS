// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.shared.tests.generatedTextResolver
import app.aaps.ui.UiStringsValues
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class StatusTimeLeftTest {

    private val dateUtil: DateUtil = mock()
    private val preferences: Preferences = mock()
    private lateinit var sut: StatusTimeLeft

    private val g7 = DeviceLifetime.of(SourceSensor.DEXCOM_G7)
    private val pod = DeviceLifetime.of(PumpType.OMNIPOD_DASH)

    @BeforeEach
    fun setUp() {
        whenever(dateUtil.now()).thenReturn(NOW)
        sut = StatusTimeLeft(generatedTextResolver("ui" to UiStringsValues::textOf), dateUtil, preferences)
    }

    private fun changedAgo(ms: Long) = TE(timestamp = NOW - ms, type = TE.Type.SENSOR_CHANGE, glucoseUnit = GlucoseUnit.MGDL)

    private fun sensor(ago: Long) = sut.of(changedAgo(ago), g7, IntKey.OverviewSageWarning, IntKey.OverviewSageCritical)

    @Test
    fun `only the models with a certain lifetime are known`() {
        assertThat(g7?.lifetimeHours).isEqualTo(240)
        assertThat(g7?.graceHours).isEqualTo(12)
        assertThat(pod?.lifetimeHours).isEqualTo(72)
        assertThat(pod?.graceHours).isEqualTo(8)
        assertThat(DeviceLifetime.of(SourceSensor.LIBRE_2)).isNull()
        assertThat(DeviceLifetime.of(PumpType.MEDTRUM_NANO)).isNull()
        assertThat(DeviceLifetime.of(null as PumpType?)).isNull()
    }

    @Test
    fun `a fresh G7 counts down in days and hours to the end of its grace period`() {
        // 10 days and 12 hours in all, 2 days gone
        val view = sensor(ago = 2 * DAY)

        assertThat(view.timeLeft).isEqualTo("8d12h")
        assertThat(view.status).isEqualTo(StatusLevel.NORMAL)
    }

    @Test
    fun `less than one day left is yellow`() {
        // 9 days 14 hours gone: 22 hours left, and the grace period starts in 10
        val view = sensor(ago = 9 * DAY + 14 * HOUR)

        assertThat(view.timeLeft).isEqualTo("22h")
        assertThat(view.status).isEqualTo(StatusLevel.WARNING)
    }

    @Test
    fun `the grace period is red, and under 10 hours the minutes show and the days go`() {
        // 10 days 3 hours 25 minutes gone: in the grace period, 8 hours 35 minutes left
        val view = sensor(ago = 10 * DAY + 3 * HOUR + 25 * MINUTE)

        assertThat(view.timeLeft).isEqualTo("8h35m")
        assertThat(view.status).isEqualTo(StatusLevel.CRITICAL)
    }

    @Test
    fun `in the last hour only the minutes show`() {
        val view = sensor(ago = 10 * DAY + 11 * HOUR + 15 * MINUTE)

        assertThat(view.timeLeft).isEqualTo("45m")
    }

    @Test
    fun `past the end it shows how long ago, with a minus`() {
        // 2 hours 10 minutes past the end, so the minutes show
        val soon = sensor(ago = 10 * DAY + 14 * HOUR + 10 * MINUTE)
        // 14 hours 5 minutes past the end, so only the hours show
        val later = sensor(ago = 11 * DAY + 2 * HOUR + 5 * MINUTE)

        assertThat(soon.timeLeft).isEqualTo("-2h10m")
        assertThat(later.timeLeft).isEqualTo("-14h")
        assertThat(later.status).isEqualTo(StatusLevel.CRITICAL)
        assertThat(later.percent).isEqualTo(1f)
    }

    @Test
    fun `a pod ends 80 hours after it was started, and is red after 72`() {
        val pod55 = sut.of(changedAgo(55 * HOUR), pod, IntKey.OverviewCageWarning, IntKey.OverviewCageCritical)
        val pod60 = sut.of(changedAgo(60 * HOUR), pod, IntKey.OverviewCageWarning, IntKey.OverviewCageCritical)
        val pod73 = sut.of(changedAgo(73 * HOUR), pod, IntKey.OverviewCageWarning, IntKey.OverviewCageCritical)

        assertThat(pod55.timeLeft).isEqualTo("1d1h")
        assertThat(pod55.status).isEqualTo(StatusLevel.NORMAL)
        assertThat(pod60.timeLeft).isEqualTo("20h")
        assertThat(pod60.status).isEqualTo(StatusLevel.WARNING)
        assertThat(pod73.timeLeft).isEqualTo("7h00m")
        assertThat(pod73.status).isEqualTo(StatusLevel.CRITICAL)
    }

    @Test
    fun `an unknown model counts down to the critical threshold with the user's colors`() {
        whenever(preferences.get(IntKey.OverviewSageWarning)).thenReturn(216)
        whenever(preferences.get(IntKey.OverviewSageCritical)).thenReturn(240)

        val view = sut.of(changedAgo(220 * HOUR), lifetime = null, IntKey.OverviewSageWarning, IntKey.OverviewSageCritical)

        assertThat(view.timeLeft).isEqualTo("20h")
        assertThat(view.status).isEqualTo(StatusLevel.WARNING)
    }

    @Test
    fun `no change at all shows a dash`() {
        val view = sut.of(null, g7, IntKey.OverviewSageWarning, IntKey.OverviewSageCritical)

        assertThat(view.timeLeft).isEqualTo("-")
        assertThat(view.status).isEqualTo(StatusLevel.UNSPECIFIED)
    }

    private companion object {

        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
