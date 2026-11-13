// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchBatteryLevelTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun broadcast(level: Int, scale: Int = 100) =
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, scale))

    @Test
    fun percentOfLevelAndScale() {
        assertThat(WatchBatteryLevel.percentOf(66, 100)).isEqualTo(66.0)
        assertThat(WatchBatteryLevel.percentOf(1, 3)).isEqualTo(33.0)
        assertThat(WatchBatteryLevel.percentOf(2, 3)).isEqualTo(67.0)
        assertThat(WatchBatteryLevel.percentOf(0, 100)).isEqualTo(0.0)
        assertThat(WatchBatteryLevel.percentOf(-1, 100)).isNull()
        assertThat(WatchBatteryLevel.percentOf(50, 0)).isNull()
    }

    @Test
    fun readsTheLastBroadcastAndKeepsItForAWhile() {
        var clock = 1_000L
        val battery = WatchBatteryLevel(context) { clock }
        broadcast(41)
        assertThat(battery.percent()).isEqualTo(41.0)
        // A new level within the time a read is kept: still the old one, no new system call
        broadcast(40)
        clock += WatchBatteryLevel.MAX_AGE_MS - 1
        assertThat(battery.percent()).isEqualTo(41.0)
        // After that time: the new one
        clock += 1
        assertThat(battery.percent()).isEqualTo(40.0)
    }

    @Test
    fun noLevelGivesNoValue() {
        broadcast(-1)
        assertThat(WatchBatteryLevel(context) { 0L }.percent()).isNull()
    }
}
