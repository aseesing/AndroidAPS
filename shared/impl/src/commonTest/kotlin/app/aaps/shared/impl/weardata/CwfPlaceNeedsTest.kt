// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.shared.impl.weardata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a design needs the place for. The snippets are cut from the real designs of the personal
 * build (D2, Cyberpunk, Grand Complication, Aurora Town and the stock AAPS 4 designs), in the form the
 * watch keeps them (the json of the zip, written again by `JSONObject.toString(4)`, or as one line).
 */
class CwfPlaceNeedsTest {

    private fun uses(json: String) = CwfPlaceNeeds.of(json)

    @Test
    fun theNextSunriseOrSunsetNeedsThePlace() {
        // D2
        val d2 = """
            {
                "metadata": {"name": "D2"},
                "dynData": {
                    "sunTime": {
                        "valueKey": "sun_next_time",
                        "minData": 0,
                        "maxData": 1,
                        "textvalue1": "@time",
                        "invalidTextvalue": "--:--"
                    }
                }
            }
        """
        assertEquals(setOf(CwfPlaceUse.SUN_AND_MOON), uses(d2))
    }

    @Test
    fun theTemperatureNeedsThePlaceForTheWeather() {
        // D2
        val d2 = """
            {
                "dynData": {
                    "temperature": {
                        "valueKey": "weather_temperature",
                        "textvalue1": "%.0f°",
                        "invalidTextvalue": ""
                    }
                }
            }
        """
        assertEquals(setOf(CwfPlaceUse.WEATHER), uses(d2))
    }

    @Test
    fun weatherLooksNeedThePlaceForTheWeather() {
        // Grand Complication, one line
        val grand = """{"weatherLooks":{"partly":{"images":{"Cloud":"CloudPartly"}},"rain":{"images":{"Cloud":"CloudRain"}}}}"""
        assertEquals(setOf(CwfPlaceUse.WEATHER), uses(grand))
    }

    @Test
    fun theSunOnItsOrbitNeedsThePlace() {
        // Cyberpunk Yellow
        val cyberpunk = """
            {
                "sun": {
                    "width": 24,
                    "height": 24,
                    "orbit": {
                        "valueKey": "sun_progress",
                        "centerX": 200,
                        "centerY": 200,
                        "radius": 188
                    }
                }
            }
        """
        assertEquals(setOf(CwfPlaceUse.SUN_AND_MOON), uses(cyberpunk))
    }

    @Test
    fun theSunAndMoonCyclesNeedThePlace() {
        // Grand Complication, one line
        assertEquals(setOf(CwfPlaceUse.SUN_AND_MOON), uses("""{"sun_disc":{"orbit":{"valueKey":"sun_cycle","radius":150}}}"""))
        assertEquals(setOf(CwfPlaceUse.SUN_AND_MOON), uses("""{"moon_disc":{"orbit":{"valueKey":"moon_cycle","radius":150}}}"""))
    }

    @Test
    fun theNightProgressNeedsThePlace() {
        // D2 and Cyberpunk Tech
        val d2 = """{"dynData": {"stars": {"valueKey": "night_progress", "minData": 0, "maxData": 1}}}"""
        assertEquals(setOf(CwfPlaceUse.SUN_AND_MOON), uses(d2))
    }

    @Test
    fun theDrawnMoonNeedsThePlace() {
        // D2: the moon turned the way it stands in the sky of the place
        val d2 = """{"moon": {"width": 40, "height": 40, "textvalue": "", "moonLight": true}}"""
        assertEquals(setOf(CwfPlaceUse.SUN_AND_MOON), uses(d2))
        // Off is off
        assertTrue(uses("""{"moon": {"moonLight": false}}""").isEmpty())
    }

    @Test
    fun theMoonLayerOfTheAnimationNeedsThePlace() {
        // Aurora Town
        val aurora = """
            {
                "animation": {
                    "durationMs": 6000,
                    "layers": [
                        {"image": "Aurora", "slot": "back"},
                        {
                            "moon": true,
                            "rect": {"left": 316, "top": 78, "width": 44, "height": 44},
                            "slot": "back",
                            "rest": 0
                        }
                    ]
                }
            }
        """
        assertEquals(setOf(CwfPlaceUse.SUN_AND_MOON), uses(aurora))
    }

    @Test
    fun aMoonKeyOutsideTheAnimationIsNotTheMoonLayer() {
        // "moon" is only a layer source inside the animation; elsewhere it is just a name
        assertTrue(uses("""{"views": {"moon": true}}""").isEmpty())
    }

    @Test
    fun theDayLookNeedsThePlace() {
        val design = """{"background": {"color": "#000000"}, "dayLook": {"background": {"color": "#87CEEB"}}}"""
        assertEquals(setOf(CwfPlaceUse.SUN_AND_MOON), uses(design))
    }

    @Test
    fun theExplicitKeyAsksForThePlace() {
        assertEquals(setOf(CwfPlaceUse.ASKED), uses("""{"metadata": {"name": "Mine"}, "needsPlace": true}"""))
        assertTrue(uses("""{"metadata": {"name": "Mine"}, "needsPlace": false}""").isEmpty())
        // Only at the top of the design
        assertTrue(uses("""{"sgv": {"needsPlace": true}}""").isEmpty())
    }

    @Test
    fun aDesignWithEverythingNeedsThePlaceForAll() {
        // Cyberpunk Tech: a drawn moon, the night progress, weather looks
        val tech = """
            {
                "moon": {"moonLight": true},
                "night": {"orbit": {"valueKey": "night_progress"}},
                "weatherLooks": {"day_rain": {"images": {"Background": "BackgroundRain"}}},
                "needsPlace": "true"
            }
        """
        assertEquals(setOf(CwfPlaceUse.SUN_AND_MOON, CwfPlaceUse.WEATHER, CwfPlaceUse.ASKED), uses(tech))
    }

    @Test
    fun aDesignWithoutThemNeedsNothing() {
        // The stock AAPS 4 Grand Complication: the day of the month, the loop, the BG, an animation without a moon
        val stock = """
            {"metadata":{"name":"Grand Complication","author_version":"1.0"},
             "dynData":{"dayNumber":{"valueKey":"day","minData":1,"maxData":31},"loopAge":{"valueKey":"loop"},"bg":{"valueKey":"sgv"}},
             "animation":{"durationMs":6000,"layers":[{"image":"Hands","slot":"front"}]},
             "phase":{"dynData":"moonPhase"},
             "moonPhaseData":{"valueKey":"moon_phase"}}
        """
        assertTrue(uses(stock).isEmpty())
        assertFalse(CwfPlaceNeeds.needsPlace(stock))
    }

    @Test
    fun aJsonThatCannotBeReadNeedsNothing() {
        assertTrue(uses("").isEmpty())
        assertTrue(uses("not json").isEmpty())
        assertTrue(uses("[1, 2]").isEmpty())
        assertFalse(CwfPlaceNeeds.needsPlace(null))
    }
}
