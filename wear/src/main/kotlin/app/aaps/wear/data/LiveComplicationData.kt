package app.aaps.wear.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.Serializer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.io.OutputStream

/**
 * The part of [ComplicationData] that the phone sends all day: BG, status, graph and treatments.
 *
 * Kept in a file of its own, apart from the custom watch face designs. The designs hold all their
 * images, often close to a megabyte, and DataStore rewrites the whole file on every write. While both
 * lived in one file, every BG rewrote the designs too, a few hundred megabytes of flash a day. This
 * file is a few kilobytes.
 *
 * Field names match [ComplicationData], so [ComplicationData.withLive] and [ComplicationData.toLive]
 * are plain copies.
 */
@Serializable
data class LiveComplicationData(
    val bgData: EventData.SingleBg,
    val bgData1: EventData.SingleBg,
    val bgData2: EventData.SingleBg,
    val statusData: EventData.Status,
    val statusData1: EventData.Status,
    val statusData2: EventData.Status,
    val graphData: EventData.GraphData,
    val treatmentData: EventData.TreatmentData,
    val lastUpdateTimestamp: Long
) {

    companion object {

        /** Nothing received yet: the same defaults as an empty [ComplicationData]. */
        val EMPTY: LiveComplicationData = ComplicationData().toLive()
    }
}

/** The live part of this data. */
fun ComplicationData.toLive() = LiveComplicationData(
    bgData = bgData, bgData1 = bgData1, bgData2 = bgData2,
    statusData = statusData, statusData1 = statusData1, statusData2 = statusData2,
    graphData = graphData, treatmentData = treatmentData, lastUpdateTimestamp = lastUpdateTimestamp
)

/** These designs with [live] data. */
fun ComplicationData.withLive(live: LiveComplicationData) = copy(
    bgData = live.bgData, bgData1 = live.bgData1, bgData2 = live.bgData2,
    statusData = live.statusData, statusData1 = live.statusData1, statusData2 = live.statusData2,
    graphData = live.graphData, treatmentData = live.treatmentData, lastUpdateTimestamp = live.lastUpdateTimestamp
)

/**
 * Fills a new live file with the live data that the old single file still holds, so the face shows
 * the last values at once after an update instead of "---" until the phone sends again.
 *
 * Runs only while the live file has never been written; the old file itself is left as it is.
 */
internal class LiveDataFromDesignFile(
    private val readDesignFile: suspend () -> ComplicationData
) : DataMigration<LiveComplicationData> {

    override suspend fun shouldMigrate(currentData: LiveComplicationData): Boolean = currentData == LiveComplicationData.EMPTY

    override suspend fun migrate(currentData: LiveComplicationData): LiveComplicationData = readDesignFile().toLive()

    override suspend fun cleanUp() {}
}

/** ProtoBuf, like the design file. A damaged file gives the empty value, the phone sends again soon. */
@OptIn(ExperimentalSerializationApi::class)
internal class LiveComplicationDataSerializer(private val aapsLogger: AAPSLogger) : Serializer<LiveComplicationData> {

    override val defaultValue: LiveComplicationData = LiveComplicationData.EMPTY

    override suspend fun readFrom(input: InputStream): LiveComplicationData =
        try {
            ProtoBuf.decodeFromByteArray(LiveComplicationData.serializer(), input.readBytes())
        } catch (e: SerializationException) {
            aapsLogger.error(LTag.WEAR, "Corrupted live complication data, using default", e)
            defaultValue
        }

    override suspend fun writeTo(t: LiveComplicationData, output: OutputStream) {
        output.write(ProtoBuf.encodeToByteArray(LiveComplicationData.serializer(), t))
    }
}
