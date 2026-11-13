// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import app.aaps.core.interfaces.alerts.EatReminder
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.scenes.SceneAutomationApi
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.sync.wear.keys.WearDayNightKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobileHelper
import app.aaps.plugins.sync.wear.wearintegration.EventWearNightWatchface
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WearPluginNightFaceTest : TestBaseWithProfile() {

    @Mock lateinit var dataHandlerMobile: DataHandlerMobile
    @Mock lateinit var dataLayerListenerServiceMobileHelper: DataLayerListenerServiceMobileHelper
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var scenes: SceneAutomationApi
    @Mock lateinit var eatReminder: EatReminder

    private lateinit var wearPlugin: WearPlugin

    @BeforeEach fun prepare() {
        wearPlugin = WearPlugin(
            aapsLogger, rh, preferences, rxBus, context, dataHandlerMobile, dataLayerListenerServiceMobileHelper, config,
            BolusProgressData(ch, CoroutineScope(Dispatchers.Unconfined)), persistenceLayer, scenes, eatReminder, mock()
        )
    }

    private fun nightFace(authorization: Boolean) = CwfData(
        "{}",
        mutableMapOf(CwfMetadataKey.CWF_NAME to "Cyberpunk", CwfMetadataKey.CWF_AUTHORIZATION to authorization.toString()),
        mutableMapOf()
    )

    /** A scope in virtual time that runs at once what an event wakes up. */
    private fun TestScope.eagerScope() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    /** Collects what the plugin sends to the watch, from now on. */
    private fun TestScope.sentToWatch(): List<EventData> {
        val sent = mutableListOf<EventData>()
        eagerScope().launch(start = CoroutineStart.UNDISPATCHED) { rxBus.toFlow(EventMobileToWear::class).collect { sent.add(it.payload) } }
        return sent
    }

    @Test
    fun `the night face the watch reports is kept apart from the main face`() = runTest {
        wearPlugin.collectNightWatchface(eagerScope())
        whenever(preferences.get(BooleanKey.WearCustomWatchfaceAuthorization)).thenReturn(true)
        val face = nightFace(authorization = true)

        rxBus.send(EventWearNightWatchface(face))
        runCurrent()

        assertThat(wearPlugin.savedNightWatchface.value).isEqualTo(face)
        assertThat(wearPlugin.savedCustomWatchface.value).isNull()
    }

    @Test
    fun `the night face gets the authorization of the phone`() = runTest {
        val sent = sentToWatch()
        wearPlugin.collectNightWatchface(eagerScope())
        whenever(preferences.get(BooleanKey.WearCustomWatchfaceAuthorization)).thenReturn(true)

        rxBus.send(EventWearNightWatchface(nightFace(authorization = false)))
        runCurrent()

        val update = sent.filterIsInstance<EventData.ActionUpdateCustomWatchface>().single()
        assertThat(update.night).isTrue()
        assertThat(update.customWatchfaceData.metadata[CwfMetadataKey.CWF_AUTHORIZATION]).isEqualTo("true")
    }

    @Test
    fun `removing the night face asks the watch and marks it gone at once`() = runTest {
        val sent = sentToWatch()
        wearPlugin.collectNightWatchface(eagerScope())
        whenever(preferences.get(BooleanKey.WearCustomWatchfaceAuthorization)).thenReturn(true)
        rxBus.send(EventWearNightWatchface(nightFace(authorization = true)))
        runCurrent()

        wearPlugin.clearNightWatchface(12345L)
        runCurrent()

        assertThat(sent).contains(EventData.ActionClearNightWatchface(12345L))
        assertThat(wearPlugin.savedNightWatchface.value).isNull()
        verify(preferences).put(WearNightFaceKey.NightWatchfaceName, "")
    }

    @Test
    fun `the two settings are in the custom watchface settings`() {
        val custom = wearPlugin.getPreferenceScreenContent().items
            .filterIsInstance<PreferenceSubScreenDef>()
            .single { it.key == "wear_custom_watchface_settings" }
        val keys = custom.items.filterIsInstance<PreferenceKey>()

        assertThat(keys).containsAtLeast(WearDayNightKey.DayNightLookSetting, WearDayNightKey.AlwaysOnLookSetting)
    }
}
