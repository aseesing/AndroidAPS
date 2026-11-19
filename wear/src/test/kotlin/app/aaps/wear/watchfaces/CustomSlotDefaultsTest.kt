package app.aaps.wear.watchfaces

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationType
import app.aaps.wear.complications.CobIconComplication
import app.aaps.wear.complications.IobIconComplication
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
    fun slotsOneAndTwoShowCarbsAndInsulin() {
        assertDefault(COB_SLOT_ID, CobIconComplication::class.java, ComplicationType.SHORT_TEXT)
        assertDefault(IOB_SLOT_ID, IobIconComplication::class.java, ComplicationType.SHORT_TEXT)
    }

    @Test
    fun slotsThreeToFiveShowNothing() {
        for (slotId in 103..105) assertThat(customSlotDefaultSource(context, slotId).primaryDataSource).isNull()
    }

    @Test
    fun theIdsAreTheCustomWatchFaceSlots() {
        // Stable forever once shipped: the system keeps the user's choice per slot id
        assertThat(CustomWatchface.complicationSlots.map { it.id }).containsAtLeast(COB_SLOT_ID, IOB_SLOT_ID).inOrder()
    }
}
