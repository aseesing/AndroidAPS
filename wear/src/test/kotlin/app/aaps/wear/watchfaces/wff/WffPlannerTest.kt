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

    private fun design(views: String, layers: String = "", lets: String = ""): JSONObject = JSONObject(
        """{"metadata": {"name": "Test"}, $views, "animation": {"durationMs": 6000, "fps": 24${if (lets.isNotEmpty()) ", \"let\": {$lets}" else ""}, "layers": [$layers]}}"""
    )

    private fun item(plan: WffPlan, name: String): WffPlanItem = plan.items.first { it.name == name || it.name.startsWith("$name ") }

    @Test
    fun glucoseAgeAndUserSlotsGetTheFixedIds() {
        val plan = plan(design(""""sgv": ${view(30, 100, 140, 100)}, "timestamp": ${view(170, 160, 50, 20)}, "direction": ${view(170, 110, 50, 50)},
            "complication1": ${view(250, 200, 100, 30)}, "complication3": ${view(250, 240, 100, 30)}"""))
        assertThat(plan.slots.map { it.id }).containsExactly(1, 3, 10, 11).inOrder()
        assertThat(item(plan, "direction").where).isEqualTo("glucose")
        assertThat(plan.canBuild).isTrue()
    }

    @Test
    fun aapsViewsGoToBlocksAndTheChartToItsOwn() {
        val plan = plan(design(""""iob1": ${view(240, 100, 30, 24)}, "cob1": ${view(240, 124, 30, 24)}, "chart": ${view(30, 250, 340, 140)}"""))
        assertThat(item(plan, "iob1").where).isEqualTo("status")
        assertThat(item(plan, "chart").where).isEqualTo("graph")
        assertThat(plan.slots.map { it.role }).containsExactly("block status", "block graph")
    }

    @Test
    fun aViewWithDynDataGoesWhereItsValueIs() {
        val d = design(""""delta": ${view(277, 60, 26, 23, ", \"dynData\": \"bat\"")}, "avg_delta": ${view(304, 60, 26, 23, ", \"dynData\": \"phone\"")}""")
        d.put("dynData", JSONObject("""{"bat": {"valueKey": "watch_battery"}, "phone": {"valueKey": "uploader_battery"}}"""))
        val plan = plan(d)
        assertThat(item(plan, "delta").tier).isEqualTo(WffTier.LIVE)
        assertThat(item(plan, "avg_delta").where).isEqualTo("status")
    }

    @Test
    fun layersBySpeedAndData() {
        val plan = plan(design(
            """"background": ${view(0, 0, 400, 400)}""",
            """{"shape": "circle", "count": 40, "x": "u * 400", "y": "200 + sin(t) * 20", "slot": "front"},
               {"shape": "rect", "x": "200", "y": "200", "rotation": "hour * 30"},
               {"text": "clock()", "x": "200", "y": "100"},
               {"text": "glucose(v_sgv)", "x": "200", "y": "300"},
               {"shape": "circle", "x": "mx", "y": "100"},
               {"shape": "line", "x": "10", "y": "10"}""",
            lets = """"mx": "200 + 100 * cos(v_moon_cycle)""""
        ))
        assertThat(item(plan, "L0").tier).isEqualTo(WffTier.BAKE)
        assertThat(item(plan, "L0").note).contains("front loop")
        assertThat(item(plan, "L1").tier).isEqualTo(WffTier.LIVE)
        assertThat(item(plan, "L2").tier).isEqualTo(WffTier.LIVE)
        assertThat(item(plan, "L3").where).isEqualTo("glucose")
        assertThat(item(plan, "L4").where).isEqualTo("sky")
        assertThat(item(plan, "L5").tier).isEqualTo(WffTier.BAKE)
        assertThat(plan.frames).isEqualTo(2 * WffPlanner.DEFAULT_FPS) // one front loop of 2 s
    }

    @Test
    fun aLayerThatReadsTheGlucoseAndItsAgeNeedsTheAgeSlot() {
        val plan = plan(design(""""sgv": ${view(30, 100, 140, 100)}""", """{"shape": "rect", "x": "100", "y": "100", "visible": "v_timestamp >= 12 && v_sgv > 0"}"""))
        assertThat(item(plan, "L0").where).isEqualTo("glucose")
        assertThat(plan.slots.map { it.id }).containsExactly(10, 11).inOrder()
    }

    @Test
    fun viewLayers() {
        val plan = plan(design(
            """"iob1": ${view(240, 100, 30, 24)}, "freetext4": ${view(188, 1, 24, 24)}, "day": ${view(10, 10, 20, 20)}""",
            """{"view": "iob1", "alpha": "sin(t * 6) > 0"}, {"view": "freetext4", "x": "100 * v_night_progress"},
               {"view": "day", "rotation": "sec * 6"}, {"view": "bgi", "x": "10"}"""
        ))
        assertThat(item(plan, "L0").tier).isEqualTo(WffTier.OMIT)
        assertThat(item(plan, "L0").severity).isEqualTo(WffSeverity.WARNING)
        assertThat(item(plan, "iob1").where).isEqualTo("status")
        assertThat(item(plan, "L1").where).isEqualTo("sky")
        assertThat(item(plan, "freetext4").where).isEqualTo("sky")
        assertThat(item(plan, "L2").tier).isEqualTo(WffTier.LIVE)
        assertThat(item(plan, "L3").note).contains("not shown")
    }

    @Test
    fun manyLiveCopiesAreBakedWithAWarning() {
        val plan = plan(design("\"background\": ${view(0, 0, 400, 400)}", """{"shape": "circle", "count": 50, "alpha": "sec / 60"}"""))
        assertThat(item(plan, "L0").tier).isEqualTo(WffTier.BAKE)
        assertThat(item(plan, "L0").severity).isEqualTo(WffSeverity.WARNING)
    }

    @Test
    fun blocksAreMergedNearestFirstUntilTheFaceFits() {
        val users = (1..5).joinToString(", ") { "\"complication$it\": ${view(10 + it * 60, 10, 50, 30)}" }
        val plan = plan(design(
            """$users, "sgv": ${view(30, 100, 140, 100)}, "timestamp": ${view(170, 160, 50, 20)}, "iob1": ${view(240, 100, 30, 24)}, "chart": ${view(30, 250, 340, 140)}""",
            """{"shape": "circle", "x": "100 + v_sun_progress", "y": "50"}, {"shape": "circle", "x": "v_weather_temperature", "y": "50"}"""
        ))
        assertThat(plan.slots.size).isAtMost(WffPlanner.MAX_SLOTS)
        assertThat(plan.notes.joinToString()).contains("merged")
        assertThat(plan.canBuild).isTrue()
    }

    @Test
    fun aStaticRunIsCutOnlyByALivePartThatOverlapsIt() {
        val oneRun = plan(design(""""background": ${view(0, 0, 400, 400)}, "sgv": ${view(30, 100, 140, 100)}, "cover_plate": ${view(300, 300, 50, 50)}"""))
        assertThat(oneRun.staticRuns).isEqualTo(1)
        val twoRuns = plan(design(""""background": ${view(0, 0, 400, 400)}, "sgv": ${view(30, 100, 140, 100)}, "cover_plate": ${view(0, 0, 400, 400)}"""))
        assertThat(twoRuns.staticRuns).isEqualTo(2)
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
            if (design.optJSONObject("sgv")?.optString("visibility") == "visible")
                assertWithMessage(zip.name).that(plan.slots.map { it.id }).contains(WffPlanner.GLUCOSE_SLOT_ID)
            assertWithMessage(zip.name).that(plan.slots.size).isAtMost(WffPlanner.MAX_SLOTS)
        }
    }
}
