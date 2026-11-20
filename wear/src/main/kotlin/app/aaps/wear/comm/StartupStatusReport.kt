// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the watch status (with the Android API level) to the phone once after the watch app starts.
 *
 * The status that `WatchFacePushHelper.syncOnStartup` sends at the start of the app does not reach
 * the phone. It goes on the bus before [DataLayerListenerServiceWear] listens to the bus, and before
 * the service knows the phone node. Without a node the service drops the message. Seen on a Wear OS 5
 * watch after an app update: the phone learned the API level only when it pinged the watch.
 *
 * The service calls [onPhoneNode] each time it may have learned the phone node. The first call with a
 * node sends the status. All later calls do nothing, so this is not a status per sync: the syncs use
 * `WatchFacePushHelper.reportStatusIfChanged`.
 *
 * @param report sends the status, normally `WatchFacePushHelper.reportStatus`
 */
class StartupStatusReport(
    private val scope: CoroutineScope,
    private val aapsLogger: AAPSLogger,
    private val report: suspend () -> Unit
) {

    private val sent = AtomicBoolean(false)

    /** The service knows [nodeId] as the phone now, or still knows no phone when it is null. */
    fun onPhoneNode(nodeId: String?) {
        if (nodeId == null || !sent.compareAndSet(false, true)) return
        scope.launch {
            try {
                aapsLogger.debug(LTag.WEAR, "Status to the phone after the start of the app")
                report()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never take the service down for a status message: the phone's next ping asks again
                aapsLogger.error(LTag.WEAR, "The status after the start of the app was not sent", e)
            }
        }
    }
}
