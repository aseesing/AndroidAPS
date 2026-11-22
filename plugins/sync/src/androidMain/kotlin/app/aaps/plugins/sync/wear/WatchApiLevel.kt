// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's Android API level from its last answer to a ping, or 0 when unknown, for [WearPlugin].
 * A second source next to the status of Watch Face Push: an older watch app reports a status without
 * the level, but every watch app answers a ping with it. The phone needs the level to pick the Watch
 * Face Format version of a face zip, see [WffFaceZip].
 */
class WatchApiLevel {

    private val _level = MutableStateFlow(0)
    val level: StateFlow<Int> = _level.asStateFlow()

    /** Keeps the API level from the watch's answer to a ping; 0 says nothing and changes nothing */
    fun update(apiLevel: Int) {
        if (apiLevel > 0) _level.value = apiLevel
    }

    /** The watch disconnected: the level is not known any more, like the status */
    fun clear() {
        _level.value = 0
    }
}
