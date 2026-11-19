package app.aaps.wear.health

import app.aaps.wear.testing.mocks.SharedPreferencesMock
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HealthValuesTest {

    private var now = 1_800_000_000_000L
    private val values = HealthValues(SharedPreferencesMock()) { now }

    @Test
    fun nothingMeasuredGivesNoHeartRate() {
        assertThat(values.heartRate()).isNull()
    }

    @Test
    fun theNewestHeartRateIsKeptAndAnOlderOneIgnored() {
        assertThat(values.recordHeartRate(72, now - 60_000)).isTrue()
        assertThat(values.recordHeartRate(90, now - 120_000)).isFalse()    // older, from the same batch
        assertThat(values.heartRate()).isEqualTo(72)
    }

    @Test
    fun aHeartRateOlderThanThirtyMinutesIsNotUsed() {
        values.recordHeartRate(72, now - HealthValues.HEART_RATE_MAX_AGE_MS)
        assertThat(values.heartRate()).isEqualTo(72)
        now += 1
        assertThat(values.heartRate()).isNull()
    }

    @Test
    fun aHeartRateStoredWhileTheClockWasAheadIsNotTrusted() {
        // The clock was 2 hours ahead when this was stored, then it was set right
        values.recordHeartRate(72, now + 2 * 3_600_000L)
        assertThat(values.heartRate()).isNull()
        // A new value is taken at once, not only after the clock passes the wrong time
        assertThat(values.recordHeartRate(65, now - 60_000)).isTrue()
        assertThat(values.heartRate()).isEqualTo(65)
    }

    @Test
    fun aTimeJustAheadOfTheClockIsStillTrusted() {
        values.recordHeartRate(72, now + HealthValues.CLOCK_MARGIN_MS)
        assertThat(values.heartRate()).isEqualTo(72)
        assertThat(values.recordHeartRate(65, now)).isFalse()
    }

    @Test
    fun onlyAChangeOfTheFaceWishIsPassedOn() {
        val changes = mutableListOf<Boolean>()
        values.onFaceWishChanged = { changes += it }
        assertThat(values.faceWantsHeartRate).isFalse()
        values.faceWantsHeartRate = false   // the same: the face reads its design again
        values.faceWantsHeartRate = true
        values.faceWantsHeartRate = true
        values.faceWantsHeartRate = false
        assertThat(changes).containsExactly(true, false).inOrder()
    }
}
