// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.app.Application
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.data.NightWatchfaceData
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The phone preview gets the same rule for when it is day as the watch, and keeps it as the watch
 * does: only while the design needs the place. The file with the rule (it can hold the place) is in
 * the phone's no-backup folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PreviewPlaceRuleTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val place = DayNightRule.Place(52.4, 4.9)

    @OptIn(ExperimentalSerializationApi::class)
    private fun storedRule(name: String): DayNightRule? =
        File(app.noBackupFilesDir, "$name/${ComplicationDataRepository.NIGHT_WATCHFACE_FILE}").takeIf { it.exists() }
            ?.let { ProtoBuf.decodeFromByteArray(NightWatchfaceData.serializer(), it.readBytes()).dayNightRule }

    @Test
    fun aDesignThatNeedsThePlaceGetsTheRuleInTheNoBackupFolder() {
        val renderer = CwfPreviewRenderer(app, mock(), "place_test")
        renderer.setDesign(TestDesigns.withPlace())

        renderer.apply(EventData.ActionSetDayNightRule(place))

        assertThat(storedRule("place_test")).isEqualTo(place)
        assertThat(File(app.filesDir, "place_test/${ComplicationDataRepository.NIGHT_WATCHFACE_FILE}").exists()).isFalse()

        // "No rule" from the phone: gone
        renderer.apply(EventData.ActionClearDayNightRule(1_000L))
        assertThat(storedRule("place_test")).isNull()
    }

    @Test
    fun aDesignThatNeedsNothingKeepsNoRule() {
        val renderer = CwfPreviewRenderer(app, mock(), "plain_test")
        renderer.setDesign(TestDesigns.still())

        renderer.apply(EventData.ActionSetDayNightRule(place))

        assertThat(storedRule("plain_test")).isNull()
    }
}
