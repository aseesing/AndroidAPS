// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.events.EventMobileToWearWffFace
import com.google.android.gms.wearable.ChannelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Sends a Watch Face Format face zip to the watch, for `DataLayerListenerServiceMobile`. The zip goes
 * on a channel: it is too large for a message. The watch reads to the end of the stream and then
 * closes the channel.
 *
 * @param channelClient the channel client of the Wear Data Layer
 * @param path the path of the channel, `path_wff_face`
 */
class WffFaceSender(private val channelClient: ChannelClient, private val path: String, private val aapsLogger: AAPSLogger) {

    /**
     * Sends the zip of [event] to the watch [nodeId] and tells [event] how it went: the Wear screen
     * waits for this answer before it says "sent". The answer is true when the whole zip went out
     * without an error, false when there is no watch or the channel failed (for example when the watch
     * closed it before the end).
     */
    suspend fun send(nodeId: String?, event: EventMobileToWearWffFace) {
        var sent = false
        try {
            sent = withContext(Dispatchers.IO) { sendOnChannel(nodeId, event.payload) }
        } finally {
            event.sent.complete(sent)
        }
    }

    private suspend fun sendOnChannel(nodeId: String?, data: ByteArray): Boolean {
        nodeId ?: run {
            aapsLogger.debug(LTag.WEAR, "sendOnChannel: $path no watch")
            return false
        }
        aapsLogger.debug(LTag.WEAR, "sendOnChannel: $path ${data.size}")
        return try {
            val channel = channelClient.openChannel(nodeId, path).await()
            channelClient.getOutputStream(channel).await().use { it.write(data) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "sendOnChannel: $path failed", e)
            false
        }
    }
}
