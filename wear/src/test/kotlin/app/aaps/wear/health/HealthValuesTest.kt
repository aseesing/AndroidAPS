package app.aaps.wear.health

import app.aaps.wear.testing.mocks.SharedPreferencesMock
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HealthValuesTest {

    private val zone = ZoneId.of("Europe/Amsterdam")
    private var now = at(2026, 11, 11, 14, 0)
    private val values = HealthValues(SharedPreferencesMock(), { now }, { zone })

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun nothingMeasuredShowsNothing() {
        assertThat(values.heartRate()).isNull()
        assertThat(values.stepsToday()).isNull()
    }

    @Test
    fun theNewestHeartRateIsKeptAndAnOlderOneIgnored() {
        assertThat(values.recordHeartRate(72, now - 60_000)).isTrue()
        assertThat(values.recordHeartRate(90, now - 120_000)).isFalse()    // older, from the same batch
        assertThat(values.heartRate()).isEqualTo(72)
    }

    @Test
    fun anOldHeartRateIsNotShown() {
        values.recordHeartRate(72, now - HealthValues.HEART_RATE_MAX_AGE_MS)
        assertThat(values.heartRate()).isEqualTo(72)
        assertThat(values.heartRateShownUntil()).isEqualTo(now)
        now += 1
        assertThat(values.heartRate()).isNull()
        assertThat(values.heartRateShownUntil()).isNull()
    }

    @Test
    fun theHeartRateIsShownUntilItIsThirtyMinutesOld() {
        val measured = now - 29 * 60_000L
        values.recordHeartRate(72, measured)
        assertThat(values.heartRateShownUntil()).isEqualTo(measured + HealthValues.HEART_RATE_MAX_AGE_MS)
    }

    @Test
    fun valuesStoredWhileTheClockWasAheadAreNotTrusted() {
        // The clock was 2 hours ahead when these were stored, then it was set right
        val ahead = now + 2 * 3_600_000L
        values.recordHeartRate(72, ahead)
        values.recordSteps(9000, ahead)
        assertThat(values.heartRate()).isNull()
        assertThat(values.stepsToday()).isNull()
        // New values are taken at once, not only after the clock passes the wrong time
        assertThat(values.recordHeartRate(65, now - 60_000)).isTrue()
        assertThat(values.recordSteps(1200, now - 60_000)).isTrue()
        assertThat(values.heartRate()).isEqualTo(65)
        assertThat(values.stepsToday()).isEqualTo(1200)
    }

    @Test
    fun aTimeJustAheadOfTheClockIsStillTrusted() {
        values.recordHeartRate(72, now + HealthValues.CLOCK_MARGIN_MS)
        assertThat(values.heartRate()).isEqualTo(72)
        assertThat(values.recordHeartRate(65, now)).isFalse()
    }

    @Test
    fun stepsOfAnEarlierDayCountAsNoneToday() {
        values.recordSteps(6482, at(2026, 11, 11, 23, 59))
        now = at(2026, 11, 11, 23, 59)
        assertThat(values.stepsToday()).isEqualTo(6482)
        now = at(2026, 11, 12, 0, 1)
        assertThat(values.stepsToday()).isEqualTo(0)
    }

    @Test
    fun onlyKindsOnAWatchFaceAreWanted() {
        assertThat(values.wanted()).isEmpty()
        values.setShown(HealthKind.HEART_RATE, 5, true)
        values.setShown(HealthKind.HEART_RATE, 7, true)    // a second watch face shows it too
        assertThat(values.wanted()).containsExactly(HealthKind.HEART_RATE)
        values.setShown(HealthKind.HEART_RATE, 5, false)
        assertThat(values.wanted()).containsExactly(HealthKind.HEART_RATE)
        values.setShown(HealthKind.HEART_RATE, 7, false)
        assertThat(values.wanted()).isEmpty()
    }
}
