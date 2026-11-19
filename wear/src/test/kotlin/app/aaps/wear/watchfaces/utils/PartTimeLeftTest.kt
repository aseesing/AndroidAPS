package app.aaps.wear.watchfaces.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PartTimeLeftTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun theHoursUntilTheEnd() {
        assertThat(PartTimeLeft.hours(80 * hour, 20 * hour)).isEqualTo(60.0)
        assertThat(PartTimeLeft.hours(80 * hour, 80 * hour)).isEqualTo(0.0)
        assertThat(PartTimeLeft.hours(80 * hour, 90 * hour + hour / 2)).isEqualTo(-10.5)
    }

    @Test
    fun noEndGivesNoValue() {
        assertThat(PartTimeLeft.hours(null, 0L)).isNull()
    }
}
