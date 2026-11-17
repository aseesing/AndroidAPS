package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Leaves out a message the watch already has, with exactly the same content.
 *
 * A resend is about ten messages, and every one of them wakes the watch: the radio, the CPU and the
 * app. Most of them (the preferences, the quick wizard, the actions, the scenes) did not change
 * since the previous resend a minute earlier. In the later resends of a BG cycle the graph did not
 * change either.
 *
 * Only these kinds are filtered, see `keyOf`: the graph, the treatments, the preferences, the quick
 * wizard, the actions, the scenes and the active scene. Each one is a full state that replaces the one
 * on the watch, so the same content again changes nothing there. Never filtered: the BG (`SingleBg`,
 * a small message, and sending it again repairs a value that was handed over but never arrived), the
 * status, the running modes, alarms, bolus progress, confirmations and all replies.
 *
 * It never makes anything later: what changed goes out at once. It does not change when a resend
 * happens either.
 *
 * It is used in one place only, through [FilteredWearSender] in [DataLayerListenerServiceMobile]: one
 * message after the other, right before the message is given to the Wear Data Layer. So "sent" here
 * means handed over, in the order the watch gets it. Everything that listens to `EventMobileToWear`
 * before that (the AAPSClient broadcast, for example) still gets every message.
 *
 * The filter must never keep back data the watch does not have. So everything goes out again:
 * - after [reset]. It is called when a resend is asked for explicitly (the watch app starts, the
 *   watch face starts, the user asks for a sync on the watch or on the phone, a watch connects or
 *   changes), and when a message to the watch could not be sent.
 * - when the same message is [MAX_AGE_MS] old, also without a change. This repairs a message that was
 *   lost on the way after the phone handed it over.
 */
@SingleIn(AppScope::class)
@Inject
class UnchangedToWearFilter {

    private val lastSent = HashMap<String, Pair<String, Long>>()

    /**
     * The serialized [event] when it should be sent at [now], then it is noted as sent. Null when the
     * watch already has it. The message is serialized once here, so the caller sends this text.
     */
    fun messageToSend(event: EventData, now: Long): String? {
        val message = event.serialize()
        val key = keyOf(event) ?: return message
        val content = contentOf(event, message)
        synchronized(lastSent) {
            val last = lastSent[key]
            if (last != null && last.first == content && now - last.second < MAX_AGE_MS) return null
            lastSent[key] = content to now
        }
        return message
    }

    /** Forgets what was sent, so everything goes out again. */
    fun reset() = synchronized(lastSent) { lastSent.clear() }

    private fun keyOf(event: EventData): String? = when (event) {
        is EventData.GraphData        -> "GraphData"
        is EventData.TreatmentData    -> "TreatmentData"
        is EventData.Preferences      -> "Preferences"
        is EventData.QuickWizard      -> "QuickWizard"
        is EventData.UserAction       -> "UserAction"
        is EventData.SceneList        -> "SceneList"
        is EventData.ActiveSceneState -> "ActiveSceneState"
        // Not SingleBg: glucose is never held back. Not Status: the watch refreshes its complications
        // when a Status arrives. Not RunningModeList: it is also the answer to the RunningModeRequest of
        // the watch, and an answer must always come.
        else                          -> null
    }

    /** The watch never reads the time the preferences were sent, so that time does not make them different. */
    private fun contentOf(event: EventData, message: String): String = when (event) {
        is EventData.Preferences -> event.copy(timeStamp = 0L).serialize()
        else                     -> message
    }

    companion object {

        /**
         * The longest time the same content is held back: 10 minutes. Then it goes out again, also
         * without a change, so a message that was lost after the phone handed it over is repaired within
         * two BG cycles. In that time the BG, the status and every changed state still go out.
         */
        const val MAX_AGE_MS = 10 * 60_000L
    }
}
