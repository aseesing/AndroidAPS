package app.aaps.wear.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.wear.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Repository for complication data using DataStore with Protocol Buffers
 *
 * Best practices implemented:
 * - Single source of truth for complication data
 * - Asynchronous reads/writes (non-blocking)
 * - Type-safe data access
 * - Corruption handling with recovery
 * - Reactive updates via Flow
 *
 */
@SingleIn(AppScope::class)
@Inject
class ComplicationDataRepository(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val sp: SP
) {

    /**
     * Default watchface variant to display, honoring the `key_include_external` preference:
     * when enabled, the full template (with external/follower views) is used.
     * Mirrors the legacy Persistence default-watchface selection.
     */
    internal fun selectDefaultWatchface(current: ComplicationData): CwfData? =
        if (sp.getBoolean(R.string.key_include_external, false))
            current.customWatchfaceDefaultFull ?: current.customWatchfaceDefault
        else
            current.customWatchfaceDefault

    private val dataStore: DataStore<ComplicationData> = DataStoreFactory.create(
        serializer = ComplicationDataSerializer(aapsLogger),
        produceFile = { context.dataStoreFile("complication_data.pb") }
    )

    /**
     * Reactive Flow of complication data
     * Complications should collect this Flow to get automatic updates
     */
    val complicationData: Flow<ComplicationData> = dataStore.data
        .catch { exception ->
            aapsLogger.error(LTag.WEAR, "Error reading complication data", exception)
            emit(ComplicationData()) // Emit default on error
        }

    /**
     * Update BG data from phone
     * Supports multiple datasets for AAPSClient mode (0=primary, 1=client1, 2=client2)
     */
    suspend fun updateBgData(singleBg: EventData.SingleBg) {
        try {
            dataStore.updateData { current ->
                when (singleBg.dataset) {
                    0 -> current.copy(bgData = singleBg, lastUpdateTimestamp = System.currentTimeMillis())
                    1 -> current.copy(bgData1 = singleBg, lastUpdateTimestamp = System.currentTimeMillis())
                    2 -> current.copy(bgData2 = singleBg, lastUpdateTimestamp = System.currentTimeMillis())
                    else -> {
                        aapsLogger.warn(LTag.WEAR, "Unknown BG dataset ${singleBg.dataset}, ignoring")
                        current
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to update BG data", e)
        }
    }

    /**
     * Update Status data from phone
     * Supports multiple datasets for AAPSClient mode (0=primary, 1=client1, 2=client2)
     */
    suspend fun updateStatusData(status: EventData.Status) {
        try {
            dataStore.updateData { current ->
                when (status.dataset) {
                    0 -> current.copy(statusData = status, lastUpdateTimestamp = System.currentTimeMillis())
                    1 -> current.copy(statusData1 = status, lastUpdateTimestamp = System.currentTimeMillis())
                    2 -> current.copy(statusData2 = status, lastUpdateTimestamp = System.currentTimeMillis())
                    else -> {
                        aapsLogger.warn(LTag.WEAR, "Unknown Status dataset ${status.dataset}, ignoring")
                        current
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to update status data", e)
        }
    }

    /**
     * Update Graph data from phone
     */
    suspend fun updateGraphData(graphData: EventData.GraphData) {
        try {
            dataStore.updateData { current ->
                current.copy(
                    graphData = graphData,
                    lastUpdateTimestamp = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to update graph data", e)
        }
    }

    /**
     * Update Treatment data from phone
     */
    suspend fun updateTreatmentData(treatmentData: EventData.TreatmentData) {
        try {
            dataStore.updateData { current ->
                current.copy(
                    treatmentData = treatmentData,
                    lastUpdateTimestamp = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to update treatment data", e)
        }
    }

    /**
     * Store custom watchface data
     * @param customWatchface Main watchface to store
     * @param customWatchfaceFull Optional full version with all resources
     * @param isDefault If true, store as default instead of current
     */
    suspend fun storeCustomWatchface(
        customWatchface: CwfData,
        customWatchfaceFull: CwfData? = null,
        isDefault: Boolean = false
    ) {
        try {
            dataStore.updateData { current ->
                if (isDefault) {
                    current.copy(
                        customWatchfaceDefault = customWatchface,
                        customWatchfaceDefaultFull = customWatchfaceFull
                    )
                } else {
                    current.copy(customWatchface = customWatchface)
                }
            }
            aapsLogger.debug(LTag.WEAR, "Stored custom watchface ${if (isDefault) "(default)" else ""}: ${customWatchface.metadata}")
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to store custom watchface", e)
        }
    }

    /**
     * Update custom watchface metadata only (preserves resources)
     * Used when syncing metadata changes without re-sending all resources
     */
    suspend fun updateCustomWatchfaceMetadata(newMetadata: Map<CwfMetadataKey, String>) {
        try {
            dataStore.updateData { current ->
                current.customWatchface?.let { withMetadata(it, newMetadata) }?.let { current.copy(customWatchface = it) } ?: current
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to update custom watchface metadata", e)
        }
    }

    /**
     * [saved] with [newMetadata], or null when the metadata is for another design (name or version
     * differ).
     */
    private fun withMetadata(saved: CwfData, newMetadata: Map<CwfMetadataKey, String>): CwfData? {
        if (newMetadata[CwfMetadataKey.CWF_NAME] != saved.metadata[CwfMetadataKey.CWF_NAME] ||
            newMetadata[CwfMetadataKey.CWF_AUTHOR_VERSION] != saved.metadata[CwfMetadataKey.CWF_AUTHOR_VERSION]
        ) return null
        aapsLogger.debug(LTag.WEAR, "Updated custom watchface metadata: $newMetadata")
        return CwfData(saved.json, newMetadata.toMutableMap(), saved.resData)
    }

    /**
     * Reset current watchface to default
     */
    suspend fun setDefaultWatchface() {
        try {
            dataStore.updateData { current ->
                selectDefaultWatchface(current)?.let { default ->
                    aapsLogger.debug(LTag.WEAR, "Reset to default watchface")
                    current.copy(customWatchface = default)
                } ?: current
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to set default watchface", e)
        }
    }

    /**
     * Get custom watchface with fallback to default
     * @param isDefault If true, read default instead of current
     * @return CwfData or null if not set
     */
    suspend fun getCustomWatchface(isDefault: Boolean = false): CwfData? {
        return try {
            val current = complicationData.first()
            if (isDefault) {
                selectDefaultWatchface(current)
            } else {
                current.customWatchface ?: selectDefaultWatchface(current)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to get custom watchface", e)
            null
        }
    }

    /**
     * Get simplified custom watchface (only main resource file)
     * Used for exporting without external resources
     * @param useDefault If true, read from default storage
     * @return Simplified CwfData or null
     */
    suspend fun getSimplifiedCustomWatchface(useDefault: Boolean = false): CwfData? {
        return try {
            val current = complicationData.first()
            val source = if (useDefault) {
                current.customWatchfaceDefaultFull ?: current.customWatchfaceDefault
            } else {
                current.customWatchface
            }

            source?.let { simplified(it) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to get simplified custom watchface", e)
            null
        }
    }

    /** [cwf] with only the main CUSTOM_WATCHFACE resource, or null when it has none. */
    private fun simplified(cwf: CwfData): CwfData? =
        cwf.resData[ResFileMap.CUSTOM_WATCHFACE.fileName]?.let { mainRes ->
            CwfData(cwf.json, cwf.metadata, mutableMapOf(ResFileMap.CUSTOM_WATCHFACE.fileName to mainRes))
        }

    // The optional night face, see CwfNight. The main face above is not touched by these. It has its
    // own file, so the BG and status writes above do not write the night design again each time. The
    // file is in the no-backup folder: the night face and its rule are never in an Auto Backup.

    private val nightStore: DataStore<NightWatchfaceData> = DataStoreFactory.create(
        serializer = NightWatchfaceSerializer(aapsLogger),
        // A file that cannot be read is replaced by "no night face", not kept unreadable for ever
        corruptionHandler = ReplaceFileCorruptionHandler { NightWatchfaceData() },
        produceFile = { File(context.noBackupFilesDir, NIGHT_WATCHFACE_FILE) }
    )

    /** Stores [cwf] as the night face. A rule for when it is day stays. */
    suspend fun storeNightWatchface(cwf: CwfData) {
        try {
            nightStore.updateData { it.copy(customWatchfaceNight = cwf) }
            aapsLogger.debug(LTag.WEAR, "Stored night watchface: ${cwf.metadata}")
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to store night watchface", e)
        }
    }

    /**
     * Stores [rule] for when it is day, but only while there is a night face: without one the rule
     * has no use, so it is not kept.
     *
     * @return true when stored, false when there is no night face
     */
    suspend fun storeDayNightRule(rule: DayNightRule): Boolean =
        try {
            nightStore.updateData { current -> if (current.customWatchfaceNight != null) current.copy(dayNightRule = rule) else current }
                .dayNightRule == rule
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to store day and night rule", e)
            false
        }

    /** Forgets the night face and its rule, so the main face shows day and night again. */
    suspend fun clearNightWatchface() {
        try {
            nightStore.updateData { NightWatchfaceData() }
            aapsLogger.debug(LTag.WEAR, "Cleared night watchface")
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to clear night watchface", e)
        }
    }

    /** The night face and its rule, or empty data when there is none. */
    suspend fun getNightWatchfaceData(): NightWatchfaceData =
        try {
            nightStore.data.first()
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to get night watchface", e)
            NightWatchfaceData()
        }

    /** The night face, or null when there is none. */
    suspend fun getNightWatchface(): CwfData? = getNightWatchfaceData().customWatchfaceNight

    /**
     * The night face for the phone, with only its main picture, or null when there is none. A night
     * face without a main picture comes without pictures, so the phone still learns its name.
     */
    suspend fun getSimplifiedNightWatchface(): CwfData? =
        getNightWatchface()?.let { simplified(it) ?: CwfData(it.json, it.metadata, mutableMapOf()) }

    /** Like [updateCustomWatchfaceMetadata], for the night face. */
    suspend fun updateNightWatchfaceMetadata(newMetadata: Map<CwfMetadataKey, String>) {
        try {
            nightStore.updateData { current ->
                current.customWatchfaceNight?.let { withMetadata(it, newMetadata) }?.let { current.copy(customWatchfaceNight = it) } ?: current
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to update night watchface metadata", e)
        }
    }

    internal companion object {

        /** The file of the night face, in the no-backup folder. */
        const val NIGHT_WATCHFACE_FILE = "night_watchface.pb"
    }
}

/**
 * Serializer for ComplicationData using Protocol Buffers
 * Handles corruption gracefully by returning default data
 */
@OptIn(ExperimentalSerializationApi::class)
private class ComplicationDataSerializer(
    private val aapsLogger: AAPSLogger
) : Serializer<ComplicationData> {

    override val defaultValue: ComplicationData = ComplicationData()

    override suspend fun readFrom(input: InputStream): ComplicationData {
        return try {
            ProtoBuf.decodeFromByteArray(
                ComplicationData.serializer(),
                input.readBytes()
            )
        } catch (e: SerializationException) {
            aapsLogger.error(LTag.WEAR, "Corrupted complication data, using default", e)
            defaultValue
        }
    }

    override suspend fun writeTo(t: ComplicationData, output: OutputStream) {
        output.write(
            ProtoBuf.encodeToByteArray(
                ComplicationData.serializer(),
                t
            )
        )
    }
}

/**
 * Serializer for [NightWatchfaceData]. Any read error is a [CorruptionException], so the store's
 * corruption handler replaces the file with "no night face" (see `ComplicationDataRepository`).
 */
@OptIn(ExperimentalSerializationApi::class)
internal class NightWatchfaceSerializer(
    private val aapsLogger: AAPSLogger
) : Serializer<NightWatchfaceData> {

    override val defaultValue: NightWatchfaceData = NightWatchfaceData()

    override suspend fun readFrom(input: InputStream): NightWatchfaceData =
        try {
            ProtoBuf.decodeFromByteArray(NightWatchfaceData.serializer(), input.readBytes())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Corrupted night watchface, using none", e)
            throw CorruptionException("Cannot read the night watchface", e)
        }

    override suspend fun writeTo(t: NightWatchfaceData, output: OutputStream) {
        output.write(ProtoBuf.encodeToByteArray(NightWatchfaceData.serializer(), t))
    }
}
