// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.design

import app.aaps.core.interfaces.rx.weardata.CwfResDataMap
import app.aaps.core.interfaces.rx.weardata.ResData
import app.aaps.core.interfaces.rx.weardata.ResFormat
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The looks of a design: laid over it in order while their condition is true, with their picture swaps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LooksTest {

    private val design = JSONObject(
        """
        {
          "hour": {"fontColor": "#00F0FF", "textsize": 64},
          "sgv": {"fontColor": "#CCCCCC"},
          "animation": {"durationMs": 15000, "layers": [{"use": "rain"}]},
          "day": {"fontColor": "#FFFFFF"},
          "looks": [
            {"when": "v_is_day == 1", "patch": {
              "hour": {"fontColor": "#0A0A0A"}, "day": {"fontColor": "#0A0A0A"}, "animation": {"fps": 12}, "highColor": "#C25E00", "sgv": null
            }, "images": {"Logo": "LogoDay"}},
            {"when": "v_is_day == 1 && v_weather_kind == 3", "patch": {"hour": {"fontColor": "#222222"}}, "images": {"Background": "BackgroundRain"}},
            {"when": "v_weather_kind == 2", "patch": {"patient_name": {"visibility": "visible"}}},
            {"when": "this is no formula", "patch": {"hour": {"fontColor": "#FF0000"}}}
          ]
        }
        """
    )

    private fun values(day: Boolean, weather: Int?): (String) -> Double? = { key ->
        when (key) {
            "is_day"       -> if (day) 1.0 else 0.0
            "weather_kind" -> weather?.toDouble()
            else           -> null
        }
    }

    private fun res(byte: Int) = ResData(byteArrayOf(byte.toByte()), ResFormat.PNG)

    @Test
    fun thePatchIsMergedDeep() {
        val day = Looks().apply(design, values(day = true, weather = null)).json
        assertThat(day.getJSONObject("hour").getString("fontColor")).isEqualTo("#0A0A0A")
        // Keys the patch does not name stay
        assertThat(day.getJSONObject("hour").getInt("textsize")).isEqualTo(64)
        assertThat(day.getJSONObject("animation").getLong("durationMs")).isEqualTo(15000)
        assertThat(day.getJSONObject("animation").getInt("fps")).isEqualTo(12)
        assertThat(day.getJSONObject("animation").getJSONArray("layers").length()).isEqualTo(1)
        // A new key is added, null removes one
        assertThat(day.getString("highColor")).isEqualTo("#C25E00")
        assertThat(day.has("sgv")).isFalse()
        // The looks themselves are not in the result, but the view "day" (day of the month) is
        assertThat(day.has(Looks.JSON_KEY)).isFalse()
        assertThat(day.getJSONObject("day").getString("fontColor")).isEqualTo("#0A0A0A")
    }

    @Test
    fun theDesignItselfIsNotChangedAndIsGivenBackWithoutALook() {
        val before = design.toString()
        val looks = Looks()
        looks.apply(design, values(day = true, weather = 3))
        assertThat(design.toString()).isEqualTo(before)
        assertThat(looks.apply(design, values(day = false, weather = null)).json).isSameInstanceAs(design)
    }

    @Test
    fun theLooksAreLaidOverEachOtherInOrder() {
        val looks = Looks()
        val rain = looks.apply(design, values(day = true, weather = 3))
        assertThat(rain.active).containsExactly(true, true, false, false).inOrder()
        assertThat(rain.json.getJSONObject("hour").getString("fontColor")).isEqualTo("#222222")
        // A weather without its own look: only the day look
        assertThat(looks.apply(design, values(day = true, weather = 0)).json.getJSONObject("hour").getString("fontColor")).isEqualTo("#0A0A0A")
        // At night only the look of that weather
        val night = looks.apply(design, values(day = false, weather = 2)).json
        assertThat(night.getJSONObject("hour").getString("fontColor")).isEqualTo("#00F0FF")
        assertThat(night.getJSONObject("patient_name").getString("visibility")).isEqualTo("visible")
    }

    @Test
    fun theSameConditionsGiveTheSameObject() {
        val looks = Looks()
        val first = looks.apply(design, values(day = true, weather = 3))
        assertThat(looks.apply(design, values(day = true, weather = 3))).isSameInstanceAs(first)
        assertThat(looks.apply(design, values(day = true, weather = 1))).isNotSameInstanceAs(first)
    }

    @Test
    fun theLooksNameTheirPictures() {
        val resources: CwfResDataMap = mutableMapOf("Background" to res(1), "BackgroundRain" to res(2), "Logo" to res(3), "LogoDay" to res(4))
        val shown = Looks().apply(design, values(day = true, weather = 3)).pictures(resources)
        assertThat(shown["Background"]!!.value[0]).isEqualTo(2.toByte())
        assertThat(shown["Logo"]!!.value[0]).isEqualTo(4.toByte())
        // The pictures of the zip themselves are not changed, and without swaps they are given back
        assertThat(resources["Background"]!!.value[0]).isEqualTo(1.toByte())
        assertThat(Looks().apply(design, values(day = false, weather = 2)).pictures(resources)).isSameInstanceAs(resources)
        // A swap to a picture the zip does not have changes nothing
        val missing = JSONObject("""{"looks": [{"images": {"Background": "Nothing"}}]}""")
        assertThat(Looks().apply(missing, values(day = false, weather = null)).pictures(resources)["Background"]!!.value[0]).isEqualTo(1.toByte())
    }

    @Test
    fun aDesignSaysWhichValuesItsLooksRead() {
        assertThat(Looks.reads(design, "is_day")).isTrue()
        assertThat(Looks.reads(design, "weather_kind")).isTrue()
        assertThat(Looks.reads(design, "is_d")).isFalse()
        assertThat(Looks.reads(JSONObject("""{"hour": {}}"""), "is_day")).isFalse()
    }
}
