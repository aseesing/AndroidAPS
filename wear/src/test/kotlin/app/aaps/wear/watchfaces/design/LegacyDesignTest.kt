// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.design

import android.graphics.Color
import app.aaps.wear.watchfaces.animation.LayerAnimator
import app.aaps.wear.watchfaces.animation.LayerGradient
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.LayerSource
import app.aaps.wear.watchfaces.animation.ShapeKind
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.animation.testColor
import app.aaps.wear.watchfaces.design.LegacyDesign.Orbit
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The translator of the older design keys: each old key gives the new form that the engine
 * draws the same, and a design without old keys is left alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyDesignTest {

    private fun translate(json: String, images: Set<String> = emptySet()) = LegacyDesign.translate(JSONObject(json), images)

    /** [value] as text with the keys of every object sorted, to compare two json values whole. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(value.opt(it)) }
        is JSONArray  -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String     -> JSONObject.quote(value)
        is Number     -> value.toDouble().toString()
        else          -> value.toString()
    }

    private fun assertSameJson(actual: Any?, expected: Any?) = assertThat(canonical(actual)).isEqualTo(canonical(expected))

    private fun layersOf(design: JSONObject): List<JSONObject> = design.getJSONObject(WakeAnimationSpec.JSON_KEY).getJSONArray("layers").let { list ->
        (0 until list.length()).map { list.getJSONObject(it) }
    }

    @Test
    fun aDesignWithoutOldKeysIsLeftAlone() {
        val design = JSONObject("""{"hour": {"fontColor": "#FFFFFF"}, "animation": {"layers": [{"use": "rain"}, {"shape": "circle"}]}, "looks": []}""")
        assertThat(LegacyDesign.hasOldKeys(design)).isFalse()
        assertThat(LegacyDesign.translate(design)).isSameInstanceAs(design)
        // and a translated design has none left
        val old = JSONObject("""{"animation": {"rain": {}, "layers": [{"moon": true}, {"shape": "band"}]}, "freetext1": {"moonLight": true, "orbit": {"valueKey": "sun_progress"}}, "dayLook": {}, "weatherLooks": {"rain": {}}}""")
        val new = LegacyDesign.translate(old)
        assertThat(LegacyDesign.hasOldKeys(new)).isFalse()
        assertThat(LegacyDesign.translate(new)).isSameInstanceAs(new)
    }

    @Test
    fun theEffectsBecomeUsesInTheOldPaintOrder() {
        val new = translate(
            """{"animation": {"durationMs": 3000,
                 "glint": {"rings": [{"cx": 200, "cy": 200, "inner": 100, "outer": 120}], "alpha": 90, "note": "dropped"},
                 "pulse": {"image": "heart", "top": 30, "fallbackBpm": 70},
                 "wobble": [{"views": ["w"]}],
                 "slide": [{"views": ["s"], "fullDx": 5}],
                 "sweep": [{"views": ["a", "", "a", "b"], "emptyAngle": -60, "fullAngle": -20}],
                 "blink": [{"views": ["x", "y"], "valueKey": "watch_battery", "atOrBelow": 20}, {"views": ["z"], "valueKey": ""}],
                 "trace": {"view": "chart", "rect": {"left": 10, "top": 20, "width": 30, "height": 40}},
                 "glitch": {"bands": 4},
                 "rain": {"count": 50},
                 "layers": [{"shape": "circle"}]
               }}"""
        )
        val animation = new.getJSONObject(WakeAnimationSpec.JSON_KEY)
        assertThat(animation.keys().asSequence().toSet()).containsExactly("durationMs", "fallbackBpm", "layers")
        assertThat(animation.getInt("fallbackBpm")).isEqualTo(70)
        assertSameJson(
            animation.getJSONArray("layers"), JSONArray(
                """[
                  {"use": "rain", "count": 50},
                  {"use": "glitch", "bands": 4},
                  {"use": "trace", "view": "chart", "rect": {"left": 10, "top": 20, "width": 30, "height": 40}},
                  {"use": "blink", "view": "x", "valueKey": "watch_battery", "atOrBelow": 20},
                  {"use": "blink", "view": "y", "valueKey": "watch_battery", "atOrBelow": 20},
                  {"use": "sweep", "view": "a", "emptyAngle": -60, "fullAngle": -20},
                  {"use": "sweep", "view": "b", "emptyAngle": -60, "fullAngle": -20},
                  {"use": "slide", "view": "s", "fullDx": 5},
                  {"use": "wobble", "view": "w"},
                  {"use": "pulse", "image": "heart", "top": 30},
                  {"use": "glint", "rings": [{"cx": 200, "cy": 200, "inner": 100, "outer": 120}], "alpha": 90},
                  {"shape": "circle"}
                ]"""
            )
        )
    }

    @Test
    fun effectsTheOldEngineLeftOutAreLeftOut() {
        val new = translate("""{"animation": {"trace": {"view": ""}, "glint": {"alpha": 90}, "sweep": [{"views": []}], "blink": [{"views": ["a"]}]}}""")
        assertThat(layersOf(new)).isEmpty()
    }

    @Test
    fun theTranslatedEffectsAreDrawnInTheOldOrder() {
        val new = translate(
            """{"animation": {"rain": {}, "glitch": {}, "trace": {"rect": {"width": 10, "height": 10}}, "blink": [{"views": ["b"], "valueKey": "v", "atOrBelow": 1}],
               "sweep": [{"views": ["hand"], "emptyAngle": -60, "fullAngle": -20}], "slide": [{"views": ["s"], "fullDx": 5}], "wobble": [{"views": ["w"]}],
               "glint": {"rings": [{"cx": 200, "cy": 200, "inner": 100, "outer": 120}]}, "layers": [{"shape": "dot"}]}}"""
        )
        val spec = WakeAnimationJson(::testColor) { null }.read(new)!!.normalized()
        assertThat(spec.errors).isEmpty()
        val kinds = spec.layers.map { (it.source as? LayerSource.Shape)?.kind ?: (it.source as LayerSource.View).key }
        assertThat(kinds).containsExactly(ShapeKind.LINE, ShapeKind.RECT, "chart", ShapeKind.RECT, "b", "hand", "s", "w", ShapeKind.RECT, ShapeKind.CIRCLE).inOrder()
        assertThat(spec.layers.last().slot).isEqualTo(LayerSlot.BACK)
        assertThat(LayerAnimator(spec.layers, spec.lets).errors).isEmpty()
    }

    @Test
    fun theOldLayerKeysBecomeTheNewOnes() {
        val layers = layersOf(
            translate(
                """{"animation": {"layers": [
                     {"moon": true, "rect": {"left": 316, "top": 78, "width": 44, "height": 44}, "rest": 0},
                     {"moon": false, "shape": "circle"},
                     {"shape": "band", "x": "100 + t", "width": 30, "y": 5, "height": 50, "color": "#FF0000"},
                     {"shape": "band"},
                     {"shape": "DOT", "x": 3, "radius": 7},
                     {"view": "chart", "clipRight": "200"}
                   ]}}"""
            )
        )
        // The moon is the built-in template, in the square of the old rect
        assertSameJson(layers[0], JSONObject("""{"use": "moon", "left": 316, "top": 78, "size": 44, "rest": 0}"""))
        assertSameJson(layers[1], JSONObject("""{"shape": "circle"}"""))
        // The old band had its middle at x
        assertSameJson(
            layers[2],
            JSONObject("""{"shape": "rect", "x": "(100 + t) - (30) / 2", "width": 30, "y": 5, "height": 50, "color": "#FF0000", "gradient": {"type": "linear", "stops": [[0, 0], [0.5, 1], [1, 0]]}}""")
        )
        assertSameJson(layers[3], JSONObject("""{"shape": "rect", "x": "(0) - (10) / 2", "gradient": {"type": "linear", "stops": [[0, 0], [0.5, 1], [1, 0]]}}"""))
        assertSameJson(layers[4], JSONObject("""{"shape": "circle", "x": 3, "radius": 7, "gradient": {"type": "radial", "stops": [[0, 1], [0.25, 1], [1, 0]]}}"""))
        // clipRight is the same key, now for any layer
        assertSameJson(layers[5], JSONObject("""{"view": "chart", "clipRight": "200"}"""))
        val spec = WakeAnimationJson(::testColor) { null }.read(JSONObject().put(WakeAnimationSpec.JSON_KEY, JSONObject().put("layers", JSONArray(layers))))!!
        assertThat(spec.errors).isEmpty()
        // The template of the moon gives several plain layers, the photo of the app among them; the other layers follow
        val own = spec.layers.size - 5
        assertThat(spec.layers.take(own).map { it.source }.filterIsInstance<LayerSource.Dynamic>().map { it.name }.distinct()).containsExactly("moon")
        assertThat((spec.layers[own + 1].source as LayerSource.Shape).let { it.kind to it.gradient?.type }).isEqualTo(ShapeKind.RECT to LayerGradient.Type.LINEAR)
        assertThat((spec.layers[own + 3].source as LayerSource.Shape).colors).containsExactly(Color.WHITE)
    }

    @Test
    fun aViewWithTheMoonShowsTheTemplateOfTheMoon() {
        val new = translate("""{"freetext1": {"width": 20, "moonLight": true}, "freetext2": {"moonLight": false}}""")
        assertSameJson(new, JSONObject("""{"freetext1": {"width": 20, "image": {"use": "moon"}}, "freetext2": {}}"""))
    }

    @Test
    fun aViewOnAnOrbitGetsALayerThatStaysAtTheEnd() {
        val new = translate(
            """{"freetext4": {"width": 24, "height": 24, "leftmargin": 188, "topmargin": 1, "orbit": {"valueKey": "sun_progress", "radius": 187, "startAngle": 195, "endAngle": 345}},
               "animation": {"layers": [{"shape": "circle"}]}}"""
        )
        assertThat(new.getJSONObject("freetext4").has(LegacyDesign.ORBIT_KEY)).isFalse()
        val layers = layersOf(new)
        assertSameJson(layers[0], JSONObject("""{"shape": "circle"}"""))
        assertSameJson(
            layers[1], JSONObject(
                """{"view": "freetext4", "rest": 0,
                    "let": {"angle": "(195 + 150 * clamp(if(v_sun_progress == v_sun_progress, v_sun_progress, 0), 0, 1)) * (pi / 180)"},
                    "x": "200 + 187 * cos(angle) - cx0", "y": "200 + 187 * sin(angle) - cy0"}"""
            )
        )
        // A design with an orbit and no animation gets one, which only places the view: no wake plays
        val only = translate("""{"freetext4": {"orbit": {"valueKey": "sun_progress"}}}""")
        val spec = WakeAnimationJson(::testColor) { null }.read(only)!!.normalized()
        val animator = LayerAnimator(spec.layers, spec.lets)
        assertThat(animator.hasWakeContent).isFalse()
        assertThat(animator.restViewKeys).containsExactly("freetext4")
        assertThat(animator.viewKeys).isEmpty()
    }

    @Test
    fun theOrbitLayerPutsTheMiddleOfTheViewWhereTheOrbitDid() {
        val orbit = Orbit("sun_cycle", centerX = 92f, centerY = 192f, radius = 26f, startAngle = 180f, endAngle = 540f)
        val view = JSONObject().put("width", 40).put("height", 40).put("leftmargin", 72).put("topmargin", 146).put(LegacyDesign.ORBIT_KEY, JSONObject().put("valueKey", "sun_cycle").put("centerX", 92).put("centerY", 192).put("radius", 26).put("startAngle", 180).put("endAngle", 540))
        val new = LegacyDesign.translate(JSONObject().put("freetext3", view))
        val spec = WakeAnimationJson(::testColor) { null }.read(new)!!.normalized()
        val animator = LayerAnimator(spec.layers, spec.lets)
        for (value in listOf(0.0, 0.1, 0.25, 0.5, 0.77, 1.0, -1.0, 2.0)) {
            val place = animator.restPlace("freetext3", 0f, 72 + 20.0, 146 + 20.0, 0L) { if (it == "sun_cycle") value else null }!!
            val (x, y) = orbit.point(value)
            // The left of the view as the old engine laid it out, at 1 pixel per design pixel
            assertWithMessage("x at $value").that((72 + place.dx).toInt()).isEqualTo((x - 40 / 2f).toInt())
            assertWithMessage("y at $value").that((146 + place.dy).toInt()).isEqualTo((y - 40 / 2f).toInt())
        }
        // No value: as the old engine, the start of the arc
        val none = animator.restPlace("freetext3", 0f, 92.0, 166.0, 0L) { null }!!
        assertThat((72 + none.dx).toInt()).isEqualTo((orbit.point(0.0).first - 20f).toInt())
    }

    @Test
    fun theOrbitMathsIsKept() {
        val orbit = Orbit("sun_progress")
        val (x0, y0) = orbit.point(0.0)
        val (xm, ym) = orbit.point(0.5)
        val (x1, y1) = orbit.point(1.0)
        assertThat(x0).isLessThan(30f)
        assertThat(y0).isLessThan(200f)
        assertThat(xm).isWithin(0.01f).of(200f)
        assertThat(ym).isWithin(0.01f).of(12f)
        assertThat(x1).isWithin(0.01f).of(400f - x0)
        assertThat(y1).isWithin(0.01f).of(y0)
        assertThat(orbit.point(-1.0)).isEqualTo(orbit.point(0.0))
        assertThat(orbit.point(3.0)).isEqualTo(orbit.point(1.0))
        assertThat(Orbit.read(JSONObject("""{"orbit": {"valueKey": "night_progress", "radius": 180, "startAngle": 190}}"""))).isEqualTo(Orbit("night_progress", radius = 180f, startAngle = 190f))
        assertThat(Orbit.read(JSONObject("""{"orbit": {}}"""))).isNull()
        assertThat(Orbit.read(null)).isNull()
    }

    // ---- looks ----

    /** The old way the watch laid the day look and the weather look over a design (the reference). */
    private fun oldLook(design: JSONObject, day: Boolean, weather: String?): JSONObject {
        val result = JSONObject(design.toString())
        result.remove(LegacyDesign.DAY_LOOK_KEY)
        result.remove(LegacyDesign.WEATHER_LOOKS_KEY)
        result.remove(LegacyDesign.IMAGES_KEY)
        if (day) design.optJSONObject(LegacyDesign.DAY_LOOK_KEY)?.let { Looks.merge(result, it) }
        weather?.let { w -> design.optJSONObject(LegacyDesign.WEATHER_LOOKS_KEY)?.let { looks -> looks.optJSONObject((if (day) "day_" else "night_") + w) ?: looks.optJSONObject(w) } }
            ?.let { Looks.merge(result, it) }
        result.remove(LegacyDesign.IMAGES_KEY)
        return result
    }

    private fun values(day: Boolean, weather: String?): (String) -> Double? = { key ->
        when (key) {
            "is_day"       -> if (day) 1.0 else 0.0
            "weather_kind" -> LegacyDesign.WEATHERS.indexOf(weather).takeIf { it >= 0 }?.toDouble()
            else           -> null
        }
    }

    private val withWeather = JSONObject(
        """
        {
          "hour": {"fontColor": "#00F0FF"},
          "dayLook": {"hour": {"fontColor": "#0A0A0A"}, "images": {"Logo": "LogoDay"}},
          "weatherLooks": {
            "day_rain": {"hour": {"fontColor": "#222222"}, "images": {"Background": "BackgroundRain"}},
            "night_cloudy": {"patient_name": {"visibility": "visible"}},
            "partly": {"sgv": {"fontColor": "#111111"}}
          }
        }
        """
    )

    @Test
    fun theLooksGiveWhatTheDayLookAndTheWeatherLooksGave() {
        val new = LegacyDesign.translate(withWeather, setOf("Background", "BackgroundDay", "Logo", "Day"))
        val looks = Looks()
        for (day in listOf(true, false)) for (weather in listOf(null) + LegacyDesign.WEATHERS) {
            val look = looks.apply(new, values(day, weather))
            val expected = oldLook(withWeather, day, weather)
            assertWithMessage("day $day, $weather").that(canonical(JSONObject(look.json.toString()).apply { remove(Looks.JSON_KEY) })).isEqualTo(canonical(expected))
        }
        // The day pictures and the swaps of the looks, in the old order
        val pictures = mapOf("Background" to 1, "BackgroundDay" to 2, "BackgroundRain" to 3, "Logo" to 4, "LogoDay" to 5)
        assertThat(looks.apply(new, values(day = true, weather = "rain")).swapped(pictures)).containsExactly(
            "Background", 3, "BackgroundDay", 2, "BackgroundRain", 3, "Logo", 5, "LogoDay", 5
        )
        assertThat(looks.apply(new, values(day = true, weather = "clear")).swapped(pictures)["Background"]).isEqualTo(2)
        assertThat(looks.apply(new, values(day = false, weather = "rain")).swapped(pictures)).isEqualTo(pictures)
    }

    @Test
    fun withoutADayLookTheNightLooksOfTheWeatherAlwaysApply() {
        // The watch was never "day" for a design without a day look
        val design = JSONObject("""{"weatherLooks": {"day_rain": {"a": 1}, "night_rain": {"a": 2}, "cloudy": {"a": 3}}, "images": {}}""")
        val new = LegacyDesign.translate(design, setOf("BackgroundDay"))
        assertSameJson(new.getJSONArray(Looks.JSON_KEY), JSONArray("""[{"when": "v_weather_kind == 2", "patch": {"a": 3}}, {"when": "v_weather_kind == 3", "patch": {"a": 2}}]"""))
        for (day in listOf(true, false)) assertThat(Looks().apply(new, values(day, "rain")).json.getInt("a")).isEqualTo(2)
    }

    @Test
    fun aLookThatChangesTheAnimationGetsTheWholeNewAnimation() {
        val design = JSONObject(
            """{"animation": {"durationMs": 9000, "pulse": {"image": "heart"}},
                "freetext4": {"orbit": {"valueKey": "sun_progress"}},
                "weatherLooks": {"rain": {"animation": {"rain": {"count": 140}}, "images": {"Background": "BackgroundRain"}},
                                 "clear": {"animation": {"pulse": null, "durationMs": null}}}}"""
        )
        val new = LegacyDesign.translate(design)
        val looks = Looks()
        for (weather in LegacyDesign.WEATHERS) {
            val look = looks.apply(new, values(day = false, weather = weather)).json
            val expected = LegacyDesign.translate(oldLook(design, day = false, weather = weather))
            assertWithMessage(weather).that(canonical(look.opt(WakeAnimationSpec.JSON_KEY))).isEqualTo(canonical(expected.opt(WakeAnimationSpec.JSON_KEY)))
        }
        assertSameJson(
            layersOf(looks.apply(new, values(day = false, weather = "rain")).json).map { it.optString("use", it.optString("view")) }.let(::JSONArray),
            JSONArray("""["rain", "pulse", "freetext4"]""")
        )
    }

    @Test
    fun aDayLookIsSplitFromTheWeatherWhenBothChangeTheAnimation() {
        val design = JSONObject(
            """{"animation": {"rain": {}},
                "dayLook": {"animation": {"rain": null}},
                "weatherLooks": {"cloudy": {"animation": {"glitch": {}}}}}"""
        )
        val new = LegacyDesign.translate(design)
        val looks = Looks()
        for (day in listOf(true, false)) for (weather in LegacyDesign.WEATHERS) {
            val look = looks.apply(new, values(day, weather)).json
            val expected = LegacyDesign.translate(oldLook(design, day, weather))
            // No layers is the same as an empty list of layers
            fun layers(json: JSONObject) = json.optJSONObject(WakeAnimationSpec.JSON_KEY)?.optJSONArray("layers") ?: JSONArray()
            assertWithMessage("day $day, $weather").that(canonical(layers(look))).isEqualTo(canonical(layers(expected)))
        }
    }
}
