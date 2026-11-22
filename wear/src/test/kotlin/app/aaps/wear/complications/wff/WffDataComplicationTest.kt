package app.aaps.wear.complications.wff

import android.os.Looper
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService.ComplicationRequestListener
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.watchfaces.wff.WffFace
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class WffDataComplicationTest {

    /** Counts the errors, and passes everything on to the test logger. */
    private class CountingLogger(private val inner: AAPSLogger = AAPSLoggerTest()) : AAPSLogger by inner {

        var errors = 0

        override fun error(tag: LTag, message: String, throwable: Throwable) {
            errors++
            inner.error(tag, message, throwable)
        }
    }

    private class Answers : ComplicationRequestListener {

        val data = mutableListOf<ComplicationData?>()

        override fun onComplicationData(complicationData: ComplicationData?) {
            data += complicationData
        }
    }

    private val app = RuntimeEnvironment.getApplication()
    private val logger = CountingLogger()

    private fun service(): WffDataComplication =
        Robolectric.buildService(WffDataComplication::class.java).get().also {
            it.aapsLogger = logger
            it.wffFace = WffFace(app, logger)
            it.painter = WffDataPainter(app, logger)
        }

    private fun request() = ComplicationRequest(complicationInstanceId = 5, complicationType = ComplicationType.SMALL_IMAGE, immediateResponseRequired = false)

    /** Lets the read on the other thread end, then runs what waits on the main thread. */
    private fun settle() {
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `without a face the answer is no data`() {
        val answers = Answers()
        service().onComplicationRequest(request(), answers)
        settle()

        assertThat(answers.data).hasSize(1)
        assertThat(answers.data.single()).isInstanceOf(NoDataComplicationData::class.java)
    }

    @Test
    fun `a request that the end of the service cancels is not answered and not logged as a failure`() {
        val answers = Answers()
        val service = service()
        service.onComplicationRequest(request(), answers)
        // The request now waits for the design, read on another thread
        service.onDestroy()
        settle()

        assertThat(answers.data).isEmpty()
        assertThat(logger.errors).isEqualTo(0)
    }
}
