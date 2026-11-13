// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.core.interfaces.rx.weardata

import kotlinx.serialization.Serializable

/**
 * One setting of the Custom watch face, as the phone and the watch keep it.
 *
 * @param value the stored value as text: `true` / `false` for an on/off setting, else the stored string
 * @param changedAt when the user last changed it, in ms. 0 when that is not known (a value the watch
 *   had before the phone ever saw it). The newer change wins, see [CwfSettingsMerge].
 */
@Serializable
data class CwfSettingValue(val value: String, val changedAt: Long)

/**
 * Phone <-> Watch: the settings of the Custom watch face that the user can change on both sides
 * (which values to show, seconds, wake-up animation, ...). Always the full set of one side.
 *
 * A subclass of [EventData] in its own file, so `EventData.kt` stays as upstream has it. An older
 * app that does not know this message decodes it as [EventData.Error] and ignores it.
 *
 * @param settings stored key on the watch -> value
 * @param isAnswer true when this is the answer to a message of the other side. An answer is never
 *   answered, so two messages are the most one exchange can cause.
 */
@Serializable
data class CwfSettingsSync(
    val settings: Map<String, CwfSettingValue>,
    val isAnswer: Boolean = false
) : EventData()

/**
 * The rule that keeps the phone and the watch on the same settings. Pure functions only, the same
 * code on both sides.
 *
 * - Per setting, the newer change wins ([wins]). Equal times with different values (rare) are
 *   decided by the value, so both sides pick the same one.
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

    /** True when [a] replaces [b]. */
    fun wins(a: CwfSettingValue, b: CwfSettingValue): Boolean =
        a.changedAt > b.changedAt || (a.changedAt == b.changedAt && a.value > b.value)

    /**
     * Merges [remote], the full set of the other side, into [local].
     *
     * @param allowed whether a key and value can be used. Others are left out, on both sides, so an
     *   unknown or broken value is never stored and never starts an answer.
     */
    fun merge(
        local: Map<String, CwfSettingValue>,
        remote: Map<String, CwfSettingValue>,
        allowed: (key: String, value: String) -> Boolean
    ): Result {
        val usableRemote = remote.filter { (key, setting) -> allowed(key, setting.value) }
        val taken = usableRemote.filter { (key, setting) -> local[key]?.let { wins(setting, it) } ?: true }
        val answer = local.any { (key, setting) ->
            if (!allowed(key, setting.value)) return@any false
            val theirs = usableRemote[key]
            theirs == null || wins(setting, theirs)
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
