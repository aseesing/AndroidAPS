package app.aaps.core.interfaces.rx.events

import kotlinx.coroutines.CompletableDeferred

/**
 * A Watch Face Format face zip for the watch, already cut down to what that watch needs.
 *
 * @param sent the sender completes it with true when the whole zip went out without an error, and
 * with false when it did not
 */
class EventMobileToWearWffFace(val payload: ByteArray, val sent: CompletableDeferred<Boolean> = CompletableDeferred()) : Event()
