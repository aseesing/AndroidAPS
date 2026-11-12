// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.health

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.robolectric.RuntimeEnvironment
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** What the heart rate and steps complications hand to the watch face, per type the slot asks for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HealthComplicationDataTest {

    private val context = RuntimeEnvironment.getApplication() as Context
    private val tap = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

    private fun ComplicationText?.plain(): String = this!!.getTextAt(context.resources, Instant.now()).toString()

    @Test
    fun heartRateAsAGauge() {
        val data = healthComplicationData(context, HealthReading(HealthKind.HEART_RATE, 72, false), ComplicationType.RANGED_VALUE, null)
            as RangedValueComplicationData
        assertThat(data.text.plain()).isEqualTo("72")
        assertThat(data.value).isEqualTo(72f)
        assertThat(data.min).isEqualTo(HealthReading.HEART_RATE_MIN)
        assertThat(data.max).isEqualTo(HealthReading.HEART_RATE_MAX)
        assertThat(data.contentDescription.plain()).isEqualTo("Heart rate 72 per minute")
        assertThat(data.monochromaticImage).isNotNull()
    }

    @Test
    fun aHeartRateOutsideTheGaugeStillDraws() {
        val high = healthComplicationData(context, HealthReading(HealthKind.HEART_RATE, 205, false), ComplicationType.RANGED_VALUE, null) as RangedValueComplicationData
        assertThat(high.value).isEqualTo(HealthReading.HEART_RATE_MAX)
        assertThat(high.text.plain()).isEqualTo("205")
    }

    @Test
    fun noValueShowsDashesAndAnEmptyGauge() {
        val data = healthComplicationData(context, HealthReading(HealthKind.HEART_RATE, null, false), ComplicationType.RANGED_VALUE, null) as RangedValueComplicationData
        assertThat(data.text.plain()).isEqualTo("--")
        assertThat(data.value).isEqualTo(HealthReading.HEART_RATE_MIN)
    }

    @Test
    fun stepsTowardsTheDailyGoal() {
        val ranged = healthComplicationData(context, HealthReading(HealthKind.STEPS, 6482, false), ComplicationType.RANGED_VALUE, null) as RangedValueComplicationData
        assertThat(ranged.value).isEqualTo(6482f)
        assertThat(ranged.max).isEqualTo(HealthReading.STEPS_GOAL)
        assertThat(ranged.contentDescription.plain()).isEqualTo("6482 steps today")

        val goal = healthComplicationData(context, HealthReading(HealthKind.STEPS, 12_000, false), ComplicationType.GOAL_PROGRESS, null) as GoalProgressComplicationData
        assertThat(goal.value).isEqualTo(12_000f)    // past the goal is fine for a goal
        assertThat(goal.targetValue).isEqualTo(HealthReading.STEPS_GOAL)
    }

    @Test
    fun shortText() {
        val data = healthComplicationData(context, HealthReading(HealthKind.STEPS, 6482, false), ComplicationType.SHORT_TEXT, null) as ShortTextComplicationData
        assertThat(data.text.plain()).isEqualTo("6482")
    }

    @Test
    fun aMissingPermissionSaysSoAndATapAsksForIt() {
        val data = healthComplicationData(context, HealthReading(HealthKind.HEART_RATE, null, true), ComplicationType.RANGED_VALUE, tap) as RangedValueComplicationData
        assertThat(data.text.plain()).isEqualTo("Allow")
        assertThat(data.tapAction).isEqualTo(tap)
    }

    @Test
    fun aTypeItCannotShowGivesNothing() {
        assertThat(healthComplicationData(context, HealthReading(HealthKind.HEART_RATE, 72, false), ComplicationType.SMALL_IMAGE, null)).isNull()
    }
}
