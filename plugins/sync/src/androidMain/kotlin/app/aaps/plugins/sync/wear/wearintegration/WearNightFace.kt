package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfMetadataMap
import app.aaps.core.interfaces.rx.weardata.CwfNight
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.daynight.WearDayNightSettings
import app.aaps.plugins.sync.wear.keys.WearNightFaceBooleanKey
import app.aaps.plugins.sync.wear.keys.WearNightFaceKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The message path for a watch face zip: [dataPath] for the main face, [dataPath] plus [CwfNight.PATH_SUFFIX] for the night face. */
fun watchfacePath(dataPath: String, night: Boolean): String =
    if (night) dataPath + CwfNight.PATH_SUFFIX else dataPath

/**
 * True for a message only for the phone's own watch, which never goes into the AAPSClient broadcast
 * to another AAPS app: the rule for when it is day can hold the phone's place.
 */
fun isForOwnWatchOnly(payload: EventData): Boolean = payload is EventData.ActionSetDayNightRule

/**
 * The phone side of the night face of the Custom watch face, see [CwfNight].
 *
 * The phone sends a night zip on its own message path and keeps what the watch says about its night
 * face apart from the main face. A night face counts as on the watch only once the watch has
 * confirmed it ([isOnWatch]). A removal waits for the watch too: until the watch says it has no
 * night face, a night face the watch reports is answered with the remove message again, so a watch
 * that was away when the user removed it can not bring it back.
 *
 * Only while the watch has confirmed a night face, it gets the rule for when it is day (see
 * [WearDayNightSettings]); only then can the phone's location be read for it.
 */
@SingleIn(AppScope::class)
@Inject
class WearNightFace(
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val dayNightSettings: WearDayNightSettings
) {

    private val _saved = MutableStateFlow<CwfData?>(null)

    /**
     * The night face the watch reported since the app started, for the Wear screen, like
     * `WearPlugin.savedCustomWatchface` for the main face. Null when the watch has none or has not
     * said yet.
     */
    val saved: StateFlow<CwfData?> = _saved.asStateFlow()

    /** True while the watch has confirmed a night face, and the user has not removed it. */
    fun isOnWatch(): Boolean =
        preferences.get(WearNightFaceBooleanKey.OnWatch) && !preferences.get(WearNightFaceBooleanKey.RemovalPending)

    /**
     * True when the phone knows of a night face: chosen, confirmed, or waiting to be removed. Only
     * then does the phone ask the watch about its night face, so without one no extra message goes.
     */
    fun isKnown(): Boolean =
        preferences.get(WearNightFaceKey.NightWatchfaceName).isNotEmpty() ||
            preferences.get(WearNightFaceBooleanKey.OnWatch) ||
            preferences.get(WearNightFaceBooleanKey.RemovalPending)

    /**
     * The user chose the zip with [metadata] as the night face; it goes to the watch now. Not on the
     * watch until the watch reports it. A removal that still waits is over: this is the newer choice.
     */
    fun chosen(metadata: CwfMetadataMap) {
        remember(metadata)
        if (preferences.get(WearNightFaceBooleanKey.RemovalPending)) preferences.put(WearNightFaceBooleanKey.RemovalPending, false)
    }

    /** The watch reports [cwf] as its night face. */
    fun reportedByWatch(cwf: CwfData) {
        if (preferences.get(WearNightFaceBooleanKey.RemovalPending)) {
            // The user removed it while the watch was away: remove it again, do not take it back
            aapsLogger.debug(LTag.WEAR, "Night watchface reported while its removal waits, removing again")
            rxBus.send(EventMobileToWear(EventData.ActionClearNightWatchface(dateUtil.now())))
            return
        }
        rememberIfNew(cwf.metadata)
        if (!preferences.get(WearNightFaceBooleanKey.OnWatch)) preferences.put(WearNightFaceBooleanKey.OnWatch, true)
        _saved.value = cwf
        checkMetadata()
        // Now the watch has a night face, it gets the rule for when it is day
        ruleMessage()?.let { rxBus.send(EventMobileToWear(it)) }
    }

    /** The watch has no night face (any more). Forgets it, but writes only when there is something to forget. */
    fun noneOnWatch() {
        _saved.value = null
        if (isKnown()) forget()
    }

    /**
     * The user removes the night face. The watch is asked to forget it, and until it confirms that it
     * has none, the removal waits (see [reportedByWatch]).
     */
    fun remove() {
        forget()
        preferences.put(WearNightFaceBooleanKey.RemovalPending, true)
        _saved.value = null
        rxBus.send(EventMobileToWear(EventData.ActionClearNightWatchface(dateUtil.now())))
    }

    /**
     * What goes to the watch with every resend of the data. Empty without a night face. While a
     * removal waits, the remove message, so a watch that was away gets it when it is back. While the
     * watch has a night face, the rule for when it is day, so a change of the settings or of the
     * phone's place reaches the watch.
     */
    fun resendMessages(): List<EventData> =
        if (preferences.get(WearNightFaceBooleanKey.RemovalPending)) listOf(EventData.ActionClearNightWatchface(dateUtil.now()))
        else listOfNotNull(ruleMessage())

    /**
     * The rule for when it is day, only while the watch has confirmed a night face. Without one,
     * null, and the settings are not read, so the phone's location is not read either.
     */
    internal fun ruleMessage(): EventData.ActionSetDayNightRule? =
        if (isOnWatch()) EventData.ActionSetDayNightRule(dayNightSettings.rule()) else null

    /** Sends new metadata for the night face when the phone settings ask for it, see [metadataUpdate]. */
    fun checkMetadata() {
        _saved.value?.let { metadataUpdate(it) }?.let {
            rxBus.send(EventMobileToWear(EventData.ActionUpdateCustomWatchface(it, night = true)))
        }
    }

    /**
     * The metadata the watch's night face must get, or null when it already has it. The same rules
     * as `WearPlugin.checkCustomWatchfacePreferences` for the main face: the authorization follows the
     * phone setting, and the file name follows the remembered one when name and version are the same.
     * Only metadata: the json is empty and there are no resources.
     */
    internal fun metadataUpdate(nightFace: CwfData): CwfData? {
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

    /** Remembers [metadata] as the night face, like the main face is remembered in `StringNonKey`. */
    private fun remember(metadata: CwfMetadataMap) {
        preferences.put(WearNightFaceKey.NightWatchfaceName, metadata[CwfMetadataKey.CWF_NAME] ?: "")
        preferences.put(WearNightFaceKey.NightAuthorVersion, metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] ?: "")
        preferences.put(WearNightFaceKey.NightFileName, metadata[CwfMetadataKey.CWF_FILENAME] ?: "")
    }

    /** Remembers [metadata] as the night face, but only when its name or version is not the one remembered. */
    private fun rememberIfNew(metadata: CwfMetadataMap) {
        if (metadata[CwfMetadataKey.CWF_NAME] != preferences.get(WearNightFaceKey.NightWatchfaceName) ||
            metadata[CwfMetadataKey.CWF_AUTHOR_VERSION] != preferences.get(WearNightFaceKey.NightAuthorVersion)
        ) remember(metadata)
    }

    /** Forgets the night face and what the watch said about it. */
    private fun forget() {
        preferences.put(WearNightFaceKey.NightWatchfaceName, "")
        preferences.put(WearNightFaceKey.NightAuthorVersion, "")
        preferences.put(WearNightFaceKey.NightFileName, "")
        preferences.put(WearNightFaceBooleanKey.OnWatch, false)
        preferences.put(WearNightFaceBooleanKey.RemovalPending, false)
    }
}
