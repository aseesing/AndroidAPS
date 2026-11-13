// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.settingsync

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.CwfSettingsMerge
import app.aaps.core.interfaces.rx.weardata.CwfSettingsSync
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.events.EventWearPreferenceChange
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The watch side of the Custom watch face settings that the phone can set too (see [CwfSettingsSync]).
 *
 * - A setting the user changes on the watch goes to the phone, with the time of the change.
 * - A setting from the phone is stored when it is newer. It is not sent back.
 * - When the phone is behind, the watch answers with all its settings.
 *
 * Keeps a small book of value and time per setting in its own preferences file. A watch setting that
 * differs from the book was changed on the watch; one that matches it was written here. That is how a
 * value from the phone is told apart from a change by the user, without echo.
 *
 * The settings themselves stay where the watch face reads them, in the watch's preferences ([sp]).
 */
@SingleIn(AppScope::class)
@Inject
class CwfSettingsSyncWear(
    private val context: Context,
    private val sp: SP,
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger,
    private val clock: Clock
) {

    private val names: CwfSettingNames by lazy { CwfSettingNames(context.resources) }
    private val book: SharedPreferences by lazy { context.getSharedPreferences(BOOK_FILE, Context.MODE_PRIVATE) }
    private var started = false

    /** Starts listening to the phone and to changes of the watch settings. Called once, from the app. */
    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        if (started) return
        started = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) { rxBus.toFlow(CwfSettingsSync::class).collect { fromPhone(it) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { rxBus.toFlow(EventWearPreferenceChange::class).collect { it.changedKey?.let(::settingChanged) } }
        scope.launch { catchUp() }
    }

    /** The settings as the watch has them now, with the time of their last change. */
    @Synchronized
    fun current(): Map<String, CwfSettingValue> {
        catchUp()
        return names.settings.keys.associateWith { name -> readBook(name) ?: CwfSettingValue(names.read(sp, names.settings.getValue(name)), 0L) }
    }

    /**
     * Brings the book up to date with the watch settings. A setting that differs from the book was
     * changed by the user on the watch, and gets the time of now. A setting not in the book yet (the
     * first run with this code) gets time 0: when it changed is not known.
     *
     * @return true when the user changed a setting
     */
    @Synchronized
    fun catchUp(): Boolean {
        var changed = false
        names.settings.forEach { (name, setting) ->
            val value = names.read(sp, setting)
            val noted = readBook(name)
            if (noted == null) writeBook(name, CwfSettingValue(value, 0L))
            else if (noted.value != value) {
                writeBook(name, CwfSettingValue(value, CwfSettingsMerge.changeTime(clock.now().toEpochMilliseconds(), noted)))
                changed = true
            }
        }
        return changed
    }

    /** A watch setting with the stored name [name] changed. Sends all settings to the phone when the user changed it here. */
    fun settingChanged(name: String) {
        if (name !in names.settings) return
        // Not changed when fromPhone wrote it: then the book has the value already
        val toSend = synchronized(this) { if (catchUp()) current() else null } ?: return
        aapsLogger.debug(LTag.WEAR, "CWF setting $name changed on the watch, sent to the phone")
        rxBus.send(EventWearToMobile(CwfSettingsSync(toSend)))
    }

    /** The settings of the phone came in. Takes the newer ones, and answers when the phone is behind. */
    fun fromPhone(message: CwfSettingsSync) {
        val answer = synchronized(this) {
            val result = CwfSettingsMerge.merge(current(), message.settings, names::isAllowed)
            result.taken.forEach { (name, taken) ->
                // The book first: the change of the watch setting below must look like ours, not like the user's
                writeBook(name, taken)
                names.settings[name]?.let { names.write(sp, it, taken.value) }
            }
            if (result.taken.isNotEmpty()) aapsLogger.debug(LTag.WEAR, "CWF settings from the phone: ${result.taken.keys}")
            result.merged.takeIf { result.answer && !message.isAnswer }
        }
        answer?.let { rxBus.send(EventWearToMobile(CwfSettingsSync(it, isAnswer = true))) }
    }

    private fun readBook(name: String): CwfSettingValue? {
        val value = book.getString(VALUE_PREFIX + name, null) ?: return null
        return CwfSettingValue(value, book.getLong(TIME_PREFIX + name, 0L))
    }

    private fun writeBook(name: String, setting: CwfSettingValue) {
        book.edit().putString(VALUE_PREFIX + name, setting.value).putLong(TIME_PREFIX + name, setting.changedAt).commit()
    }

    private companion object {

        /** Not the watch's own preferences file: a write here must not look like a change of a watch setting. */
        const val BOOK_FILE = "cwf_settings_sync"
        const val VALUE_PREFIX = "value_"
        const val TIME_PREFIX = "time_"
    }
}
