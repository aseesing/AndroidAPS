// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.cwfsettings

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.CwfSettingsSync
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.wear.watchfaces.settingsync.CwfSettingNames
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart

/**
 * The phone side of the Custom watch face settings that the phone can set too (see [CwfSettingsSync]).
 *
 * - A change on the phone ([setByUser]) is stored with its time and sent to the watch at once.
 * - The settings of the watch are merged in ([fromWatch]): the newer change wins, nothing is sent
 *   back unless the watch is behind.
 * - Whenever the watch asks for everything ([EventData.ActionResendData]: the watch connects, a
 *   face or menu starts, the wear screen opens), the phone sends its settings too. A watch that has
 *   newer ones answers with them. So after a restart or a lost connection both agree again.
 *
 * The live preview follows [CwfWatchSettingsStore.values], see `CwfLivePreviewViewModel`.
 */
@SingleIn(AppScope::class)
@Inject
class CwfSettingsSyncPhone(
    private val store: CwfWatchSettingsStore,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val context: Context
) {

    /** The settings by their stored names on the watch, from the watch's own code and resources. */
    val names: CwfSettingNames by lazy { CwfSettingNames(context.resources) }

    /** Listens to the watch for as long as [scope] lives: the life of the wear connection service. */
    fun start(scope: CoroutineScope) {
        rxBus.toFlow(CwfSettingsSync::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { fromWatch(it) }
        rxBus.toFlow(EventData.ActionResendData::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { sendAll() }
    }

    /** The user set [name] to [value] on the phone. Stored and sent to the watch; a value the watch cannot take is left out. */
    fun setByUser(name: String, value: String) {
        if (!names.isAllowed(name, value)) return
        val all = store.setByUser(name, value, dateUtil.now())
        aapsLogger.debug(LTag.WEAR, "CWF setting $name set on the phone, sent to the watch")
        rxBus.send(EventMobileToWear(CwfSettingsSync(usable(all))))
    }

    /** The settings of the watch came in. Takes the newer ones, and answers when the watch is behind. */
    fun fromWatch(message: CwfSettingsSync) {
        val result = store.merge(message.settings, names::isAllowed)
        if (result.taken.isNotEmpty()) aapsLogger.debug(LTag.WEAR, "CWF settings from the watch: ${result.taken.keys}")
        if (result.answer && !message.isAnswer)
            rxBus.send(EventMobileToWear(CwfSettingsSync(usable(result.merged), isAnswer = true)))
    }

    /** Sends all settings the phone knows. The watch takes the newer ones and answers with its own newer ones. */
    fun sendAll() {
        rxBus.send(EventMobileToWear(CwfSettingsSync(usable(store.values.value))))
    }

    /** Only what the watch can take: a setting stored by an older app version may be gone or changed. */
    private fun usable(settings: Map<String, CwfSettingValue>): Map<String, CwfSettingValue> =
        settings.filter { (name, setting) -> names.isAllowed(name, setting.value) }
}
