// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/** What the phone remembers for the import of watch face designs, see `CwfDesignImport`. */
enum class WearDesignImportKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean
) : StringNonPreferenceKey {

    /**
     * The last zip picked in the file picker, so the picker opens in the same folder next time.
     * Not exported: the link only works on this phone.
     */
    LastPickedDesign(key = "wear_cwf_design_import_last_picked", defaultValue = "", exportable = false),
    ;
}
