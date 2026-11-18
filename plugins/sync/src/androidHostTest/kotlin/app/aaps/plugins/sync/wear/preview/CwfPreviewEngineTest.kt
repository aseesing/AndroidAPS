package app.aaps.plugins.sync.wear.preview

import android.app.Application
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.plugins.sync.wear.WearPlugin
import app.aaps.plugins.sync.wear.cwfsettings.CwfWatchSettingsStore
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.shared.impl.rx.bus.RxBusImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The preview's thread and face live only while a preview is shown, and a failure switches it off. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CwfPreviewEngineTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val logger: AAPSLogger = mock()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher()) // the view model scope
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Waits until [engine] stopped its thread, which [CwfPreviewEngine.release] does in the background. */
    private suspend fun stopped(engine: CwfPreviewEngine) = withTimeout(5_000) {
        while (engine.isRunning) delay(10)
    }

    @Test
    fun releaseStopsTheThreadAndTheNextUseStartsAgain() = runBlocking {
        val engine = CwfPreviewEngine(app, logger)
        assertThat(engine.isRunning).isFalse()

        assertThat(engine.use { Thread.currentThread().name }).startsWith("CwfPreview")
        assertThat(engine.isRunning).isTrue()

        engine.release()
        stopped(engine)

        assertThat(engine.use { 42 }).isEqualTo(42)
        assertThat(engine.isRunning).isTrue()
        engine.release()
        stopped(engine)
    }

    @Test
    fun aFailureSwitchesThePreviewOff() = runBlocking {
        val engine = CwfPreviewEngine(app, logger)
        val result: Int? = engine.use<Int> { error("broken") }
        assertThat(result).isNull()
        assertThat(engine.failed).isTrue()
        assertThat(engine.use { 1 }).isNull()
        engine.release()
        stopped(engine)
    }

    @Test
    fun whenTheScreenStopsShowingThePreviewItIsReleased() = runBlocking {
        val engine = CwfPreviewEngine(app, logger)
        val wearPlugin: WearPlugin = mock { on { savedCustomWatchface } doReturn MutableStateFlow<CwfData?>(null) }
        val dataHandlerMobile: DataHandlerMobile = mock { on { lastSentToWatchFace() } doReturn emptyList() }
        val viewModel = CwfLivePreviewViewModel(
            engine, wearPlugin, CwfFullDesigns(mock<FileListProvider>()), dataHandlerMobile, RxBusImpl(logger), CwfWatchSettingsStore(app)
        )
        engine.use { }
        assertThat(engine.isRunning).isTrue()

        // No design yet: the static picture. Taking one frame and stopping, as the screen does on STOP
        assertThat(viewModel.frames(100).first()).isNull()
        stopped(engine)
        assertThat(engine.isRunning).isFalse()
    }
}
