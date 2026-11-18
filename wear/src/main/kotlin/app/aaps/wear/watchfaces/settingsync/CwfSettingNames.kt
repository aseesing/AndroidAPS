package app.aaps.wear.watchfaces.settingsync

import android.content.res.Resources
import app.aaps.core.interfaces.rx.weardata.CwfWatchSetting
import app.aaps.core.interfaces.rx.weardata.CwfWatchSettings
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.watchfaces.CustomWatchface

/**
 * The settings of [CwfWatchSettings] as the watch stores them: each name of the shared table matched
 * to the preference of the Custom watch face with that name.
 *
 * `CwfSettingsSyncWearTest` checks the table against the watch face menu: every setting is there,
 * with the same default.
 */
class CwfSettingNames(resources: Resources) {

    /** Stored name -> preference key, for every on/off preference of the Custom watch face. */
    private val preferenceKeys: Map<String, Int> = CustomWatchface.onOffPreferences().associateBy { resources.getString(it) }

    /** Stored name -> setting, in the order of [CwfWatchSettings.all]. Only the ones the watch has. */
    val settings: Map<String, CwfWatchSetting> = CwfWatchSettings.all.filter { it.key in preferenceKeys }.associateBy { it.key }

    /** The value of [setting] in [sp], as text. */
    fun read(sp: SP, setting: CwfWatchSetting): String = sp.getBoolean(preferenceKeys.getValue(setting.key), setting.default).toString()

    /** Stores [value] for [setting] in [sp]. [value] must be allowed, see [CwfWatchSettings.isAllowed]. */
    fun write(sp: SP, setting: CwfWatchSetting, value: String) = sp.putBoolean(preferenceKeys.getValue(setting.key), value.toBooleanStrict())
}
