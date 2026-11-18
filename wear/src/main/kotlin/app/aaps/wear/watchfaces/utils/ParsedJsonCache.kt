package app.aaps.wear.watchfaces.utils

import org.json.JSONObject

/**
 * Parses a watch face design only when its text changed.
 *
 * The custom watch face restyles itself every minute, also in ambient mode, and used to parse its
 * whole design each time - 1440 parses a day of the same text. The parsed object is only read, never
 * changed, so the same one can be handed out again.
 *
 * A text that does not parse throws, as `JSONObject` does, and is not kept.
 */
class ParsedJsonCache {

    private var text: String? = null
    private var parsed: JSONObject? = null

    /** How often a text was really parsed, for tests. */
    var parses = 0
        private set

    fun parse(json: String): JSONObject {
        parsed?.let { if (json == text) return it }
        val result = JSONObject(json)
        parses++
        text = json
        parsed = result
        return result
    }
}
