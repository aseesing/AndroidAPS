// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationType
import app.aaps.wear.complications.CobIconComplication
import app.aaps.wear.complications.IobIconComplication
import app.aaps.wear.health.HeartRateComplication
import app.aaps.wear.health.StepsComplication
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomSlotDefaultsTest {

    private val context = RuntimeEnvironment.getApplication() as Context

    private fun assertDefault(slotId: Int, source: Class<*>, type: ComplicationType) {
        val policy = customSlotDefaultSource(context, slotId)
        assertThat(policy.primaryDataSource).isEqualTo(ComponentName(context, source))
        assertThat(policy.primaryDataSourceDefaultType).isEqualTo(type)
    }

    @Test
    fun slotsOneToFourShowHeartRateStepsCarbsAndInsulin() {
        assertDefault(HEART_RATE_SLOT_ID, HeartRateComplication::class.java, ComplicationType.RANGED_VALUE)
        assertDefault(STEPS_SLOT_ID, StepsComplication::class.java, ComplicationType.RANGED_VALUE)
        assertDefault(COB_SLOT_ID, CobIconComplication::class.java, ComplicationType.SHORT_TEXT)
        assertDefault(IOB_SLOT_ID, IobIconComplication::class.java, ComplicationType.SHORT_TEXT)
    }

    @Test
    fun theFifthSlotShowsNothing() {
        assertThat(customSlotDefaultSource(context, 105).primaryDataSource).isNull()
    }

    @Test
    fun theIdsAreTheCustomWatchFaceSlots() {
        // Stable forever once shipped: the system keeps the user's choice per slot id
        assertThat(CustomWatchface.complicationSlots.map { it.id }).containsAtLeast(HEART_RATE_SLOT_ID, STEPS_SLOT_ID, COB_SLOT_ID, IOB_SLOT_ID).inOrder()
    }
}
