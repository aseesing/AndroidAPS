// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import app.aaps.core.interfaces.rx.weardata.EventData

/**
 * The values one live preview has not given to its renderer yet: the newest of each kind.
 *
 * They are taken only at the moment they are given to the renderer (see `CwfLivePreviewViewModel.frames`).
 * Before, they were taken first and given later; a preview that closed in between lost them, and the
 * face stayed without glucose until the phone sent it again.
 *
 * Thread safe: the values come from the event bus, the renderer takes them on its own thread.
 */
internal class PreviewPendingValues {

    private val values = LinkedHashMap<String, EventData>()

    /** Counts the puts, and for each kind the count at its last put, see [mark]. */
    private var puts = 0L
    private val putAt = HashMap<String, Long>()

    /** Keeps [event] in place of an older value of its [kind]. */
    @Synchronized
    fun put(kind: String, event: EventData) {
        values.remove(kind)
        values[kind] = event
        putAt[kind] = ++puts
    }

    /** The moment of now, for [putUnlessNewer]. */
    @Synchronized
    fun mark(): Long = puts

    /**
     * Keeps [event], read at [mark], unless a value of its [kind] came after that: then that one is
     * newer. For values that take time to read, while new ones keep coming from the event bus.
     */
    @Synchronized
    fun putUnlessNewer(kind: String, event: EventData, mark: Long) {
        if ((putAt[kind] ?: 0L) <= mark) put(kind, event)
    }

    /** The waiting values, the oldest first, and forgets them. Call only where they are surely used. */
    @Synchronized
    fun take(): List<EventData> = values.values.toList().also { values.clear() }
}
