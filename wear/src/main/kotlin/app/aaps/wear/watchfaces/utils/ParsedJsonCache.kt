// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
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

    /**
     * [json] parsed and then changed by [transform], made again only when [json] or [extra] (compared
     * with `equals`, for example the names of the pictures of the zip that [transform] reads) changed.
     */
    fun parse(json: String, extra: Any? = null, transform: (JSONObject) -> JSONObject = { it }): JSONObject {
        parsed?.let { if (json == text && extra == this.extra) return it }
        val result = transform(JSONObject(json))
        parses++
        text = json
        this.extra = extra
        parsed = result
        return result
    }

    private var extra: Any? = null
}
