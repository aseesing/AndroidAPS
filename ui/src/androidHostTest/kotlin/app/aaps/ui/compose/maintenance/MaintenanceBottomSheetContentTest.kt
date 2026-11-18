package app.aaps.ui.compose.maintenance

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.ui.R
import app.aaps.ui.UiStringIds
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.ui.R as CoreUiR

/** Robolectric composable test for [MaintenanceBottomSheetContent] (all-default params): renders the
 *  file-management section + rows and fires the Log-settings row callback. Headless JVM, no emulator.
 *  The "Save logs to Downloads" row is shown only where the platform can save there. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MaintenanceBottomSheetContentTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fileManagement: String
    private lateinit var logSettings: String
    private lateinit var saveLogs: String

    @Before
    fun setUp() {
        // MainApp does this in production. Without it the "Save logs" row would show its raw name.
        TextRefIdRegistry.register("ui") { name -> UiStringIds.idOf(name) }
        val ctx: Context = RuntimeEnvironment.getApplication()
        fileManagement = ctx.getString(CoreUiR.string.file_management)
        logSettings = ctx.getString(CoreUiR.string.nav_logsettings)
        saveLogs = ctx.getString(R.string.save_logs)
    }

    @Test
    fun rendersSectionsAndFiresLogSettingsRow() {
        var logSettingsClicked = false
        compose.setContent {
            MaterialTheme {
                MaintenanceBottomSheetContent(onLogSettingsClick = { logSettingsClicked = true })
            }
        }

        compose.onNodeWithText(fileManagement).assertIsDisplayed()
        compose.onNodeWithText(logSettings).assertIsDisplayed()

        compose.onNodeWithText(logSettings).performClick()
        assertThat(logSettingsClicked).isTrue()
    }

    @Test
    fun saveLogsRowIsShownAndFiresWhereThePlatformCanSave() {
        var saveLogsClicked = false
        compose.setContent {
            MaterialTheme {
                MaintenanceBottomSheetContent(showSaveLogs = true, onSaveLogsClick = { saveLogsClicked = true })
            }
        }

        compose.onNodeWithText(saveLogs).performClick()
        assertThat(saveLogsClicked).isTrue()
    }

    @Test
    fun saveLogsRowIsAbsentWhereThePlatformCannotSave() {
        compose.setContent {
            MaterialTheme {
                MaintenanceBottomSheetContent(showSaveLogs = false)
            }
        }

        compose.onNodeWithText(logSettings).assertIsDisplayed()
        compose.onNodeWithText(saveLogs).assertDoesNotExist()
    }
}
