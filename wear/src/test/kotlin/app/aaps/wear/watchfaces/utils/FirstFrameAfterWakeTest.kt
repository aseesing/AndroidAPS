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
    fun eachTimeTheFaceComesOnTheScreenIsTold() {
        var shown = 0
        wakeFrame.onShown = { shown++ }
        wakeFrame.onVisibility(true)   // the face starts on the screen
        wakeFrame.onVisibility(false)
        assertThat(shown).isEqualTo(1)
        wakeFrame.onVisibility(true)
        assertThat(shown).isEqualTo(2)
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

    /** What `BaseWatchFace.setDataFieldsAfterWakeFrame` asks for, recorded in order. */
    private val calls = mutableListOf<String>()

    private fun refresh(quickIsEnough: Boolean) = wakeFrame.refresh(
        quickIsEnough = { quickIsEnough },
        full = { calls.add("full") },
        quick = { calls.add("quick") },
        later = { calls.add("later") }
    )

    @Test
    fun outsideTheWakeFrameTheFullRefreshRunsAtOnce() {
        wakeFrame.beginFrame(leftAmbient = false, interactive = true)
        refresh(quickIsEnough = true)
        runPosted()
        assertThat(calls).containsExactly("full")
    }

    @Test
    fun inTheWakeFrameTheQuickPartRunsNowAndTheFullRefreshAfterTheFrame() {
        wakeFrame.beginFrame(leftAmbient = true, interactive = true)
        refresh(quickIsEnough = true)                  // the mode change
        refresh(quickIsEnough = true)                  // the new minute, same frame
        wakeFrame.endFrame()
        assertThat(calls).containsExactly("quick", "quick").inOrder()

        runPosted()
        assertThat(calls).containsExactly("quick", "quick", "later").inOrder()
    }

    @Test
    fun whenAValueBecameOldTheWakeFrameRefreshesFullyAtOnce() {
        // The stale marks and colours must not claim fresh data, not even for one frame
        wakeFrame.beginFrame(leftAmbient = true, interactive = true)
        refresh(quickIsEnough = false)
        wakeFrame.endFrame()
        runPosted()
        assertThat(calls).containsExactly("full")
    }

    @Test
    fun aNewEngineWakesNormallyAfterTheOldOneIsGone() {
        // The system can make a second engine on the same service. Each renderer has its own
        // instance, so the release of the old one does not touch the new one.
        val oldEngine = FirstFrameAfterWake { posted.add(it) }
        val newEngine = FirstFrameAfterWake { posted.add(it) }
        oldEngine.release()

        var refreshes = 0
        newEngine.beginFrame(leftAmbient = true, interactive = true)
        assertThat(newEngine.isActive).isTrue()
        newEngine.refreshAfterFrame { refreshes++ }
        newEngine.endFrame()
        runPosted()
        assertThat(refreshes).isEqualTo(1)
    }
}
