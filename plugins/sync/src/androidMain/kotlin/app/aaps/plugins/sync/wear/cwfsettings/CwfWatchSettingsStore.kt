// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.cwfsettings

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.CwfSettingsMerge
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's copy of the Custom watch face settings that the phone can set too (see
 * `CwfSettingsSync`): per setting the value and the time of its last change, by the setting's
 * stored name on the watch.
 *
 * Kept in its own preferences file, not in the AAPS settings: these are settings of the watch, the
 * watch has them too, and a value only counts together with its time.
 *
 * Holds no rule of its own beyond storing: [setByUser] for a change on the phone, [merge] for the
 * settings of the watch (see [CwfSettingsMerge]).
 */
@SingleIn(AppScope::class)
@Inject
class CwfWatchSettingsStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val state: MutableStateFlow<Map<String, CwfSettingValue>> by lazy { MutableStateFlow(load()) }

    /** All settings the phone knows, by stored name. Empty until the watch or the user gave one. */
    val values: StateFlow<Map<String, CwfSettingValue>> by lazy { state.asStateFlow() }

    /** The user set [name] to [value] on the phone at [now]. Returns all settings after the change. */
    @Synchronized
    fun setByUser(name: String, value: String, now: Long): Map<String, CwfSettingValue> {
        val previous = state.value[name]
        if (previous?.value == value) return state.value
        save(mapOf(name to CwfSettingValue(value, CwfSettingsMerge.changeTime(now, previous))))
        return state.value
    }

    /** Takes the newer settings of [remote], the full set of the watch. See [CwfSettingsMerge.merge]. */
    @Synchronized
    fun merge(remote: Map<String, CwfSettingValue>, allowed: (name: String, value: String) -> Boolean): CwfSettingsMerge.Result {
        val result = CwfSettingsMerge.merge(state.value, remote, allowed)
        if (result.taken.isNotEmpty()) save(result.taken)
        return result
    }

    private fun save(changes: Map<String, CwfSettingValue>) {
        prefs.edit().apply {
            changes.forEach { (name, setting) ->
                putString(VALUE_PREFIX + name, setting.value)
                putLong(TIME_PREFIX + name, setting.changedAt)
            }
        }.apply()
        state.value += changes
    }

    private fun load(): Map<String, CwfSettingValue> =
        prefs.all.keys.filter { it.startsWith(VALUE_PREFIX) }.associate { key ->
            val name = key.removePrefix(VALUE_PREFIX)
            name to CwfSettingValue(prefs.getString(key, null).orEmpty(), prefs.getLong(TIME_PREFIX + name, 0L))
        }

    private companion object {

        const val FILE = "cwf_watch_settings"
        const val VALUE_PREFIX = "value_"
        const val TIME_PREFIX = "time_"
    }
}
