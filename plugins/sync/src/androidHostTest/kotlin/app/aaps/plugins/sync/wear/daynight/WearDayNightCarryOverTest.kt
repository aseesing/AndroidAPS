// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.daynight

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.sync.wear.PreferencesInMemory
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearDayNightCarryOverKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Day and night of the old personal build, carried over once, see [WearDayNightCarryOver]. */
class WearDayNightCarryOverTest {

    private lateinit var store: PreferencesInMemory
    private lateinit var location: PhoneRoughLocation
    private lateinit var sut: WearDayNightCarryOver
    private lateinit var settings: WearDayNightSettings

    private val phonePlace = DayNightRule.Place(52.4, 4.9)
    private val fixedTimes = DayNightRule.FixedTimes(dayStartMinute = 7 * 60, nightStartMinute = 19 * 60)

    @BeforeEach
    fun setUp() {
        store = PreferencesInMemory()
        // A key that was never stored reads as not there
        whenever(store.preferences.getIfExists(WearDayNightBooleanKey.UsePhoneLocation)).thenAnswer { store.values[WearDayNightBooleanKey.UsePhoneLocation] as Boolean? }
        location = mock()
        whenever(location.hasPermission()).thenReturn(true)
        whenever(location.read()).thenReturn(phonePlace)
        sut = WearDayNightCarryOver(store.preferences, location, mock<AAPSLogger>())
        settings = WearDayNightSettings(store.preferences, location, TimeZoneCities())
    }

    /** The old build knew the main design of the watch */
    private fun oldBuildWithMainDesign() {
        store.values[StringNonKey.WearCwfWatchfaceName] = "D2"
    }

    private fun designDone() = store.values[WearDayNightCarryOverKey.DesignDone] == true

    // ---- the night face, at the first start of this build ----

    @Test
    fun `with a night face the phone's location stays in use, and the screen shows the switch on`() {
        store.values[WearNightFaceKey.NightWatchfaceName] = "Night"

        sut.runOnce()

        assertThat(settings.usePhoneLocationStored()).isTrue()
        assertThat(settings.rule()).isEqualTo(phonePlace)
        assertThat(store.values[WearDayNightCarryOverKey.Done]).isEqualTo(true)
    }

    @Test
    fun `without the location permission nothing is turned on, and it does not run again`() {
        store.values[WearNightFaceKey.NightWatchfaceName] = "Night"
        whenever(location.hasPermission()).thenReturn(false)

        sut.runOnce()

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
        assertThat(settings.rule()).isEqualTo(fixedTimes)
        assertThat(store.values[WearDayNightCarryOverKey.Done]).isEqualTo(true)

        // The permission granted later, for another reason: still nothing
        whenever(location.hasPermission()).thenReturn(true)
        sut.runOnce()
        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
    }

    @Test
    fun `without a night face the defaults stay, location off`() {
        sut.runOnce()

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
        assertThat(settings.usePhoneLocationStored()).isFalse()
        assertThat(settings.rule()).isEqualTo(fixedTimes)
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

    // ---- the main design, with the first design the phone learns of ----

    @Test
    fun `old build - a first design that needs the place keeps the phone's location, and the screen shows the switch on`() {
        oldBuildWithMainDesign()
        sut.runOnce()
        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
        assertThat(designDone()).isFalse()

        sut.designKnown(needsPlace = true)

        assertThat(settings.usePhoneLocationStored()).isTrue()
        assertThat(settings.usesPhoneLocation()).isTrue()
        assertThat(designDone()).isTrue()
    }

    @Test
    fun `a fresh install or a new phone - no sign of the old build, the design part is done at once`() {
        sut.runOnce()
        assertThat(designDone()).isTrue()

        // Months later the user loads a sun and moon design: the switch stays as it is
        sut.designKnown(needsPlace = true)

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
    }

    @Test
    fun `old build - a request round without a design ends the design part`() {
        oldBuildWithMainDesign()
        sut.runOnce()

        // The watch has only the default design and does not answer
        sut.designRoundOver()
        assertThat(designDone()).isTrue()

        // A sun and moon design loaded later: the user decides on the screen
        sut.designKnown(needsPlace = true)
        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
    }

    @Test
    fun `old build - a design sent by the phone ends the design part`() {
        oldBuildWithMainDesign()
        sut.runOnce()

        sut.designSentByPhone()
        sut.designKnown(needsPlace = true)

        assertThat(designDone()).isTrue()
        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
    }

    @Test
    fun `old build - without the permission the design turns nothing on, and the part is over`() {
        oldBuildWithMainDesign()
        sut.runOnce()
        whenever(location.hasPermission()).thenReturn(false)

        sut.designKnown(needsPlace = true)

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
        assertThat(designDone()).isTrue()

        // The permission granted later: still nothing
        whenever(location.hasPermission()).thenReturn(true)
        sut.designKnown(needsPlace = true)
        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
    }

    @Test
    fun `a first design that needs nothing turns nothing on`() {
        oldBuildWithMainDesign()
        sut.runOnce()

        sut.designKnown(needsPlace = false)

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
        assertThat(designDone()).isTrue()
    }

    @Test
    fun `only the first design counts`() {
        oldBuildWithMainDesign()
        sut.runOnce()
        sut.designKnown(needsPlace = false)

        // A design that needs the place, loaded later: the user decides on the screen
        sut.designKnown(needsPlace = true)

        assertThat(store.values).doesNotContainKey(WearDayNightBooleanKey.UsePhoneLocation)
    }

    @Test
    fun `a switch the user set off is never changed by the design either`() {
        oldBuildWithMainDesign()
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false
        sut.runOnce()

        sut.designKnown(needsPlace = true)

        assertThat(settings.usePhoneLocationStored()).isFalse()
    }

    @Test
    fun `the design part runs also after the night face part has run, but never against the user`() {
        store.values[WearNightFaceKey.NightWatchfaceName] = "Night"
        sut.runOnce()
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = false // the user turned it off again

        sut.designKnown(needsPlace = true)

        assertThat(settings.usePhoneLocationStored()).isFalse()
        assertThat(designDone()).isTrue()
    }

    @Test
    fun `a later start without a design ends a design part that is still open`() {
        oldBuildWithMainDesign()
        sut.runOnce()
        assertThat(designDone()).isFalse()

        store.values[StringNonKey.WearCwfWatchfaceName] = ""
        sut.runOnce()

        assertThat(designDone()).isTrue()
    }
}
