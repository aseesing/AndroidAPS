// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfMetadataMap
import app.aaps.core.interfaces.rx.weardata.CwfNight
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.keys.WearDayNightKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey

/*
 * The phone side of a day face and a night face for the Custom watch face, see `CwfNight`.
 *
 * The watch keeps a main face (also the day face) and an optional night face. The phone sends a
 * night zip on its own message path, keeps what the watch reports about its night face apart from
 * the main face, and sends the two day / night settings with the other preferences.
 */

/**
 * The night face the watch reported, or null when the phone asked it to forget its night face.
 * Sent by `DataHandlerMobile`, kept by `WearPlugin`.
 */
data class EventWearNightWatchface(val customWatchfaceData: CwfData?) : Event()

/** The message path for a watch face zip: [dataPath] for the main face, [dataPath] plus [CwfNight.PATH_SUFFIX] for the night face. */
fun watchfacePath(dataPath: String, night: Boolean): String =
    if (night) dataPath + CwfNight.PATH_SUFFIX else dataPath

/**
 * What the phone stores about the night face and the two day / night settings.
 *
 * @param preferences where the remembered night face and the settings are kept
 */
class WearNightFacePreferences(private val preferences: Preferences) {

    /** [watchPreferences] plus the phone's choice for the two day / night settings of the watch. */
    fun withDayNightLooks(watchPreferences: EventData.Preferences): EventData.Preferences =
        watchPreferences.copy(
            dayNightLook = preferences.get(WearDayNightKey.DayNightLookSetting),
            alwaysOnLook = preferences.get(WearDayNightKey.AlwaysOnLookSetting)
        )

    /** Remembers [metadata] as the night face, like the main face is remembered in `StringNonKey`. */
    fun remember(metadata: CwfMetadataMap) {
        preferences.put(WearNightFaceKey.NightWatchfaceName, metadata[CwfMetadataKey.CWF_NAME] ?: "")
        preferences.put(WearNightFaceKey.NightAuthorVersion, metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] ?: "")
        preferences.put(WearNightFaceKey.NightFileName, metadata[CwfMetadataKey.CWF_FILENAME] ?: "")
    }

    /** Remembers [metadata] as the night face, but only when its name or version is not the one remembered. */
    fun rememberIfNew(metadata: CwfMetadataMap) {
        if (metadata[CwfMetadataKey.CWF_NAME] != preferences.get(WearNightFaceKey.NightWatchfaceName) ||
            metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] != preferences.get(WearNightFaceKey.NightAuthorVersion)
        ) remember(metadata)
    }

    /** Forgets the remembered night face. */
    fun forget() {
        preferences.put(WearNightFaceKey.NightWatchfaceName, "")
        preferences.put(WearNightFaceKey.NightAuthorVersion, "")
        preferences.put(WearNightFaceKey.NightFileName, "")
    }

    /**
     * The metadata the watch's night face must get, or null when it already has it. The same rules
     * as `WearPlugin.checkCustomWatchfacePreferences` for the main face: the authorization follows the
     * phone setting, and the file name follows the remembered one when name and version are the same.
     * Only metadata: the json is empty and there are no resources.
     */
    fun metadataUpdate(nightFace: CwfData): CwfData? {
        val metadata = nightFace.metadata.toMutableMap()
        var toUpdate = false
        val authorization = preferences.get(BooleanKey.WearCustomWatchfaceAuthorization)
        if (authorization != metadata[CwfMetadataKey.CWF_AUTHORIZATION]?.toBooleanStrictOrNull()) {
            metadata[CwfMetadataKey.CWF_AUTHORIZATION] = authorization.toString()
            toUpdate = true
        }
        val fileName = preferences.get(WearNightFaceKey.NightFileName)
        if (preferences.get(WearNightFaceKey.NightWatchfaceName) == metadata[CwfMetadataKey.CWF_NAME] &&
            preferences.get(WearNightFaceKey.NightAuthorVersion) == metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] &&
            fileName != metadata[CwfMetadataKey.CWF_FILENAME]
        ) {
            metadata[CwfMetadataKey.CWF_FILENAME] = fileName
            toUpdate = true
        }
        return if (toUpdate) CwfData("", metadata, mutableMapOf()) else null
    }
}
