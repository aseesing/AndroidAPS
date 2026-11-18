package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The live preview gets the last value of each kind the watch face draws, and nothing else. */
class LastWatchFaceValuesTest {

    private fun bg(dataset: Int, at: Long) = EventData.SingleBg(dataset = dataset, timeStamp = at, sgv = 100.0, high = 180.0, low = 70.0)

    @Test
    fun aNewerValueReplacesTheOlderOneOfItsKind() {
        val sut = LastWatchFaceValues()
        sut.note(bg(0, 1))
        sut.note(bg(1, 2))
        sut.note(bg(0, 3))
        // One per dataset, the newest last
        assertThat(sut.all()).containsExactly(bg(1, 2), bg(0, 3)).inOrder()
    }

    @Test
    fun whatAWatchFaceDoesNotDrawIsLeftOut() {
        val sut = LastWatchFaceValues()
        sut.note(EventData.ActionPing(1))
        assertThat(sut.all()).isEmpty()
        assertThat(LastWatchFaceValues.kindOf(EventData.ActionPing(1))).isNull()
    }

    @Test
    fun takeAllForgetsWhatItGave() {
        val sut = LastWatchFaceValues()
        sut.note(bg(0, 1))
        assertThat(sut.takeAll()).containsExactly(bg(0, 1))
        assertThat(sut.takeAll()).isEmpty()
    }
}
