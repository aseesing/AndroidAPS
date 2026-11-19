package app.aaps.wear.watchfaces.colour

import app.aaps.wear.R
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Robolectric for the real org.json and the resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ColourLooksTest {

    private val design = JSONObject(
        """
        {
          "hour": {"fontColor": "#00E5FF", "textsize": 64},
          "day": {"fontColor": "#B6FF1A"},
          "cover_chart": {"color": "#00E5FF"},
          "colourLooks": {
            "colour1": {"hour": {"fontColor": "@colour"}, "cover_chart": {"color": "#80@colour"}},
            "colour2": {"day": {"fontColor": "@colour"}}
          }
        }
        """
    )

    private fun choices(first: String = ColourLooks.AS_DESIGNED, second: String = ColourLooks.AS_DESIGNED) = listOf(first, second)

    @Test
    fun theChosenColourTakesThePlaceOfThePlaceholder() {
        val look = ColourLooks.lookOf(design, choices("#FF3B3B"))
        assertThat(look.getJSONObject("hour").getString("fontColor")).isEqualTo("#FF3B3B")
        // A colour with an alpha before the placeholder keeps the alpha
        assertThat(look.getJSONObject("cover_chart").getString("color")).isEqualTo("#80FF3B3B")
        // Keys the look does not name stay, and the second slot is as designed
        assertThat(look.getJSONObject("hour").getInt("textsize")).isEqualTo(64)
        assertThat(look.getJSONObject("day").getString("fontColor")).isEqualTo("#B6FF1A")
    }

    @Test
    fun bothSlotsCanBeChosen() {
        val look = ColourLooks.lookOf(design, choices("#3D8BFF", "#FFE11A"))
        assertThat(look.getJSONObject("hour").getString("fontColor")).isEqualTo("#3D8BFF")
        assertThat(look.getJSONObject("day").getString("fontColor")).isEqualTo("#FFE11A")
    }

    @Test
    fun asDesignedOrNotAColourLeavesTheDesign() {
        assertThat(ColourLooks.lookOf(design, choices())).isSameInstanceAs(design)
        assertThat(ColourLooks.lookOf(design, choices("red", "#12345"))).isSameInstanceAs(design)
        assertThat(ColourLooks.lookOf(design, emptyList())).isSameInstanceAs(design)
    }

    @Test
    fun theDesignItselfIsNotChanged() {
        val before = design.toString()
        ColourLooks.lookOf(design, choices("#FF3B3B", "#FFE11A"))
        assertThat(design.toString()).isEqualTo(before)
    }

    @Test
    fun theResultIsKeptWhileNothingChanges() {
        val looks = ColourLooks()
        val first = looks.apply(design, choices("#FF3B3B"))
        assertThat(looks.apply(design, choices("#FF3B3B"))).isSameInstanceAs(first)
        val other = looks.apply(design, choices("#2EE66B"))
        assertThat(other).isNotSameInstanceAs(first)
        assertThat(other.getJSONObject("hour").getString("fontColor")).isEqualTo("#2EE66B")
    }

    @Test
    fun onlyTheSlotsTheDesignOffersGetARow() {
        assertThat(ColourLooks.rows(design).map { it.key }).containsExactly(R.string.key_cwf_colour_1, R.string.key_cwf_colour_2).inOrder()
        val oneSlot = JSONObject("""{"colourLooks": {"colour2": {"day": {"fontColor": "@colour"}}}}""")
        assertThat(ColourLooks.rows(oneSlot).map { it.key }).containsExactly(R.string.key_cwf_colour_2)
        assertThat(ColourLooks.rows(JSONObject("""{"hour": {}}"""))).isEmpty()
        assertThat(ColourLooks.rows(null)).hasSize(2)
    }

    @Test
    fun everyValueOfTheListIsAColourOrAsDesigned() {
        val resources = RuntimeEnvironment.getApplication().resources
        val values = resources.getStringArray(R.array.cwf_colour_values)
        assertThat(values.size).isEqualTo(resources.getStringArray(R.array.cwf_colour_names).size)
        assertThat(values.first()).isEqualTo(ColourLooks.AS_DESIGNED)
        for (value in values.drop(1)) assertThat(value).matches("#[0-9A-F]{6}")
    }
}
