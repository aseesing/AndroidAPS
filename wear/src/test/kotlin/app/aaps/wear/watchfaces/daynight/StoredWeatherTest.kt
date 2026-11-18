// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The weather the phone sends; one without a kind means "no weather" (the place is no longer allowed). */
class StoredWeatherTest {

    private val sp = mock<SP>()

    @Test
    fun `a weather is kept`() {
        whenever(sp.getString(any<Int>(), any())).thenReturn("")

        StoredWeather.save(sp, EventData.Weather(1_000L, EventData.Weather.RAIN, 12.5))

        verify(sp).putString(R.string.key_weather_kind, EventData.Weather.RAIN)
        verify(sp).putLong(R.string.key_weather_time, 1_000L)
        verify(sp).putString(R.string.key_weather_temperature, "12.5")
    }

    @Test
    fun `a weather without a kind forgets the weather at once`() {
        StoredWeather.save(sp, EventData.Weather(1_000L, ""))

        verify(sp).remove(R.string.key_weather_kind)
        verify(sp).remove(R.string.key_weather_time)
        verify(sp).remove(R.string.key_weather_temperature)
        verify(sp, never()).putString(any<Int>(), any())
    }
}
