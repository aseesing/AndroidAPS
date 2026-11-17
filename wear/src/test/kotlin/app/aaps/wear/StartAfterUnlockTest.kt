// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The app start waits for the unlock of the watch, and then runs once */
internal class StartAfterUnlockTest {

    private var unlocked = false
    private var starts = 0
    private var listeners = 0
    private var stops = 0
    private var onUnlock: (() -> Unit)? = null

    /** Set to make the unlock come between the first check and the listening. */
    private var unlockWhileListening = false

    private val sut = StartAfterUnlock(
        isUnlocked = { unlocked },
        listenForUnlock = { callback ->
            listeners++
            onUnlock = callback
            if (unlockWhileListening) unlocked = true
            ({ stops++ })
        }
    )

    private fun start() {
        starts++
    }

    @Test
    fun `an unlocked watch starts at once`() {
        unlocked = true

        assertThat(sut.postpones(::start)).isFalse()

        // The caller goes on with its own start code; nothing is started or listened for here
        assertThat(starts).isEqualTo(0)
        assertThat(listeners).isEqualTo(0)
    }

    @Test
    fun `a locked watch does not start`() {
        assertThat(sut.postpones(::start)).isTrue()

        assertThat(starts).isEqualTo(0)
        assertThat(listeners).isEqualTo(1)
        assertThat(stops).isEqualTo(0)
    }

    @Test
    fun `the unlock starts the app and ends the listening`() {
        sut.postpones(::start)

        unlocked = true
        onUnlock?.invoke()

        assertThat(starts).isEqualTo(1)
        assertThat(stops).isEqualTo(1)
    }

    @Test
    fun `a second unlock message does not start again`() {
        sut.postpones(::start)

        unlocked = true
        onUnlock?.invoke()
        onUnlock?.invoke()

        assertThat(starts).isEqualTo(1)
        assertThat(stops).isEqualTo(1)
    }

    @Test
    fun `an unlock between the check and the listening still starts once`() {
        unlockWhileListening = true

        assertThat(sut.postpones(::start)).isTrue()

        assertThat(starts).isEqualTo(1)
        assertThat(stops).isEqualTo(1)

        // The message that was already on its way changes nothing
        onUnlock?.invoke()
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun `the start that runs after the unlock is not postponed again`() {
        // As WearApp does: the start code asks again when it runs after the unlock
        var postponedAgain: Boolean? = null
        sut.postpones { postponedAgain = sut.postpones(::start) }

        unlocked = true
        onUnlock?.invoke()

        assertThat(postponedAgain).isFalse()
        assertThat(listeners).isEqualTo(1)
    }
}
