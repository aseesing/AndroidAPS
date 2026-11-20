package app.aaps.plugins.sync.wear.watchapp

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.InputStream
import kotlin.time.Duration.Companion.seconds

class WatchAppUpdaterTest {

    private val app = EmbeddedWatchApp("4.0.1 (wear.apk)", 100, 300)

    @Test
    fun `an unexpected error ends as a failure, not as a crash`() = runBlocking {
        val broken = object : EmbeddedWatchAppSource {
            override fun info() = app
            override fun open(): InputStream = throw IllegalStateException("asset manager closed")
        }
        val sut = WatchAppUpdater(broken, { null }, { null }, mock<AAPSLogger>(), mock<RxBus>(), CoroutineScope(SupervisorJob() + Dispatchers.IO))

        sut.start()
        val end = withTimeout(5_000) { sut.state.first { !it.isBusy } }

        assertThat(end).isEqualTo(WatchAppUpdateState.Failed(WatchAppUpdateState.Reason.ERROR, "asset manager closed"))
    }

    @Test
    fun `a build without the watch app offers nothing`() {
        val none = object : EmbeddedWatchAppSource {
            override fun info(): EmbeddedWatchApp? = null
            override fun open(): InputStream = throw AssertionError("must not be opened")
        }

        assertThat(WatchAppUpdater(none, { null }, { null }, mock<AAPSLogger>(), mock<RxBus>(), CoroutineScope(Dispatchers.IO)).embedded).isNull()
    }

    @Test
    fun `after an update the phone pings the watch a few times, so the new watch app reports`() = runTest {
        val rxBus = mock<RxBus>()
        val sut = WatchAppUpdater(object : EmbeddedWatchAppSource {
            override fun info() = app
            override fun open(): InputStream = throw AssertionError("must not be opened")
        }, { null }, { null }, mock<AAPSLogger>(), rxBus, this)
        fun pings(): Int {
            val captor = argumentCaptor<Event>()
            verify(rxBus, atLeast(0)).send(captor.capture())
            return captor.allValues.count { it is EventMobileToWear && it.payload is EventData.ActionPing }
        }

        launch { sut.askNewWatchAppToReport() }
        runCurrent()
        assertThat(pings()).isEqualTo(0)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertThat(pings()).isEqualTo(1)
        advanceTimeBy(80.seconds)
        runCurrent()
        assertThat(pings()).isEqualTo(3)
        advanceTimeBy(600.seconds)
        runCurrent()
        assertThat(pings()).isEqualTo(3)
    }
}
