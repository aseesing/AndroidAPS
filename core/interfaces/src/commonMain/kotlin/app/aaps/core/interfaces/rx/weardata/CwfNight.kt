// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.core.interfaces.rx.weardata

/**
 * A day face and a night face for the Custom watch face.
 *
 * The watch keeps two designs: the main one (what it always had) and an optional night one. With
 * no night face the main face shows day and night, as before. With a night face, the watch shows
 * the main face in the day and the night face at night; when it is day follows the watch setting
 * "Day / night look" (automatic from sunrise to sunset, always day, always night).
 *
 * The phone sends a night face zip on the message path of a normal zip plus [PATH_SUFFIX], so the
 * watch's listener (which filters on the path prefix) receives it without a manifest change.
 */
object CwfNight {

    /** Added to `path_rx_data_bridge` for a night face zip. */
    const val PATH_SUFFIX = "_night"
}
