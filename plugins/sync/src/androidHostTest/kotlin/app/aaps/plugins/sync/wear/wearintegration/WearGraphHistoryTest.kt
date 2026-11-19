package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.wear.watchfaces.utils.LogTimeGraph
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WearGraphHistoryTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_700_000_000_000L

    /** Every 5 minutes from now back [hours] hours, newest first like the bucketed table. */
    private fun times(hours: Long): List<Long> = (0 until hours * 12).map { now - it * 5 * minute }

    @Test
    fun withoutTheLogGraphTheLastSixHoursGoWhole() {
        val history = WearGraphHistory(6 * hour) { false }
        val picked = history.pick(times(30), now) { it }
        assertThat(picked).isEqualTo(times(30).filter { it >= now - 6 * hour })
        assertThat(history.treatmentsFrom(now)).isEqualTo(now - 5 * hour - 30 * minute)
    }

    @Test
    fun withTheLogGraphADayGoesThinnedFurtherBack() {
        val history = WearGraphHistory(6 * hour) { true }
        val picked = history.pick(times(30), now) { it }
        // All of the last 6 hours
        assertThat(picked.filter { it >= now - 6 * hour }).isEqualTo(times(6).let { it + (now - 6 * hour) })
        // Nothing older than a day, and something from near the end of the day
        assertThat(picked.min()).isAtLeast(now - 24 * hour)
        assertThat(picked.min()).isLessThan(now - 20 * hour)
        // Further back the gaps grow: at least a twelfth of the age
        val old = picked.filter { it < now - 6 * hour }
        for ((newer, older) in old.zipWithNext()) assertThat(newer - older).isAtLeast((now - older) / 12)
        // About 90 points instead of 72 for 6 hours
        assertThat(picked.size).isIn(Range.closed(80, 95))
        // In the order given
        assertThat(picked).isInOrder(Comparator.reverseOrder<Long>())
        assertThat(history.treatmentsFrom(now)).isEqualTo(now - 24 * hour)
    }

    @Test
    fun theSettingComesFromThePhonesCopyOfTheWatchSettings() {
        assertThat(WearGraphHistory.logGraphOn(emptyMap())).isEqualTo(LogTimeGraph.DEFAULT)
        assertThat(WearGraphHistory.logGraphOn(mapOf(LogTimeGraph.SETTING_NAME to CwfSettingValue("false", 1L)))).isFalse()
        assertThat(WearGraphHistory.logGraphOn(mapOf(LogTimeGraph.SETTING_NAME to CwfSettingValue("true", 1L)))).isTrue()
        assertThat(WearGraphHistory.logGraphOn(mapOf(LogTimeGraph.SETTING_NAME to CwfSettingValue("x", 1L)))).isEqualTo(LogTimeGraph.DEFAULT)
    }
}
