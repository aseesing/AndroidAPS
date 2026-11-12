// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

import app.aaps.core.interfaces.rx.weardata.CwfResDataMap
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for the real org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayOverlayTest {

    private val design = JSONObject(
        """
        {
          "hour": {"fontColor": "#00F0FF", "textsize": 64},
          "sgv": {"fontColor": "#CCCCCC"},
          "animation": {"durationMs": 15000, "rain": {"count": 140}, "pulse": {"image": "LogoPulse"}},
          "day": {"fontColor": "#FFFFFF"},
          "dayLook": {
            "hour": {"fontColor": "#0A0A0A"},
            "day": {"fontColor": "#0A0A0A"},
            "animation": {"rain": null, "glitch": {"bands": 3}},
            "highColor": "#C25E00"
          }
        }
        """
    )

    @Test
    fun theOverlayIsMergedDeep() {
        val day = DayOverlay.apply(design)
        assertThat(day.getJSONObject("hour").getString("fontColor")).isEqualTo("#0A0A0A")
        // Keys the overlay does not name stay
        assertThat(day.getJSONObject("hour").getInt("textsize")).isEqualTo(64)
        assertThat(day.getJSONObject("sgv").getString("fontColor")).isEqualTo("#CCCCCC")
        assertThat(day.getJSONObject("animation").getLong("durationMs")).isEqualTo(15000)
        assertThat(day.getJSONObject("animation").getJSONObject("pulse").getString("image")).isEqualTo("LogoPulse")
        // A new key is added, null removes one
        assertThat(day.getString("highColor")).isEqualTo("#C25E00")
        assertThat(day.getJSONObject("animation").has("rain")).isFalse()
        assertThat(day.getJSONObject("animation").getJSONObject("glitch").getInt("bands")).isEqualTo(3)
        // The overlay itself is not in the result, but the view "day" (day of the month) is
        assertThat(day.has(DayOverlay.JSON_KEY)).isFalse()
        assertThat(day.getJSONObject("day").getString("fontColor")).isEqualTo("#0A0A0A")
    }

    @Test
    fun theDesignItselfIsNotChanged() {
        val before = design.toString()
        DayOverlay.apply(design)
        assertThat(design.toString()).isEqualTo(before)
    }

    @Test
    fun aDesignWithoutOverlayHasNoDayLook() {
        assertThat(DayOverlay.hasDayLook(design)).isTrue()
        assertThat(DayOverlay.hasDayLook(JSONObject("""{"hour": {}}"""))).isFalse()
    }

    @Test
    fun dayPicturesTakeThePlaceOfTheOthers() {
        fun res(byte: Int) = ResData(byteArrayOf(byte.toByte()), ResFormat.PNG)
        val resources: CwfResDataMap = mutableMapOf(
            "Background" to res(1), "BackgroundDay" to res(2), "CoverPlate" to res(3), "Moon05" to res(4), "Moon05Day" to res(5), "Day" to res(6)
        )
        val day = DayOverlay.dayResources(resources)
        assertThat(day["Background"]!!.value[0]).isEqualTo(2.toByte())
        assertThat(day["Moon05"]!!.value[0]).isEqualTo(5.toByte())
        // Without a day picture the picture stays
        assertThat(day["CoverPlate"]!!.value[0]).isEqualTo(3.toByte())
        // A picture called just "Day" replaces nothing
        assertThat(day.keys).doesNotContain("")
        // The original map is not changed
        assertThat(resources["Background"]!!.value[0]).isEqualTo(1.toByte())
    }

    private val withWeather = JSONObject(
        """
        {
          "hour": {"fontColor": "#00F0FF"},
          "dayLook": {"hour": {"fontColor": "#0A0A0A"}, "images": {"Logo": "LogoDay"}},
          "weatherLooks": {
            "day_rain": {"hour": {"fontColor": "#222222"}, "images": {"Background": "BackgroundRain"}},
            "night_cloudy": {"patient_name": {"visibility": "visible"}}
          }
        }
        """
    )

    @Test
    fun theWeatherLookIsLaidOverTheDayLook() {
        val rain = DayOverlay.apply(withWeather, day = true, weather = "rain")
        assertThat(rain.getJSONObject("hour").getString("fontColor")).isEqualTo("#222222")
        // The picture swaps of both looks are kept together
        assertThat(rain.getJSONObject(DayOverlay.IMAGES_KEY).getString("Logo")).isEqualTo("LogoDay")
        assertThat(rain.getJSONObject(DayOverlay.IMAGES_KEY).getString("Background")).isEqualTo("BackgroundRain")
        assertThat(rain.has(DayOverlay.WEATHER_KEY)).isFalse()
        // A weather without a look: only the day look
        assertThat(DayOverlay.apply(withWeather, day = true, weather = "clear").getJSONObject("hour").getString("fontColor")).isEqualTo("#0A0A0A")
        // At night only the night look of that weather
        val night = DayOverlay.apply(withWeather, day = false, weather = "cloudy")
        assertThat(night.getJSONObject("hour").getString("fontColor")).isEqualTo("#00F0FF")
        assertThat(night.getJSONObject("patient_name").getString("visibility")).isEqualTo("visible")
        assertThat(night.has(DayOverlay.IMAGES_KEY)).isFalse()
    }

    @Test
    fun theLookNamesItsPictures() {
        fun res(byte: Int) = ResData(byteArrayOf(byte.toByte()), ResFormat.PNG)
        val resources: CwfResDataMap = mutableMapOf("Background" to res(1), "BackgroundRain" to res(2), "Logo" to res(3), "LogoDay" to res(4))
        val look = DayOverlay.apply(withWeather, day = true, weather = "rain")
        val shown = DayOverlay.resources(resources, look, day = true)
        assertThat(shown["Background"]!!.value[0]).isEqualTo(2.toByte())
        assertThat(shown["Logo"]!!.value[0]).isEqualTo(4.toByte())
        // A swap to a picture the zip does not have changes nothing
        val missing = JSONObject("""{"images": {"Background": "Nothing"}}""")
        assertThat(DayOverlay.resources(resources, missing, day = false)["Background"]!!.value[0]).isEqualTo(1.toByte())
    }
}
