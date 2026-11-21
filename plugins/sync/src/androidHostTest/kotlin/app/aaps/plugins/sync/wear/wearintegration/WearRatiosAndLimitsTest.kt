// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.objects.constraints.ConstraintObject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

class WearRatiosAndLimitsTest {

    /** The active APS is a plugin with constraints, as OpenAPS SMB, AMA and autoISF are. */
    private interface ApsWithConstraints : APS, PluginConstraints

    private val logger: AAPSLogger = mock()
    private val numbers = StatusNumbers(iob = 1.0)

    private fun sut(maxIob: Double = 6.0, maxCarbs: Int = 120, client: Boolean = false, aps: APS? = null): WearRatiosAndLimits {
        val activeAps = aps ?: mock<ApsWithConstraints> { onBlocking { applyMaxIOBConstraints(any()) } doReturn ConstraintObject(maxIob, logger) }
        val activePlugin: ActivePlugin = mock { on { activeAPS } doReturn activeAps }
        val constraints: ConstraintsChecker = mock { on { getMaxCarbsAllowed() } doReturn ConstraintObject(maxCarbs, logger) }
        val config: Config = mock { on { AAPSCLIENT } doReturn client }
        return WearRatiosAndLimits(activePlugin, constraints, config, logger)
    }

    private val profile: Profile = mock {
        on { getIc() } doReturn 8.5
        on { getIsfMgdl(any()) } doReturn 45.0
    }

    @Test
    fun givesTheRatiosOfTheProfileAndTheLimits() = runTest {
        val result = sut().addTo(numbers, profile, "test")
        assertThat(result).isEqualTo(numbers.copy(ic = 8.5, isfMgdl = 45.0, maxIob = 6.0, maxCarbs = 120.0))
    }

    @Test
    fun withoutProfileThereAreNoRatios() = runTest {
        val result = sut().addTo(numbers, null, "test")
        assertThat(result.ic).isNull()
        assertThat(result.isfMgdl).isNull()
        assertThat(result.maxIob).isEqualTo(6.0)
    }

    @Test
    fun aZeroLimitIsNotSent() = runTest {
        val result = sut(maxIob = 0.0, maxCarbs = 0).addTo(numbers, profile, "test")
        assertThat(result.maxIob).isNull()
        assertThat(result.maxCarbs).isNull()
    }

    @Test
    fun anApsWithoutConstraintsGivesNoMaxIob() = runTest {
        // Double.MAX_VALUE would be the result of a constraint that sets nothing: also no max IOB
        assertThat(sut(maxIob = Double.MAX_VALUE).addTo(numbers, profile, "test").maxIob).isNull()
        assertThat(sut(aps = mock<APS>()).addTo(numbers, profile, "test").maxIob).isNull()
    }

    @Test
    fun aClientSendsNoMaxIob() = runTest {
        val result = sut(client = true).addTo(numbers, profile, "test")
        assertThat(result.maxIob).isNull()
        assertThat(result.ic).isEqualTo(8.5)
    }

    @Test
    fun anErrorKeepsTheOtherNumbers() = runTest {
        val broken: Profile = mock { on { getIc() } doThrow IllegalStateException("APS not defined") }
        assertThat(sut().addTo(numbers, broken, "test")).isEqualTo(numbers)
    }
}
