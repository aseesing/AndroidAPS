// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
// Upstream has a file with this name that knows only the on/off settings of its table
// `CwfWatchSettings`. This one knows every setting of the watch face menu, also the choices
// (see CwfSyncedSettings), and is used in its place on both sides.
package app.aaps.wear.watchfaces.settingsync

import android.content.res.Resources
import app.aaps.core.interfaces.sharedPreferences.SP

/**
 * The settings of [CwfSyncedSettings] by their stored names, read from [resources]. Reads, writes and
 * checks values the same way on the watch and on the phone.
 */
class CwfSettingNames(resources: Resources) {

    /** Stored name -> setting, in the order of [CwfSyncedSettings.all]. */
    val settings: Map<String, CwfSyncedSetting> = CwfSyncedSettings.all.associateBy { resources.getString(it.key) }

    private val names: Map<CwfSyncedSetting, String> = settings.entries.associate { (name, setting) -> setting to name }

    private val allowedChoices: Map<String, Set<String>> = settings.mapNotNull { (name, setting) ->
        (setting as? CwfSyncedSetting.Choice)?.let { name to resources.getStringArray(it.entryValues).toSet() }
    }.toMap()

    /** The stored name of [setting]. */
    fun nameOf(setting: CwfSyncedSetting): String = names.getValue(setting)

    /** True when [name] is one of these settings and [value] is a value it can have. */
    fun isAllowed(name: String, value: String): Boolean =
        when (settings[name]) {
            is CwfSyncedSetting.Toggle -> value == "true" || value == "false"
            is CwfSyncedSetting.Choice -> value in allowedChoices[name].orEmpty()
            null                       -> false
        }

    /** The value of [setting] in [sp], as text. */
    fun read(sp: SP, setting: CwfSyncedSetting): String =
        when (setting) {
            is CwfSyncedSetting.Toggle -> sp.getBoolean(setting.key, setting.default).toString()
            is CwfSyncedSetting.Choice -> sp.getString(setting.key, setting.defaultValue)
        }

    /** Stores [value] for [setting] in [sp], with the type the watch uses. [value] must be allowed. */
    fun write(sp: SP, setting: CwfSyncedSetting, value: String) {
        when (setting) {
            is CwfSyncedSetting.Toggle -> sp.putBoolean(setting.key, value.toBooleanStrict())
            is CwfSyncedSetting.Choice -> sp.putString(setting.key, value)
        }
    }
}
