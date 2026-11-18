// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.wear.daynight.WearPlaceUse
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * The weather for the look of the Custom watch face, see [OpenMeteoWeather].
 *
 * Looked up at most every half hour, and only when a design on the watch shows the weather and there
 * may be a place for it ([WearPlaceUse.weatherAllowed]): the phone's rough place with the switch "Use
 * the phone's location" on (and the permission), else a city the user chose (public data). Without
 * that, no location is read and nothing goes to the weather service; the watch is told once (in each
 * process, and again after each new weather) that there is no weather, so it forgets the last one at
 * once (see `StoredWeather` on the watch).
 *
 * Nothing about the place is logged: not the place, not the request (it holds the place), not the
 * text of an error.
 */
@SingleIn(AppScope::class)
@Inject
class WearWeather(
    private val placeUse: WearPlaceUse,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger
) {

    /** Reads the weather at a place; replaced in the tests. */
    internal var lookUp: (latitude: Double, longitude: Double) -> OpenMeteoWeather.Now? = OpenMeteoWeather(::httpGet)::read

    @Volatile private var last: EventData.Weather? = null
    @Volatile private var askedAt = 0L

    /**
     * True when the watch was told "no weather" and got no weather since. Starts false, so the first
     * refresh of a process without weather tells the watch: after a restart (revoking the permission
     * stops the app) the phone does not know what the watch still shows.
     */
    @Volatile private var toldNoWeather = false

    /** The last weather sent to the watch, so a watch that restarted has it at once; null when there is none. */
    fun last(): EventData.Weather? = last

    /**
     * Looks up the weather when the last look-up is half an hour old, in [scope], and sends it to the
     * watch. Sent also when it did not change, so the watch knows it is still current.
     *
     * Never throws: it runs before the status goes to the watch, and the status must always go.
     */
    fun refresh(scope: CoroutineScope) {
        try {
            if (!placeUse.weatherAllowed()) return forget()
            val now = dateUtil.now()
            if (now - askedAt < OpenMeteoWeather.REFRESH_MS) return
            val place = placeUse.weatherPlace() ?: return
            askedAt = now
            scope.launch {
                val reading = try {
                    lookUp(place.latitude, place.longitude)
                } catch (e: Exception) {
                    // Only the kind of error: the text could hold the place
                    aapsLogger.debug(LTag.WEAR, "Weather not read: ${e.javaClass.simpleName}")
                    null
                } ?: return@launch
                aapsLogger.debug(LTag.WEAR, "Weather for the watch: $reading")
                EventData.Weather(now, reading.kind, reading.temperature).also {
                    last = it
                    toldNoWeather = false
                    rxBus.send(EventMobileToWear(it))
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Weather not refreshed: ${e.javaClass.simpleName}")
        }
    }

    /** No weather any more: the watch forgets the last one. Told once, and again only after a new weather. */
    private fun forget() {
        askedAt = 0L
        last = null
        if (toldNoWeather) return
        toldNoWeather = true
        rxBus.send(EventMobileToWear(EventData.Weather(dateUtil.now(), NO_WEATHER)))
    }

    /** The body of an https GET, or null when it fails. Short timeouts: the weather can wait. */
    private fun httpGet(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 10_000
            try {
                if (responseCode == HttpURLConnection.HTTP_OK) inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                disconnect()
            }
        }
    } catch (e: Exception) {
        // Only the kind of error: the text of some errors holds the address, which holds the place
        aapsLogger.debug(LTag.WEAR, "Weather not read: ${e.javaClass.simpleName}")
        null
    }

    companion object {

        /** The kind of a weather message that says there is no weather. */
        const val NO_WEATHER = ""
    }
}
