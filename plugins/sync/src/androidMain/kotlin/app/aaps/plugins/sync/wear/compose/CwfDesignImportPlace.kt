package app.aaps.plugins.sync.wear.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.keys.WearDesignImportKey

/*
 * The file picker of the design import opens where the wearer picked designs last time.
 *
 * Without this the picker opens at the place Android chooses. A wearer who keeps all designs in
 * one folder (in Google Drive, for example) imports from there every time, so the phone remembers
 * the last picked file and opens the picker in its folder again.
 */

/**
 * Remembers the last picked design, under [WearDesignImportKey.LastPickedDesign].
 *
 * @param preferences where the link to the last picked file is kept
 */
internal class CwfDesignImportPlace(private val preferences: Preferences) {

    /** The link to the last picked file, so the file picker opens in its folder. Null the first time. */
    fun last(): String? = preferences.get(WearDesignImportKey.LastPickedDesign).ifEmpty { null }

    /** Remembers the first of [uris] as the place of the next pick. Does nothing for an empty pick. */
    fun remember(uris: List<Uri>) {
        val first = uris.firstOrNull() ?: return
        preferences.put(WearDesignImportKey.LastPickedDesign, first.toString())
    }
}

/**
 * The file picker for one or more design zips. It opens at the place
 * [lastPlace] gives when there is one. [lastPlace] is read when the picker opens, so a pick made
 * just before counts.
 */
internal class OpenDesignsAt(private val lastPlace: () -> String?) : ActivityResultContracts.OpenMultipleDocuments() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            lastPlace()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it.toUri()) }
        }
}
