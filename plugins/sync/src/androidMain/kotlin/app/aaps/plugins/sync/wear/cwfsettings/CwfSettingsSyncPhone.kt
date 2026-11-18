package app.aaps.plugins.sync.wear.cwfsettings

import android.content.Context // PERSONAL PATCH: the settings by the watch's own names
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.wear.watchfaces.settingsync.CwfSettingNames // PERSONAL PATCH: every setting of the watch face menu, also the choices
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart

/**
 * The phone side of the Custom watch face settings that the phone can set too (see
 * [EventData.CwfSettingsSync]).
 *
 * - A change on the phone ([setByUser]) is stored with its time and sent to the watch at once.
 * - The settings of the watch are merged in ([fromWatch]): the newer change wins, nothing is sent
 *   back unless the watch is behind.
 * - Whenever the watch asks for everything ([EventData.ActionResendData]: the watch connects, a
 *   face or menu starts, the wear screen opens), the phone sends its settings too. A watch that has
 *   newer ones answers with them. So after a restart or a lost connection both agree again.
 */
@SingleIn(AppScope::class)
@Inject
class CwfSettingsSyncPhone(
    private val store: CwfWatchSettingsStore,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val context: Context // PERSONAL PATCH: for the watch's own names, see below
) {

    /** PERSONAL PATCH: the settings by their stored names on the watch, from the watch's own code and resources (not only the on/off ones). */
    val names: CwfSettingNames by lazy { CwfSettingNames(context.resources) }

    /** Listens to the watch for as long as [scope] lives: the life of the wear connection service. */
    fun start(scope: CoroutineScope) {
        rxBus.toFlow(EventData.CwfSettingsSync::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { fromWatch(it) }
        rxBus.toFlow(EventData.ActionResendData::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { sendAll() }
    }

    /** The user set [name] to [value] on the phone. Stored and sent to the watch; a value the watch cannot take is left out. */
    fun setByUser(name: String, value: String) {
        if (!names.isAllowed(name, value)) return // PERSONAL PATCH: names, was CwfWatchSettings
        val all = store.setByUser(name, value, dateUtil.now())
        aapsLogger.debug(LTag.WEAR, "CWF setting $name set on the phone, sent to the watch")
        rxBus.send(EventMobileToWear(EventData.CwfSettingsSync(usable(all))))
    }

    /** The settings of the watch came in. Takes the newer ones, and answers when the watch is behind. */
    fun fromWatch(message: EventData.CwfSettingsSync) {
        val result = store.merge(message.settings, names::isAllowed) // PERSONAL PATCH: names, was CwfWatchSettings
        if (result.taken.isNotEmpty()) aapsLogger.debug(LTag.WEAR, "CWF settings from the watch: ${result.taken.keys}")
        if (result.answer && !message.isAnswer)
            rxBus.send(EventMobileToWear(EventData.CwfSettingsSync(usable(result.merged), isAnswer = true)))
    }

    /** Sends all settings the phone knows. The watch takes the newer ones and answers with its own newer ones. */
    fun sendAll() {
        rxBus.send(EventMobileToWear(EventData.CwfSettingsSync(usable(store.values.value))))
    }

    /** Only what the watch can take: a setting stored by an older app version may be gone or changed. */
    private fun usable(settings: Map<String, CwfSettingValue>): Map<String, CwfSettingValue> =
        settings.filter { (name, setting) -> names.isAllowed(name, setting.value) } // PERSONAL PATCH: names, was CwfWatchSettings
}
