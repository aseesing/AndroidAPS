// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.plugins.sync.wear.keys.WffFaceCoverKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** What the phone knows by itself about the face from a face zip, see [WffFaceCover]. */
class WffFaceCoverTest {

    private val store = PreferencesInMemory()
    private val sut = WffFaceCover(store.preferences)

    private fun designSentAfter() = store.values[WffFaceCoverKey.DesignSentAfter] == true

    /** A watch without Watch Face Push that keeps the face "D2" and says [shown] about it. */
    private fun watchSays(shown: Boolean?) = sut.watchReported(EventData.WatchFacePushStatus(supported = false, apiLevel = 34, designName = "D2", designShown = shown))

    @Test
    fun `a design sent by the phone counts until the face is chosen again`() {
        assertThat(designSentAfter()).isFalse()

        sut.designSent()
        assertThat(designSentAfter()).isTrue()

        // The face zip is sent again, or the face is chosen on the phone
        sut.faceChosen()
        assertThat(designSentAfter()).isFalse()
    }

    @Test
    fun `a watch that never says, or only ever says shown, changes nothing`() {
        sut.designSent()

        // An older watch app, or a face that was not on the screen when the app was updated
        watchSays(null)
        assertThat(designSentAfter()).isTrue()
        // A watch whose system never said that it let go of the face: an old state
        watchSays(true)
        watchSays(true)
        assertThat(designSentAfter()).isTrue()
    }

    @Test
    fun `the face counts again when the watch says it came back on the screen`() {
        sut.designSent()
        // The Custom watchface is on the screen
        watchSays(false)
        assertThat(designSentAfter()).isTrue()

        // The wearer picked the face from the zip on the watch
        watchSays(true)
        assertThat(designSentAfter()).isFalse()

        // Only once: the next design sent counts again, whatever the watch keeps saying
        sut.designSent()
        watchSays(true)
        assertThat(designSentAfter()).isTrue()
    }

    @Test
    fun `a report that changes nothing writes nothing`() {
        watchSays(false)
        val writes = store.writes

        watchSays(false)
        watchSays(null)
        sut.faceChosen()

        assertThat(store.writes).isEqualTo(writes)
    }
}
