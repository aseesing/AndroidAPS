package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

/**
 * What the watch of this phone said about its night face. Not exported: another phone has another
 * watch, or the same watch in another state.
 */
enum class WearNightFaceBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = false
) : BooleanNonPreferenceKey {

    /** The watch confirmed that it has a night face. */
    OnWatch(key = "wear_cwf_night_on_watch", defaultValue = false),

    /** The user removed the night face, and the watch has not confirmed it yet. */
    RemovalPending(key = "wear_cwf_night_removal_pending", defaultValue = false),
    ;
}
