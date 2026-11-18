package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfNight
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.sync.wear.PreferencesInMemory
import app.aaps.plugins.sync.wear.daynight.PhoneRoughLocation
import app.aaps.plugins.sync.wear.daynight.TimeZoneCities
import app.aaps.plugins.sync.wear.daynight.WearDayNightSettings
import app.aaps.plugins.sync.wear.daynight.WearDayNightCarryOver // PERSONAL PATCH
import app.aaps.plugins.sync.wear.daynight.WearPlaceUse // PERSONAL PATCH
import app.aaps.plugins.sync.wear.keys.WearDayNightBooleanKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceBooleanKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class WearNightFaceTest {

    private lateinit var store: PreferencesInMemory
    private lateinit var location: PhoneRoughLocation
    private lateinit var sut: WearNightFace

    /** What the phone sent to the watch */
    private val sent = mutableListOf<EventData>()

    @BeforeEach
    fun setUp() {
        store = PreferencesInMemory()
        val rxBus = mock<RxBus>()
        doAnswer { (it.arguments[0] as? EventMobileToWear)?.let { event -> sent.add(event.payload) }; Unit }.whenever(rxBus).send(any<Event>())
        val dateUtil = mock<DateUtil>()
        whenever(dateUtil.now()).thenReturn(NOW)
        location = mock()
        whenever(location.hasPermission()).thenReturn(true)
        whenever(location.read()).thenReturn(DayNightRule.Place(52.4, 4.9))
        val settings = WearDayNightSettings(store.preferences, location, TimeZoneCities())
        val placeUse = WearPlaceUse(store.preferences, settings, location, WearDayNightCarryOver(store.preferences, mock()), rxBus, dateUtil) // PERSONAL PATCH
        sut = WearNightFace(store.preferences, rxBus, dateUtil, mock<AAPSLogger>(), settings, placeUse) // PERSONAL PATCH: placeUse
    }

    private fun face(name: String, version: String = "1", fileName: String = name, authorization: Boolean? = null) =
        CwfData(
            json = "{}",
            metadata = mutableMapOf(
                CwfMetadataKey.CWF_NAME to name,
                CwfMetadataKey.CWF_AUTHOR_VERSION to version,
                CwfMetadataKey.CWF_FILENAME to fileName
            ).also { map -> authorization?.let { map[CwfMetadataKey.CWF_AUTHORIZATION] = it.toString() } },
            resData = mutableMapOf()
        )

    private fun nameKept() = store.preferences.get(WearNightFaceKey.NightWatchfaceName)

    @Test
    fun `the main face goes on the data path, the night face on its own path`() {
        assertThat(watchfacePath("/rx_data_bridge", night = false)).isEqualTo("/rx_data_bridge")
        assertThat(watchfacePath("/rx_data_bridge", night = true)).isEqualTo("/rx_data_bridge" + CwfNight.PATH_SUFFIX)
    }

    @Test
    fun `without a night face nothing is known, sent, written or read`() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true

        assertThat(sut.isKnown()).isFalse()
        assertThat(sut.isOnWatch()).isFalse()
        assertThat(sut.resendMessages()).isEmpty()

        // The watch says it has none, as it does when asked
        sut.noneOnWatch()

        assertThat(store.writes).isEqualTo(0)
        assertThat(sent).isEmpty()
        verifyNoInteractions(location)
    }

    @Test
    fun `a night face that is only chosen gets no rule and the location is not read`() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true

        sut.chosen(face("Night").metadata)

        assertThat(sut.resendMessages()).isEmpty()
        assertThat(sent).isEmpty()
        verifyNoInteractions(location)
    }

    @Test
    fun `once the watch confirms its night face it gets the rule`() {
        sut.reportedByWatch(face("Night"))

        assertThat(sent).contains(EventData.ActionSetDayNightRule(DayNightRule.FixedTimes(7 * 60, 19 * 60)))
        assertThat(sut.resendMessages()).containsExactly(EventData.ActionSetDayNightRule(DayNightRule.FixedTimes(7 * 60, 19 * 60)))
    }

    @Test
    fun `with the switch off the location is not read, also with a night face`() {
        sut.reportedByWatch(face("Night"))
        sut.resendMessages()

        verify(location, never()).read()
    }

    @Test
    fun `with the switch on the confirmed night face gets the rough place`() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true

        sut.reportedByWatch(face("Night"))

        assertThat(sent).contains(EventData.ActionSetDayNightRule(DayNightRule.Place(52.4, 4.9)))
        assertThat(sut.resendMessages()).containsExactly(EventData.ActionSetDayNightRule(DayNightRule.Place(52.4, 4.9)))
    }

    @Test
    fun `while a removal waits no rule goes and the location is not read`() {
        store.values[WearDayNightBooleanKey.UsePhoneLocation] = true
        sut.reportedByWatch(face("Night"))
        sut.remove()
        clearInvocations(location)

        assertThat(sut.resendMessages()).containsExactly(EventData.ActionClearNightWatchface(NOW))
        verify(location, never()).read()
    }

    @Test
    fun `the rule is only for the own watch, never for the AAPSClient broadcast`() {
        assertThat(isForOwnWatchOnly(EventData.ActionSetDayNightRule(DayNightRule.Place(52.4, 4.9)))).isTrue()
        assertThat(isForOwnWatchOnly(EventData.ActionSetDayNightRule(DayNightRule.FixedTimes(420, 1140)))).isTrue()
        assertThat(isForOwnWatchOnly(EventData.ActionPing(1L))).isFalse()
    }

    @Test
    fun `a chosen night face is not on the watch until the watch reports it`() {
        sut.chosen(face("Night", "2", "Night_face").metadata)

        assertThat(sut.isKnown()).isTrue()
        assertThat(sut.isOnWatch()).isFalse()
        assertThat(nameKept()).isEqualTo("Night")
        assertThat(store.preferences.get(WearNightFaceKey.NightFileName)).isEqualTo("Night_face")

        sut.reportedByWatch(face("Night", "2", "Night_face"))

        assertThat(sut.isOnWatch()).isTrue()
        assertThat(sut.saved.value?.metadata?.get(CwfMetadataKey.CWF_NAME)).isEqualTo("Night")
    }

    @Test
    fun `a zip that never arrives is forgotten when the watch says it has none`() {
        sut.chosen(face("Night").metadata)

        sut.noneOnWatch()

        assertThat(sut.isKnown()).isFalse()
        assertThat(nameKept()).isEmpty()
    }

    @Test
    fun `a night face the phone did not know is taken from the watch`() {
        sut.reportedByWatch(face("Night", "3", "night.zip"))

        assertThat(sut.isOnWatch()).isTrue()
        assertThat(nameKept()).isEqualTo("Night")
        assertThat(store.preferences.get(WearNightFaceKey.NightAuthorVersion)).isEqualTo("3")
    }

    @Test
    fun `removing asks the watch and stops counting the night face at once`() {
        sut.reportedByWatch(face("Night"))

        sut.remove()

        assertThat(sent).contains(EventData.ActionClearNightWatchface(NOW))
        assertThat(sut.isOnWatch()).isFalse()
        assertThat(sut.saved.value).isNull()
        assertThat(nameKept()).isEmpty()
        assertThat(store.preferences.get(WearNightFaceBooleanKey.RemovalPending)).isTrue()
    }

    @Test
    fun `while a removal waits a reported night face is removed again, not taken back`() {
        sut.reportedByWatch(face("Night"))
        sut.remove()
        sent.clear()

        // The watch was away and reports the night face it still has
        sut.reportedByWatch(face("Night"))

        // Only the remove message: no rule for a night face that is being removed
        assertThat(sent).containsExactly(EventData.ActionClearNightWatchface(NOW))
        assertThat(sut.isOnWatch()).isFalse()
        assertThat(sut.saved.value).isNull()
        assertThat(nameKept()).isEmpty()
    }

    @Test
    fun `while a removal waits every resend carries the remove message`() {
        sut.reportedByWatch(face("Night"))
        sut.remove()

        assertThat(sut.resendMessages()).containsExactly(EventData.ActionClearNightWatchface(NOW))
        assertThat(sut.isKnown()).isTrue()
    }

    @Test
    fun `the removal ends when the watch says it has none`() {
        sut.reportedByWatch(face("Night"))
        sut.remove()

        sut.noneOnWatch()

        assertThat(store.preferences.get(WearNightFaceBooleanKey.RemovalPending)).isFalse()
        assertThat(sut.resendMessages()).isEmpty()
        assertThat(sut.isKnown()).isFalse()
        // Now a night face from the watch counts again
        sut.reportedByWatch(face("Other"))
        assertThat(sut.isOnWatch()).isTrue()
    }

    @Test
    fun `a new choice ends a removal that waits`() {
        sut.reportedByWatch(face("Night"))
        sut.remove()

        sut.chosen(face("Other").metadata)

        assertThat(store.preferences.get(WearNightFaceBooleanKey.RemovalPending)).isFalse()
        assertThat(sut.resendMessages()).isEmpty()
    }

    @Test
    fun `a reported night face is remembered only when name or version is new`() {
        sut.chosen(face("Night", "2", "Night_face").metadata)

        sut.reportedByWatch(face("Night", "2", "other"))
        assertThat(store.preferences.get(WearNightFaceKey.NightFileName)).isEqualTo("Night_face")

        sut.reportedByWatch(face("Night", "3", "other"))
        assertThat(store.preferences.get(WearNightFaceKey.NightFileName)).isEqualTo("other")
    }

    @Test
    fun `a night face with the right metadata needs no update`() {
        sut.chosen(face("Night", "2", "Night_face").metadata)
        store.values[BooleanKey.WearCustomWatchfaceAuthorization] = true

        assertThat(sut.metadataUpdate(face("Night", "2", "Night_face", authorization = true))).isNull()
    }

    @Test
    fun `the night face follows the authorization setting of the phone`() {
        sut.chosen(face("Night", "2", "Night_face").metadata)
        store.values[BooleanKey.WearCustomWatchfaceAuthorization] = true
        val nightFace = face("Night", "2", "Night_face", authorization = false)

        sut.reportedByWatch(nightFace)

        val update = sent.filterIsInstance<EventData.ActionUpdateCustomWatchface>().single()
        assertThat(update.night).isTrue()
        assertThat(update.customWatchfaceData.metadata[CwfMetadataKey.CWF_AUTHORIZATION]).isEqualTo("true")
        assertThat(update.customWatchfaceData.json).isEmpty()
        // The face the watch reported is not changed in place
        assertThat(nightFace.metadata[CwfMetadataKey.CWF_AUTHORIZATION]).isEqualTo("false")
    }

    @Test
    fun `the night face gets the remembered file name when name and version are the same`() {
        sut.chosen(face("Night", "2", "Night_face").metadata)

        val update = sut.metadataUpdate(face("Night", "2", "old_name", authorization = false))
        assertThat(update?.metadata?.get(CwfMetadataKey.CWF_FILENAME)).isEqualTo("Night_face")

        // Another face: its own file name stays
        assertThat(sut.metadataUpdate(face("Other", "2", "old_name", authorization = false))).isNull()
    }

    private companion object {

        const val NOW = 12345L
    }
}
