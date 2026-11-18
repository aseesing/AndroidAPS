// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.daynight

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.plugins.sync.wear.PreferencesInMemory
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightCarryOverKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Day and night of the personal build, carried over once, see [WearDayNightCarryOver]. */
class WearDayNightCarryOverTest {

    private lateinit var store: PreferencesInMemory
    private lateinit var location: PhoneRoughLocation
    private lateinit var sut: WearDayNightCarryOver
    private lateinit var settings: WearDayNightSettings

    private val phonePlace = DayNightRule.Place(52.4, 4.9)

    @BeforeEach
    fun setUp() {
        store = PreferencesInMemory()
        // A key that was never stored reads as not there
        whenever(store.preferences.getIfExists(WearDayNightBooleanKey.UsePhoneLocation)).thenAnswer { store.values[WearDayNightBooleanKey.UsePhoneLocation] as Boolean? }
        location = mock()
        whenever(location.hasPermission()).thenReturn(true)
        whenever(location.read()).thenReturn(phonePlace)
        sut = WearDayNightCarryOver(store.preferences, mock<AAPSLogger>())
        settings = WearDayNightSettings(store.preferences, location, TimeZoneCities())
    }

    @Test
    fun `with a night face the phone's location stays in use, and the screen shows the switch on`() {
        store.values[WearNightFaceKey.NightWatchfaceName] = "Night"

        sut.runOnce()

        assertThat(settings.usePhoneLocationStored()).isTrue()
        assertThat(settings.rule()).isEqualTo(phonePlace)
        assertThat(store.values[WearDayNightCarryOverKey.Done]).isEqualTo(true)
    }

    @Test
    fun `without the location permission the fallback of the screen is used`() {
        store.values[WearNightFaceKey.NightWatchfaceName] = "Night"
        whenever(location.hasPermission()).thenReturn(false)

        sut.runOnce()

        assertThat(settings.usePhoneLocationStored()).isTrue()
        assertThat(settings.rule()).isEqualTo(DayNightRule.FixedTimes(dayStartMinute = 7 * 60, nightStartMinute = 19 * 60))
    }

    @Test
    fun `without a night face the defaults stay, location off`() {
        sut.runOnce()

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
        assertThat(settings.usePhoneLocationStored()).isFalse()
        assertThat(settings.rule()).isEqualTo(DayNightRule.FixedTimes(dayStartMinute = 7 * 60, nightStartMinute = 19 * 60))
        assertThat(store.values[WearDayNightCarryOverKey.Done]).isEqualTo(true)
    }

    @Test
    fun `a switch the user set is never changed`() {
        store.values[WearNightFaceKey.NightWatchfaceName] = "Night"
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false

        sut.runOnce()

        assertThat(settings.usePhoneLocationStored()).isFalse()
    }

    @Test
    fun `it runs only once, also when a night face comes later`() {
        sut.runOnce()
        store.values[WearNightFaceKey.NightWatchfaceName] = "Night"

        sut.runOnce()

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
    }

    // The design of the watch, carried over with the first design the phone learns of

    @Test
    fun `a first design that needs the place keeps the phone's location, and the screen shows the switch on`() {
        sut.runOnce() // no night face: nothing yet
        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)

        sut.designKnown(needsPlace = true)

        assertThat(settings.usePhoneLocationStored()).isTrue()
        assertThat(settings.usesPhoneLocation()).isTrue()
        assertThat(store.values[WearDayNightCarryOverKey.DesignDone]).isEqualTo(true)
    }

    @Test
    fun `a first design that needs nothing turns nothing on`() {
        sut.designKnown(needsPlace = false)

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
        assertThat(store.values[WearDayNightCarryOverKey.DesignDone]).isEqualTo(true)
    }

    @Test
    fun `only the first design counts`() {
        sut.designKnown(needsPlace = false)

        // A design that needs the place, loaded later: the user decides on the screen
        sut.designKnown(needsPlace = true)

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
    }

    @Test
    fun `a switch the user set is never changed by the design either`() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false

        sut.designKnown(needsPlace = true)

        assertThat(settings.usePhoneLocationStored()).isFalse()
    }

    @Test
    fun `the design part runs also after the night face part has run`() {
        store.values[WearNightFaceKey.NightWatchfaceName] = "Night"
        sut.runOnce()
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false // the user turned it off again

        sut.designKnown(needsPlace = true)

        assertThat(settings.usePhoneLocationStored()).isFalse()
        assertThat(store.values[WearDayNightCarryOverKey.DesignDone]).isEqualTo(true)
    }
}
