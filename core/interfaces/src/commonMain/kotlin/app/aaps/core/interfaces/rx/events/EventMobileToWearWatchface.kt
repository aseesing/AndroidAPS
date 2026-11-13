package app.aaps.core.interfaces.rx.events

/**
 * A Custom watch face zip for the watch.
 *
 * @param night private patch: true for the night face (see `CwfNight`), false for the main face,
 *   which is also the day face when a night face is set
 */
data class EventMobileToWearWatchface(val payload: ByteArray, val night: Boolean = false) : Event()