// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import android.graphics.Color
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

internal class WffPlannerTest {

    private fun color(text: String, default: Int): Int = runCatching { Color.parseColor(text) }.getOrDefault(default)

    private fun animation(design: JSONObject): WakeAnimationSpec? = WakeAnimationJson(::color) { null }.read(design)

    private fun plan(design: JSONObject): WffPlan = WffPlanner().plan(design, animation(design))

    private fun view(left: Int, top: Int, width: Int, height: Int, extra: String = ""): String =
        """{"leftmargin": $left, "topmargin": $top, "width": $width, "height": $height, "visibility": "visible"$extra}"""

    private fun design(views: String, layers: String = "", lets: String = "", dynData: String = ""): JSONObject = JSONObject(
        """{"metadata": {"name": "Test", "key_show_date": "true"}, $views, "dynData": {$dynData}, "animation": {"durationMs": 6000, "fps": 24${if (lets.isNotEmpty()) ", \"let\": {$lets}" else ""}, "layers": [$layers]}}"""
    )

    private fun item(plan: WffPlan, name: String): WffPlanItem = plan.items.first { it.name == name || it.name.startsWith("$name ") }

    @Test
    fun plainGlucoseViewsAreLiveInTheirSlots() {
        val plan = plan(design(""""sgv": ${view(30, 100, 140, 100)}, "timestamp": ${view(170, 160, 50, 20)}, "direction": ${view(170, 110, 50, 50)}, "delta": ${view(170, 90, 50, 20)},
            "complication1": ${view(250, 200, 100, 30)}, "complication3": ${view(250, 240, 100, 30)}"""))
        assertThat(plan.slots.map { it.id }).containsExactly(1, 3, 10, 11).inOrder()
        assertThat(item(plan, "sgv").where).isEqualTo("glucose")
        assertThat(item(plan, "direction").where).isEqualTo("glucose")
        assertThat(item(plan, "delta").where).isEqualTo("glucose")
        assertThat(item(plan, "timestamp").where).isEqualTo("age")
        assertThat(plan.canBuild).isTrue()
    }

    @Test
    fun aGlucoseViewWithOnlyColourStepsStaysLive() {
        val plan = plan(design(
            """"sgv": ${view(30, 100, 140, 100, ", \"dynData\": \"c\"")}, "timestamp": ${view(170, 160, 50, 20, ", \"dynData\": \"a\"")}""",
            dynData = """"c": {"valueKey": "sgvLevel", "minData": -1, "maxData": 1, "fontColor1": "#FF0000", "fontColor2": "#00FF00", "fontColor3": "#FFFF00"},
                "a": {"valueKey": "timestamp", "minData": 0, "maxData": 20, "textvalue1": "%.0f'", "fontColor1": "#FFFFFF", "fontColor2": "#FF0000"}"""
        ))
        assertThat(item(plan, "sgv").where).isEqualTo("glucose")
        assertThat(item(plan, "timestamp").where).isEqualTo("age")
    }

    @Test
    fun viewsThatShowDataOrFollowAPreferenceGoToTheDataPicture() {
        val plan = plan(design(
            """"iob1": ${view(240, 100, 30, 24)}, "chart": ${view(30, 250, 340, 140)}, "time": ${view(10, 10, 40, 40, ", \"dynData\": \"w\"")},
               "patient_name": ${view(100, 100, 150, 150, ", \"dynData\": \"hand\"")}, "hour": ${view(100, 60, 200, 80, ", \"dynPref\": \"units\"")},
               "freetext2": ${view(5, 5, 30, 30, ", \"image\": {\"use\": \"moon\"}")}, "sgv_Ext1": ${view(1, 1, 10, 10)}""",
            dynData = """"w": {"valueKey": "night_progress", "image1": "Sun"}, "hand": {"valueKey": "sgv", "image1": "Hand", "rotationOffset1": -60}"""
        ))
        for (key in listOf("iob1", "chart", "time", "patient_name", "hour", "freetext2", "sgv_Ext1")) assertWithMessage(key).that(item(plan, key).where).isEqualTo("data")
        assertThat(plan.slots.map { it.id }).containsExactly(WffPlanner.DATA_SLOT_ID)
    }

    @Test
    fun plainClockDateAndHandsAreLiveAndStaticArtIsBaked() {
        val plan = plan(design(""""background": ${view(0, 0, 400, 400)}, "time": ${view(100, 60, 200, 70)}, "day": ${view(10, 10, 20, 20)}, "hour_hand": ${view(0, 0, 400, 400)},
            "freetext1": ${view(10, 300, 50, 20)}"""))
        assertThat(item(plan, "time").tier).isEqualTo(WffTier.LIVE)
        assertThat(item(plan, "day").tier).isEqualTo(WffTier.LIVE)
        assertThat(item(plan, "hour_hand").tier).isEqualTo(WffTier.LIVE)
        assertThat(item(plan, "background").run).isEqualTo(0)
        assertThat(item(plan, "freetext1").run).isEqualTo(0)
    }

    @Test
    fun aLiveViewIsLeftOutWhenTheDesignSwitchesItsPreferenceOff() {
        val d = design(""""sgv": ${view(30, 100, 140, 100)}, "day": ${view(10, 10, 20, 20)}, "iob1": ${view(240, 100, 30, 24)}""")
        d.getJSONObject("metadata").put("key_show_date", "false").put("key_show_bg", "false").put("key_show_iob", "false")
        val plan = plan(d)
        assertThat(item(plan, "day").tier).isEqualTo(WffTier.OMIT)
        assertThat(item(plan, "sgv").tier).isEqualTo(WffTier.OMIT)
        // The data picture follows the watch itself
        assertThat(item(plan, "iob1").where).isEqualTo("data")
        assertThat(plan.slots.map { it.id }).containsExactly(WffPlanner.DATA_SLOT_ID)
    }

    @Test
    fun layersByWhatTheyRead() {
        val plan = plan(design(
            """"background": ${view(0, 0, 400, 400)}""",
            """{"shape": "circle", "count": 40, "x": "u * 400", "y": "200 + sin(t) * 20", "slot": "front"},
               {"shape": "rect", "x": "200", "y": "200", "rotation": "hour * 30"},
               {"text": "clock()", "x": "200", "y": "100"},
               {"text": "glucose(v_sgv)", "x": "200", "y": "300"},
               {"shape": "circle", "x": "mx", "y": "100"},
               {"shape": "line", "x": "10", "y": "10"},
               {"shape": "rect", "x": "10", "y": "10", "visible": "v_timestamp >= 12"},
               {"shape": "rect", "x": "10", "y": "10", "rotation": "sec * 6"}""",
            lets = """"mx": "200 + 100 * cos(v_moon_cycle)""""
        ))
        assertThat(item(plan, "L0").loop).isTrue()
        assertThat(item(plan, "L1").where).isEqualTo("data")
        assertThat(item(plan, "L2").tier).isEqualTo(WffTier.LIVE)
        assertThat(item(plan, "L3").where).isEqualTo("glucose")
        assertThat(item(plan, "L4").where).isEqualTo("data")
        // A static layer drawn after a part of another kind lies over it: a picture of its own
        assertThat(item(plan, "L5").run).isEqualTo(1)
        assertThat(item(plan, "L6").where).isEqualTo("data")
        assertThat(item(plan, "L6").severity).isEqualTo(WffSeverity.WARNING)
        assertThat(item(plan, "L7").severity).isEqualTo(WffSeverity.WARNING)
    }

    @Test
    fun aLayerThatMovesAViewGoesWithItsView() {
        val plan = plan(design(
            """"iob1": ${view(240, 100, 30, 24)}, "freetext1": ${view(188, 1, 24, 24)}, "day": ${view(10, 10, 20, 20)}""",
            """{"view": "iob1", "alpha": "sin(t * 6) > 0"}, {"view": "freetext1", "x": "20"}, {"view": "day", "alpha": "sin(t * 6) > 0"}, {"view": "bgi", "x": "10"}"""
        ))
        assertThat(item(plan, "L0").where).isEqualTo("data")
        assertThat(item(plan, "L1").tier).isEqualTo(WffTier.BAKE)
        assertThat(item(plan, "L2").tier).isEqualTo(WffTier.OMIT)
        assertThat(item(plan, "L2").severity).isEqualTo(WffSeverity.WARNING)
        assertThat(item(plan, "L3").note).contains("not shown")
    }

    @Test
    fun aStaticPartOverALivePartStartsANewPicture() {
        val one = plan(design(""""background": ${view(0, 0, 400, 400)}, "sgv": ${view(30, 100, 140, 100)}, "cover_plate": ${view(300, 300, 50, 50)}"""))
        assertThat(one.staticRuns).isEqualTo(1)
        val two = plan(design(""""background": ${view(0, 0, 400, 400)}, "sgv": ${view(30, 100, 140, 100)}, "cover_plate": ${view(0, 0, 400, 400)}"""))
        assertThat(two.staticRuns).isEqualTo(2)
        assertThat(item(two, "cover_plate").run).isEqualTo(1)
    }

    @Test
    fun aWakeLayerThatShowsAtRestBecomesStatic() {
        val planner = WffPlanner()
        val d = design(""""background": ${view(0, 0, 400, 400)}""", """{"shape": "circle", "x": "100 + sin(t)", "y": "100"}, {"shape": "circle", "x": "200 + sin(t)", "y": "100"}""")
        val settled = planner.settle(planner.plan(d, animation(d)), still = setOf(0))
        assertThat(item(settled, "L0").loop).isFalse()
        assertThat(item(settled, "L0").run).isEqualTo(0)
        assertThat(item(settled, "L1").loop).isTrue()
    }

    /** Every design of the design repository, when it is next to this repository: the plan can be built and keeps the glucose. */
    @Test
    fun theDesignsOfTheDesignRepository() {
        val folder = File(System.getProperty("user.home"), "Projects/aaps-watchfaces/cwf-designs")
        assumeTrue(folder.isDirectory, "the design repository is not here")
        val out = File("build/wff-lint").apply { mkdirs() }
        val zips = folder.listFiles { f -> f.name.endsWith(".zip") }!!.sorted()
        assertThat(zips).isNotEmpty()
        for (zip in zips) {
            val json = ZipFile(zip).use { z -> z.getInputStream(z.getEntry("CustomWatchface.json")).readBytes().decodeToString() }
            val design = JSONObject(json)
            val plan = plan(design)
            File(out, zip.nameWithoutExtension + ".txt").writeText(plan.lint())
            assertWithMessage(zip.name + "\n" + plan.lint()).that(plan.canBuild).isTrue()
            assertWithMessage(zip.name).that(plan.slots.size).isAtMost(WffPlanner.MAX_SLOTS)
            // Every visible view of the design has a line in the plan
            val visible = design.keys().asSequence().filter { design.optJSONObject(it)?.optString("visibility") == "visible" }.toSet()
            assertWithMessage(zip.name).that(plan.items.mapNotNull { it.view }.toSet()).containsAtLeastElementsIn(visible)
        }
    }
}
