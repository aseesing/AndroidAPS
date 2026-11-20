package app.aaps.plugins.sync.wear

import app.aaps.core.interfaces.alerts.EatReminder
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.scenes.SceneAutomationApi
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobileHelper
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock

/** The watch's Android API level from its answer to a ping, the second source next to the Watch Face Push status */
class WearPluginWatchApiLevelTest : TestBaseWithProfile() {

    @Mock lateinit var dataHandlerMobile: DataHandlerMobile
    @Mock lateinit var dataLayerListenerServiceMobileHelper: DataLayerListenerServiceMobileHelper
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var scenes: SceneAutomationApi
    @Mock lateinit var eatReminder: EatReminder

    private lateinit var wearPlugin: WearPlugin

    @BeforeEach fun prepare() {
        wearPlugin = WearPlugin(
            aapsLogger, rh, preferences, rxBus, context, dataHandlerMobile, dataLayerListenerServiceMobileHelper, config,
            BolusProgressData(ch, CoroutineScope(Dispatchers.Unconfined)), persistenceLayer, scenes, eatReminder, mock(), mock()
        )
    }

    @Test
    fun `unknown until the watch answers a ping`() {
        assertThat(wearPlugin.watchApiLevel.value).isEqualTo(0)

        wearPlugin.updateWatchApiLevel(34)

        assertThat(wearPlugin.watchApiLevel.value).isEqualTo(34)
    }

    @Test
    fun `an answer without a level keeps the known level`() {
        wearPlugin.updateWatchApiLevel(36)

        wearPlugin.updateWatchApiLevel(0)

        assertThat(wearPlugin.watchApiLevel.value).isEqualTo(36)
    }

    @Test
    fun `the level is forgotten when the watch disconnects, like the status`() {
        wearPlugin.updateConnectedDevice("Galaxy Watch 5")
        wearPlugin.updateWatchApiLevel(34)

        wearPlugin.updateConnectedDevice(null)

        assertThat(wearPlugin.watchApiLevel.value).isEqualTo(0)
        assertThat(wearPlugin.watchFacePushStatus.value).isNull()
    }
}
