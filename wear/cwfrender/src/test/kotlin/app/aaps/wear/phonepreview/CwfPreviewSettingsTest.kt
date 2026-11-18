package app.aaps.wear.phonepreview

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The watch settings chosen on the phone reach the preview, with the types the watch face reads. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CwfPreviewSettingsTest {

    @Test
    fun theSettingsAreWrittenWithTheirTypes() {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock())

        val changed = renderer.applyWatchSettings(
            mapOf("show_iob" to "false", "show_second" to "false", "dark" to "maybe", "units_mgdl" to "false")
        )

        assertThat(changed).isTrue()
        val settings = renderer.watchSettings
        assertThat(settings.getBoolean("show_iob", true)).isFalse()
        assertThat(settings.getBoolean("show_second", true)).isFalse()
        // Not a value the watch can have, and not a setting the phone may set
        assertThat(settings.contains("dark")).isFalse()
        assertThat(settings.contains("units_mgdl")).isFalse()

        // The same again changes nothing
        assertThat(renderer.applyWatchSettings(mapOf("show_iob" to "false"))).isFalse()
        assertThat(renderer.applyWatchSettings(mapOf("show_iob" to "true"))).isTrue()
        assertThat(settings.getBoolean("show_iob", false)).isTrue()
    }
}
