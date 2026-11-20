package app.aaps.wear.complications.wff

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.data.ComplicationData as ComplicationStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class WffGlucoseComplicationsTest {

    private fun glucose(): WffGlucoseComplication =
        Robolectric.buildService(WffGlucoseComplication::class.java).get().also { it.aapsLogger = AAPSLoggerTest() }

    private fun age(): WffGlucoseAgeComplication =
        Robolectric.buildService(WffGlucoseAgeComplication::class.java).get().also { it.aapsLogger = AAPSLoggerTest() }

    private val tap: PendingIntent = PendingIntent.getActivity(RuntimeEnvironment.getApplication(), 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

    private fun store(sgv: Double, timeStamp: Long, sgvString: String = "120") = ComplicationStore(
        bgData = EventData.SingleBg(
            dataset = 0, timeStamp = timeStamp, sgvString = sgvString, slopeArrow = "→", delta = "+3",
            sgv = sgv, high = 180.0, low = 70.0
        )
    )

    @Test
    fun `glucose sends the mg per dl value, the text, the delta and an arrow`() {
        val data = glucose().buildComplicationData(ComplicationType.RANGED_VALUE, store(120.0, System.currentTimeMillis(), "6.7"), tap)
        assertThat(data).isInstanceOf(RangedValueComplicationData::class.java)
        data as RangedValueComplicationData
        assertThat(data.value).isEqualTo(120f)
        assertThat(data.min).isEqualTo(GlucoseRange.MIN)
        assertThat(data.max).isEqualTo(GlucoseRange.MAX)
        assertThat(data.text!!.getTextAt(RuntimeEnvironment.getApplication().resources, Instant.now())).isEqualTo("6.7")
        assertThat(data.title!!.getTextAt(RuntimeEnvironment.getApplication().resources, Instant.now())).isEqualTo("+3")
        assertThat(data.monochromaticImage).isNotNull()
        assertThat(data.smallImage).isNull()
    }

    @Test
    fun `glucose outside the sensor range is kept at the ends`() {
        assertThat(GlucoseRange.value(20.0)).isEqualTo(GlucoseRange.MIN)
        assertThat(GlucoseRange.value(520.0)).isEqualTo(GlucoseRange.MAX)
        assertThat(GlucoseRange.value(181.0)).isEqualTo(181f)
    }

    @Test
    fun `without a reading both send no data`() {
        val empty = store(0.0, 0L, "---")
        assertThat(glucose().buildComplicationData(ComplicationType.RANGED_VALUE, empty, tap)).isInstanceOf(NoDataComplicationData::class.java)
        assertThat(age().buildComplicationData(ComplicationType.RANGED_VALUE, empty, tap)).isInstanceOf(NoDataComplicationData::class.java)
    }

    @Test
    fun `only the ranged value type is served`() {
        val data = store(120.0, System.currentTimeMillis())
        assertThat(glucose().buildComplicationData(ComplicationType.SHORT_TEXT, data, tap)).isNull()
        assertThat(age().buildComplicationData(ComplicationType.SMALL_IMAGE, data, tap)).isNull()
    }

    @Test
    fun `age is a dynamic value with the minutes as fallback`() {
        val now = System.currentTimeMillis()
        val data = age().buildComplicationData(ComplicationType.RANGED_VALUE, store(120.0, now - 7 * 60_000L), tap) as RangedValueComplicationData
        assertThat(data.dynamicValue).isNotNull()
        assertThat(data.value).isWithin(0.1f).of(7f)
        assertThat(data.min).isEqualTo(0f)
        assertThat(data.max).isEqualTo(WffGlucoseAgeComplication.MAX_MINUTES)
    }

    @Test
    fun `age minutes are kept inside 0 to the maximum`() {
        assertThat(WffGlucoseAgeComplication.ageMinutes(1_000_000L, 1_000_000L + 90_000L)).isEqualTo(1.5f)
        assertThat(WffGlucoseAgeComplication.ageMinutes(1_000_000L, 900_000L)).isEqualTo(0f)
        assertThat(WffGlucoseAgeComplication.ageMinutes(0L, 10L * 3_600_000L)).isEqualTo(WffGlucoseAgeComplication.MAX_MINUTES)
    }

    @Test
    fun `previews build a ranged value`() {
        assertThat(glucose().getPreviewData(ComplicationType.RANGED_VALUE)).isInstanceOf(RangedValueComplicationData::class.java)
        assertThat(age().getPreviewData(ComplicationType.RANGED_VALUE)).isInstanceOf(RangedValueComplicationData::class.java)
    }
}
