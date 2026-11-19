// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.shared.impl.weardata

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 *   - a `dayLook`, laid over the design from sunrise to sunset,
 *   - `weatherLooks`: their names say day or night (`day_rain`), so they follow sunrise and sunset,
 *   - a picture `"image": "@moon"` ([MOON_IMAGE]) of a view or of a layer,
 *   - a formula (any text of the json: of a layer, of a `let`, the `when` of a look) that reads one of
 *     the values above as `v_<name>`, or `v_is_day`;
 * - [CwfPlaceUse.WEATHER]: `weatherLooks`, a `valueKey` that is [WEATHER_TEMPERATURE], or a formula
 *   that reads `v_weather_kind` or `v_weather_temperature`;
 * - [CwfPlaceUse.ASKED]: `"needsPlace": true` at the top of the json, for a design that wants the
 *   place for another reason. Older code does not know the key and leaves it alone.
 *
 * Only [needsRule] designs get the rule (which can hold the place) on the watch. The weather alone
 * does not: the phone looks it up and sends only the weather.
 *
 * Everything else, the moon phase (`moon_phase`) too, is the same all over the world.
 *
 * The json is read as leniently as the watch reads it (`org.json`): comments (line comments and block comments),
 * commas before a closing bracket, keys and texts without quotes. The watch also takes `#` comments,
 * single quotes and `=` or `;` as separators; a design with those cannot be read here. A json that
 * cannot be read counts as needing nothing (so nothing is read or sent for it), and the caller is told
 * with `onUnreadable`. A very deep json cannot crash this: the parser stops at 400 levels (then the
 * json cannot be read), and the walk keeps its own list instead of calling itself.
 *
 * Pure functions only.
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

    /** The value of the kind of weather, a number (`StoredWeather` on the watch). */
    const val WEATHER_KIND = "weather_kind"

    /** The value that is 1 while the face shows the day (`DayNightChoice` on the watch). */
    const val IS_DAY = "is_day"

    /** The picture of the moon as it stands in the sky, the `image` of a view or of a layer (`MoonPicture` on the watch). */
    const val MOON_IMAGE = "@moon"

    private const val IMAGE = "image"

    private const val VALUE_KEY = "valueKey"

    /** The values that come from the sun and the moon of the place. */
    private val SKY_VALUES = setOf(
        JsonKeyValues.SUN_PROGRESS.key, JsonKeyValues.NIGHT_PROGRESS.key, JsonKeyValues.SUN_CYCLE.key, JsonKeyValues.MOON_CYCLE.key, SUN_NEXT_TIME
    )

    /** The values as a formula names them (`v_sun_cycle`), with what each needs the place for. */
    private val FORMULA_VALUES: Map<String, CwfPlaceUse> =
        (SKY_VALUES + IS_DAY).associate { "v_$it" to CwfPlaceUse.SUN_AND_MOON } + listOf(WEATHER_KIND, WEATHER_TEMPERATURE).associate { "v_$it" to CwfPlaceUse.WEATHER }

    /** A whole name in a formula, so `v_is_day` is not found inside a longer name. */
    private val FORMULA_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        isLenient = true
        allowComments = true
        allowTrailingComma = true
    }

    /**
     * What the design with [designJson] needs the place for; empty when it needs nothing or the json
     * cannot be read. [onUnreadable] is called when a json that is not empty cannot be read.
     */
    fun of(designJson: String?, onUnreadable: () -> Unit = {}): Set<CwfPlaceUse> {
        if (designJson.isNullOrBlank()) return emptySet()
        val root = try {
            json.parseToJsonElement(designJson) as? JsonObject
        } catch (_: Exception) {
            null
        }
        if (root == null) {
            onUnreadable()
            return emptySet()
        }
        val uses = mutableSetOf<CwfPlaceUse>()
        if (root[NEEDS_PLACE].isTrue()) uses += CwfPlaceUse.ASKED
        walk(root, uses)
        return uses
    }

    /** True when the design with [designJson] needs the place for anything. */
    fun needsPlace(designJson: String?): Boolean = of(designJson).isNotEmpty()

    /**
     * True when a design with [uses] needs the rule for when it is day (which can hold the place) on
     * the watch: for the sun and the moon, or because it asks for the place. Not for the weather alone.
     */
    fun needsRule(uses: Set<CwfPlaceUse>): Boolean = CwfPlaceUse.SUN_AND_MOON in uses || CwfPlaceUse.ASKED in uses

    /** True when the design with [designJson] needs the rule on the watch, see [needsRule]. */
    fun needsRule(designJson: String?): Boolean = needsRule(of(designJson))

    /** Goes through every value of [root], with a list of its own, so a deep json does not fill the call stack. */
    private fun walk(root: JsonObject, uses: MutableSet<CwfPlaceUse>) {
        // Each element, and whether it is inside the animation
        val toVisit = ArrayDeque<Pair<JsonElement, Boolean>>()
        toVisit.addLast(root to false)
        while (toVisit.isNotEmpty()) {
            val (element, inAnimation) = toVisit.removeLast()
            when (element) {
                is JsonArray  -> element.forEach { toVisit.addLast(it to inAnimation) }
                is JsonObject -> element.forEach { (key, value) ->
                    when {
                        key == VALUE_KEY && value.text() in SKY_VALUES           -> uses += CwfPlaceUse.SUN_AND_MOON
                        key == VALUE_KEY && value.text() == WEATHER_TEMPERATURE  -> uses += CwfPlaceUse.WEATHER
                        key == MOON_LIGHT && value.isTrue()                      -> uses += CwfPlaceUse.SUN_AND_MOON
                        key == IMAGE && value.text() == MOON_IMAGE               -> uses += CwfPlaceUse.SUN_AND_MOON
                        key == ANIMATION_MOON && inAnimation && value.isTrue()   -> uses += CwfPlaceUse.SUN_AND_MOON
                        key == DAY_LOOK && value is JsonObject                   -> uses += CwfPlaceUse.SUN_AND_MOON
                        key == WEATHER_LOOKS && value is JsonObject              -> uses += setOf(CwfPlaceUse.WEATHER, CwfPlaceUse.SUN_AND_MOON)
                    }
                    toVisit.addLast(value to (inAnimation || key == ANIMATION))
                }

                // Any text can be a formula
                else          -> element.text()?.takeIf { "v_" in it }?.let { text -> FORMULA_NAME.findAll(text).forEach { found -> FORMULA_VALUES[found.value]?.let { uses += it } } }
            }
        }
    }

    /** The text of a value, also of one without quotes, as the watch reads it (`optString`). */
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** `true`, or the text "true", as the watch reads a boolean of a design (`optBoolean`). */
    private fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.content?.equals("true", ignoreCase = true) == true
}
