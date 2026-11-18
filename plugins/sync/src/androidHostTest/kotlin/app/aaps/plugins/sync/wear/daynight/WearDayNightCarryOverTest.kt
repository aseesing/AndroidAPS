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
}
