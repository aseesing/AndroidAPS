package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData

/** Hands one message to the watch. */
fun interface WearMessageTransport {

    /**
     * Starts sending [message]. False when there is no watch to send it to. [onFailure] is called
     * later when the send fails on the way.
     */
    fun send(message: String, onFailure: () -> Unit): Boolean
}

/**
 * Sends a message to the watch through [UnchangedToWearFilter]: the filter notes the message, the
 * [transport] sends it, and the filter is reset when the message did not go out. So a state that never
 * reached the Data Layer is not held back later as "unchanged".
 *
 * Call it from one place, one message after the other, so the filter sees the real order of the sends.
 */
class FilteredWearSender(
    private val filter: UnchangedToWearFilter,
    private val transport: WearMessageTransport,
    private val now: () -> Long
) {

    fun send(event: EventData) {
        val message = filter.messageToSend(event, now()) ?: return
        val started = try {
            transport.send(message) { filter.reset() }
        } catch (e: Exception) {
            filter.reset()
            throw e
        }
        if (!started) filter.reset()
    }
}
