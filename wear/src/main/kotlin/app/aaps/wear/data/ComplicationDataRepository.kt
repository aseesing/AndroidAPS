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
import app.aaps.wear.watchdirect.WatchDirectReadings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
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
     * The live data - BG, status, graph, treatments - in a small file of its own. The file above keeps
     * only the designs; its live fields are no longer written or read, except once to fill this file.
     * See [LiveComplicationData].
     */
    private val liveStore: DataStore<LiveComplicationData> = DataStoreFactory.create(
        serializer = LiveComplicationDataSerializer(aapsLogger),
        migrations = listOf(
            LiveDataFromDesignFile(
                liveFileExists = { context.dataStoreFile(LIVE_FILE).exists() },
                readDesignFile = { dataStore.data.first() }
            )
        ),
        produceFile = { context.dataStoreFile(LIVE_FILE) }
    )

    /**
     * Reactive Flow of complication data
     * Complications should collect this Flow to get automatic updates
     *
     * While the watch shows its own sensor reading and the phone has had no new reading for too long,
     * the values only the phone knows (IOB, COB, basal) come out blank and the predictions empty, see
     * [WatchDirectReadings.withoutOldPhoneValues]. The store keeps the last values: the blank is made
     * here, on the way out, so every face, complication and tile gets it, and the next status from
     * the phone shows at once.
     */
    val complicationData: Flow<ComplicationData> = combine(
        // Each file on its own: a file that cannot be read gives its defaults, the other keeps working
        dataStore.data.catch { exception ->
            aapsLogger.error(LTag.WEAR, "Error reading watch face designs", exception)
            emit(ComplicationData())
        },
        liveStore.data.catch { exception ->
            aapsLogger.error(LTag.WEAR, "Error reading live complication data", exception)
            emit(LiveComplicationData.EMPTY)
        }
    ) { designs, live -> designs.withLive(live) }
        .catch { exception ->
            aapsLogger.error(LTag.WEAR, "Error reading complication data", exception)
            emit(ComplicationData()) // Emit default on error
        }
        .map { stored ->
            if (WatchDirectReadings.phoneValuesAreOld(stored.bgData.fromSensor, stored.phoneReadingTimestamp, System.currentTimeMillis()))
                stored.copy(
                    statusData = WatchDirectReadings.withoutOldPhoneValues(stored.statusData),
                    treatmentData = WatchDirectReadings.withoutOldPredictions(stored.treatmentData)
                )
            else stored
        }

    /**
     * Update BG data from phone
     * Supports multiple datasets for AAPSClient mode (0=primary, 1=client1, 2=client2)
     */
    suspend fun updateBgData(singleBg: EventData.SingleBg) {
        try {
            updateLiveData { current ->
                when (singleBg.dataset) {
                    // A newer reading the watch took from the sensor itself (direct-to-watch) is not
                    // replaced by an older one from the phone. The phone's copy of the same reading wins.
                    0    -> {
                        val chosen = WatchDirectReadings.choose(current.bgData, singleBg)
                        // The reading's own time. The phone sends an old reading again when it has no new one.
                        val phoneTime = if (singleBg.fromSensor) current.phoneReadingTimestamp else maxOf(current.phoneReadingTimestamp, singleBg.timeStamp)
                        if (same(EventData.SingleBg.serializer(), current.bgData, chosen) && phoneTime == current.phoneReadingTimestamp) current
                        else current.copy(bgData = chosen, phoneReadingTimestamp = phoneTime)
                    }
                    1    -> if (same(EventData.SingleBg.serializer(), current.bgData1, singleBg)) current else current.copy(bgData1 = singleBg)
                    2    -> if (same(EventData.SingleBg.serializer(), current.bgData2, singleBg)) current else current.copy(bgData2 = singleBg)
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
     * A reading the watch took from the sensor itself (direct-to-watch). It goes on the face when it
     * is newer than what the phone sent, and into the graph where the graph has no reading of that
     * time. It stays on this watch.
     */
    suspend fun addSensorReading(reading: EventData.SingleBg) {
        try {
            // Live data, like the phone's updates: one batched write, and updateLiveData sets the time
            updateLiveData { current ->
                current.copy(
                    bgData = WatchDirectReadings.choose(current.bgData, reading),
                    graphData = EventData.GraphData(WatchDirectReadings.mergeIntoGraph(current.graphData.entries, listOf(reading)))
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to add sensor reading", e)
        }
    }

    /** What the faces show right now. */
    suspend fun current(): ComplicationData = complicationData.first()

    /**
     * Update Status data from phone
     * Supports multiple datasets for AAPSClient mode (0=primary, 1=client1, 2=client2)
     */
    suspend fun updateStatusData(status: EventData.Status) {
        try {
            updateLiveData { current ->
                when (status.dataset) {
                    0    -> if (same(EventData.Status.serializer(), current.statusData, status)) current else current.copy(statusData = status)
                    1    -> if (same(EventData.Status.serializer(), current.statusData1, status)) current else current.copy(statusData1 = status)
                    2    -> if (same(EventData.Status.serializer(), current.statusData2, status)) current else current.copy(statusData2 = status)
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
     * Update Graph data from phone. Readings the watch took from the sensor itself (direct-to-watch)
     * stay where the phone has none of that time, see [WatchDirectReadings.keepOwnReadings].
     */
    suspend fun updateGraphData(graphData: EventData.GraphData) {
        try {
            updateLiveData { current ->
                val merged = EventData.GraphData(WatchDirectReadings.keepOwnReadings(graphData.entries, current.graphData.entries))
                if (same(EventData.GraphData.serializer(), current.graphData, merged)) current else current.copy(graphData = merged)
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
            updateLiveData { current ->
                if (same(EventData.TreatmentData.serializer(), current.treatmentData, treatmentData)) current else current.copy(treatmentData = treatmentData)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to update treatment data", e)
        }
    }

    /**
     * How long the first live update of a sync waits for the others, so they reach the file together.
     *
     * The phone sends BG, graph, treatments and status as separate messages within a fraction of a
     * second. Written one by one, that was four file writes per sync, and every collector - the watch
     * face, the tiles, the complications - redrew four times for one change.
     *
     * A var only so a test can shorten it.
     */
    internal var batchWindowMs = 500L

    /**
     * How long a sync with nothing new may go without stamping [ComplicationData.lastUpdateTimestamp].
     *
     * The stamp says when the phone was last heard from - the complications show "no sync" once it is
     * old - so a message with known data must still renew it, but not with a full write every time.
     */
    internal var timestampRefreshMs = 60_000L

    private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingChanges = ArrayList<(LiveComplicationData) -> LiveComplicationData>()
    private var pendingWrite: CompletableDeferred<Unit>? = null

    /**
     * Applies [change] together with the other live updates that arrive within [batchWindowMs], in one
     * write. A change that returns its input unchanged costs nothing, and when no change in the batch
     * found anything new the file is not written at all, apart from renewing an old timestamp.
     *
     * Returns once the write is done, like a direct `updateData` call did, so a caller that refreshes
     * something after it still sees the new data.
     */
    private suspend fun updateLiveData(change: (LiveComplicationData) -> LiveComplicationData) {
        val written = synchronized(pendingChanges) {
            pendingChanges.add(change)
            pendingWrite ?: CompletableDeferred<Unit>().also { deferred ->
                pendingWrite = deferred
                batchScope.launch {
                    delay(batchWindowMs)
                    writePending(deferred)
                }
            }
        }
        written.await()
    }

    private suspend fun writePending(written: CompletableDeferred<Unit>) {
        val changes = synchronized(pendingChanges) {
            pendingWrite = null
            pendingChanges.toList().also { pendingChanges.clear() }
        }
        try {
            liveStore.updateData { current ->
                val now = System.currentTimeMillis()
                val changed = changes.fold(current) { data, change -> change(data) }
                when {
                    changed !== current                                  -> changed.copy(lastUpdateTimestamp = now)
                    now - current.lastUpdateTimestamp >= timestampRefreshMs -> current.copy(lastUpdateTimestamp = now)
                    else                                                 -> current
                }
            }
            written.complete(Unit)
        } catch (e: Throwable) {
            // Anything, so no caller waits forever; the callers log it
            written.completeExceptionally(e)
        }
    }

    /**
     * Compares by the stored bytes, not by `equals`: [EventData.SingleBg.equals] looks only at the time
     * and the colour, so a corrected value with the same time would otherwise be dropped.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> same(serializer: KSerializer<T>, stored: T, received: T): Boolean =
        ProtoBuf.encodeToByteArray(serializer, stored).contentEquals(ProtoBuf.encodeToByteArray(serializer, received))

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
            dropUnusedRule() // PERSONAL PATCH: the place stays only while something needs it, see PlaceRuleKeeping.kt
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
            dropUnusedRule() // PERSONAL PATCH: the place stays only while something needs it, see PlaceRuleKeeping.kt
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
        migrations = listOf(NightFaceFromDesignFile(dataStore, nightFileExists = { File(context.noBackupFilesDir, NIGHT_WATCHFACE_FILE).exists() })), // PERSONAL PATCH
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
     * has no use, so it is not kept. PERSONAL PATCH: also while the main design needs the place, see
     * [PlaceRuleKeeping].
     *
     * @return true when stored, false when there is no night face
     */
    suspend fun storeDayNightRule(rule: DayNightRule): Boolean =
        try {
            val main = getCustomWatchface() // PERSONAL PATCH: a design that needs the place keeps the rule too
            nightStore.updateData { current -> if (placeRule.keepsRule(current.customWatchfaceNight, main)) current.copy(dayNightRule = rule) else current } // PERSONAL PATCH
                .dayNightRule == rule
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to store day and night rule", e)
            false
        }

    /** Forgets the night face and its rule, so the main face shows day and night again. */
    suspend fun clearNightWatchface() {
        try {
            val main = getCustomWatchface() // PERSONAL PATCH: a main design that needs the place keeps the rule
            nightStore.updateData { current -> NightWatchfaceData(dayNightRule = current.dayNightRule.takeIf { placeRule.mainNeedsRule(main) }) } // PERSONAL PATCH
            aapsLogger.debug(LTag.WEAR, "Cleared night watchface")
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to clear night watchface", e)
        }
    }

    // ---- PERSONAL PATCH: the rule without a night face, see PlaceRuleKeeping.kt ----

    private val placeRule = PlaceRuleKeeping()

    /**
     * The phone says nothing needs the rule any more: it is forgotten, unless the watch still has a
     * night face (then the phone is wrong about it and must be told, see `DataHandlerWear`).
     *
     * @return true when the watch has no rule now, false when it keeps it for its night face
     */
    suspend fun clearDayNightRule(): Boolean =
        try {
            nightStore.updateData { current -> if (current.customWatchfaceNight != null) current else current.copy(dayNightRule = null) }
                .dayNightRule == null
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to clear day and night rule", e)
            true
        }

    /**
     * Forgets the rule when neither a night face nor the main design needs it, after the main design
     * changed. Without the night face file there is no rule: then the file is not opened.
     */
    private suspend fun dropUnusedRule() {
        if (!File(context.noBackupFilesDir, NIGHT_WATCHFACE_FILE).exists()) return
        try {
            val main = getCustomWatchface()
            nightStore.updateData { current ->
                if (current.dayNightRule != null && !placeRule.keepsRule(current.customWatchfaceNight, main)) current.copy(dayNightRule = null) else current
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Failed to drop the day and night rule", e)
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

        /** The file of [LiveComplicationData]. */
        private const val LIVE_FILE = "complication_live.pb"

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
