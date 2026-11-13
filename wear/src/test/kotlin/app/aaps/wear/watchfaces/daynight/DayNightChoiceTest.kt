// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DayNightChoiceTest {

    private fun show(active: String?, alwaysOn: String?, ambient: Boolean, sunUp: Boolean): Boolean =
        DayNightChoice.showDay(active, alwaysOn, ambient) { sunUp }

    @Test
    fun theActiveScreenFollowsTheFirstSetting() {
        for (sunUp in listOf(true, false)) {
            assertThat(show(DayNightChoice.AUTO, null, ambient = false, sunUp = sunUp)).isEqualTo(sunUp)
            assertThat(show(DayNightChoice.DAY, null, ambient = false, sunUp = sunUp)).isTrue()
            assertThat(show(DayNightChoice.NIGHT, null, ambient = false, sunUp = sunUp)).isFalse()
        }
    }

    @Test
    fun withoutAChoiceTheActiveScreenFollowsTheSun() {
        assertThat(show(null, null, ambient = false, sunUp = true)).isTrue()
        assertThat(show(null, null, ambient = false, sunUp = false)).isFalse()
        assertThat(show("something else", null, ambient = false, sunUp = true)).isTrue()
    }

    @Test
    fun alwaysOnIsNightUnlessChosenOtherwise() {
        for (active in listOf(null, DayNightChoice.AUTO, DayNightChoice.DAY, DayNightChoice.NIGHT)) {
            assertThat(show(active, null, ambient = true, sunUp = true)).isFalse()
            assertThat(show(active, DayNightChoice.NIGHT, ambient = true, sunUp = true)).isFalse()
            assertThat(show(active, "something else", ambient = true, sunUp = true)).isFalse()
            assertThat(show(active, DayNightChoice.DAY, ambient = true, sunUp = false)).isTrue()
        }
    }

    @Test
    fun alwaysOnCanFollowTheActiveScreen() {
        assertThat(show(DayNightChoice.DAY, DayNightChoice.AS_ACTIVE, ambient = true, sunUp = false)).isTrue()
        assertThat(show(DayNightChoice.NIGHT, DayNightChoice.AS_ACTIVE, ambient = true, sunUp = true)).isFalse()
        assertThat(show(DayNightChoice.AUTO, DayNightChoice.AS_ACTIVE, ambient = true, sunUp = true)).isTrue()
        assertThat(show(DayNightChoice.AUTO, DayNightChoice.AS_ACTIVE, ambient = true, sunUp = false)).isFalse()
    }

    @Test
    fun theSunIsOnlyAskedForAutomatic() {
        var asked = 0
        DayNightChoice.showDay(DayNightChoice.DAY, null, ambient = false) { asked++; true }
        DayNightChoice.showDay(DayNightChoice.AUTO, null, ambient = true) { asked++; true }
        assertThat(asked).isEqualTo(0)
        DayNightChoice.showDay(DayNightChoice.AUTO, null, ambient = false) { asked++; true }
        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun aValueFromThePhoneIsTakenOnlyWhenThePhoneChangedIt() {
        val allowed = DayNightChoice.ACTIVE_VALUES
        // First time, or the phone setting changed: taken
        assertThat(DayNightChoice.fromPhone(DayNightChoice.NIGHT, null, allowed)).isEqualTo(DayNightChoice.NIGHT)
        assertThat(DayNightChoice.fromPhone(DayNightChoice.NIGHT, "", allowed)).isEqualTo(DayNightChoice.NIGHT)
        assertThat(DayNightChoice.fromPhone(DayNightChoice.DAY, DayNightChoice.NIGHT, allowed)).isEqualTo(DayNightChoice.DAY)
        // The same as last time: the watch keeps what the wearer chose there
        assertThat(DayNightChoice.fromPhone(DayNightChoice.NIGHT, DayNightChoice.NIGHT, allowed)).isNull()
        // An older phone sends nothing, and an unknown value is never stored
        assertThat(DayNightChoice.fromPhone(null, DayNightChoice.NIGHT, allowed)).isNull()
        assertThat(DayNightChoice.fromPhone("something else", null, allowed)).isNull()
        assertThat(DayNightChoice.fromPhone(DayNightChoice.AS_ACTIVE, null, allowed)).isNull()
        assertThat(DayNightChoice.fromPhone(DayNightChoice.AS_ACTIVE, null, DayNightChoice.ALWAYS_ON_VALUES)).isEqualTo(DayNightChoice.AS_ACTIVE)
    }
}
