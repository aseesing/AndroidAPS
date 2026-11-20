// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.wff.WffSlotLayout.Box
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

internal class WffSlotLayoutTest {

    @Test
    fun aWideRangedSlotHasTheRingOnTheLeftWithTheIconInIt() {
        // 180 x 60 design pixels on a face of 454: 204 x 68, corners of 12 -> 14
        val parts = WffSlotLayout.of(204, 68, 14, 10, ranged = true, textSize = 29)
        assertThat(parts.ring).isEqualTo(Box(11, 11, 57, 57))
        assertThat(parts.icon).isEqualTo(Box(22, 22, 46, 46))
        // Right of the ring square, cut by the rounded corners, with a small room before the text
        assertThat(parts.text).isEqualTo(Box(68, 6, 198, 62))
        assertThat(parts.textStart).isEqualTo(6)
        assertThat(parts.centred).isFalse()
        assertThat(parts.textSize).isEqualTo(29)
    }

    @Test
    fun aRoundRangedSlotHasTheIconAboveTheTextInsideTheRing() {
        // 100 x 100 design pixels: 114 x 114, ring 6 -> 7
        val parts = WffSlotLayout.of(114, 114, 56, 7, ranged = true, textSize = 27)
        // The ring keeps a margin to the edge of the slot
        assertThat(parts.ring).isEqualTo(Box(9, 9, 105, 105))
        assertThat(parts.icon).isEqualTo(Box(43, 29, 71, 57))
        assertThat(parts.text).isEqualTo(Box(29, 57, 85, 85))
        assertThat(parts.centred).isTrue()
        assertThat(parts.textStart).isEqualTo(0)
        assertThat(parts.radius).isEqualTo(56)
    }

    @Test
    fun theTextIsAtMostAsHighAsItsBox() {
        assertThat(WffSlotLayout.of(600, 100, 0, 7, ranged = true, textSize = null).textSize).isEqualTo(100)
        assertThat(WffSlotLayout.of(600, 100, 0, 7, ranged = true, textSize = 120).textSize).isEqualTo(100)
        assertThat(WffSlotLayout.of(600, 100, 0, 7, ranged = true, textSize = 40).textSize).isEqualTo(40)
    }

    @Test
    fun theTextIsSmallEnoughForFourDigits() {
        // The text box of a round slot of 114 is 56 wide: our engine shows 6482 at 24 px there, not 27
        assertThat(WffSlotLayout.of(114, 114, 56, 7, ranged = true, textSize = 27).textSize).isEqualTo(24)
    }

    @Test
    fun aShortTextSlotHasNoRing() {
        val parts = WffSlotLayout.of(181, 45, 0, 10, ranged = false, textSize = null)
        assertThat(parts.ring).isNull()
        // The icon fills the square on the left (an odd side loses a pixel, as in Android's
        // LayoutUtils.getCentralSquare), the text is right of it
        assertThat(parts.icon).isEqualTo(Box(0, 0, 44, 44))
        assertThat(parts.text).isEqualTo(Box(45, 0, 181, 45))
    }

    @Test
    fun aSlotWithoutOwnStyleUsesTheComplicationStyleSection() {
        val xml = slotXml(
            """{"complicationStyle": {"color": "#3A86FF", "borderRadius": 12, "fontColor": "#FFD166", "textsize": 26},
               "complication2": {"leftmargin": 110, "topmargin": 170, "width": 180, "height": 60}}"""
        )
        assertThat(xml).contains("""cornerRadiusX="12" cornerRadiusY="12"><Fill color="#FF3A86FF" />""")
        // No ring colour: a white ring on 50 % white, as our engine
        assertThat(xml).contains("""<Stroke color="#FFFFFFFF" thickness="9" />""")
        assertThat(xml).contains("""<Stroke color="#80FFFFFF" thickness="9" />""")
        // No icon colour: the icon in the text colour
        assertThat(xml).contains("""tintColor="#FFFFD166"""")
        assertThat(xml).contains("""size="26" color="#FFFFD166"""")
    }

    @Test
    fun theKeysOfTheSlotWinOverTheSection() {
        val xml = slotXml(
            """{"complicationStyle": {"color": "#3A86FF", "fontColor": "#FFD166", "ringPrimaryColor": "#FF0000"},
               "complication2": {"leftmargin": 150, "topmargin": 150, "width": 100, "height": 100, "color": "#00000000", "fontColor": "#1C2A5A",
                                 "ringPrimaryColor": "#1D3F9E", "ringSecondaryColor": "#00000000"}}"""
        )
        assertThat(xml).doesNotContain("#FF3A86FF")
        assertThat(xml).doesNotContain("#FFFF0000")
        assertThat(xml).contains("""<Stroke color="#FF1D3F9E"""")
        assertThat(xml).contains("""tintColor="#FF1C2A5A"""")
        // The ring leaves a gap at the top and starts after it
        assertThat(xml).contains("""<Transform target="startAngle"""")
    }

    /** The xml of a face of 400 units with only `complication2` of [design]. */
    private fun slotXml(design: String): String {
        val json = JSONObject(design)
        val v = json.getJSONObject("complication2")
        val box = DesignRect(v.getInt("leftmargin").toFloat(), v.getInt("topmargin").toFloat(), v.getInt("width").toFloat(), v.getInt("height").toFloat())
        val plan = WffPlan("Test", 4, listOf(WffPlanItem("complication2", WffTier.LIVE, "user slot 2", where = "complication2", box = box)), emptyList(), 0)
        return WffFaceWriter("app.aaps.wear").write(json, null, plan).xml
    }
}
