// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.shared.impl.weardata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a Custom watch face design needs the user's place for, see [CwfPlaceNeeds]. */
enum class CwfPlaceUse {

    /** The real sun and moon on the face, the day look, the next sunrise or sunset */
    SUN_AND_MOON,

    /** The weather */
    WEATHER,

    /** The design asks for the place itself, with [CwfPlaceNeeds.NEEDS_PLACE] */
    ASKED
}

/**
 * Which parts of a Custom watch face design cannot work without the user's place. The phone and the
 * watch both use this one rule: the phone to know when it may read the location for the design and
 * send it, the watch to know how long it keeps the place.
 *
 * A design needs the place when its json (the animation is a part of it) has any of these:
 * - [CwfPlaceUse.SUN_AND_MOON]:
 *   - a `valueKey` (of a `dynData`, a `dynPref`, an `orbit` or an animation `blink`) that is one of
 *     `sun_progress`, `night_progress`, `sun_cycle`, `moon_cycle` or [SUN_NEXT_TIME],
 *   - a view with `"moonLight": true` (the moon drawn the way it stands in the sky there),
 *   - a layer with `"moon": true` inside an `animation`,
 *   - a `dayLook`, laid over the design from sunrise to sunset;
 * - [CwfPlaceUse.WEATHER]: `weatherLooks`, or a `valueKey` that is [WEATHER_TEMPERATURE];
 * - [CwfPlaceUse.ASKED]: `"needsPlace": true` at the top of the json, for a design that wants the
 *   place for another reason. Older code does not know the key and leaves it alone.
 *
 * Everything else, the moon phase (`moon_phase`) too, is the same all over the world. A json that
 * cannot be read needs nothing. Pure functions only.
 */
object CwfPlaceNeeds {

    /** The top level key a design sets to `true` when it wants the place for its own reason. */
    const val NEEDS_PLACE = "needsPlace"

    /** The day look of a design (`DayOverlay` on the watch). */
    const val DAY_LOOK = "dayLook"

    /** The weather looks of a design (`DayOverlay` on the watch). */
    const val WEATHER_LOOKS = "weatherLooks"

    /** A view that shows the moon (`MoonPicture` on the watch). */
    const val MOON_LIGHT = "moonLight"

    /** The wake-up animation of a design (`WakeAnimationSpec` on the watch). */
    const val ANIMATION = "animation"

    /** A layer of the animation that shows the moon (`WakeAnimationJson` on the watch). */
    const val ANIMATION_MOON = "moon"

    /** The value of the next sunrise or sunset, a time (`SkyTimes` on the watch). */
    const val SUN_NEXT_TIME = "sun_next_time"

    /** The value of the temperature of the weather (`StoredWeather` on the watch). */
    const val WEATHER_TEMPERATURE = "weather_temperature"

    private const val VALUE_KEY = "valueKey"

    /** The values that come from the sun and the moon of the place. */
    private val SKY_VALUES = setOf(
        JsonKeyValues.SUN_PROGRESS.key, JsonKeyValues.NIGHT_PROGRESS.key, JsonKeyValues.SUN_CYCLE.key, JsonKeyValues.MOON_CYCLE.key, SUN_NEXT_TIME
    )

    private val json = Json { isLenient = true }

    /** What the design with [designJson] needs the place for; empty when it needs nothing or the json cannot be read. */
    fun of(designJson: String?): Set<CwfPlaceUse> {
        if (designJson.isNullOrBlank()) return emptySet()
        val root = try {
            json.parseToJsonElement(designJson) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptySet()
        val uses = mutableSetOf<CwfPlaceUse>()
        if (root[NEEDS_PLACE].isTrue()) uses += CwfPlaceUse.ASKED
        walk(root, inAnimation = false, uses)
        return uses
    }

    /** True when the design with [designJson] needs the place for anything. */
    fun needsPlace(designJson: String?): Boolean = of(designJson).isNotEmpty()

    private fun walk(element: JsonElement, inAnimation: Boolean, uses: MutableSet<CwfPlaceUse>) {
        when (element) {
            is JsonArray  -> element.forEach { walk(it, inAnimation, uses) }
            is JsonObject -> element.forEach { (key, value) ->
                when {
                    key == VALUE_KEY && value.text() in SKY_VALUES           -> uses += CwfPlaceUse.SUN_AND_MOON
                    key == VALUE_KEY && value.text() == WEATHER_TEMPERATURE  -> uses += CwfPlaceUse.WEATHER
                    key == MOON_LIGHT && value.isTrue()                      -> uses += CwfPlaceUse.SUN_AND_MOON
                    key == ANIMATION_MOON && inAnimation && value.isTrue()   -> uses += CwfPlaceUse.SUN_AND_MOON
                    key == DAY_LOOK && value is JsonObject                   -> uses += CwfPlaceUse.SUN_AND_MOON
                    key == WEATHER_LOOKS && value is JsonObject              -> uses += CwfPlaceUse.WEATHER
                }
                walk(value, inAnimation || key == ANIMATION, uses)
            }

            else          -> Unit
        }
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `true`, or the text "true", as the watch reads a boolean of a design (`optBoolean`). */
    private fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.content?.equals("true", ignoreCase = true) == true
}
