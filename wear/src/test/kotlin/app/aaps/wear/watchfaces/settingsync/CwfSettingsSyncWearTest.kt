// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.settingsync

import android.app.Application
import androidx.preference.PreferenceManager
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.CwfSettingsMerge
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
        assertThat(current).hasSize(CwfSyncedSettings.all.size)
        assertThat(current["show_iob"]).isEqualTo(CwfSettingValue("true", 0L))
        assertThat(current["wake_animation_length"]).isEqualTo(CwfSettingValue("design", 0L))
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
        sut.fromPhone(EventData.CwfSettingsSync(mapOf("show_iob" to CwfSettingValue("false", now), "wake_animation_length" to CwfSettingValue("3000", now))))

        assertThat(sp.getBoolean(R.string.key_show_iob, true)).isFalse()
        assertThat(sp.getString(R.string.key_wake_animation_length, "")).isEqualTo("3000")
        // The phone lacks the other settings, so it gets them once
        assertThat(sent.single().isAnswer).isTrue()
        assertThat(sent.single().settings["show_iob"]).isEqualTo(CwfSettingValue("false", now))

        // The watch announces the change of its settings; that is not a change by the user
        sent.clear()
        sut.settingChanged("show_iob")
        sut.settingChanged("wake_animation_length")
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
        sut.fromPhone(EventData.CwfSettingsSync(mapOf("wake_animation_length" to CwfSettingValue("1234", now), "units_mgdl" to CwfSettingValue("false", now))))
        assertThat(sp.getString(R.string.key_wake_animation_length, "design")).isEqualTo("design")
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
     * The look settings the phone offers are the rows of the watch's own Custom watch face menu, with
     * the same keys, labels and defaults. Left out: the complication pickers, the type priority and
     * simplified view (they need the watch), and the day / night look (the phone has its own).
     */
    @Test
    fun theLookSettingsAreTheRowsOfTheWatchMenu() {
        val leftOut = setOf(R.string.key_simplify_ui, R.string.key_day_night_look, R.string.key_day_night_always_on)
        val rows = CustomWatchface.settingRows(null, systemEditor = true, hasNightFace = false)
            .filter { it is WatchFaceSettingRow.Toggle || it is WatchFaceSettingRow.Choice }
            .filter { it.key !in leftOut }
        val expected = rows.map { row ->
            when (row) {
                is WatchFaceSettingRow.Toggle -> Triple(row.key, row.title, row.defaultValue.toString())
                is WatchFaceSettingRow.Choice -> Triple(row.key, row.title, row.defaultValue)
                else                          -> error("not a stored row")
            }
        }
        assertThat(CwfSyncedSettings.faceLook.map { Triple(it.key, it.title, it.defaultValue) }).containsExactlyElementsIn(expected).inOrder()
    }

    /**
     * Every setting a design can carry has the watch key, default and label the watch face itself
     * uses (`CustomWatchface.PrefMap`, private, so read by reflection).
     */
    @Test
    fun theDesignSettingsMatchTheWatchFace() {
        val prefMap = Class.forName("app.aaps.wear.watchfaces.CustomWatchface\$PrefMap")
        fun <T> get(entry: Any, getter: String): T = prefMap.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(entry).let {
            @Suppress("UNCHECKED_CAST")
            it as T
        }
        val watch = prefMap.enumConstants.mapNotNull { entry ->
            get<Any?>(entry, "getMetadataKey")?.let { key ->
                key to Triple(get<Int>(entry, "getPrefKey"), get<Any>(entry, "getDefaultValue").toString(), get<Int?>(entry, "getTitle"))
            }
        }.toMap()
        val ours = CwfSyncedSettings.all.mapNotNull { setting -> setting.metadataKey?.let { it to Triple(setting.key, setting.defaultValue, setting.title) } }.toMap()

        assertThat(ours).isEqualTo(watch)
    }

    /**
     * A design that sets a value (with the authorization) settles after one round: the watch takes the
     * design's value and sends it once, with the time of the change; the phone takes it and does not answer.
     */
    @Test
    fun aValueForcedByTheDesignIsSentOnceAndThenStill() {
        val names = CwfSettingNames(RuntimeEnvironment.getApplication().resources)
        sut.current()
        now += 1_000
        sp.putBoolean(R.string.key_show_iob, false) // as CustomWatchface.updatePref does for the design
        sut.settingChanged("show_iob")
        val toPhone = sent.single()
        assertThat(toPhone.settings["show_iob"]).isEqualTo(CwfSettingValue("false", now))

        // The phone merges it: the newer value wins there, and it has nothing newer to send back
        val onPhone = CwfSettingsMerge.merge(mapOf("show_iob" to CwfSettingValue("true", 0L)), toPhone.settings, localIsWatch = false, allowed = names::isAllowed)
        assertThat(onPhone.taken["show_iob"]).isEqualTo(CwfSettingValue("false", now))
        assertThat(onPhone.answer).isFalse()

        // The design writes the same value again on each style pass: no new message
        sent.clear()
        sut.settingChanged("show_iob")
        assertThat(sent).isEmpty()
    }

    /**
     * A new watch (or a new install of the watch app) meets a phone that learned other values from
     * another watch, all with an unknown time. The watch keeps its own values and the phone takes them,
     * for an on/off setting and for a choice alike.
     */
    @Test
    fun atFirstContactTheWatchKeepsItsValuesAndThePhoneTakesThem() {
        val names = CwfSettingNames(RuntimeEnvironment.getApplication().resources)
        sp.putBoolean(R.string.key_show_date, false)
        sp.putBoolean(R.string.key_show_iob, true)
        var phone = mapOf(
            "show_date" to CwfSettingValue("true", 0L),
            "show_iob" to CwfSettingValue("false", 0L),
            "wake_animation_length" to CwfSettingValue("3000", 0L)
        )

        // The phone sends all it has, as when the watch asks for all data
        sut.fromPhone(EventData.CwfSettingsSync(phone))
        assertThat(sp.getBoolean(R.string.key_show_date, true)).isFalse()
        assertThat(sp.getBoolean(R.string.key_show_iob, false)).isTrue()
        assertThat(sp.getString(R.string.key_wake_animation_length, "design")).isEqualTo("design") // not stored: the default
        val answer = sent.single()
        assertThat(answer.isAnswer).isTrue()

        // The phone side takes the values of the watch and does not answer again
        val onPhone = CwfSettingsMerge.merge(phone, answer.settings, localIsWatch = false, allowed = names::isAllowed)
        phone = onPhone.merged
        assertThat(onPhone.answer).isFalse()
        assertThat(phone["show_date"]).isEqualTo(CwfSettingValue("false", 0L))
        assertThat(phone["show_iob"]).isEqualTo(CwfSettingValue("true", 0L))
        assertThat(phone["wake_animation_length"]).isEqualTo(CwfSettingValue("design", 0L))
        assertThat(phone).isEqualTo(sut.current())

        // Sent again later: both sides agree, nothing moves
        sent.clear()
        sut.fromPhone(EventData.CwfSettingsSync(phone))
        assertThat(sent).isEmpty()
    }
}
