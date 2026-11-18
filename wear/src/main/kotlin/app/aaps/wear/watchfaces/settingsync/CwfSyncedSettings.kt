// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.settingsync

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.wear.R
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.colour.ColourLooks
import app.aaps.wear.watchfaces.utils.WatchFaceSettingRow

/*
 * The settings of the Custom watch face that the phone can set too, see `EventData.CwfSettingsSync`.
 *
 * Shared code: the watch uses it to send and take these settings, and the phone app builds it a second
 * time inside `:wear:cwfrender` (listed there in `wearShared`), so both sides use the same keys,
 * defaults, labels and allowed values. [CwfSettingNames] gives them by their stored names.
 *
 * A personal extension of the upstream sync: the upstream table `CwfWatchSettings` has only the on/off
 * settings; this list has every row of the watch face menu, also the choices (colours, the log time
 * graph, the length of the wake-up animation, ...). The same message and the same merge rule carry them.
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
     * Watch settings the phone does not offer, though the watch menu has them: the simplified view
     * (it needs the watch), and the day / night look (the phone has its own settings for those, sent in
     * `EventData.Preferences`). The complication pickers and the type priority have no value, so they
     * are never offered.
     */
    private val NOT_SYNCED = setOf(R.string.key_simplify_ui, R.string.key_day_night_look, R.string.key_day_night_always_on)

    /**
     * How the face looks: every setting row of the watch's own Custom watch face menu, read from that
     * menu, so a new row there (like the colours of [ColourLooks]) is offered by the phone too, with no
     * change here. Those of [NOT_SYNCED] are left out. [faceLookFor] keeps the ones a design uses.
     */
    val faceLook: List<CwfSyncedSetting> = run {
        val metadataKeys = CustomWatchface.metadataToggles().associate { (metadataKey, toggle) -> toggle.key to metadataKey }
        CustomWatchface.settingRows(null, systemEditor = true, hasNightFace = false).filter { it.key !in NOT_SYNCED }.mapNotNull { row ->
            when (row) {
                is WatchFaceSettingRow.Toggle -> CwfSyncedSetting.Toggle(row.key, row.title, row.defaultValue, metadataKeys[row.key])
                is WatchFaceSettingRow.Choice -> CwfSyncedSetting.Choice(row.key, row.title, row.entries, row.entryValues, row.defaultValue)
                else                          -> null
            }
        }
    }

    /**
     * Which values the face shows: the watch's "Display" settings, which a design can also carry. Every
     * on/off setting a design can carry in its metadata ([CustomWatchface.metadataToggles]) that is not
     * already in [faceLook].
     */
    val shownValues: List<CwfSyncedSetting.Toggle> = run {
        val inFaceLook = faceLook.map { it.key }.toSet()
        CustomWatchface.metadataToggles().filter { (_, toggle) -> toggle.key !in inFaceLook && toggle.key !in NOT_SYNCED }
            .map { (metadataKey, toggle) -> CwfSyncedSetting.Toggle(toggle.key, toggle.title, toggle.defaultValue, metadataKey) }
    }

    val all: List<CwfSyncedSetting> = faceLook + shownValues

    /**
     * The [faceLook] settings that the design [json] gives something to act on, as the watch's menu
     * shows them. All of them when [json] is null or cannot be read.
     */
    fun faceLookFor(json: String?): List<CwfSyncedSetting> {
        val used = CustomWatchface.settingRows(json, systemEditor = true, hasNightFace = false).map { it.key }.toSet()
        return faceLook.filter { it.key in used }
    }
}
