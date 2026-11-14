// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

/**
 * The time the loop last enacted a temp basal, kept across loop runs, for the watch.
 *
 * The loop keeps the enact time of its last run only. Each run first sets it to 0, and sets the real time
 * once the pump has confirmed the temp basal: with a pod over Bluetooth that takes seconds. A status sent
 * to the watch in that moment carried "no time", and the watch face showed "LOOP --" for minutes, while
 * the loop was fine. This remembers the newest enact time seen, so a run in progress does not hide the
 * one before it.
 *
 * A loop that stops enacting is still seen: the time is not moved forward, so its age grows and the watch
 * warns once it is old.
 *
 * Read and written from the one coroutine that sends the status to the watch.
 */
class LastLoopEnact {

    private var newest = NONE

    /**
     * Takes the enact time [current] of the loop's last run (0 or less: that run has enacted nothing, or
     * not yet) and gives the newest enact time seen so far, or [NONE] when there was none since the app
     * started.
     */
    fun remember(current: Long): Long {
        // Only a real time: 0 means "nothing enacted", and stored it would read as a loop run in 1970
        if (current > 0L && current > newest) newest = current
        return newest
    }

    companion object {

        /** No enact seen: what the watch reads as "no loop time". */
        const val NONE = -1L
    }
}
