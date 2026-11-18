package app.aaps.core.interfaces.rx.weardata

import kotlinx.serialization.Serializable

/**
 * One setting of the Custom watch face, as the phone and the watch keep it.
 *
 * @param value the stored value as text: `true` or `false` for an on/off setting
 * @param changedAt when the user last changed it, in ms. 0 when that is not known (a value the watch
 *   had before the phone ever saw it). The newer change wins, see [CwfSettingsMerge].
 */
@Serializable
data class CwfSettingValue(val value: String, val changedAt: Long)

/**
 * The rule that keeps the phone and the watch on the same settings of the Custom watch face (see
 * [EventData.CwfSettingsSync]). Pure functions only: the same code runs on both sides.
 *
 * - Per setting, the newer change wins ([wins]). Equal times with different values (rare) are
 *   decided by the value, so both sides pick the same one.
 * - When neither side knows the time (both 0), the value of the watch wins, on both sides. The
 *   settings really live on the watch: a new watch, or a new install of the watch app, keeps its own
 *   values and is not switched by old values the phone learned from another watch.
 * - A value taken from the other side keeps its time. It is not a new change, so it is never sent
 *   back as one: no echo.
 * - When the receiver has a newer value, or one the sender does not have, it answers with its full
 *   set. After the answer both sides are equal, and an answer is not answered.
 */
object CwfSettingsMerge {

    /**
     * @param merged all settings after the merge
     * @param taken the settings taken from the other side, to store and use
     * @param answer true when the other side should get [merged], because it is behind
     */
    data class Result(
        val merged: Map<String, CwfSettingValue>,
        val taken: Map<String, CwfSettingValue>,
        val answer: Boolean
    )

    /**
     * True when [a] replaces [b].
     *
     * @param aIsWatch true when [a] is the value of the watch and [b] the value of the phone
     */
    fun wins(a: CwfSettingValue, b: CwfSettingValue, aIsWatch: Boolean): Boolean = when {
        a.changedAt != b.changedAt -> a.changedAt > b.changedAt
        a.value == b.value         -> false
        // Neither side knows when it changed: the watch has the value it really uses
        a.changedAt == 0L          -> aIsWatch
        else                       -> a.value > b.value
    }

    /**
     * Merges [remote], the full set of the other side, into [local].
     *
     * @param localIsWatch true on the watch, false on the phone. When neither side knows when a setting
     *   changed (both times 0), the value of the watch wins: a new watch, or a new install of the watch
     *   app, keeps its own values and the phone takes them.
     * @param allowed whether a key and value can be used. Others are left out, on both sides, so an
     *   unknown or broken value is never stored and never starts an answer.
     */
    fun merge(
        local: Map<String, CwfSettingValue>,
        remote: Map<String, CwfSettingValue>,
        localIsWatch: Boolean,
        allowed: (key: String, value: String) -> Boolean
    ): Result {
        val usableRemote = remote.filter { (key, setting) -> allowed(key, setting.value) }
        val taken = usableRemote.filter { (key, setting) -> local[key]?.let { wins(setting, it, aIsWatch = !localIsWatch) } ?: true }
        val answer = local.any { (key, setting) ->
            if (!allowed(key, setting.value)) return@any false
            val theirs = usableRemote[key]
            theirs == null || wins(setting, theirs, aIsWatch = localIsWatch)
        }
        return Result(merged = local + taken, taken = taken, answer = answer)
    }

    /**
     * The time to give a change the user made now, to a setting last changed at [previous]. Never
     * older than [previous], so a change also wins when the clocks of phone and watch differ a little.
     */
    fun changeTime(now: Long, previous: CwfSettingValue?): Long =
        maxOf(now, (previous?.changedAt ?: 0L) + 1)
}
