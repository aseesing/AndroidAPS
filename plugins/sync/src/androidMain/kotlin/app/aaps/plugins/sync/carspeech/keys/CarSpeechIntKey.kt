// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.carspeech.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

/**
 * Both are lists. The plugin adds the choices (`withEntries`), so the threshold is shown in the
 * glucose unit of the user, while it is stored in mg/dl.
 */
enum class CarSpeechIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.LIST,
    override val defaultedBySM: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
) : IntPreferenceKey {

    /** In mg/dl. */
    ChangeThreshold("car_speech_change_threshold", 10, 5, 50, title = SyncStrings.car_speech_change_threshold, summary = SyncStrings.car_speech_change_threshold_summary),

    /** In minutes. */
    OutOfRangeRepeat("car_speech_out_of_range_repeat", 10, 5, 60, title = SyncStrings.car_speech_out_of_range_repeat, summary = SyncStrings.car_speech_out_of_range_repeat_summary),
    ;
}
