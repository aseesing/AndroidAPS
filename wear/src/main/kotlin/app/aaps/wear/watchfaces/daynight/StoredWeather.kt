// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R

/**
 * The weather the phone sent, kept in the preferences for the Custom watch face. Private patch.
 *
 * The watch keeps it when the message comes (`DataHandlerWear`), and so does the phone's live
 * preview of the face (`CwfPreviewRenderer`). The face reads the kind of weather for its weather looks
 * and the temperature for the value [JSON_VALUE_KEY].
 */
object StoredWeather {

    /** The value of the temperature for a design (`dynData`), in °C. */
    const val JSON_VALUE_KEY = "weather_temperature"

    /** Keeps [weather] in [sp]. A weather without a temperature (from an older phone) clears the old one. */
    fun save(sp: SP, weather: EventData.Weather) {
        if (sp.getString(R.string.key_weather_kind, "") != weather.kind) sp.putString(R.string.key_weather_kind, weather.kind)
        sp.putLong(R.string.key_weather_time, weather.timeStamp)
        sp.putString(R.string.key_weather_temperature, weather.temperature?.toString() ?: "")
    }

    /** Forgets the weather kept in [sp]. The phone's live preview does this when it starts, so it never shows the weather of an earlier run. */
    fun clear(sp: SP) {
        sp.remove(R.string.key_weather_kind)
        sp.remove(R.string.key_weather_time)
        sp.remove(R.string.key_weather_temperature)
    }

    /** The temperature kept in [sp], in °C, or null when there is none. Whether the weather is still current is for the caller. */
    fun temperature(sp: SP): Double? = sp.getString(R.string.key_weather_temperature, "").toDoubleOrNull()
}
