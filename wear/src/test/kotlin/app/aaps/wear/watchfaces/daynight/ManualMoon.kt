package app.aaps.wear.watchfaces.daynight

import org.json.JSONObject
import java.io.File

/**
 * The moon template of the manual (`docs/watchface-animation.html`, chapter 13, the example
 * `ex-moon-template`). The watch has no moon template of its own: a design brings this one. The tests
 * read it from the manual, so what a reader copies is what is tested.
 */
object ManualMoon {

    /** The `animation.templates` of the example: the template `moon`. */
    val templates: JSONObject by lazy {
        val page = listOf("docs/watchface-animation.html", "../docs/watchface-animation.html").map(::File).first { it.exists() }.readText()
        val figure = page.substringAfter("""<figure class="ex" id="ex-moon-template"""")
        val text = figure.substringAfter("<pre>").substringBefore("</pre>").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        JSONObject(text).getJSONObject("animation").getJSONObject("templates")
    }

    /** A design json with only an animation: the template and these [layers] (json text of each). */
    fun design(vararg layers: String): JSONObject = JSONObject("""{"animation": {"layers": [${layers.joinToString()}]}}""").apply {
        getJSONObject("animation").put("templates", JSONObject(templates.toString()))
    }
}
