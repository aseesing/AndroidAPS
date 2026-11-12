// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.carspeech

import app.aaps.plugins.sync.carspeech.CarSpeechRule.LastSpoken
import app.aaps.plugins.sync.carspeech.CarSpeechRule.Reason
import app.aaps.plugins.sync.carspeech.CarSpeechRule.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class CarSpeechRuleTest {

    private val minute = 60_000L
    private val now = 1_000_000_000L
    private val settings = Settings(lowMgdl = 72.0, highMgdl = 180.0, changeThresholdMgdl = 10.0, outOfRangeRepeatMs = 10 * minute)

    private fun decide(value: Double, last: LastSpoken?, inVehicle: Boolean = true, readingAt: Long = now) =
        CarSpeechRule.decide(inVehicle, value, readingAt, now, settings, last)

    @Test
    fun `nothing is said outside the car`() {
        assertEquals(Reason.NONE, decide(50.0, null, inVehicle = false))
    }

    @Test
    fun `an old reading is not said`() {
        assertEquals(Reason.NONE, decide(50.0, null, readingAt = now - 5 * minute))
    }

    @Test
    fun `the same reading is not said twice`() {
        assertEquals(Reason.NONE, decide(50.0, LastSpoken(50.0, now)))
    }

    @Test
    fun `the first reading in the car is said`() {
        assertEquals(Reason.FIRST, decide(120.0, null))
    }

    @Test
    fun `a small change inside the range is not said`() {
        assertEquals(Reason.NONE, decide(125.0, LastSpoken(120.0, now - 5 * minute)))
    }

    @Test
    fun `a change of the threshold inside the range is said`() {
        assertEquals(Reason.CHANGED, decide(130.0, LastSpoken(120.0, now - 5 * minute)))
        assertEquals(Reason.CHANGED, decide(110.0, LastSpoken(120.0, now - 5 * minute)))
    }

    @Test
    fun `low and high are said`() {
        assertEquals(Reason.LOW, decide(65.0, null))
        assertEquals(Reason.HIGH, decide(200.0, null))
    }

    @Test
    fun `leaving the range is said at once`() {
        assertEquals(Reason.LOW, decide(70.0, LastSpoken(75.0, now - 5 * minute)))
    }

    @Test
    fun `a low is repeated only after the repeat time`() {
        assertEquals(Reason.NONE, decide(66.0, LastSpoken(65.0, now - 5 * minute)))
        assertEquals(Reason.LOW, decide(66.0, LastSpoken(65.0, now - 10 * minute)))
    }

    @Test
    fun `coming back in range is said, also with a small step`() {
        assertEquals(Reason.CHANGED, decide(74.0, LastSpoken(70.0, now - 5 * minute)))
    }
}
