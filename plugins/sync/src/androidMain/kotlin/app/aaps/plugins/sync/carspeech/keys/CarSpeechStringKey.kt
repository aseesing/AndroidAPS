// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.carspeech.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/**
 * The Bluetooth audio device of the car, and the one that connected last. Like the
 * `vehicle-mode-audio-mac` and `bluetooth-last-audio-connected-*` values of xDrip+.
 *
 * Not exported: a Bluetooth address belongs to this phone and this car.
 */
enum class CarSpeechStringKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = false
) : StringNonPreferenceKey {

    CarAddress("car_speech_car_address", ""),
    CarName("car_speech_car_name", ""),
    LastConnectedAddress("car_speech_last_connected_address", ""),
    LastConnectedName("car_speech_last_connected_name", ""),
}
