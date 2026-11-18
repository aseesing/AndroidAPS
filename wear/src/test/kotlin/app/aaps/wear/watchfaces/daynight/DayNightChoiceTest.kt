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
    fun alwaysOnFollowsTheActiveScreenUnlessChosenOtherwise() {
        for (alwaysOn in listOf(null, DayNightChoice.AS_ACTIVE, "something else")) {
            assertThat(show(DayNightChoice.DAY, alwaysOn, ambient = true, sunUp = false)).isTrue()
            assertThat(show(DayNightChoice.NIGHT, alwaysOn, ambient = true, sunUp = true)).isFalse()
            assertThat(show(DayNightChoice.AUTO, alwaysOn, ambient = true, sunUp = true)).isTrue()
            assertThat(show(DayNightChoice.AUTO, alwaysOn, ambient = true, sunUp = false)).isFalse()
            // So in the day raising the wrist does not change the face
            assertThat(show(null, alwaysOn, ambient = true, sunUp = true)).isEqualTo(show(null, null, ambient = false, sunUp = true))
        }
    }

    @Test
    fun alwaysOnCanBeFixed() {
        for (active in listOf(null, DayNightChoice.AUTO, DayNightChoice.DAY, DayNightChoice.NIGHT)) {
            assertThat(show(active, DayNightChoice.NIGHT, ambient = true, sunUp = true)).isFalse()
            assertThat(show(active, DayNightChoice.DAY, ambient = true, sunUp = false)).isTrue()
        }
    }

    @Test
    fun theSunIsOnlyAskedForAutomatic() {
        var asked = 0
        DayNightChoice.showDay(DayNightChoice.DAY, null, ambient = false) { asked++; true }
        DayNightChoice.showDay(DayNightChoice.AUTO, DayNightChoice.NIGHT, ambient = true) { asked++; true }
        assertThat(asked).isEqualTo(0)
        DayNightChoice.showDay(DayNightChoice.AUTO, null, ambient = false) { asked++; true }
        assertThat(asked).isEqualTo(1)
    }
}
