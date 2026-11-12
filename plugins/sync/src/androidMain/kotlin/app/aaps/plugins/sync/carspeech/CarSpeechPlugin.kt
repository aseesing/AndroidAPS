// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.carspeech

import android.Manifest
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.keys.interfaces.withEntries
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.extensions.directionToDescription
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.carspeech.keys.CarSpeechBooleanKey
import app.aaps.plugins.sync.carspeech.keys.CarSpeechIntKey
import app.aaps.plugins.sync.carspeech.keys.CarSpeechIntentKey
import app.aaps.plugins.sync.carspeech.keys.CarSpeechStringKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Says the glucose value and its trend out loud while the phone is in the car, on the car speakers
 * through Android Auto or Bluetooth. The "vehicle mode" speech of xDrip+ (GPLv3,
 * NightscoutFoundation/xDrip), so xDrip+ is not needed on the phone for it.
 *
 * - In the car: see [VehicleDetector] (car Bluetooth audio, or activity recognition).
 * - When to speak: see [CarSpeechRule] (only when the value changed, or is too low or too high).
 * - How: see [BgSpeaker].
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(380)
@SingleIn(AppScope::class)
@Inject
class CarSpeechPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val profileUtil: ProfileUtil,
    private val dateUtil: DateUtil,
    notificationManager: NotificationManager
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.GENERAL)
        .icon(Icons.Default.DirectionsCar)
        .pluginName(SyncStrings.car_speech)
        .description(SyncStrings.car_speech_description),
    ownPreferences = CarSpeechBooleanKey.entries + CarSpeechIntKey.entries + CarSpeechStringKey.entries + CarSpeechIntentKey.entries,
    aapsLogger, rh, preferences, notificationManager
) {

    private val vehicleDetector = VehicleDetector(context, preferences, aapsLogger)
    private val speaker = BgSpeaker(context, aapsLogger)

    private var scope: CoroutineScope? = null

    /** What was said last in this car trip. Cleared when the trip ends. */
    private var lastSpoken: CarSpeechRule.LastSpoken? = null
    private val speakMutex = Mutex()

    override suspend fun onStart() {
        super.onStart()
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope

        // Each detector runs only while its switch is on
        preferences.observe(CarSpeechBooleanKey.ViaCarAudio)
            .collectResilient(newScope, aapsLogger, LTag.CORE) { on -> if (on) vehicleDetector.startCarAudio() else vehicleDetector.stopCarAudio() }
        preferences.observe(CarSpeechBooleanKey.ViaMotion)
            .collectResilient(newScope, aapsLogger, LTag.CORE) { on -> if (on) vehicleDetector.startMotion() else vehicleDetector.stopMotion() }

        // In the car: say the current value at once; out of the car: start again next trip
        val inVehicle = combine(vehicleDetector.carAudio, vehicleDetector.motion) { audio, motion -> audio || motion }
            .stateIn(newScope, SharingStarted.Eagerly, false)
        inVehicleFlow = inVehicle
        inVehicle
            .collectResilient(newScope, aapsLogger, LTag.CORE) { inCar ->
                aapsLogger.debug(LTag.CORE, "Car speech: in the car = $inCar")
                speakMutex.withLock { lastSpoken = null }
                if (inCar) persistenceLayer.getLastGlucoseValue()?.takeIf { it.isValid }?.let { onReading(it) }
            }

        persistenceLayer.observeChanges(GV::class)
            .collectResilient(newScope, aapsLogger, LTag.CORE) { changed ->
                // The permission for motion may have been given since the switch was turned on
                if (preferences.get(CarSpeechBooleanKey.ViaMotion)) vehicleDetector.startMotion()
                // A removed (invalidated) reading is also a change: never say that one
                changed.filter { it.isValid }.maxByOrNull { it.timestamp }?.let { onReading(it) }
            }
    }

    override suspend fun onStop() {
        scope?.cancel()
        scope = null
        inVehicleFlow = null
        vehicleDetector.stopCarAudio()
        vehicleDetector.stopMotion()
        speaker.shutdown()
        super.onStop()
    }

    /** True while the phone is in the car. Null while the plugin is stopped. */
    private var inVehicleFlow: StateFlow<Boolean>? = null

    private suspend fun onReading(gv: GV) = speakMutex.withLock {
        val reason = CarSpeechRule.decide(
            inVehicle = inVehicleFlow?.value == true,
            valueMgdl = gv.value,
            readingTimestamp = gv.timestamp,
            now = dateUtil.now(),
            settings = settings(),
            lastSpoken = lastSpoken
        )
        if (reason == CarSpeechRule.Reason.NONE) return@withLock
        aapsLogger.debug(LTag.CORE, "Car speech: speaking ${gv.value} because $reason")
        // Counted as said also when the engine fails, so a broken engine is not tried on every reading
        lastSpoken = CarSpeechRule.LastSpoken(gv.value, gv.timestamp)
        speaker.say(textFor(gv))
    }

    private fun settings() = CarSpeechRule.Settings(
        lowMgdl = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.OverviewLowMark)),
        highMgdl = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.OverviewHighMark)),
        changeThresholdMgdl = preferences.get(CarSpeechIntKey.ChangeThreshold).toDouble(),
        outOfRangeRepeatMs = preferences.get(CarSpeechIntKey.OutOfRangeRepeat) * 60_000L
    )

    /** "7.2, rising", in the unit and the number format of the user. */
    private fun textFor(gv: GV): String {
        val value = profileUtil.fromMgdlToStringInUnits(gv.value)
        return if (gv.trendArrow == TrendArrow.NONE) value
        else rh.gs(SyncStrings.car_speech_text, value, rh.gs(gv.trendArrow.directionToDescription()))
    }

    /** The test button: says the newest value now, also outside the car. */
    private fun testSpeech() {
        val testScope = scope ?: return
        testScope.launch {
            val gv = persistenceLayer.getLastGlucoseValue()
            speaker.say(if (gv != null) textFor(gv) else rh.gs(SyncStrings.car_speech_test_no_value))
        }
    }

    override fun requiredPermissions(): List<PermissionGroup> =
        if (preferences.get(CarSpeechBooleanKey.ViaMotion)) listOf(
            PermissionGroup(
                permissions = listOf(Manifest.permission.ACTIVITY_RECOGNITION),
                rationaleTitle = SyncStrings.car_speech_permission_motion_title,
                rationaleDescription = SyncStrings.car_speech_permission_motion_description,
            )
        ) else emptyList()

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "car_speech_settings",
        title = SyncStrings.car_speech,
        items = listOf(
            CarSpeechIntentKey.Setup.withCompose(
                ComposeScreenContent { onBack ->
                    CarSpeechSetupScreen(
                        lastConnected = vehicleDetector.lastConnected,
                        inVehicle = inVehicleFlow ?: vehicleDetector.carAudio,
                        carName = { vehicleDetector.carName() },
                        onChooseCar = { vehicleDetector.chooseCar(it) },
                        onForgetCar = { vehicleDetector.forgetCar() },
                        onTest = { testSpeech() },
                        onBack = onBack
                    )
                }
            ),
            CarSpeechBooleanKey.ViaCarAudio,
            CarSpeechBooleanKey.ViaMotion,
            CarSpeechIntKey.ChangeThreshold.withEntries(THRESHOLDS_MGDL.associateWith { TextRef.Literal(profileUtil.fromMgdlToStringWithUnits(it.toDouble())) }),
            CarSpeechIntKey.OutOfRangeRepeat.withEntries(REPEAT_MINUTES.associateWith { CoreUiStrings.format_mins.withArgs(it) }),
        ),
        icon = pluginDescription.icon
    )

    companion object {

        /** Choices for the change threshold, in mg/dl (about 0.3 to 2.8 mmol/l). */
        private val THRESHOLDS_MGDL = listOf(5, 10, 15, 20, 30, 50)

        /** Choices for the repeat time of a low or a high, in minutes. */
        private val REPEAT_MINUTES = listOf(5, 10, 15, 20, 30, 60)
    }
}
