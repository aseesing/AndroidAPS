// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first

/**
 * Moves the night face that the personal build kept in the design file
 * ([ComplicationData.customWatchfaceNight]) once into the night face file ([NightWatchfaceData]), so it
 * does not vanish with the update. The rule for when it is day was never stored there: the phone sends
 * it as soon as the watch reports its night face.
 *
 * Runs only while the night face file does not exist yet. Afterwards the night face is removed from the
 * design file, so the frequent writes there no longer carry it. Like `LiveDataFromDesignFile`.
 *
 * @param designs the design file
 * @param nightFileExists true when the night face file is on the disk
 */
internal class NightFaceFromDesignFile(
    private val designs: DataStore<ComplicationData>,
    private val nightFileExists: () -> Boolean
) : DataMigration<NightWatchfaceData> {

    override suspend fun shouldMigrate(currentData: NightWatchfaceData): Boolean = !nightFileExists()

    override suspend fun migrate(currentData: NightWatchfaceData): NightWatchfaceData =
        designs.data.first().customWatchfaceNight?.let { currentData.copy(customWatchfaceNight = it) } ?: currentData

    /** After the night face file is written: the design file forgets its copy. */
    override suspend fun cleanUp() {
        designs.updateData { if (it.customWatchfaceNight != null) it.copy(customWatchfaceNight = null) else it }
    }
}
