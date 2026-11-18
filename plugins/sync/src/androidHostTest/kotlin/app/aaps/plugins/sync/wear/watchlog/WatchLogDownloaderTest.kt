package app.aaps.plugins.sync.wear.watchlog

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.sync.wear.watchapp.WatchChannel
import app.aaps.plugins.sync.wear.watchapp.WatchChannelOpener
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** One download at a time on the phone; the next one may start when it ended, and the temp file is gone */
class WatchLogDownloaderTest {

    @TempDir lateinit var folder: File

    @Test
    fun `a second start while one runs does nothing, and after the end a new one runs`() = runBlocking {
        val opened = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val opener = WatchChannelOpener {
            opened.incrementAndGet()
            gate.await()
            null as WatchChannel?
        }
        val temp = File(folder, "temp.zip").apply { writeText("old") }
        val sut = WatchLogDownloader(WatchLogReceiver(opener), { _, _ -> throw AssertionError("nothing to save") }, temp, mock<AAPSLogger>(), CoroutineScope(SupervisorJob() + Dispatchers.IO))

        sut.start()
        sut.start()
        withTimeout(5_000) { while (opened.get() == 0) delay(10) }
        assertThat(opened.get()).isEqualTo(1)

        gate.complete(Unit)
        val end = withTimeout(5_000) { sut.state.first { !it.isBusy } }
        assertThat(end).isEqualTo(WatchLogState.Failed(WatchLogState.Reason.NO_WATCH))
        withTimeout(5_000) { while (temp.exists()) delay(10) }

        sut.start()
        withTimeout(5_000) { sut.state.first { !it.isBusy } }
        assertThat(opened.get()).isEqualTo(2)
    }
}
