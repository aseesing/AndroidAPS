// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.data

import android.content.Context
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Performance test for the watch's data store.
 *
 * DataStore rewrites a whole file on every write. The custom watch face designs, with all their
 * images, used to share one file with the live data (BG, status, graph, treatments), so every BG
 * rewrote the designs as well.
 *
 * The test plays one hour of phone messages and counts how often each file is really rewritten.
 * DataStore writes a temp file and renames it over the old one, so every real write gives the file
 * a new inode - that is what is counted.
 *
 * Baseline, measured with the original code and the same hour sent one message at a time: 76
 * messages gave 76 rewrites of the one 879 KB file, 65 MB written per hour.
 */
class ComplicationDataWritePerfTest {

    @TempDir lateinit var tempDir: File

    private lateinit var repository: ComplicationDataRepository
    private val designFile get() = File(tempDir, "datastore/complication_data.pb")
    private val liveFile get() = File(tempDir, "datastore/complication_live.pb")

    @BeforeEach
    fun setUp() {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(tempDir)
        repository = ComplicationDataRepository(context, AAPSLoggerTest(), mock<SP>())
        repository.batchWindowMs = 50
    }

    private fun fileId(file: File): Any? =
        if (file.exists()) Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey() else null

    private var messages = 0
    private var rewrites = 0          // of the live file
    private var designRewrites = 0
    private var bytesWritten = 0L

    /**
     * Delivers the messages of one sync the way the watch does: each in its own coroutine, all at
     * once. Counts a rewrite when the file was replaced, so a sync counts at most one - which is
     * what the store should do; the baseline (one write per message) was measured with one message
     * per sync, see the class comment.
     */
    private suspend fun sync(vararg calls: suspend () -> Unit) {
        val liveBefore = fileId(liveFile)
        val designBefore = fileId(designFile)
        coroutineScope { calls.forEach { call -> launch { call() } } }
        messages += calls.size
        if (fileId(liveFile) != liveBefore) {
            rewrites++
            bytesWritten += liveFile.length()
        }
        if (fileId(designFile) != designBefore) {
            designRewrites++
            bytesWritten += designFile.length()
        }
    }

    private fun cwf(name: String, imageBytes: Int) = CwfData(
        json = "{}",
        metadata = mutableMapOf(CwfMetadataKey.CWF_NAME to name),
        resData = mutableMapOf("background" to ResData(ByteArray(imageBytes) { (it % 251).toByte() }, ResFormat.PNG))
    )

    private fun bg(time: Long, sgv: Double) = EventData.SingleBg(
        dataset = 0, timeStamp = time, sgvString = sgv.toInt().toString(), glucoseUnits = "mg/dl",
        slopeArrow = "→", delta = "+1", deltaDetailed = "+1.0", avgDelta = "+1", avgDeltaDetailed = "+1.0",
        sgv = sgv, high = 180.0, low = 70.0
    )

    private fun status(iob: String) = EventData.Status(
        dataset = 0, externalStatus = "Closed loop", iobSum = iob, iobDetail = "($iob|0.00)", cob = "0g",
        currentBasal = "0.80U/h", battery = "80", rigBattery = "--", openApsStatus = T0, bgi = "0",
        batteryLevel = 1, tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0,
        reservoirLevel = 0
    )

    private fun treatments(bolusCount: Int) = EventData.TreatmentData(
        temps = ArrayList(), basals = ArrayList(),
        boluses = ArrayList(List(bolusCount) { EventData.TreatmentData.Treatment(T0 + it * 1000L, 0.5, 0.0, true, true) }),
        predictions = ArrayList()
    )

    /**
     * One hour, as a closed loop phone sends it: per 5 minute cycle a new BG, a new graph, the
     * treatments (new only every third cycle) and the status, which the loop run then sends a second
     * time unchanged. Every 15 minutes a full resend repeats everything unchanged.
     */
    private suspend fun playOneHour() {
        val history = ArrayList<EventData.SingleBg>()
        for (cycle in 0 until 12) {
            val time = T0 + cycle * FIVE_MIN
            val bg = bg(time, 100.0 + cycle)
            history.add(bg)
            val graph = EventData.GraphData(ArrayList(history))
            val treatments = treatments(bolusCount = cycle / 3)
            val status = status(iob = "%.2f".format(1.0 + cycle / 10.0))

            sync(
                { repository.updateBgData(bg) },
                { repository.updateGraphData(graph) },
                { repository.updateTreatmentData(treatments) },
                { repository.updateStatusData(status) }
            )
            sync({ repository.updateStatusData(status.copy()) })   // loop run, nothing changed
            if (cycle % 3 == 2) sync(                               // resend, nothing changed
                { repository.updateBgData(bg.copy()) },
                { repository.updateGraphData(EventData.GraphData(ArrayList(history))) },
                { repository.updateTreatmentData(treatments(bolusCount = cycle / 3)) },
                { repository.updateStatusData(status.copy()) }
            )
        }
    }

    @Test
    fun oneHourOfPhoneMessagesRewritesTheStoreOnlyForRealChanges() = runBlocking {
        // Three designs as the watch keeps them: current, default and default full
        repository.storeCustomWatchface(cwf("default", 300_000), cwf("defaultFull", 300_000), isDefault = true)
        repository.storeCustomWatchface(cwf("current", 300_000))
        sync({ repository.updateBgData(bg(T0 - FIVE_MIN, 99.0)) })   // creates the live file
        messages = 0; rewrites = 0; designRewrites = 0; bytesWritten = 0

        playOneHour()

        println(
            "PERF store: $messages messages -> $rewrites live rewrites of ${liveFile.length() / 1024} KB and $designRewrites design rewrites " +
                "of ${designFile.length() / 1024} KB = ${bytesWritten / 1024} KB written per hour (baseline 65 MB)"
        )
        // Before: one rewrite of the whole file per message, 76. Now one live write per sync that
        // brings something new, and the designs are not touched at all.
        assertThat(rewrites).isEqualTo(12)
        assertThat(designRewrites).isEqualTo(0)
        assertThat(bytesWritten).isLessThan(200_000L)
    }

    @Test
    fun identicalStatusDoesNotRewriteTheStore() = runBlocking {
        repository.storeCustomWatchface(cwf("current", 300_000))
        sync({ repository.updateStatusData(status("1.00")) })
        sync({ repository.updateStatusData(status("1.00")) })
        assertThat(rewrites).isEqualTo(1)
    }

    @Test
    fun identicalStatusStillRenewsAnOldTimestamp() = runBlocking {
        // The timestamp tells the complications the phone is still there, see timestampRefreshMs
        sync({ repository.updateStatusData(status("1.00")) })
        val first = repository.complicationData.first().lastUpdateTimestamp
        repository.timestampRefreshMs = 0
        Thread.sleep(5)
        sync({ repository.updateStatusData(status("1.00")) })
        assertThat(rewrites).isEqualTo(2)
        assertThat(repository.complicationData.first().lastUpdateTimestamp).isGreaterThan(first)
    }

    @Test
    fun oneSyncKeepsEveryValue() = runBlocking {
        sync(
            { repository.updateBgData(bg(T0, 123.0)) },
            { repository.updateGraphData(EventData.GraphData(arrayListOf(bg(T0, 123.0)))) },
            { repository.updateTreatmentData(treatments(bolusCount = 2)) },
            { repository.updateStatusData(status("2.50")) }
        )
        val data = repository.complicationData.first()
        assertThat(rewrites).isEqualTo(1)
        assertThat(data.bgData.sgvString).isEqualTo("123")
        assertThat(data.graphData.entries).hasSize(1)
        assertThat(data.treatmentData.boluses).hasSize(2)
        assertThat(data.statusData.iobSum).isEqualTo("2.50")
    }

    @Test
    fun designsSurviveLiveUpdates() = runBlocking {
        repository.storeCustomWatchface(cwf("current", 1000))
        sync({ repository.updateBgData(bg(T0, 100.0)) })
        assertThat(repository.complicationData.first().customWatchface?.metadata?.get(CwfMetadataKey.CWF_NAME)).isEqualTo("current")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun liveDataOfTheOldSingleFileIsTakenOver() = runBlocking {
        // A watch updated from the old version: BG and design in the one old file, no live file yet
        val old = ComplicationData(bgData = bg(T0, 141.0), customWatchface = cwf("current", 1000))
        File(tempDir, "datastore").mkdirs()
        designFile.writeBytes(ProtoBuf.encodeToByteArray(ComplicationData.serializer(), old))
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(tempDir)
        val updated = ComplicationDataRepository(context, AAPSLoggerTest(), mock<SP>())

        val data = updated.complicationData.first()

        assertThat(data.bgData.sgvString).isEqualTo("141")
        assertThat(data.customWatchface?.metadata?.get(CwfMetadataKey.CWF_NAME)).isEqualTo("current")
    }

    @Test
    fun bgWithSameTimeButOtherValueIsStillStored() = runBlocking {
        // SingleBg.equals() only looks at time and color, a corrected value must not be lost
        repository.updateBgData(bg(T0, 100.0))
        repository.updateBgData(bg(T0, 104.0))
        assertThat(repository.complicationData.first().bgData.sgvString).isEqualTo("104")
    }

    private companion object {

        const val T0 = 1_700_000_000_000L
        const val FIVE_MIN = 5 * 60 * 1000L
    }
}
