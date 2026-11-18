package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.utils.DateUtil
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
    private lateinit var sut: StatusTimeLeft

    private val g6 = DeviceLifetime.of(SourceSensor.DEXCOM_G6)!!
    private val pod = DeviceLifetime.of(PumpType.OMNIPOD_DASH)!!

    /** Not a real model: 10 days and a 12 hour grace period, to test the grace period on a long life. */
    private val tenDaysWithGrace = DeviceLifetime(lifetimeHours = 240, graceHours = 12)

    @BeforeEach
    fun setUp() {
        whenever(dateUtil.now()).thenReturn(NOW)
        sut = StatusTimeLeft(generatedTextResolver("ui" to UiStringsValues::textOf), dateUtil)
    }

    private fun withGrace(ago: Long) = sut.of(NOW - ago, tenDaysWithGrace)

    @Test
    fun `only the models with a certain lifetime are known`() {
        assertThat(g6.lifetimeHours).isEqualTo(240)
        assertThat(g6.graceHours).isEqualTo(0)
        assertThat(pod.lifetimeHours).isEqualTo(72)
        assertThat(pod.graceHours).isEqualTo(8)
        assertThat(DeviceLifetime.of(PumpType.OMNIPOD_EROS)?.lifetimeHours).isEqualTo(72)
        // A 10 day and a 15 day G7 send the same label, so its end is not known
        assertThat(DeviceLifetime.of(SourceSensor.DEXCOM_G7)).isNull()
        assertThat(DeviceLifetime.of(SourceSensor.LIBRE_2)).isNull()
        assertThat(DeviceLifetime.of(SourceSensor.DEXCOM_UNKNOWN)).isNull()
        assertThat(DeviceLifetime.of(null as SourceSensor?)).isNull()
        assertThat(DeviceLifetime.of(PumpType.MEDTRUM_NANO)).isNull()
        assertThat(DeviceLifetime.of(null as PumpType?)).isNull()
    }

    @Test
    fun `the sensor life the user set is used only when the life of the model is not known`() {
        // Known model: its own life, whatever the user set
        assertThat(DeviceLifetime.forSensor(SourceSensor.DEXCOM_G6, userLifetimeHours = 100)?.lifetimeHours).isEqualTo(240)
        // Unknown model: the user's value, with no grace period
        val g7 = DeviceLifetime.forSensor(SourceSensor.DEXCOM_G7, userLifetimeHours = 360)
        assertThat(g7?.lifetimeHours).isEqualTo(360)
        assertThat(g7?.graceHours).isEqualTo(0)
        // No reading at all: the user's value too
        assertThat(DeviceLifetime.forSensor(null, userLifetimeHours = 48)?.lifetimeHours).isEqualTo(48)
        // 0 means not set: no countdown
        assertThat(DeviceLifetime.forSensor(SourceSensor.DEXCOM_G7, userLifetimeHours = 0)).isNull()
        assertThat(DeviceLifetime.forSensor(SourceSensor.LIBRE_2, userLifetimeHours = 0)).isNull()
    }

    @Test
    fun `a fresh part counts down in days and hours to the end of its grace period`() {
        // 10 days and 12 hours in all, 2 days gone
        val view = withGrace(ago = 2 * DAY)

        assertThat(view.text).isEqualTo("8d12h left")
        assertThat(view.status).isEqualTo(StatusLevel.NORMAL)
        assertThat(view.percent).isWithin(0.001f).of(48f / 252f)
    }

    @Test
    fun `less than one day left is yellow`() {
        // 9 days 14 hours gone: 22 hours left, and the grace period starts in 10
        val view = withGrace(ago = 9 * DAY + 14 * HOUR)

        assertThat(view.text).isEqualTo("22h left")
        assertThat(view.status).isEqualTo(StatusLevel.WARNING)
    }

    @Test
    fun `the grace period is red, and under 10 hours the minutes show and the days go`() {
        // 10 days 3 hours 25 minutes gone: in the grace period, 8 hours 35 minutes left
        val view = withGrace(ago = 10 * DAY + 3 * HOUR + 25 * MINUTE)

        assertThat(view.text).isEqualTo("8h35m left")
        assertThat(view.status).isEqualTo(StatusLevel.CRITICAL)
    }

    @Test
    fun `in the last hour only the minutes show`() {
        val view = withGrace(ago = 10 * DAY + 11 * HOUR + 15 * MINUTE)

        assertThat(view.text).isEqualTo("45m left")
    }

    @Test
    fun `past the end it shows how long ago, with a minus`() {
        // 2 hours 10 minutes past the end, so the minutes show
        val soon = withGrace(ago = 10 * DAY + 14 * HOUR + 10 * MINUTE)
        // 14 hours 5 minutes past the end, so only the hours show
        val later = withGrace(ago = 11 * DAY + 2 * HOUR + 5 * MINUTE)

        assertThat(soon.text).isEqualTo("-2h10m")
        assertThat(later.text).isEqualTo("-14h")
        assertThat(later.status).isEqualTo(StatusLevel.CRITICAL)
        assertThat(later.percent).isEqualTo(1f)
    }

    @Test
    fun `a G6 has no grace period, so it is red only at its end`() {
        val lastDay = sut.of(NOW - (9 * DAY + 2 * HOUR), g6)
        val ended = sut.of(NOW - 10 * DAY, g6)

        assertThat(lastDay.text).isEqualTo("22h left")
        assertThat(lastDay.status).isEqualTo(StatusLevel.WARNING)
        assertThat(ended.text).isEqualTo("0m left")
        assertThat(ended.status).isEqualTo(StatusLevel.CRITICAL)
    }

    @Test
    fun `a pod ends 80 hours after it was started, and is red after 72`() {
        val pod55 = sut.of(NOW - 55 * HOUR, pod)
        val pod60 = sut.of(NOW - 60 * HOUR, pod)
        val pod73 = sut.of(NOW - 73 * HOUR, pod)

        assertThat(pod55.text).isEqualTo("1d1h left")
        assertThat(pod55.status).isEqualTo(StatusLevel.NORMAL)
        assertThat(pod60.text).isEqualTo("20h left")
        assertThat(pod60.status).isEqualTo(StatusLevel.WARNING)
        assertThat(pod73.text).isEqualTo("7h00m left")
        assertThat(pod73.status).isEqualTo(StatusLevel.CRITICAL)
    }

    private companion object {

        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
