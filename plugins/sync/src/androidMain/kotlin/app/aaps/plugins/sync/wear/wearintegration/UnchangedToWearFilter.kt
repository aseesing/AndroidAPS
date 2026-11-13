// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData

/**
 * Leaves out a state message the watch already has.
 *
 * A resend is about ten messages, and every one of them wakes the watch: the radio, the CPU and the
 * app. Most of them - the preferences, the quick wizard, the actions, the scenes - did not change
 * since the previous resend a minute earlier, and in the second resend of a BG cycle the BG and the
 * graph did not change either.
 *
 * Only state messages are filtered; everything else (alarms, bolus progress, confirmations, replies,
 * the status, the running modes) always goes out. An unchanged state message still goes out once
 * it is [MAX_AGE_MS] old, so a message lost on the way is repaired. When the watch asks for a resend
 * itself, [reset] makes everything go out again.
 */
class UnchangedToWearFilter {

    private val lastSent = HashMap<String, Pair<String, Long>>()

    /** The last value of each kind the watch face draws, sent or not. For the live preview on the phone. */
    private val seen = LinkedHashMap<String, EventData>()

    /** True when [event] should be sent at [now]; then it is noted as sent. */
    @Synchronized
    fun shouldSend(event: EventData, now: Long): Boolean {
        previewKeyOf(event)?.let { seen[it] = event }
        val key = keyOf(event) ?: return true
        val content = contentOf(event)
        val last = lastSent[key]
        if (last != null && last.first == content && now - last.second < MAX_AGE_MS) return false
        lastSent[key] = content to now
        return true
    }

    /** Forgets what was sent, so everything goes out again. */
    @Synchronized
    fun reset() = lastSent.clear()

    /**
     * The last value of each kind the watch face draws, as the phone last built it for the watch. Also
     * the ones this filter held back: the watch has those already, so they are its current values too.
     */
    @Synchronized
    fun lastSeen(): List<EventData> = seen.values.toList()

    private fun previewKeyOf(event: EventData): String? = when (event) {
        is EventData.SingleBg      -> "SingleBg${event.dataset}"
        is EventData.Status        -> "Status${event.dataset}"
        is EventData.GraphData     -> "GraphData"
        is EventData.TreatmentData -> "TreatmentData"
        is EventData.Preferences   -> "Preferences"
        else                       -> null
    }

    private fun keyOf(event: EventData): String? = when (event) {
        is EventData.SingleBg         -> "SingleBg${event.dataset}"
        is EventData.GraphData        -> "GraphData"
        is EventData.TreatmentData    -> "TreatmentData"
        is EventData.Preferences      -> "Preferences"
        is EventData.QuickWizard      -> "QuickWizard"
        is EventData.UserAction       -> "UserAction"
        is EventData.SceneList        -> "SceneList"
        is EventData.ActiveSceneState -> "ActiveSceneState"
        // Not Status: the watch refreshes its complications when a Status arrives, and stamps the time
        // it last heard from the phone. Not RunningModeList: it is also the answer to the watch's
        // RunningModeRequest, and an answer must always come.
        else                          -> null
    }

    /** The watch never reads the time the preferences were sent, so it does not make them different. */
    private fun contentOf(event: EventData): String = when (event) {
        is EventData.Preferences -> event.copy(timeStamp = 0L).serialize()
        else                     -> event.serialize()
    }

    companion object {

        /** Below the 12 minutes after which the watch shows that the phone is not heard from. */
        const val MAX_AGE_MS = 10 * 60_000L
    }
}
