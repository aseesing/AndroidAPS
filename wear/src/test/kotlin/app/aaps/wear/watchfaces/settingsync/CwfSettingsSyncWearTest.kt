package app.aaps.wear.watchfaces.settingsync

import android.app.Application
import androidx.preference.PreferenceManager
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.CwfSettingsMerge
import app.aaps.core.interfaces.rx.weardata.CwfWatchSettings
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.impl.rx.bus.RxBusImpl
import app.aaps.shared.impl.sharedPreferences.SPImpl
import app.aaps.wear.AAPSLoggerTest
import app.aaps.wear.R
import app.aaps.wear.events.EventWearPreferenceChange
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.utils.WatchFaceSettingRow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The watch side of the Custom watch face settings: a change on the watch goes to the phone, a
 * value from the phone is stored and not sent back, and the shared list of settings matches the
 * watch's own settings menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CwfSettingsSyncWearTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val logger = AAPSLoggerTest()
    private val rxBus = RxBusImpl(logger)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var now = 5_000_000L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    }

    /** What the watch sent to the phone. */
    private val sent = CopyOnWriteArrayList<EventData.CwfSettingsSync>()
    private lateinit var collector: Job
    private lateinit var sp: SP
    private lateinit var sut: CwfSettingsSyncWear

    @Before
    fun setUp() {
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            rxBus.toFlow(EventWearToMobile::class).collect { (it.payload as? EventData.CwfSettingsSync)?.let(sent::add) }
        }
        sp = SPImpl(PreferenceManager.getDefaultSharedPreferences(app), app)
        sut = CwfSettingsSyncWear(app, sp, rxBus, logger, clock)
    }

    @After
    fun tearDown() {
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun beforeAnyChangeEverySettingIsSentWithAnUnknownTime() {
        val current = sut.current()
        assertThat(current).hasSize(CwfWatchSettings.all.size)
        assertThat(current["show_iob"]).isEqualTo(CwfSettingValue("true", 0L))
        assertThat(current["dark"]).isEqualTo(CwfSettingValue("true", 0L))
    }

    @Test
    fun aChangeOnTheWatchGoesToThePhoneWithItsTime() {
        sut.current()
        sp.putBoolean(R.string.key_show_iob, false)
        sut.settingChanged("show_iob")

        val message = sent.single()
        assertThat(message.isAnswer).isFalse()
        assertThat(message.settings["show_iob"]).isEqualTo(CwfSettingValue("false", now))
        assertThat(message.settings["show_cob"]).isEqualTo(CwfSettingValue("true", 0L))
    }

    @Test
    fun otherWatchSettingsAreNotSent() {
        sp.putBoolean(R.string.key_units_mgdl, false)
        sut.settingChanged("units_mgdl")
        assertThat(sent).isEmpty()
    }

    @Test
    fun aNewerValueFromThePhoneIsStoredAndNotSentBack() {
        sut.current()
        sut.fromPhone(EventData.CwfSettingsSync(mapOf("show_iob" to CwfSettingValue("false", now), "dark" to CwfSettingValue("false", now))))

        assertThat(sp.getBoolean(R.string.key_show_iob, true)).isFalse()
        assertThat(sp.getBoolean(R.string.key_dark, true)).isFalse()
        // The phone lacks the other settings, so it gets them once
        assertThat(sent.single().isAnswer).isTrue()
        assertThat(sent.single().settings["show_iob"]).isEqualTo(CwfSettingValue("false", now))

        // The watch announces the change of its settings; that is not a change by the user
        sent.clear()
        sut.settingChanged("show_iob")
        sut.settingChanged("dark")
        assertThat(sent).isEmpty()
    }

    @Test
    fun theFullSetFromThePhoneGivesNoAnswer() {
        val all = sut.current().toMutableMap()
        all["show_iob"] = CwfSettingValue("false", now)
        sut.fromPhone(EventData.CwfSettingsSync(all))
        assertThat(sp.getBoolean(R.string.key_show_iob, true)).isFalse()
        assertThat(sent).isEmpty()
    }

    @Test
    fun anOlderValueFromThePhoneLosesAgainstAChangeOnTheWatch() {
        sut.current()
        sp.putBoolean(R.string.key_show_iob, false)
        sut.settingChanged("show_iob")
        sent.clear()

        sut.fromPhone(EventData.CwfSettingsSync(mapOf("show_iob" to CwfSettingValue("true", now - 60_000))))

        assertThat(sp.getBoolean(R.string.key_show_iob, true)).isFalse()
        assertThat(sent.single().isAnswer).isTrue()
        assertThat(sent.single().settings["show_iob"]).isEqualTo(CwfSettingValue("false", now))

        // An answer of the phone is never answered
        sent.clear()
        sut.fromPhone(EventData.CwfSettingsSync(mapOf("show_iob" to CwfSettingValue("true", now - 60_000)), isAnswer = true))
        assertThat(sent).isEmpty()
    }

    @Test
    fun aValueTheWatchCannotTakeIsLeftOut() {
        sut.fromPhone(EventData.CwfSettingsSync(mapOf("dark" to CwfSettingValue("maybe", now), "units_mgdl" to CwfSettingValue("false", now))))
        assertThat(sp.getBoolean(R.string.key_dark, true)).isTrue()
        assertThat(sp.getBoolean(R.string.key_units_mgdl, true)).isTrue()
    }

    @Test
    fun aChangeWhileNotListeningIsNotedAtTheNextStart() {
        sut.current()
        now += 1_000
        sp.putBoolean(R.string.key_dark, false) // no event, as when the watch app did not listen

        assertThat(sut.catchUp()).isTrue()
        assertThat(sut.current()["dark"]).isEqualTo(CwfSettingValue("false", now))
    }

    @Test
    fun startedItListensToThePhoneAndToTheWatchSettings() {
        sut.start(scope)
        rxBus.send(EventData.CwfSettingsSync(mapOf("show_date" to CwfSettingValue("true", now))))
        assertThat(sp.getBoolean(R.string.key_show_date, false)).isTrue()

        sent.clear()
        sp.putBoolean(R.string.key_show_cob, false)
        rxBus.send(EventWearPreferenceChange("show_cob"))
        assertThat(sent.single().settings["show_cob"]).isEqualTo(CwfSettingValue("false", now))
    }

    /**
     * The shared table is the on/off rows of the Custom watch face menu that AAPS shows on the watch,
     * with the same names, defaults and order, then the values a design can carry.
     */
    @Test
    fun theLookSettingsAreTheRowsOfTheWatchMenu() {
        val app = RuntimeEnvironment.getApplication()
        val menu = CustomWatchface.settingRows(null, systemEditor = false, hasNightFace = false)
            .filterIsInstance<WatchFaceSettingRow.Toggle>()
            .map { app.getString(it.key) to it.defaultValue }
        assertThat(CwfWatchSettings.look.map { it.key to it.default }).containsExactlyElementsIn(menu).inOrder()
        // Every setting of the table is one the watch has
        assertThat(CwfSettingNames(app.resources).settings.keys).containsExactlyElementsIn(CwfWatchSettings.all.map { it.key }).inOrder()
    }

    /**
     * Every setting a design can carry has the watch name, default and label the watch face itself
     * uses (`CustomWatchface.PrefMap`, private, so read by reflection). Only the complication slots are
     * left out: a slot needs its data source picked in the system editor on the watch.
     */
    @Test
    fun theDesignSettingsMatchTheWatchFace() {
        val app = RuntimeEnvironment.getApplication()
        val prefMap = Class.forName("app.aaps.wear.watchfaces.CustomWatchface\$PrefMap")
        fun <T> get(entry: Any, getter: String): T = prefMap.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(entry).let {
            @Suppress("UNCHECKED_CAST")
            it as T
        }
        val watch = prefMap.enumConstants.mapNotNull { entry ->
            get<CwfMetadataKey?>(entry, "getMetadataKey")?.let { key ->
                key to Triple(app.getString(get<Int>(entry, "getPrefKey")), get<Any>(entry, "getDefaultValue") as Boolean, key.label)
            }
        }.toMap()
        val ours = CwfWatchSettings.all.mapNotNull { setting -> setting.metadataKey?.let { it to Triple(setting.key, setting.default, setting.label) } }.toMap()

        assertThat(watch).containsAtLeastEntriesIn(ours)
        assertThat(watch.keys - ours.keys).containsExactly(
            CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1, CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2, CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3,
            CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4, CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5
        )
        assertThat(CwfWatchSettings.all.map { it.key }).containsNoDuplicates()
    }

    /**
     * A design that sets a value (with the authorization) settles after one round: the watch takes the
     * design's value and sends it once, with the time of the change; the phone takes it and does not answer.
     */
    @Test
    fun aValueForcedByTheDesignIsSentOnceAndThenStill() {
        sut.current()
        now += 1_000
        sp.putBoolean(R.string.key_show_iob, false) // as CustomWatchface.updatePref does for the design
        sut.settingChanged("show_iob")
        val toPhone = sent.single()
        assertThat(toPhone.settings["show_iob"]).isEqualTo(CwfSettingValue("false", now))

        // The phone merges it: the newer value wins there, and it has nothing newer to send back
        val onPhone = CwfSettingsMerge.merge(mapOf("show_iob" to CwfSettingValue("true", 0L)), toPhone.settings, localIsWatch = false, allowed = CwfWatchSettings::isAllowed)
        assertThat(onPhone.taken["show_iob"]).isEqualTo(CwfSettingValue("false", now))
        assertThat(onPhone.answer).isFalse()

        // The design writes the same value again on each style pass: no new message
        sent.clear()
        sut.settingChanged("show_iob")
        assertThat(sent).isEmpty()
    }

    /**
     * A new watch (or a new install of the watch app) meets a phone that learned other values from
     * another watch, all with an unknown time. The watch keeps its own values and the phone takes them.
     */
    @Test
    fun atFirstContactTheWatchKeepsItsValuesAndThePhoneTakesThem() {
        sp.putBoolean(R.string.key_show_week_number, false)
        sp.putBoolean(R.string.key_show_date, false)
        sp.putBoolean(R.string.key_dark, true)
        var phone = mapOf(
            "show_week_number" to CwfSettingValue("true", 0L),
            "show_date" to CwfSettingValue("true", 0L),
            "dark" to CwfSettingValue("false", 0L)
        )

        // The phone sends all it has, as when the watch asks for all data
        sut.fromPhone(EventData.CwfSettingsSync(phone))
        assertThat(sp.getBoolean(R.string.key_show_week_number, true)).isFalse()
        assertThat(sp.getBoolean(R.string.key_show_date, true)).isFalse()
        assertThat(sp.getBoolean(R.string.key_dark, false)).isTrue()
        val answer = sent.single()
        assertThat(answer.isAnswer).isTrue()

        // The phone side takes the values of the watch and does not answer again
        val onPhone = CwfSettingsMerge.merge(phone, answer.settings, localIsWatch = false, allowed = CwfWatchSettings::isAllowed)
        phone = onPhone.merged
        assertThat(onPhone.answer).isFalse()
        assertThat(phone["show_week_number"]).isEqualTo(CwfSettingValue("false", 0L))
        assertThat(phone["show_date"]).isEqualTo(CwfSettingValue("false", 0L))
        assertThat(phone["dark"]).isEqualTo(CwfSettingValue("true", 0L))
        assertThat(phone).isEqualTo(sut.current())

        // Sent again later: both sides agree, nothing moves
        sent.clear()
        sut.fromPhone(EventData.CwfSettingsSync(phone))
        assertThat(sent).isEmpty()
    }
}
