package app.aaps.wear.complications.cwf

import android.app.Application
import app.aaps.shared.impl.rx.bus.RxBusImpl
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.data.ComplicationDataRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * With the code-based watch face nobody asks for the face picture, all day. The producer and the
 * clock loop used to look every 5 s whether somebody did; now they end, and the first request starts
 * them again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CwfComplicationUpdaterIdleTest {

    private val logger = AAPSLoggerTest()

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun withoutDemandTheLoopsEndAndADemandStartsThemAgain() {
        // No face bound: clears any demand left over from another test
        CwfFaceComplication.noteActivated("test", logger)
        CwfFaceComplication.noteDeactivated("test", logger)

        val repository = mock<ComplicationDataRepository>()
        whenever(repository.complicationData).thenReturn(flowOf(ComplicationData()))
        val updater = CwfComplicationUpdater(RuntimeEnvironment.getApplication(), RxBusImpl(logger), repository, logger)
        updater.start()

        assertThat(waitFor(3_000) { !updater.loopsRunning() }).isTrue()

        // A face binds the picture: both loops run again at once
        CwfFaceComplication.noteActivated("test", logger)
        assertThat(updater.loopsRunning()).isTrue()

        // ... and end again when it lets go
        CwfFaceComplication.noteDeactivated("test", logger)
        assertThat(waitFor(3_000) { !updater.loopsRunning() }).isTrue()
    }
}
