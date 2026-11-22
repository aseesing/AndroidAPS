// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.core.interfaces.rx.events

import kotlinx.coroutines.CompletableDeferred

/**
 * A Watch Face Format face zip for the watch, already cut down to what that watch needs.
 *
 * @param sent the sender completes it with true when the whole zip went out without an error, and
 * with false when it did not
 */
class EventMobileToWearWffFace(val payload: ByteArray, val sent: CompletableDeferred<Boolean> = CompletableDeferred()) : Event()
