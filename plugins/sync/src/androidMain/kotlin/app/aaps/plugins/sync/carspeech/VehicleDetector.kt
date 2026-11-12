// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.carspeech

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.carspeech.keys.CarSpeechStringKey
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Knows when the phone is in the car ("vehicle mode"), in the two ways xDrip+ has:
 *
 * 1. Car audio: the Bluetooth audio device chosen as the car connects. A port of xDrip+
 *    `HeadsetStateReceiver` and `SelectAudioDevice` by jamorham (GPLv3, NightscoutFoundation/xDrip).
 *    The last device that connected is remembered, so the user can choose it as the car.
 *    xDrip+ listens to the hands-free profile only; the media profile (A2DP) is added here, because
 *    some cars connect only that one.
 * 2. Motion: Google's activity recognition reports "in a vehicle", as in xDrip+
 *    `ActivityRecognizedService`. xDrip+ polls the old API; this uses the transition API of the
 *    same service, which reports only the start and the end of driving and so uses less battery.
 *
 * Each source has its own state, and the phone is in the car when either says so. In xDrip+ both
 * write one flag, so a short stop in the car ("still") could switch off a car connected over
 * Bluetooth.
 */
class VehicleDetector(
    private val context: Context,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {

    /**
     * All work of this class runs here, one task after the other, so the order of connect and
     * disconnect events is kept. Android delivers the broadcasts, the Bluetooth profile answers and
     * the clicks on the main thread: those only hand their work to this scope, so a bug or a slow
     * Bluetooth call here can never stop the main thread or the loop.
     */
    private val worker = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
            CoroutineExceptionHandler { _, e -> aapsLogger.error(LTag.CORE, "Car speech: vehicle detection failed", e) }
    )

    private fun work(block: () -> Unit) {
        worker.launch { block() }
    }

    private val carAudioConnected = MutableStateFlow(false)
    private val motionInVehicle = MutableStateFlow(false)

    /** True while the chosen car is connected over Bluetooth. */
    val carAudio: StateFlow<Boolean> = carAudioConnected.asStateFlow()

    /** True while activity recognition says the phone is in a vehicle. */
    val motion: StateFlow<Boolean> = motionInVehicle.asStateFlow()

    private val _lastConnected = MutableStateFlow(runCatching { readLastConnected() }.getOrNull())

    /** The Bluetooth audio device that connected last, so it can be chosen as the car. */
    val lastConnected: StateFlow<AudioDevice?> = _lastConnected.asStateFlow()

    data class AudioDevice(val address: String, val name: String)

    // Only read and written on the worker
    private var audioReceiverRegistered = false
    private var motionReceiverRegistered = false
    private var motionPermissionWarned = false

    // ---- car audio ------------------------------------------------------------------------------

    private val audioReceiver = object : BroadcastReceiver() {
        // Main thread: hand over at once
        override fun onReceive(context: Context, intent: Intent) = work { onAudioIntent(intent) }
    }

    private fun onAudioIntent(intent: Intent) {
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
        val name = deviceName(device)
        when {
            state == BluetoothProfile.STATE_CONNECTED && previous != BluetoothProfile.STATE_CONNECTED       -> {
                aapsLogger.debug(LTag.CORE, "Car speech: Bluetooth audio connected: $name")
                rememberLastConnected(AudioDevice(device.address, name))
                if (device.address == carAddress()) setCarAudio(true)
            }

            state == BluetoothProfile.STATE_DISCONNECTED && previous != BluetoothProfile.STATE_DISCONNECTED -> {
                aapsLogger.debug(LTag.CORE, "Car speech: Bluetooth audio disconnected: $name")
                if (device.address == carAddress()) setCarAudio(false)
            }
        }
    }

    /** Starts listening to the car. Returns at once; the work runs on the worker. */
    fun startCarAudio() = work {
        if (audioReceiverRegistered) return@work
        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        // Sent by the system, so the receiver must be exported
        ContextCompat.registerReceiver(context, audioReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        audioReceiverRegistered = true
        checkCarAlreadyConnected()
    }

    /** Stops listening to the car. Returns at once; the work runs on the worker. */
    fun stopCarAudio() = work {
        if (audioReceiverRegistered) {
            audioReceiverRegistered = false
            context.unregisterReceiver(audioReceiver)
        }
        setCarAudio(false)
    }

    /** Makes [device] the car, and looks whether it is already connected. Returns at once. */
    fun chooseCar(device: AudioDevice) = work {
        preferences.put(CarSpeechStringKey.CarAddress, device.address)
        preferences.put(CarSpeechStringKey.CarName, device.name)
        setCarAudio(false)
        if (audioReceiverRegistered) checkCarAlreadyConnected()
    }

    /** Forgets the car. Returns at once. */
    fun forgetCar() = work {
        preferences.put(CarSpeechStringKey.CarAddress, "")
        preferences.put(CarSpeechStringKey.CarName, "")
        setCarAudio(false)
    }

    /** For the screen. A read from memory, so it is safe on the main thread. */
    fun carName(): String = runCatching { preferences.get(CarSpeechStringKey.CarName) }.getOrDefault("")

    private fun carAddress(): String = preferences.get(CarSpeechStringKey.CarAddress)

    private fun setCarAudio(connected: Boolean) {
        if (carAudioConnected.value != connected) aapsLogger.debug(LTag.CORE, "Car speech: car audio ${if (connected) "connected" else "gone"}")
        carAudioConnected.value = connected
    }

    private fun rememberLastConnected(device: AudioDevice) {
        preferences.put(CarSpeechStringKey.LastConnectedAddress, device.address)
        preferences.put(CarSpeechStringKey.LastConnectedName, device.name)
        _lastConnected.value = device
    }

    private fun readLastConnected(): AudioDevice? {
        val address = preferences.get(CarSpeechStringKey.LastConnectedAddress)
        return if (address.isEmpty()) null else AudioDevice(address, preferences.get(CarSpeechStringKey.LastConnectedName))
    }

    private fun hasBluetoothPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in hasBluetoothPermission
    private fun deviceName(device: BluetoothDevice): String =
        (if (hasBluetoothPermission()) device.name else null) ?: device.address

    /**
     * The car may already be connected when the plugin starts or the car is chosen. As in xDrip+
     * `areWeConnectedToMac`, ask the profiles which devices are connected now.
     */
    private fun checkCarAlreadyConnected() {
        val address = carAddress()
        if (address.isEmpty() || !hasBluetoothPermission()) return
        val adapter: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter ?: return
        for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                // Main thread: hand over at once
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) = work {
                    try {
                        @SuppressLint("MissingPermission") // checked above
                        val connected = proxy.connectedDevices.any { it.address == address }
                        if (connected) setCarAudio(true)
                    } finally {
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, profile)
        }
    }

    // ---- motion ---------------------------------------------------------------------------------

    private val motionReceiver = object : BroadcastReceiver() {
        // Main thread: hand over at once
        override fun onReceive(context: Context, intent: Intent) = work { onMotionIntent(intent) }
    }

    private fun onMotionIntent(intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        // The newest event is the state now
        val last = result.transitionEvents.lastOrNull { it.activityType == DetectedActivity.IN_VEHICLE } ?: return
        val inVehicle = last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        aapsLogger.debug(LTag.CORE, "Car speech: activity recognition says in vehicle = $inVehicle")
        motionInVehicle.value = inVehicle
    }

    private val motionPendingIntent: PendingIntent by lazy {
        // Mutable: Google Play services fills in the result
        PendingIntent.getBroadcast(
            context, MOTION_REQUEST_CODE,
            Intent(MOTION_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun hasMotionPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts activity recognition. Returns at once. Safe to call again: it does nothing while
     * running, and it starts once the permission is given later.
     */
    @SuppressLint("MissingPermission") // checked in hasMotionPermission
    fun startMotion() = work {
        if (motionReceiverRegistered) return@work
        if (!hasMotionPermission()) {
            if (!motionPermissionWarned) aapsLogger.warn(LTag.CORE, "Car speech: no permission for activity recognition, motion detection waits for it")
            motionPermissionWarned = true
            return@work
        }
        motionPermissionWarned = false
        // The pending intent is sent with this app's identity, so the receiver need not be exported
        ContextCompat.registerReceiver(context, motionReceiver, IntentFilter(MOTION_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        motionReceiverRegistered = true
        val transitions = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(it)
                .build()
        }
        // Asynchronous: Google Play services answers later, on the main thread, with only a log line
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), motionPendingIntent)
            .addOnFailureListener { aapsLogger.error(LTag.CORE, "Car speech: activity recognition could not start", it) }
    }

    /** Stops activity recognition. Returns at once. */
    @SuppressLint("MissingPermission") // removing needs no more than what started it
    fun stopMotion() = work {
        if (motionReceiverRegistered) {
            motionReceiverRegistered = false
            if (hasMotionPermission()) ActivityRecognition.getClient(context).removeActivityTransitionUpdates(motionPendingIntent)
            context.unregisterReceiver(motionReceiver)
        }
        motionInVehicle.value = false
    }

    companion object {

        private const val MOTION_ACTION = "app.aaps.plugins.sync.carspeech.ACTIVITY_TRANSITION"
        private const val MOTION_REQUEST_CODE = 7301
    }
}
