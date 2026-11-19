package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.aps.IobTotal
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StatusNumbersTest {

    private val bolusIob = IobTotal(time = 0L, iob = 0.9, activity = 0.01)
    private val basalIob = IobTotal(time = 0L, basaliob = -0.35, activity = 0.006)

    @Test
    fun givesTheIobPartsAndTheirSum() {
        val numbers = StatusNumbers.of(bolusIob, basalIob, basalRate = 0.4, isfMgdl = 50.0)
        assertThat(numbers.iob).isWithin(1e-9).of(0.55)
        assertThat(numbers.iobBolus).isEqualTo(0.9)
        assertThat(numbers.iobBasal).isEqualTo(-0.35)
        assertThat(numbers.basalRate).isEqualTo(0.4)
    }

    @Test
    fun bgiIsTheFallOfGlucoseIn5MinutesInMgdl() {
        // The same rule as the BGI text the phone sends: -(activity) * 5 * ISF
        val numbers = StatusNumbers.of(bolusIob, basalIob, basalRate = 0.4, isfMgdl = 50.0)
        assertThat(numbers.bgiMgdl!!).isWithin(1e-9).of(-4.0)
    }

    @Test
    fun withoutGlucoseStatusThereIsNoBgi() {
        assertThat(StatusNumbers.of(bolusIob, basalIob, basalRate = 0.4, isfMgdl = null).bgiMgdl).isNull()
    }
}
