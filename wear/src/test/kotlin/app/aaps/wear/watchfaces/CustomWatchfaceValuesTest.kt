package app.aaps.wear.watchfaces

import android.content.Intent
import android.os.BatteryManager
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import app.aaps.wear.watchfaces.daynight.DayNightClock
import app.aaps.wear.watchfaces.daynight.GeoPoint
import app.aaps.wear.watchfaces.daynight.MoonLight
import app.aaps.wear.watchfaces.daynight.MoonPhase
import app.aaps.wear.watchfaces.daynight.StoredWeather
import app.aaps.wear.watchfaces.utils.PartTimeLeft
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

/**
 * The values a design reads by key (`v_<key>` of a formula and of a look condition, the resting wake
 * layers), through [CustomWatchface.designValue]. Every key a design can use is
 * checked against the data the face was given, also when there is no data.
 *
 * Written against the old path (a new `DynProvider` per read) first, so it shows that the one value
 * source gives the very same values.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CustomWatchfaceValuesTest {

    private fun newFace() = CustomWatchface().apply {
        prepareForRendering(RuntimeEnvironment.getApplication())
        renderToBitmap(400, 400).recycle()
    }

    /** Reads the stored data again, as a render-only face does before a render. */
    private fun CustomWatchface.reload() {
        Thread.sleep(600)   // past the render data share window
        refreshRenderData()
    }

    private fun setWatchBattery(percent: Int) {
        RuntimeEnvironment.getApplication().sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, percent).putExtra(BatteryManager.EXTRA_SCALE, 100)
        )
    }

    @Test
    fun everyDesignKeyGivesItsValue() {
        setWatchBattery(64)
        val face = newFace()
        val now = System.currentTimeMillis()
        runBlocking {
            face.complicationDataRepository.updateBgData(
                EventData.SingleBg(
                    dataset = 0, timeStamp = now - 2 * MINUTE, sgvString = "6,7", glucoseUnits = "mmol", slopeArrow = "→",
                    delta = "+0,1", deltaDetailed = "+0,12", avgDelta = "+0,1", avgDeltaDetailed = "+0,08", sgvLevel = 0, sgv = 121.0,
                    high = 180.0, low = 70.0, color = 0, deltaMgdl = 1.8, avgDeltaMgdl = 1.4
                )
            )
            face.complicationDataRepository.updateStatusData(
                EventData.Status(
                    dataset = 0, externalStatus = "", iobSum = "1.25", iobDetail = "(0.90|0.35)", cob = "12g", currentBasal = "0.85U/h",
                    battery = "78", rigBattery = "--", openApsStatus = now - 3 * MINUTE, bgi = "-0.4", batteryLevel = 1,
                    tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0,
                    sensorEndsAt = now + 60 * HOUR, pumpEndsAt = now + 10 * HOUR, cobValue = 12.0,
                    iobValue = 1.25, iobBolusValue = 0.9, iobBasalValue = 0.35, basalRateValue = 1.02, bgiMgdl = -7.2
                )
            )
            // The second phone: the same numbers under the keys that end in _Ext1, and a percent temp basal as text
            face.complicationDataRepository.updateStatusData(
                EventData.Status(
                    dataset = 1, externalStatus = "", iobSum = "-0,40", iobDetail = "(0,00|-0,40)", cob = "0g", currentBasal = "50%",
                    battery = "55", rigBattery = "--", openApsStatus = now - 1 * MINUTE, bgi = "+0,2", batteryLevel = 1,
                    tempTarget = "100", tempTargetLevel = 0, reservoirString = "", reservoir = 0.0, reservoirLevel = 0, cobValue = 0.0,
                    iobValue = -0.4, iobBolusValue = 0.0, iobBasalValue = -0.4, basalRateValue = 0.4, bgiMgdl = 3.6
                )
            )
        }
        runBlocking {
            // The watch keeps the place only for a design that needs it (see `PlaceRuleKeeping`)
            face.complicationDataRepository.storeCustomWatchface(
                CwfData("""{"needsPlace": true}""", mutableMapOf(CwfMetadataKey.CWF_NAME to "Values", CwfMetadataKey.CWF_AUTHOR_VERSION to "1"), mutableMapOf())
            )
            face.complicationDataRepository.storeDayNightRule(DayNightRule.Place(PLACE.latitude, PLACE.longitude))
        }
        StoredWeather.save(face.sp, EventData.Weather(now - 10 * MINUTE, EventData.Weather.CLEAR, 7.5))
        face.reload()
        face.setRenderInstant(now)
        val sky = DayNightClock(location = { PLACE })

        val expected = mapOf(
            "sgv" to 121.0,
            "delta" to 1.8,
            "avg_delta" to 1.4,
            "uploader_battery" to 78.0,
            "rig_battery" to null,
            "reservoir" to 120.0,
            "loop" to 3.0,
            "timestamp" to 2.0,
            "month" to LocalDateTime.now().monthValue.toDouble(),
            "watch_battery" to 64.0,
            "weather_temperature" to 7.5,
            "sensor_left" to PartTimeLeft.hours(now + 60 * HOUR, now),
            "pump_left" to PartTimeLeft.hours(now + 10 * HOUR, now),
            "moon_phase" to MoonPhase.at(now),
            "sun_progress" to sky.sunProgress(now),
            "night_progress" to sky.nightProgress(now),
            "sun_cycle" to sky.sunCycle(now),
            "moon_cycle" to sky.moonCycle(now),
            "moon_lit" to MoonLight.at(now, PLACE).litFraction,
            "moon_limb" to MoonLight.at(now, PLACE).limbTurn,
            "moon_north" to MoonLight.at(now, PLACE).northTurn,
            "sun_next_time" to sky.nextSunEvent(now)?.toDouble(),
            "iob" to 1.25,
            "iob_bolus" to 0.9,
            "iob_basal" to 0.35,
            "cob" to 12.0,
            "basalRate" to 1.02,
            "bgi" to -7.2,
            "iob_Ext1" to -0.4,
            "iob_bolus_Ext1" to 0.0,
            "iob_basal_Ext1" to -0.4,
            "cob_Ext1" to 0.0,
            "basalRate_Ext1" to 0.4,
            "bgi_Ext1" to 3.6
        )
        for ((key, value) in expected) assertWithMessage(key).that(face.designValue(key)).isEqualTo(value)
        // A key that is no value, and the empty key, have none
        assertThat(face.designValue("no_such_key")).isNull()
        assertThat(face.designValue("")).isNull()
    }

    @Test
    fun withoutDataTheDataKeysHaveNoValue() {
        val face = newFace()
        face.reload()
        for (key in listOf("sgv", "delta", "avg_delta", "timestamp", "loop", "uploader_battery", "weather_temperature", "sun_next_time", "sensor_left", "pump_left",
            "iob", "iob_bolus", "iob_basal", "cob", "basalRate", "bgi", "iob_Ext2", "cob_Ext2", "basalRate_Ext2", "bgi_Ext2"))
            assertWithMessage(key).that(face.designValue(key)).isNull()
        // The sky needs no data: without a place it follows the fixed hours
        assertThat(face.designValue("sun_cycle")).isNotNull()
    }

    private companion object {

        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        val PLACE = GeoPoint(52.1, 5.1)
    }
}
