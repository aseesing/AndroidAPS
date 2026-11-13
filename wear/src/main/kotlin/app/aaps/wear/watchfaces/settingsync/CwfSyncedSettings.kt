// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.settingsync

import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import app.aaps.core.interfaces.InterfacesStringIds
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.wear.R
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.animation.WakeLength

/*
 * The settings of the Custom watch face that the phone can set too, see `CwfSettingsSync`.
 *
 * Shared code: the watch uses it to send and take these settings, and the phone app builds it a second
 * time inside `:wear:cwfrender` (listed there in `wearShared`), so both sides use the same keys,
 * defaults, labels and allowed values.
 */

/** One watch setting that the phone can set too. */
sealed interface CwfSyncedSetting {

    /** The key of the watch setting. Its text is the name in the watch's preferences and in the messages. */
    @get:StringRes val key: Int

    /** The label, the same one the watch shows. */
    @get:StringRes val title: Int

    /** The value when nothing is stored, as text. */
    val defaultValue: String

    /**
     * The CWF metadata entry with which a design can set this setting, or null. With the
     * authorization in the phone's wear settings the design then decides, see `CustomWatchface.updatePref`.
     */
    val metadataKey: CwfMetadataKey?

    /** An on/off setting. Its value is `true` or `false`. */
    data class Toggle(
        @StringRes override val key: Int,
        @StringRes override val title: Int,
        val default: Boolean,
        override val metadataKey: CwfMetadataKey? = null
    ) : CwfSyncedSetting {

        override val defaultValue: String get() = default.toString()
    }

    /** A setting with a fixed list of values. */
    data class Choice(
        @StringRes override val key: Int,
        @StringRes override val title: Int,
        @ArrayRes val entries: Int,
        @ArrayRes val entryValues: Int,
        override val defaultValue: String
    ) : CwfSyncedSetting {

        override val metadataKey: CwfMetadataKey? get() = null
    }
}

/** All settings the phone can set, with the defaults the Custom watch face uses. */
object CwfSyncedSettings {

    /**
     * How the face looks: the rows of the watch's own settings menu of the Custom watch face, without
     * the complication pickers (they need the watch) and without the day / night look (the phone
     * has its own settings for those, sent in `EventData.Preferences`).
     */
    val faceLook: List<CwfSyncedSetting> = listOf(
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_SECONDS, R.string.key_show_seconds, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_WEEK_NUMBER, R.string.key_show_week_number, false),
        CwfSyncedSetting.Toggle(R.string.key_dark, R.string.pref_dark, true),
        CwfSyncedSetting.Toggle(R.string.key_match_divider, R.string.pref_matching_divider, false),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE, R.string.key_show_date, false),
        CwfSyncedSetting.Toggle(R.string.key_switch_external, R.string.pref_switch_external, false),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1, R.string.key_show_complication_1, false),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2, R.string.key_show_complication_2, false),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3, R.string.key_show_complication_3, false),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4, R.string.key_show_complication_4, false),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5, R.string.key_show_complication_5, false),
        CwfSyncedSetting.Toggle(R.string.key_include_external, R.string.pref_include_external, false),
        WakeLength.row().let { CwfSyncedSetting.Choice(it.key, it.title, it.entries, it.entryValues, it.defaultValue) }
    )

    /** Which values the face shows: the watch's "Display" settings, which a design can also carry. */
    val shownValues: List<CwfSyncedSetting.Toggle> = listOf(
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_BG, R.string.key_show_bg, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DIRECTION, R.string.key_show_direction, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_AGO, R.string.key_show_ago, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DELTA, R.string.key_show_delta, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_AVG_DELTA, R.string.key_show_avg_delta, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DETAILED_DELTA, R.string.key_show_detailed_delta, false),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_IOB, R.string.key_show_iob, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DETAILED_IOB, R.string.key_show_detailed_iob, false),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COB, R.string.key_show_cob, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_BGI, R.string.key_show_bgi, false),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_TEMP_BASAL, R.string.key_show_temp_basal, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_TEMP_TARGET, R.string.key_show_temp_target, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_RESERVOIR_LEVEL, R.string.key_show_reservoir_level, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_LOOP_STATUS, R.string.key_show_external_status, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_UPLOADER_BATTERY, R.string.key_show_uploader_battery, true),
        metadataToggle(CwfMetadataKey.CWF_PREF_WATCH_SHOW_RIG_BATTERY, R.string.key_show_rig_battery, false)
    )

    val all: List<CwfSyncedSetting> = faceLook + shownValues

    /**
     * The [faceLook] settings that the design [json] gives something to act on, as the watch's menu
     * shows them. All of them when [json] is null or cannot be read.
     */
    fun faceLookFor(json: String?): List<CwfSyncedSetting> {
        val used = CustomWatchface.settingRows(json, systemEditor = true, hasNightFace = false).map { it.key }.toSet()
        return faceLook.filter { it.key in used }
    }

    /** A toggle a design can carry: labelled with the same string the phone and the watch use for it. */
    private fun metadataToggle(metadataKey: CwfMetadataKey, @StringRes key: Int, default: Boolean): CwfSyncedSetting.Toggle =
        CwfSyncedSetting.Toggle(key, titleOf(metadataKey), default, metadataKey)

    @StringRes private fun titleOf(metadataKey: CwfMetadataKey): Int =
        (metadataKey.label as? TextRef.Named)?.let { InterfacesStringIds.idOf(it.name) }
            ?: error("No string for ${metadataKey.name}")
}

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
