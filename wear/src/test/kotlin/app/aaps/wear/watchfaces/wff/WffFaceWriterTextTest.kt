// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

/** Where and how big [WffFaceWriter] writes the texts of the classic views, in a face of 454 units. */
internal class WffFaceWriterTextTest {

    @Test
    fun theTimeIsATextWithTheHourOfTheWatchSetting() {
        val xml = faceXml("""{"time": {"leftmargin": 60, "topmargin": 150, "width": 280, "height": 100, "textsize": 72, "gravity": "left"}}""", "time")
        // Not a TimeText: it puts about 1 px more between the signs than the classic view
        assertThat(xml).doesNotContain("TimeText")
        assertThat(xml).contains("""<Expression name="h12">[IS_24_HOUR_MODE] == 0</Expression>""")
        assertThat(xml).contains("""<Template>%s:%s<Parameter expression="[HOUR_1_12_Z]" /><Parameter expression="[MINUTE_Z]" /></Template>""")
        assertThat(xml).contains("""<Template>%s:%s<Parameter expression="[HOUR_0_23_Z]" /><Parameter expression="[MINUTE_Z]" /></Template>""")
        // The size keeps its fraction: 72 * 454 / 400
        assertThat(xml).contains("""size="81.72"""")
    }

    @Test
    fun theTimeWithSecondsHasASecondTextForAlwaysOn() {
        val xml = faceXml(
            """{"enableSecond": true, "time": {"leftmargin": 40, "topmargin": 150, "width": 320, "height": 100, "textsize": 64, "gravity": "center"}}""", "time"
        )
        assertThat(xml).contains("""<Template>%s:%s:%s<Parameter expression="[HOUR_0_23_Z]" /><Parameter expression="[MINUTE_Z]" /><Parameter expression="[SECOND_Z]" /></Template>""")
        assertThat(xml).contains("""<Variant mode="AMBIENT" target="alpha" value="0" /><Text align="CENTER">""")
        assertThat(xml).contains("""alpha="0"><Variant mode="AMBIENT" target="alpha" value="255" /><Text align="CENTER">""")
    }

    @Test
    fun aViewIsInWholePixelsCutDownAsTheClassicFacePutsIt() {
        // 100 * 1.135 = 113.5: the classic face makes it 113, rounding would make it 114
        val xml = faceXml("""{"minute": {"leftmargin": 100, "topmargin": 150, "width": 100, "height": 100, "textsize": 72, "gravity": "right"}}""", "minute")
        assertThat(xml).contains("""<DigitalClock x="113" """)
        assertThat(xml).contains("""<TimeText format="mm" hourFormat="SYNC_TO_DEVICE" align="END" x="0" y="0" width="113" height="113">""")
        // The size keeps its fraction: 72 * 454 / 400
        assertThat(xml).contains("""size="81.72"""")
    }

    @Test
    fun theGlucoseTextIsPlacedInWholePixels() {
        val design = """{"sgv": {"leftmargin": 80, "topmargin": 140, "width": 240, "height": 110, "textsize": 80, "gravity": "center"}}"""
        val plan = WffPlan("Test", 4, listOf(WffPlanItem("sgv", WffTier.LIVE, "glucose", where = WffPlanner.GLUCOSE, view = "sgv")), emptyList(), 0)
        val xml = WffFaceWriter("app.aaps.wear", faceSize = 454).write(JSONObject(design), null, plan).xml
        // The view is 90, 158, 272 x 124. Its base line is 97 px below its top (Roboto of 90.8 px:
        // 96 px above and 25 below it, 1 px of room above); WFF centres the line 93.05 px down, so the
        // text box moves 3.95 px down, to 162. Android centres the line by an even number of pixels,
        // up to 1 px right of the middle: in a view of an even width the WFF box is 1 px wider
        assertThat(xml).contains("""x="90" y="158" width="273" height="128">""")
        assertThat(xml).contains("""<PartText x="0" y="4" width="273" height="124">""")
    }

    /** The xml of a face of 454 units with only the classic view [view] of [design]. */
    private fun faceXml(design: String, view: String): String {
        val plan = WffPlan("Test", 4, listOf(WffPlanItem(view, WffTier.LIVE, "live", view = view)), emptyList(), 0)
        return WffFaceWriter("app.aaps.wear", faceSize = 454).write(JSONObject(design), null, plan).xml
    }
}
