// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfNight
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.keys.AlwaysOnLook
import app.aaps.plugins.sync.wear.keys.DayNightLook
import app.aaps.plugins.sync.wear.keys.WearDayNightKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WearNightFaceTest {

    private lateinit var preferences: Preferences
    private lateinit var sut: WearNightFacePreferences

    @BeforeEach
    fun setUp() {
        preferences = mock()
        sut = WearNightFacePreferences(preferences)
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

    private fun remembered(name: String, version: String, fileName: String) {
        whenever(preferences.get(WearNightFaceKey.NightWatchfaceName)).thenReturn(name)
        whenever(preferences.get(WearNightFaceKey.NightAuthorVersion)).thenReturn(version)
        whenever(preferences.get(WearNightFaceKey.NightFileName)).thenReturn(fileName)
    }

    @Test
    fun `the main face goes on the data path, the night face on its own path`() {
        assertThat(watchfacePath("/rx_data_bridge", night = false)).isEqualTo("/rx_data_bridge")
        assertThat(watchfacePath("/rx_data_bridge", night = true)).isEqualTo("/rx_data_bridge" + CwfNight.PATH_SUFFIX)
    }

    @Test
    fun `the preferences for the watch carry the two day and night settings`() {
        whenever(preferences.get(WearDayNightKey.DayNightLookSetting)).thenReturn(DayNightLook.NIGHT)
        whenever(preferences.get(WearDayNightKey.AlwaysOnLookSetting)).thenReturn(AlwaysOnLook.ACTIVE)
        val before = EventData.Preferences(
            timeStamp = 1L, wearControl = true, unitsMgdl = true, bolusPercentage = 100, maxCarbs = 50, maxBolus = 5.0,
            insulinButtonIncrement1 = 0.5, insulinButtonIncrement2 = 1.0, carbsButtonIncrement1 = 5, carbsButtonIncrement2 = 10
        )

        val after = sut.withDayNightLooks(before)

        assertThat(after.dayNightLook).isEqualTo("night")
        assertThat(after.alwaysOnLook).isEqualTo("active")
        // Nothing else changes
        assertThat(after.copy(dayNightLook = null, alwaysOnLook = null)).isEqualTo(before)
    }

    @Test
    fun `the defaults are the defaults of the watch`() {
        assertThat(WearDayNightKey.DayNightLookSetting.defaultValue).isEqualTo("auto")
        assertThat(WearDayNightKey.AlwaysOnLookSetting.defaultValue).isEqualTo("night")
        assertThat(WearDayNightKey.DayNightLookSetting.entries.keys).containsExactly("auto", "day", "night").inOrder()
        assertThat(WearDayNightKey.AlwaysOnLookSetting.entries.keys).containsExactly("night", "day", "active").inOrder()
    }

    @Test
    fun `a chosen night face is remembered in its own keys`() {
        sut.remember(face("Cyberpunk", "2", "Cyberpunk_night").metadata)

        verify(preferences).put(WearNightFaceKey.NightWatchfaceName, "Cyberpunk")
        verify(preferences).put(WearNightFaceKey.NightAuthorVersion, "2")
        verify(preferences).put(WearNightFaceKey.NightFileName, "Cyberpunk_night")
    }

    @Test
    fun `a reported night face is remembered only when name or version is new`() {
        remembered("Cyberpunk", "2", "Cyberpunk_night")

        sut.rememberIfNew(face("Cyberpunk", "2", "other").metadata)
        verify(preferences, never()).put(eq(WearNightFaceKey.NightFileName), any<String>())

        sut.rememberIfNew(face("Cyberpunk", "3", "other").metadata)
        verify(preferences).put(WearNightFaceKey.NightFileName, "other")
    }

    @Test
    fun `forget clears the remembered night face`() {
        sut.forget()

        verify(preferences).put(WearNightFaceKey.NightWatchfaceName, "")
        verify(preferences).put(WearNightFaceKey.NightAuthorVersion, "")
        verify(preferences).put(WearNightFaceKey.NightFileName, "")
    }

    @Test
    fun `a night face with the right metadata needs no update`() {
        remembered("Cyberpunk", "2", "Cyberpunk_night")
        whenever(preferences.get(BooleanKey.WearCustomWatchfaceAuthorization)).thenReturn(true)

        assertThat(sut.metadataUpdate(face("Cyberpunk", "2", "Cyberpunk_night", authorization = true))).isNull()
    }

    @Test
    fun `the night face follows the authorization setting of the phone`() {
        remembered("Cyberpunk", "2", "Cyberpunk_night")
        whenever(preferences.get(BooleanKey.WearCustomWatchfaceAuthorization)).thenReturn(true)
        val nightFace = face("Cyberpunk", "2", "Cyberpunk_night", authorization = false)

        val update = sut.metadataUpdate(nightFace)

        assertThat(update?.metadata?.get(CwfMetadataKey.CWF_AUTHORIZATION)).isEqualTo("true")
        assertThat(update?.json).isEmpty()
        // The face the watch reported is not changed in place
        assertThat(nightFace.metadata[CwfMetadataKey.CWF_AUTHORIZATION]).isEqualTo("false")
    }

    @Test
    fun `the night face gets the remembered file name when name and version are the same`() {
        remembered("Cyberpunk", "2", "Cyberpunk_night")
        whenever(preferences.get(BooleanKey.WearCustomWatchfaceAuthorization)).thenReturn(false)

        val update = sut.metadataUpdate(face("Cyberpunk", "2", "old_name", authorization = false))
        assertThat(update?.metadata?.get(CwfMetadataKey.CWF_FILENAME)).isEqualTo("Cyberpunk_night")

        // Another face: its own file name stays
        assertThat(sut.metadataUpdate(face("Other", "2", "old_name", authorization = false))).isNull()
    }
}
