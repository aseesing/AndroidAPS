package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData

/**
 * The last value of each kind that a watch face draws, as the phone last sent it to the watch. For
 * the live preview of the watch face on the phone, which draws what the watch has now.
 */
class LastWatchFaceValues {

    private val last = LinkedHashMap<String, EventData>()

    /** Notes [event] when a watch face draws it, in place of an older value of the same kind. */
    @Synchronized
    fun note(event: EventData) {
        kindOf(event)?.let { kind ->
            last.remove(kind)
            last[kind] = event
        }
    }

    /** The last value of each kind, the oldest first. */
    @Synchronized
    fun all(): List<EventData> = last.values.toList()

    /** The last value of each kind, the oldest first, and forgets them: for a reader that only wants what is new. */
    @Synchronized
    fun takeAll(): List<EventData> = last.values.toList().also { last.clear() }

    companion object {

        /**
         * The kind of [event] for a watch face, or null when a watch face does not draw it. One value
         * per kind is enough: a newer one replaces all of an older one.
         */
        fun kindOf(event: EventData): String? = when (event) {
            is EventData.SingleBg      -> "SingleBg${event.dataset}"
            is EventData.Status        -> "Status${event.dataset}"
            is EventData.GraphData     -> "GraphData"
            is EventData.TreatmentData -> "TreatmentData"
            is EventData.Preferences   -> "Preferences"
            else                       -> null
        }
    }
}
