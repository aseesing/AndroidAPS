package app.aaps.plugins.sync.wear

import app.aaps.core.interfaces.alerts.EatReminder
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.scenes.SceneAutomationApi
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
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
            BolusProgressData(ch, CoroutineScope(Dispatchers.Unconfined)), persistenceLayer, scenes, eatReminder, mock(), mock()
        )
    }

    @Test
    fun `the phone has no day and night look settings, they are on the watch`() {
        val custom = wearPlugin.getPreferenceScreenContent().items
            .filterIsInstance<PreferenceSubScreenDef>()
            .single { it.key == "wear_custom_watchface_settings" }

        assertThat(custom.items.filterIsInstance<PreferenceKey>()).containsExactly(BooleanKey.WearCustomWatchfaceAuthorization)
    }
}
