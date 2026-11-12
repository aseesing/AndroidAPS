// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FirstFrameAfterWakeTest {

    private val posted = mutableListOf<Runnable>()
    private val wakeFrame = FirstFrameAfterWake { posted.add(it) }

    private fun runPosted() {
        val tasks = posted.toList()
        posted.clear()
        tasks.forEach { it.run() }
    }

    @Test
    fun leavingAmbientMakesTheFirstInteractiveFrameTheWakeFrame() {
        wakeFrame.beginFrame(leftAmbient = true, interactive = true)
        assertThat(wakeFrame.isActive).isTrue()
        wakeFrame.endFrame()
        assertThat(wakeFrame.isActive).isFalse()

        wakeFrame.beginFrame(leftAmbient = false, interactive = true)
        assertThat(wakeFrame.isActive).isFalse()
    }

    @Test
    fun anOrdinaryFrameIsNotAWakeFrame() {
        wakeFrame.beginFrame(leftAmbient = false, interactive = true)
        assertThat(wakeFrame.isActive).isFalse()
    }

    @Test
    fun aFailedFrameIsResetByTheNextOne() {
        wakeFrame.beginFrame(leftAmbient = true, interactive = true)   // no endFrame: the frame threw
        wakeFrame.beginFrame(leftAmbient = false, interactive = true)
        assertThat(wakeFrame.isActive).isFalse()
    }

    @Test
    fun shownAfterHiddenIsAWakeOnce() {
        wakeFrame.onVisibility(true)
        wakeFrame.beginFrame(leftAmbient = false, interactive = true)
        assertThat(wakeFrame.isActive).isFalse()   // shown the first time is the start, not a wake
        wakeFrame.endFrame()

        wakeFrame.onVisibility(false)
        wakeFrame.onVisibility(true)
        wakeFrame.beginFrame(leftAmbient = false, interactive = true)
        assertThat(wakeFrame.isActive).isTrue()
        wakeFrame.endFrame()

        wakeFrame.beginFrame(leftAmbient = false, interactive = true)
        assertThat(wakeFrame.isActive).isFalse()
    }

    @Test
    fun aWakeSeenDuringAmbientWaitsForTheFirstInteractiveFrame() {
        wakeFrame.onVisibility(false)
        wakeFrame.onVisibility(true)
        wakeFrame.beginFrame(leftAmbient = false, interactive = false)
        assertThat(wakeFrame.isActive).isFalse()
        wakeFrame.endFrame()

        wakeFrame.beginFrame(leftAmbient = true, interactive = true)
        assertThat(wakeFrame.isActive).isTrue()
    }

    @Test
    fun aHeadlessOrAmbientFrameIsNeverTheWakeFrame() {
        wakeFrame.beginFrame(leftAmbient = true, interactive = false)
        assertThat(wakeFrame.isActive).isFalse()
    }

    @Test
    fun theRefreshRunsOnceAfterTheFrameHoweverOftenAsked() {
        var refreshes = 0
        wakeFrame.beginFrame(leftAmbient = true, interactive = true)
        wakeFrame.refreshAfterFrame { refreshes++ }   // the mode change
        wakeFrame.refreshAfterFrame { refreshes++ }   // the new minute, same frame
        wakeFrame.endFrame()
        assertThat(refreshes).isEqualTo(0)            // not inside the frame

        runPosted()
        assertThat(refreshes).isEqualTo(1)

        wakeFrame.refreshAfterFrame { refreshes++ }   // a later wake may ask again
        runPosted()
        assertThat(refreshes).isEqualTo(2)
    }

    @Test
    fun noRefreshAfterRelease() {
        var refreshes = 0
        wakeFrame.refreshAfterFrame { refreshes++ }
        wakeFrame.release()
        runPosted()
        wakeFrame.refreshAfterFrame { refreshes++ }
        runPosted()
        assertThat(refreshes).isEqualTo(0)
    }
}
