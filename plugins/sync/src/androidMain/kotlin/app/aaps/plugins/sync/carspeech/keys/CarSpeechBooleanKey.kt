// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.carspeech.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

/** The two ways to know the phone is in the car, as in the vehicle mode of xDrip+. */
enum class CarSpeechBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val defaultedBySM: Boolean = false,
    override val hideParentScreenIfHidden: Boolean = false,
) : BooleanPreferenceKey {

    ViaCarAudio("car_speech_via_car_audio", true, title = SyncStrings.car_speech_via_car_audio, summary = SyncStrings.car_speech_via_car_audio_summary),
    ViaMotion("car_speech_via_motion", false, title = SyncStrings.car_speech_via_motion, summary = SyncStrings.car_speech_via_motion_summary),
    ;
}
