package app.aaps.wear.phonepreview

import android.app.Application
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.data.ComplicationData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The preview never shows values or a design that an earlier run of the app left on disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PreviewHostTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theValuesOfAnEarlierRunAreRemovedBeforeTheFirstRead() {
        val app: Application = RuntimeEnvironment.getApplication()
        // What the face's DataStore kept in an earlier run of the app
        val old = ComplicationData(bgData = EventData.SingleBg(dataset = 0, timeStamp = 1_000L, sgvString = "OLD", sgv = 321.0, high = 180.0, low = 70.0))
        val file = File(app.filesDir, "cwf_preview/datastore/complication_data.pb")
        file.parentFile?.mkdirs()
        file.writeBytes(ProtoBuf.encodeToByteArray(ComplicationData.serializer(), old))

        val host = PreviewHost(app, mock())

        assertThat(file.exists()).isFalse()
        val read = runBlocking { host.repository.complicationData.first() }
        assertThat(read.bgData.sgvString).isNotEqualTo("OLD")
        assertThat(read).isEqualTo(ComplicationData())
    }
}
