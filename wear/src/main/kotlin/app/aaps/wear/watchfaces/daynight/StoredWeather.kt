// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.impl.weardata.CwfPlaceNeeds
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
    const val JSON_VALUE_KEY = CwfPlaceNeeds.WEATHER_TEMPERATURE

    /** The value of the kind of weather for a design: [KINDS] gives its number. */
    const val KIND_VALUE_KEY = "weather_kind"

    /** The kinds of weather the phone sends, in the order of their number: 0 clear, 1 partly cloudy, 2 cloudy, 3 rain. */
    val KINDS = listOf(EventData.Weather.CLEAR, EventData.Weather.PARTLY, EventData.Weather.CLOUDY, EventData.Weather.RAIN)

    /** The number of the weather [kind] (see [KINDS]), or null for no weather or one of another kind. */
    fun kindNumber(kind: String?): Double? = KINDS.indexOf(kind).takeIf { it >= 0 }?.toDouble()

    /**
     * Keeps [weather] in [sp]. A weather without a temperature (from an older phone) clears the old one.
     * A weather without a kind is "no weather": the phone sends it when the weather may not be looked
     * up any more (the switch "Use the phone's location" is off, or no design shows it), so the old one
     * is forgotten at once, as when there never was one.
     */
    fun save(sp: SP, weather: EventData.Weather) {
        if (weather.kind.isEmpty()) return clear(sp)
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
