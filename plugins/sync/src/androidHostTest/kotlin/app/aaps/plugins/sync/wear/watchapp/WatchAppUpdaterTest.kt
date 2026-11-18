package app.aaps.plugins.sync.wear.watchapp

import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.InputStream

class WatchAppUpdaterTest {

    private val app = EmbeddedWatchApp("4.0.1 (wear.apk)", 100, 300)

    @Test
    fun `an unexpected error ends as a failure, not as a crash`() = runBlocking {
        val broken = object : EmbeddedWatchAppSource {
            override fun info() = app
            override fun open(): InputStream = throw IllegalStateException("asset manager closed")
        }
        val sut = WatchAppUpdater(broken, { null }, mock<AAPSLogger>(), CoroutineScope(SupervisorJob() + Dispatchers.IO))

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

        assertThat(WatchAppUpdater(none, { null }, mock<AAPSLogger>(), CoroutineScope(Dispatchers.IO)).embedded).isNull()
    }
}
