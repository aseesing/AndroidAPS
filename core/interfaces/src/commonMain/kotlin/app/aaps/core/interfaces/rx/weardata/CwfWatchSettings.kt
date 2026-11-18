package app.aaps.core.interfaces.rx.weardata

import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.keys.interfaces.TextRef

/**
 * One on/off setting of the Custom watch face menu on the watch that the phone can set too, see
 * [EventData.CwfSettingsSync].
 *
 * @param key the name of the setting in the watch's preferences and in the messages
 * @param label the label the phone shows. For a setting a design can carry this is the label of its
 *   [metadataKey], the same one the watch shows.
 * @param default the value when nothing is stored, as the watch face uses it
 * @param metadataKey the CWF metadata entry with which a design can set this setting, or null. With
 *   the authorization in the phone's wear settings the design then decides.
 */
data class CwfWatchSetting(
    val key: String,
    val label: TextRef,
    val default: Boolean,
    val metadataKey: CwfMetadataKey? = null
)

/**
 * The settings of the Custom watch face that the phone can set too. One table for the phone and the
 * watch: a test of the watch app checks it against the watch face menu (keys, defaults, labels).
 *
 * Left out: the complication slots (a slot needs its data source picked in the system editor on the
 * watch) and the simplified view (it depends on the watch being charged or not).
 */
object CwfWatchSettings {

    /** How the face looks, in the order of the watch face menu. */
    val look: List<CwfWatchSetting> = listOf(
        CwfWatchSetting("show_second", CwfMetadataKey.CWF_PREF_WATCH_SHOW_SECONDS.label, true, CwfMetadataKey.CWF_PREF_WATCH_SHOW_SECONDS),
        CwfWatchSetting("show_week_number", CwfMetadataKey.CWF_PREF_WATCH_SHOW_WEEK_NUMBER.label, false, CwfMetadataKey.CWF_PREF_WATCH_SHOW_WEEK_NUMBER),
        CwfWatchSetting("dark", InterfacesStrings.cwf_setting_dark, true),
        CwfWatchSetting("match_divider", InterfacesStrings.cwf_setting_matching_divider, false),
        CwfWatchSetting("show_date", CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE.label, false, CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE),
        CwfWatchSetting("switch_external", InterfacesStrings.cwf_setting_switch_external, false),
        CwfWatchSetting("include_external", InterfacesStrings.cwf_setting_include_external, false)
    )

    /** Which values the face shows. All of them a design can carry in its metadata. */
    val shownValues: List<CwfWatchSetting> = listOf(
        shown("showBG", CwfMetadataKey.CWF_PREF_WATCH_SHOW_BG, true),
        shown("show_direction", CwfMetadataKey.CWF_PREF_WATCH_SHOW_DIRECTION, true),
        shown("showAgo", CwfMetadataKey.CWF_PREF_WATCH_SHOW_AGO, true),
        shown("showDelta", CwfMetadataKey.CWF_PREF_WATCH_SHOW_DELTA, true),
        shown("showAvgDelta", CwfMetadataKey.CWF_PREF_WATCH_SHOW_AVG_DELTA, true),
        shown("showDetailedDelta", CwfMetadataKey.CWF_PREF_WATCH_SHOW_DETAILED_DELTA, false),
        shown("show_iob", CwfMetadataKey.CWF_PREF_WATCH_SHOW_IOB, true),
        shown("show_detailed_iob", CwfMetadataKey.CWF_PREF_WATCH_SHOW_DETAILED_IOB, false),
        shown("show_cob", CwfMetadataKey.CWF_PREF_WATCH_SHOW_COB, true),
        shown("showBGI", CwfMetadataKey.CWF_PREF_WATCH_SHOW_BGI, false),
        shown("show_temp_basal", CwfMetadataKey.CWF_PREF_WATCH_SHOW_TEMP_BASAL, true),
        shown("show_temp_target", CwfMetadataKey.CWF_PREF_WATCH_SHOW_TEMP_TARGET, true),
        shown("show_reservoir_level", CwfMetadataKey.CWF_PREF_WATCH_SHOW_RESERVOIR_LEVEL, true),
        shown("showExternalStatus", CwfMetadataKey.CWF_PREF_WATCH_SHOW_LOOP_STATUS, true),
        shown("show_uploader_battery", CwfMetadataKey.CWF_PREF_WATCH_SHOW_UPLOADER_BATTERY, true),
        shown("show_rig_battery", CwfMetadataKey.CWF_PREF_WATCH_SHOW_RIG_BATTERY, false)
    )

    val all: List<CwfWatchSetting> = look + shownValues

    private val byKey: Map<String, CwfWatchSetting> = all.associateBy { it.key }

    /** The setting with the name [key], or null when the phone may not set it. */
    fun of(key: String): CwfWatchSetting? = byKey[key]

    /** True when [key] is one of these settings and [value] is a value it can have. */
    fun isAllowed(key: String, value: String): Boolean = key in byKey && (value == "true" || value == "false")

    private fun shown(key: String, metadataKey: CwfMetadataKey, default: Boolean) = CwfWatchSetting(key, metadataKey.label, default, metadataKey)
}
