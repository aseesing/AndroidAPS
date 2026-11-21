// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.watchfaces.WatchFacePushHelper
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Notes whether the watch shows the Watch Face Format face from a face zip, and tells the phone when
 * that changes, so the phone's line "On the watch: ..." names the face only while it is on the screen.
 *
 * The app cannot ask the system which face is on the screen. But the system tells a complication
 * source when a face starts to use it and when it stops, and the sources in `complications/wff`
 * are made for the face from a face zip. So while one of them is in use, that face is shown. The
 * state itself is kept in [WffFace.shown].
 *
 * The system does not always say that it let go of a source. So there is a second sign, which does
 * not go through this class: a code-based face of the app that comes on the screen notes "not
 * shown" itself ([WffShownSlots.otherFaceShown]). The phone hears of that with the next sync, when
 * `WatchFacePushHelper.reportStatusIfChanged` runs.
 */
@SingleIn(AppScope::class)
@Inject
class WffFaceShown(private val wffFace: WffFace, private val watchFacePushHelper: WatchFacePushHelper, private val aapsLogger: AAPSLogger) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The system started to use the complication [instanceId] of the source [source] on a face. */
    fun activated(source: Class<*>, instanceId: Int) = note { wffFace.slotActivated(key(source, instanceId)) }

    /** The system stopped using the complication [instanceId] of the source [source]. */
    fun deactivated(source: Class<*>, instanceId: Int) = note { wffFace.slotDeactivated(key(source, instanceId)) }

    private fun key(source: Class<*>, instanceId: Int) = "${source.simpleName}#$instanceId"

    private fun note(change: () -> Unit) {
        scope.launch {
            try {
                change()
                watchFacePushHelper.reportStatusIfChanged()
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "WffFaceShown: not noted", e)
            }
        }
    }
}
