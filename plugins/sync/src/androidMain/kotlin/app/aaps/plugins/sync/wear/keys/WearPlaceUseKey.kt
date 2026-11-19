package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/**
 * What the designs on the watch need the place for, as the phone last learned it from the watch, see
 * `WearPlaceUse`. Kept, so the phone knows it after a restart too, before the watch sends its design
 * again. Names of `CwfPlaceUse`, with a comma between them; empty when the design needs nothing. Not
 * exported: it is about the watch of this phone.
 */
enum class WearPlaceUseKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = false
) : StringNonPreferenceKey {

    /** The main face. Not stored before the phone learned it once. */
    MainDesign(key = "wear_place_uses_main", defaultValue = ""),

    /** The night face, only while the watch has one. */
    NightDesign(key = "wear_place_uses_night", defaultValue = ""),
    ;
}
