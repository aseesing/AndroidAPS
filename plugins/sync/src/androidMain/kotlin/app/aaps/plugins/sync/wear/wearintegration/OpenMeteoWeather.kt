package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * The weather now at a rough place, from Open-Meteo (free, no account), for the look of the Custom
 * watch face. Only the kind of weather (clear, partly cloudy, cloudy or rain) and the temperature go
 * to the watch.
 *
 * @param download reads the body of an https GET, or null when it fails
 */
class OpenMeteoWeather(private val download: (String) -> String?) {

    /** The weather now: one of the `EventData.Weather` kinds, and the temperature in °C (null when the answer has none). */
    data class Now(val kind: String, val temperature: Double?)

    /** The weather at [latitude], [longitude], or null when it cannot be read. */
    fun read(latitude: Double, longitude: Double): Now? {
        val body = download(url(latitude, longitude)) ?: return null
        val code = parseCode(body) ?: return null
        return Now(kindOf(code), parseTemperature(body))
    }

    companion object {

        /** How often the weather is read. */
        const val REFRESH_MS = 30 * 60 * 1000L

        /** The request: only the weather code and the temperature (°C, the default) of now, for a place rounded to 0.1 degree. */
        fun url(latitude: Double, longitude: Double): String =
            String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f&current=weather_code,temperature_2m", latitude, longitude)

        /** The WMO weather code of now in an Open-Meteo answer, or null when it has none. */
        fun parseCode(body: String): Int? = try {
            Json.parseToJsonElement(body).jsonObject["current"]?.jsonObject?.get("weather_code")?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }

        /** The temperature of now in an Open-Meteo answer, in °C, or null when it has none. */
        fun parseTemperature(body: String): Double? = try {
            Json.parseToJsonElement(body).jsonObject["current"]?.jsonObject?.get("temperature_2m")?.jsonPrimitive?.doubleOrNull
        } catch (_: Exception) {
            null
        }

        /**
         * The kind of weather for a WMO weather code: 0 and 1 clear, 2 partly cloudy, 3 and fog cloudy,
         * and all rain, drizzle, snow, showers and thunder rain (something falls from the sky).
         */
        fun kindOf(code: Int): String = when (code) {
            0, 1     -> EventData.Weather.CLEAR
            2        -> EventData.Weather.PARTLY
            3, 45, 48 -> EventData.Weather.CLOUDY
            else     -> if (code in 51..99) EventData.Weather.RAIN else EventData.Weather.CLOUDY
        }
    }
}
