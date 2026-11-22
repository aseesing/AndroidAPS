// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventMobileToWearWffFace
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.PushedWatchfaceId
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.SyncStrings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Sends a Watch Face Format face zip to the watch, for the Wear screen (`WearViewModel`): it asks the
 * watch for its Android API level, cuts the zip down to the face that watch can run ([WffFaceZip]),
 * sends it and waits until it went out.
 */
class WffFaceToWatch(
    private val wearPlugin: WearPlugin,
    private val rxBus: RxBus,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    private val config: Config
) {

    /** How a send ended: the [message] for the wearer, and whether the zip was [sent] to the watch. */
    class Outcome(val message: String, val sent: Boolean)

    /** How long a face zip waits for the watch to tell its Android API level after a ping */
    var watchVersionWait: Duration = 8.seconds

    /** How long a face zip waits for the phone to finish sending it to the watch */
    var faceSendWait: Duration = 2.minutes

    /** Sends the face zip [bytes] to the watch, cut down to the face that watch can run. */
    suspend fun send(bytes: ByteArray): Outcome {
        val apiLevel = watchApiLevel()
        if (apiLevel == 0) return Outcome(rh.gs(SyncStrings.wear_wff_face_watch_unknown), sent = false)
        return when (val result = WffFaceZip.forWatch(bytes, config.APPLICATION_ID, apiLevel)) {
            is WffFaceZip.Result.ForWatch    -> {
                val event = EventMobileToWearWffFace(result.zip)
                rxBus.send(event)
                // "Sent" only when it really went out: the watch can close the channel before the end
                val sent = withTimeoutOrNull(faceSendWait) { event.sent.await() } == true
                // The watch puts the face in the app's one slot, so the choice follows
                if (sent && result.usesPush) preferences.put(StringKey.WearPushedWatchface, PushedWatchfaceId.DESIGN)
                Outcome(
                    rh.gs(
                        when {
                            !sent           -> SyncStrings.wear_wff_face_not_sent
                            result.usesPush -> SyncStrings.wear_wff_face_sent
                            else            -> SyncStrings.wear_wff_face_sent_confirm
                        }
                    ),
                    sent
                )
            }

            is WffFaceZip.Result.WatchTooOld -> Outcome(rh.gs(SyncStrings.wear_wff_face_watch_too_old, wearOsVersion(result.minApiLevel)), sent = false)
            WffFaceZip.Result.OtherApp       -> Outcome(rh.gs(SyncStrings.wear_wff_face_other_app), sent = false)
            WffFaceZip.Result.NoPushToken    -> Outcome(rh.gs(SyncStrings.wear_wff_face_no_push_token), sent = false)
            WffFaceZip.Result.Invalid        -> Outcome(rh.gs(SyncStrings.wear_not_a_watchface_file), sent = false)
        }
    }

    /**
     * The watch's Android API level, or 0 when the watch does not tell it. The status of Watch Face
     * Push carries it, and so does the watch's answer to a ping. When neither has it yet (an old
     * status from an older watch app, or none at all), the phone pings the watch and waits a little.
     */
    private suspend fun watchApiLevel(): Int {
        knownWatchApiLevel(wearPlugin.watchFacePushStatus.value, wearPlugin.watchApiLevel.value).takeIf { it > 0 }?.let { return it }
        rxBus.send(EventMobileToWear(EventData.ActionPing(dateUtil.now())))
        return withTimeoutOrNull(watchVersionWait) {
            combine(wearPlugin.watchFacePushStatus, wearPlugin.watchApiLevel, ::knownWatchApiLevel).first { it > 0 }
        } ?: 0
    }

    private fun knownWatchApiLevel(status: EventData.WatchFacePushStatus?, pongApiLevel: Int): Int =
        status?.apiLevel?.takeIf { it > 0 } ?: pongApiLevel

    /** The Wear OS version that comes with Android API level [apiLevel], as people know it */
    private fun wearOsVersion(apiLevel: Int): String = when {
        apiLevel >= 36 -> "6"
        apiLevel >= 35 -> "5.1"
        else           -> "5"
    }
}
