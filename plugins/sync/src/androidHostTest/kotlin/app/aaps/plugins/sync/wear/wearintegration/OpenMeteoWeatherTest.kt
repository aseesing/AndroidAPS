// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OpenMeteoWeatherTest {

    private val answer = """
        {"latitude":52.4,"longitude":4.9,"current_units":{"time":"iso8601","interval":"seconds","weather_code":"wmo code"},
         "current":{"time":"2026-11-12T09:00","interval":900,"weather_code":61}}
    """.trimIndent()

    @Test
    fun theRequestHasOnlyTheRoundedPlaceAndTheCode() {
        assertThat(OpenMeteoWeather.url(52.3712, 4.8961))
            .isEqualTo("https://api.open-meteo.com/v1/forecast?latitude=52.4&longitude=4.9&current=weather_code")
        assertThat(OpenMeteoWeather.url(-33.86, 151.21)).contains("latitude=-33.9&longitude=151.2")
    }

    @Test
    fun theCodeIsReadFromTheAnswer() {
        assertThat(OpenMeteoWeather.parseCode(answer)).isEqualTo(61)
        assertThat(OpenMeteoWeather.parseCode("""{"current":{}}""")).isNull()
        assertThat(OpenMeteoWeather.parseCode("not json")).isNull()
    }

    @Test
    fun everyCodeHasAKind() {
        assertThat(OpenMeteoWeather.kindOf(0)).isEqualTo(EventData.Weather.CLEAR)
        assertThat(OpenMeteoWeather.kindOf(1)).isEqualTo(EventData.Weather.CLEAR)
        assertThat(OpenMeteoWeather.kindOf(2)).isEqualTo(EventData.Weather.PARTLY)
        assertThat(OpenMeteoWeather.kindOf(3)).isEqualTo(EventData.Weather.CLOUDY)
        assertThat(OpenMeteoWeather.kindOf(45)).isEqualTo(EventData.Weather.CLOUDY)
        for (code in listOf(51, 61, 65, 71, 80, 95, 99)) assertThat(OpenMeteoWeather.kindOf(code)).isEqualTo(EventData.Weather.RAIN)
        // An unknown code is not shown as sun
        assertThat(OpenMeteoWeather.kindOf(20)).isEqualTo(EventData.Weather.CLOUDY)
    }

    @Test
    fun readGivesTheKindOrNull() {
        val asked = mutableListOf<String>()
        assertThat(OpenMeteoWeather { asked += it; answer }.read(52.37, 4.90)).isEqualTo(EventData.Weather.RAIN)
        assertThat(asked.single()).contains("latitude=52.4")
        assertThat(OpenMeteoWeather { null }.read(52.37, 4.90)).isNull()
    }
}
