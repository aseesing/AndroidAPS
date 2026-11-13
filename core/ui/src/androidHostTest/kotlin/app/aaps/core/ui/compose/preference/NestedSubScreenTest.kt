// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.core.ui.compose.preference

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.LocalProfileUtil
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.interfaces.configuration.Config as AppConfig
import app.aaps.core.ui.R as CoreUiR

/**
 * A subscreen inside a subscreen inside a subscreen. The settings screen and the search sheet drew
 * only two levels, so the third (for example the settings of each CGM alarm under Local alerts →
 * CGM alarms) was missing. Each level must now open as a collapsible section.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NestedSubScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)

    // Level 1 > level 2 > level 3 > level 4 (the last one only proves level 3 has content)
    private val level4 = PreferenceSubScreenDef(key = "level4", titleResId = CoreUiR.string.careportal)
    private val level3 = PreferenceSubScreenDef(key = "level3", titleResId = CoreUiR.string.profile, items = listOf(level4))
    private val level2 = PreferenceSubScreenDef(key = "level2", titleResId = CoreUiR.string.insulin_label, items = listOf(level3))
    private val level1 = PreferenceSubScreenDef(key = "level1", titleResId = CoreUiR.string.treatments, items = listOf(level2))

    private fun render(block: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(
            LocalPreferences provides mock<Preferences>(),
            LocalConfig provides mock<AppConfig>(),
            LocalProfileUtil provides mock<ProfileUtil>()
        ) {
            PreviewTheme { block() }
        }
    }

    @Test
    fun settingsScreenShowsTheThirdLevel() {
        render {
            val state = remember { PreferenceSectionState() }
            LazyColumn { addPreferenceSubScreenDef(level1, onShowMessage = {}, sectionState = state) }
        }
        compose.onNodeWithText(text(CoreUiR.string.treatments)).performClick()
        compose.onNodeWithText(text(CoreUiR.string.insulin_label)).performClick()
        compose.onNodeWithText(text(CoreUiR.string.profile)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(CoreUiR.string.careportal)).assertIsDisplayed()
    }

    @Test
    fun searchSheetShowsTheThirdLevel() {
        render { PreferenceSheetContent(settingsDef = level1) }
        compose.onNodeWithText(text(CoreUiR.string.insulin_label)).performClick()
        compose.onNodeWithText(text(CoreUiR.string.profile)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(CoreUiR.string.careportal)).assertIsDisplayed()
    }
}
