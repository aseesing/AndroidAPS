// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
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
    fun theSettingsAreWrittenWithTheirTypesAndTheWakeLengthIsReported() {
        val renderer = CwfPreviewRenderer(RuntimeEnvironment.getApplication(), mock())

        val wakeChanged = renderer.applyWatchSettings(
            mapOf("show_iob" to "false", "wake_animation_length" to "3000", "dark" to "maybe", "units_mgdl" to "false")
        )

        assertThat(wakeChanged).isTrue()
        val settings = renderer.watchSettings
        assertThat(settings.getBoolean("show_iob", true)).isFalse()
        assertThat(settings.getString("wake_animation_length", null)).isEqualTo("3000")
        // Not a value the watch can have, and not a setting the phone may set
        assertThat(settings.contains("dark")).isFalse()
        assertThat(settings.contains("units_mgdl")).isFalse()

        // The same again changes nothing
        assertThat(renderer.applyWatchSettings(mapOf("wake_animation_length" to "3000"))).isFalse()
        assertThat(renderer.applyWatchSettings(mapOf("show_iob" to "true"))).isFalse()
        assertThat(settings.getBoolean("show_iob", false)).isTrue()
    }
}
