// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import android.app.Application
import android.util.Base64
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.plugins.sync.wear.WearPlugin
import app.aaps.plugins.sync.wear.cwfsettings.CwfWatchSettingsStore
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.shared.impl.rx.bus.RxBusImpl
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.phonepreview.PreviewHost
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The values of the phone reach the live preview: the glucose shows as soon as the preview opens. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CwfLivePreviewValuesTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val logger: AAPSLogger = mock()
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher()) // the view model scope
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun valuesAreNotLostWhenThePreviewClosesBeforeTheyReachTheRenderer() = runBlocking {
        val engine = CwfPreviewEngine(app, logger)
        val viewModel = viewModel(engine, sent = listOf(bg(now - 120_000L), status()))
        // The renderers are busy: another preview draws, or the face shown before is being freed
        val busy = CountDownLatch(1)
        val holds = CountDownLatch(1)
        val holder = launch(Dispatchers.Default) {
            engine.use(PreviewSlot.NIGHT) {
                holds.countDown()
                busy.await()
            }
        }
        holds.await()
        // The preview opens and closes again before its turn comes: the screen is laid out again, or shows
        // the loading indicator of a new design
        val first = launch(Dispatchers.Default) { viewModel.frames(SIZE, PreviewSlot.DAY).collect { } }
        delay(500)
        first.cancelAndJoin()
        busy.countDown()
        holder.join()

        // Opened again: the face is drawn with the glucose the phone has
        assertThat(withTimeout(30_000) { viewModel.frames(SIZE, PreviewSlot.DAY).first { it != null } }).isNotNull()
        assertThat(glucoseTimeIn(engine)).isEqualTo(now - 120_000L)
        engine.release()
    }

    @Test
    fun theLastGlucoseOfThePhoneShowsEachTimeThePreviewOpens() = runBlocking {
        val engine = CwfPreviewEngine(app, logger)
        // Right after a start of the app: a status went to the watch, the glucose not yet
        val phoneHas = mutableListOf<EventData>(status(), bg(now - 120_000L))
        val viewModel = viewModel(engine, phoneHas)

        assertThat(withTimeout(30_000) { viewModel.frames(SIZE, PreviewSlot.DAY).first { it != null } }).isNotNull()
        assertThat(glucoseTimeIn(engine)).isEqualTo(now - 120_000L)

        // Closed, a new reading on the phone, opened again
        phoneHas[1] = bg(now)
        assertThat(withTimeout(30_000) { viewModel.frames(SIZE, PreviewSlot.DAY).first { it != null } }).isNotNull()
        assertThat(glucoseTimeIn(engine)).isEqualTo(now)
        engine.release()
    }

    /** The time of the glucose reading the renderer of the day face has; 0 when it has none. */
    private suspend fun glucoseTimeIn(engine: CwfPreviewEngine): Long? = engine.use(PreviewSlot.DAY) { renderer ->
        val host = renderer.javaClass.getDeclaredField("host").apply { isAccessible = true }.get(renderer) as PreviewHost
        runBlocking { host.repository.current().bgData.timeStamp }
    }

    private fun viewModel(engine: CwfPreviewEngine, sent: List<EventData>): CwfLivePreviewViewModel {
        val wearPlugin: WearPlugin = mock {
            on { savedCustomWatchface } doReturn MutableStateFlow<CwfData?>(design())
            on { savedNightWatchface } doReturn MutableStateFlow<CwfData?>(null)
        }
        val dataHandlerMobile: DataHandlerMobile = mock { onBlocking { previewStartValues() } doAnswer { sent.toList() } }
        return CwfLivePreviewViewModel(
            engine, wearPlugin, CwfFullDesigns(mock<FileListProvider>()), dataHandlerMobile, RxBusImpl(logger), CwfWatchSettingsStore(app)
        )
    }

    /** A small whole design, as the phone loads it from a zip: with its background picture. */
    private fun design(): CwfData {
        val json = JSONObject().put("metadata", JSONObject().put("name", "Test Values").put("cwf_version", "3.0"))
        val picture = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", Base64.DEFAULT)
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("CustomWatchface.json"))
                zip.write(json.toString().toByteArray())
                zip.putNextEntry(ZipEntry("${ResFileMap.CUSTOM_WATCHFACE.fileName}.png"))
                zip.write(picture)
                zip.putNextEntry(ZipEntry("${ResFileMap.BACKGROUND.fileName}.png"))
                zip.write(picture)
                zip.closeEntry()
            }
        }.toByteArray()
        return ZipWatchfaceFormat.loadCustomWatchface(zip, "Test_Values.zip", authorization = true)!!.cwfData
    }

    private fun bg(time: Long) = EventData.SingleBg(
        dataset = 0, timeStamp = time, sgvString = "6,7", glucoseUnits = "mmol", slopeArrow = "→",
        delta = "+0,2", deltaDetailed = "+0,2", avgDelta = "+0,1", avgDeltaDetailed = "+0,1", sgvLevel = 0, sgv = 120.0,
        high = 180.0, low = 70.0, color = 0
    )

    private fun status() = EventData.Status(
        dataset = 0, externalStatus = "", iobSum = "0,43", iobDetail = "(0,40|0,03)", cob = "0g", currentBasal = "0.85U/h",
        battery = "78", rigBattery = "--", openApsStatus = now - 180_000L, bgi = "-0.4", batteryLevel = 1,
        tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0
    )

    private companion object {

        const val SIZE = 200
    }
}
