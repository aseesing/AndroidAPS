// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.SystemDataSources
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

    private fun assertAapsDefault(slotId: Int, source: Class<*>) {
        val policy = customSlotDefaultSource(context, slotId)
        assertThat(policy.primaryDataSource).isEqualTo(ComponentName(context, source))
        assertThat(policy.primaryDataSourceDefaultType).isEqualTo(ComplicationType.SHORT_TEXT)
        assertThat(policy.systemDataSourceFallback).isEqualTo(SystemDataSources.NO_DATA_SOURCE)
    }

    /** A source of the watch itself, and no AAPS source: AAPS has no heart rate or steps complication. */
    private fun assertSystemDefault(slotId: Int, source: Int) {
        val policy = customSlotDefaultSource(context, slotId)
        assertThat(policy.primaryDataSource).isNull()
        assertThat(policy.secondaryDataSource).isNull()
        assertThat(policy.systemDataSourceFallback).isEqualTo(source)
        assertThat(policy.systemDataSourceFallbackDefaultType).isEqualTo(ComplicationType.SHORT_TEXT)
    }

    @Test
    fun theSlotsStartAsOnAWatchFaceFormatFace() {
        // Slots 1 to 5, as `WffFaceWriter.USER_SLOT_DEFAULTS` of the face tool: HEART_RATE, STEP_COUNT, COB, IOB, nothing
        assertThat(CustomWatchface.complicationSlots.map { customSlotDefault(it.id) }).containsExactly(
            CustomSlotDefault.HEART_RATE, CustomSlotDefault.STEPS, CustomSlotDefault.COB, CustomSlotDefault.IOB, CustomSlotDefault.NONE
        ).inOrder()
    }

    @Test
    fun slotsOneAndTwoShowTheHeartRateAndTheStepsOfTheWatch() {
        assertSystemDefault(HEART_RATE_SLOT_ID, SystemDataSources.DATA_SOURCE_HEART_RATE)
        assertSystemDefault(STEPS_SLOT_ID, SystemDataSources.DATA_SOURCE_STEP_COUNT)
    }

    @Test
    @Config(sdk = [33])
    fun aWatchOlderThanAndroid14StartsSlotOneEmpty() {
        // The system has the heart rate source from Android 14 on
        val policy = customSlotDefaultSource(context, HEART_RATE_SLOT_ID)
        assertThat(policy.primaryDataSource).isNull()
        assertThat(policy.systemDataSourceFallback).isEqualTo(SystemDataSources.NO_DATA_SOURCE)
        assertSystemDefault(STEPS_SLOT_ID, SystemDataSources.DATA_SOURCE_STEP_COUNT)
    }

    @Test
    fun slotsThreeAndFourShowCarbsAndInsulin() {
        assertAapsDefault(COB_SLOT_ID, CobIconComplication::class.java)
        assertAapsDefault(IOB_SLOT_ID, IobIconComplication::class.java)
    }

    @Test
    fun slotFiveShowsNothing() {
        val policy = customSlotDefaultSource(context, 105)
        assertThat(policy.primaryDataSource).isNull()
        assertThat(policy.systemDataSourceFallback).isEqualTo(SystemDataSources.NO_DATA_SOURCE)
    }

    @Test
    fun theIdsAreTheCustomWatchFaceSlots() {
        // Stable forever once shipped: the system keeps the user's choice per slot id
        assertThat(CustomWatchface.complicationSlots.map { it.id }).containsExactly(HEART_RATE_SLOT_ID, STEPS_SLOT_ID, COB_SLOT_ID, IOB_SLOT_ID, 105).inOrder()
    }
}
