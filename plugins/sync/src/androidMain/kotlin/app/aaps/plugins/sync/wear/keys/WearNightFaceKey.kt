package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/**
 * The night face of the Custom watch face (see `CwfNight`) that the phone last chose or the watch
 * last reported, the same as `StringNonKey` `WearCwfWatchfaceName`, `WearCwfAuthorVersion` and
 * `WearCwfFileName` do for the main face. An empty name means: no night face.
 */
enum class WearNightFaceKey(
    override val key: String,
    override val defaultValue: String
) : StringNonPreferenceKey {

    NightWatchfaceName(key = "wear_cwf_night_watchface_name", defaultValue = ""),
    NightAuthorVersion(key = "wear_cwf_night_author_version", defaultValue = ""),
    NightFileName(key = "wear_cwf_night_filename", defaultValue = ""),
    ;
}
