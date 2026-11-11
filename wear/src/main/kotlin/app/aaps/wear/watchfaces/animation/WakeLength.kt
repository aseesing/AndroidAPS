package app.aaps.wear.watchfaces.animation

import app.aaps.wear.R
import app.aaps.wear.watchfaces.utils.WatchFaceSettingRow

/**
 * The watch setting "Wake-up animation": how long the animation plays after the wrist is raised, or
 * off. The stored value is a length in ms, "0" for off, or [AS_DESIGN] to keep the design's own
 * length. Pure functions only.
 */
internal object WakeLength {

    /** Keep the length the design asks for. The default. */
    const val AS_DESIGN = "design"

    /**
     * The stored setting [value] as a length in ms: null to keep the design's own length, 0 for off.
     * An unknown value keeps the design's own length.
     */
    fun parse(value: String?): Long? = value?.toLongOrNull()?.takeIf { it >= 0 }

    /** The row on the Custom watch face's settings screen. */
    fun row(): WatchFaceSettingRow.Choice = WatchFaceSettingRow.Choice(
        key = R.string.key_wake_animation_length,
        title = R.string.pref_wake_animation_length,
        entries = R.array.wake_animation_length_names,
        entryValues = R.array.wake_animation_length_values,
        defaultValue = AS_DESIGN
    )
}
