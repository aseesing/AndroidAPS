// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.preview

import app.aaps.wear.phonepreview.PreviewHost

/**
 * Which face a live preview shows. Each has its own renderer and storage, so both can be on screen.
 *
 * @param storageName the folder and preferences prefix of its renderer, see [PreviewHost]
 */
enum class PreviewSlot(val storageName: String) {

    /** The main face: the day face, or the only face. Keeps the storage of the first preview. */
    DAY(PreviewHost.DEFAULT_NAME),

    /** The night face. */
    NIGHT("cwf_preview_night")
}
