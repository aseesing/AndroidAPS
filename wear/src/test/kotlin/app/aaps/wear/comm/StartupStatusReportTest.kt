// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// ============================================================================================
package app.aaps.wear.comm

import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class StartupStatusReportTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private var reports = 0

    private fun sut(report: suspend () -> Unit = { reports++ }) = StartupStatusReport(scope, AAPSLoggerTest(), report)

    @Test
    fun `nothing is sent while the phone node is not known`() {
        val sut = sut()

        sut.onPhoneNode(null)
        sut.onPhoneNode(null)

        assertThat(reports).isEqualTo(0)
    }

    @Test
    fun `the first known phone node sends the status`() {
        sut().onPhoneNode("phone")

        assertThat(reports).isEqualTo(1)
    }

    @Test
    fun `the status is sent only once, not on every later message`() {
        val sut = sut()

        sut.onPhoneNode(null)
        sut.onPhoneNode("phone")
        sut.onPhoneNode("phone")
        sut.onPhoneNode("other phone")
        sut.onPhoneNode(null)

        assertThat(reports).isEqualTo(1)
    }

    @Test
    fun `a failing report does not throw`() {
        val sut = sut { error("the listing failed") }

        sut.onPhoneNode("phone")
        sut.onPhoneNode("phone")
    }
}
