// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.AnimLayerSpec
import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.LayerBlend
import app.aaps.wear.watchfaces.animation.LayerCopies
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.LayerSource
import app.aaps.wear.watchfaces.animation.LayerVariables
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import org.json.JSONObject

/**
 * Makes the [WffPlan] of a design: how each view and layer goes to a Watch Face Format (WFF) face.
 * It only reads; it draws nothing and builds nothing, so it also runs in the design tests and on
 * the watch.
 *
 * A converted face is made of four kinds of parts:
 *
 * - **live**: WFF draws them itself, so they are right while our process sleeps: the plain clock
 *   and date texts, the hands, the wearer's own complication slots, and the plain glucose text, the
 *   arrow, the delta and the age of the reading (in the glucose and age slots, fed by AAPS).
 * - **baked**: static art, drawn once by our engine into pictures ([WffPlanItem.run]: 0 is the
 *   picture at the bottom; a later run lies above parts that overlap it), and the layers that move in
 *   the wake ([WffPlanItem.loop]) as frames.
 * - **the data picture**: everything else that shows a value. Designs use views as free sprites (a
 *   weather icon in the `time` view, a gauge hand in `patient_name`, a view placed by a watch
 *   preference), so only our engine can draw them right. The watch draws them all into one
 *   transparent picture for one complication slot ([DATA]), with the wearer's own settings.
 * - **left out**: said so in the lint.
 *
 * The glucose is never only in the data picture when the design shows it as a plain text: the live
 * slot keeps it, its colour and its age right in always-on.
 *
 * Known simplifications: the paint order of the classic views is the usual one, not worked out per
 * design; the box of a layer is its picture's box or the whole face; the data picture is one layer,
 * so a data part cannot lie both under and over a live part; a baked layer that blends (`add`,
 * `screen`) stays in the picture below it, so a data part drawn between them comes to lie over it.
 *
 * @param version the WFF version of the watch (4 on Wear OS 6)
 */
class WffPlanner(private val version: Int = 4) {

    /** The plan of [design] (the design json) with its read animation [animation], or null when it has none. */
    fun plan(design: JSONObject, animation: WakeAnimationSpec?): WffPlan {
        val title = design.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotEmpty() } ?: "design"
        val names = Names(animation)
        val views = HashMap<String, WffPlanItem>()
        fun viewItem(key: String): WffPlanItem? = view(design, key)?.also { views[key] = it }

        // Paint order: background, back layers, the views, the views layers, the cover plate and the hands, front layers
        val ordered = ArrayList<WffPlanItem>()
        viewItem("background")?.let { ordered += it }
        ordered += layers(animation, LayerSlot.BACK, names, design, views)
        for (key in DATA_VIEWS) viewItem(key)?.let { ordered += it }
        // Any other view of the design (the views of a second person, a view a newer app added)
        val known = DATA_VIEWS.toSet() + TOP_VIEWS + "background"
        for (key in design.keys().asSequence().toList().sorted()) if (key !in known && design.optJSONObject(key)?.has("visibility") == true) viewItem(key)?.let { ordered += it }
        val topViews = TOP_VIEWS.mapNotNull { viewItem(it) }
        ordered += layers(animation, LayerSlot.VIEWS, names, design, views)
        ordered += topViews
        ordered += layers(animation, LayerSlot.FRONT, names, design, views)

        val items = withRuns(ordered)
        val run = items.maxOfOrNull { it.run ?: 0 } ?: 0

        val slots = ArrayList<WffPlanSlot>()
        for (item in items) if (item.where in USER_SLOTS) slots += WffPlanSlot(item.where!!.last().digitToInt(), item.where, "wearer's choice", item.box ?: DesignRect.FULL)
        boxOf(items, GLUCOSE)?.let { slots += WffPlanSlot(GLUCOSE_SLOT_ID, GLUCOSE, "RANGED_VALUE", it) }
        boxOf(items, AGE)?.let { slots += WffPlanSlot(AGE_SLOT_ID, AGE, "RANGED_VALUE", it) }
        if (items.any { it.where == DATA }) slots += WffPlanSlot(DATA_SLOT_ID, DATA, "SMALL_IMAGE", DesignRect.FULL)

        return WffPlan(title = title, version = version, items = items, slots = slots.sortedBy { it.id }, staticRuns = run + 1)
    }

    /**
     * [plan] after its wake layers were looked at: a layer of [still] also shows at rest, so it is no
     * loop but a part of a static picture, in its rest pose.
     */
    fun settle(plan: WffPlan, still: Set<Int>): WffPlan {
        val items = withRuns(plan.items.map { if (it.loop && it.layer in still) it.copy(loop = false, note = "shows at rest too: its rest pose in a static picture, its wake motion is left out", severity = WffSeverity.WARNING) else it })
        return plan.copy(items = items, staticRuns = (items.maxOfOrNull { it.run ?: 0 } ?: 0) + 1)
    }

    /** Gives every baked part its static picture: a part that lies over an earlier part of another kind starts a new picture. */
    private fun withRuns(ordered: List<WffPlanItem>): List<WffPlanItem> {
        val items = ArrayList<WffPlanItem>()
        var run = 0
        val between = ArrayList<DesignRect>()
        for (item in ordered) {
            val box = item.box ?: DesignRect.FULL
            if (item.tier == WffTier.BAKE && !item.loop) {
                // A layer that mixes with what lies below it cannot start a picture of its own: it stays
                // with the parts below it, and so lies under the parts in between
                if (!item.blends && between.any { overlaps(it, box) }) {
                    run++
                    between.clear()
                }
                items += item.copy(run = run)
            } else {
                if (item.tier == WffTier.LIVE || item.tier == WffTier.COMPLICATION || item.loop) between += box
                items += item.copy(run = null)
            }
        }
        return items
    }

    private fun boxOf(items: List<WffPlanItem>, where: String): DesignRect? =
        items.filter { it.where == where }.map { it.box ?: DesignRect.FULL }.reduceOrNull(::union)

    // ---- the classic views ----

    private fun view(design: JSONObject, key: String): WffPlanItem? {
        val json = design.optJSONObject(key) ?: return null
        if (json.optString("visibility") != "visible") return null
        val box = DesignRect(json.optDouble("leftmargin", 0.0).toFloat(), json.optDouble("topmargin", 0.0).toFloat(), json.optDouble("width", 0.0).toFloat(), json.optDouble("height", 0.0).toFloat())
        fun item(tier: WffTier, note: String, where: String? = null, severity: WffSeverity = WffSeverity.INFO) = WffPlanItem(key, tier, note, severity, where, box, view = key)
        fun data(why: String) = item(WffTier.COMPLICATION, why, DATA)

        if (key == "background") return item(WffTier.BAKE, "static picture")
        if (key in USER_SLOTS) return item(WffTier.LIVE, "user slot ${key.last()}", key)
        // A view that a watch preference changes: the watch knows the preference, so it draws it
        if (json.has("dynPref") || json.has("twinView")) return data("changed by a watch preference: in the data picture")
        val dyn = json.optString("dynData").takeIf { it.isNotEmpty() }?.let { design.optJSONObject("dynData")?.optJSONObject(it) }
        val dynKey = dyn?.optString("valueKey")
        val dynKinds = dyn?.keys()?.asSequence()?.filter { it !in DYN_BASE }?.map { name -> name.trimEnd { it.isDigit() } }?.toSet().orEmpty()
        val coloursOnly = dynKinds.all { it in COLOUR_KINDS }
        val plain = dyn == null
        val planned = when (key) {
            "sgv"                                 ->
                if (plain || (dynKey == "sgvLevel" && coloursOnly)) item(WffTier.COMPLICATION, "live text, coloured by the glucose level", GLUCOSE) else data("the glucose with $dynKey steps: in the data picture")

            "direction"                           -> if (plain) item(WffTier.COMPLICATION, "the arrow, monochromatic image", GLUCOSE) else data("an arrow with steps: in the data picture")
            "delta"                               -> if (plain) item(WffTier.COMPLICATION, "the title of the glucose slot", GLUCOSE) else data("shows $dynKey: in the data picture")
            "timestamp"                           ->
                if (plain || (dynKey == "timestamp" && dynKinds.all { it in COLOUR_KINDS || it in TEXT_KINDS })) item(WffTier.COMPLICATION, "minutes that keep counting", AGE)
                else data("the age with steps: in the data picture")

            "time", "hour", "minute", "second"    -> if (plain) item(WffTier.LIVE, "DigitalClock") else data("shows $dynKey: in the data picture")
            "day_name", "day", "month"            -> if (plain) item(WffTier.LIVE, "date text") else data("shows $dynKey: in the data picture")
            "hour_hand", "minute_hand", "second_hand" -> if (plain) item(WffTier.LIVE, "AnalogClock hand") else data("a hand with steps: in the data picture")
            in STATIC_VIEWS                       ->
                if (plain && json.optJSONObject("image") == null) item(WffTier.BAKE, "static picture") else data(if (plain) "a picture the watch makes: in the data picture" else "shows $dynKey: in the data picture")

            else                                  -> data(if (plain) "AAPS data: in the data picture" else "shows $dynKey: in the data picture")
        }
        // The watch hides a view by a preference. The data picture follows the watch; a live part
        // cannot, so it follows what the design sets (or the preference's own default).
        val pref = VIEW_PREFS[key]
        return if (planned.where != DATA && planned.tier != WffTier.BAKE && pref != null && !prefOn(design, pref.first, pref.second))
            item(WffTier.OMIT, "off by the watch preference ${pref.first}, as the design sets it")
        else planned
    }

    /** A show or hide preference of the watch as the design sets it in its metadata, else its [default]. */
    private fun prefOn(design: JSONObject, key: String, default: Boolean): Boolean =
        design.optJSONObject("metadata")?.optString(key)?.takeIf { it.isNotEmpty() }?.let { it == "true" } ?: default

    // ---- the layers ----

    private fun layers(animation: WakeAnimationSpec?, slot: LayerSlot, names: Names, design: JSONObject, views: Map<String, WffPlanItem>): List<WffPlanItem> =
        animation?.layers.orEmpty().withIndex().filter { slotOf(it.value) == slot }.map { (index, layer) -> layer(index, layer, names, design, views) }

    /** The engine draws a view layer with the views, whatever slot it names. */
    private fun slotOf(layer: AnimLayerSpec): LayerSlot = if (layer.source is LayerSource.View) LayerSlot.VIEWS else layer.slot

    private fun layer(index: Int, layer: AnimLayerSpec, names: Names, design: JSONObject, views: Map<String, WffPlanItem>): WffPlanItem {
        val source = layer.source
        val read = names.groupsOf(layer)
        val copies = if (layer.copies is LayerCopies.Slices) " (slices)" else if (layer.copies.size > 1) " (${layer.copies.size} copies)" else ""
        fun item(tier: WffTier, note: String, where: String? = null, severity: WffSeverity = WffSeverity.INFO, loop: Boolean = false, box: DesignRect? = (source as? LayerSource.Image)?.rect) =
            WffPlanItem("L$index ${describe(layer)}", tier, note, severity, where, box, layer = index, loop = loop, slot = slotOf(layer).name.lowercase(), blends = layer.blend != LayerBlend.NORMAL || layer.group != null)

        val wake = NameGroup.WAKE in read || NameGroup.RANDOM in read || NameGroup.HEART in read
        val data = read.any { it in DATA_GROUPS } || (source is LayerSource.Image && source.key == MOON_IMAGE) || source is LayerSource.Dynamic
        val glucose = NameGroup.GLUCOSE in read || NameGroup.AGE in read

        if (source is LayerSource.View) {
            // A layer that moves a view goes where its view goes; the engine draws its rest pose there
            val view = views[source.key] ?: view(design, source.key)
            return when {
                view == null                                    -> item(WffTier.OMIT, "${source.key} is not shown")
                view.where == DATA || view.tier == WffTier.BAKE -> item(view.tier, "moves ${source.key}: drawn with it, at rest" + if (wake) " (its wake motion is left out)" else "", view.where, box = view.box)
                wake && !data && NameGroup.CLOCK !in read        -> item(WffTier.OMIT, "${source.key} moves only during the wake: it stays at its rest place", severity = WffSeverity.WARNING)
                else                                            -> item(WffTier.OMIT, "${source.key} is a live part: this move is not written", severity = WffSeverity.WARNING)
            }
        }
        if (source is LayerSource.Text && glucose && !wake) {
            return if (NameGroup.GLUCOSE in read && NameGroup.AGE !in read) item(WffTier.COMPLICATION, "glucose text: live in the glucose slot", GLUCOSE, box = DesignRect.FULL)
            else item(WffTier.COMPLICATION, "reads the glucose and its age: in the data picture", DATA)
        }
        if (source !is LayerSource.Text && NameGroup.AGE in read && !wake)
            return item(
                WffTier.COMPLICATION, "a mark of an old reading, in the data picture: it shows when the watch makes the picture again, at least every 5 minutes", DATA,
                severity = WffSeverity.WARNING
            )
        if (source is LayerSource.Text && names.textReadsClock(source.text) && !wake && !data)
            return item(WffTier.LIVE, "clock text: DigitalClock with the zip font", box = DesignRect.FULL)
        if (wake) {
            val heart = if (NameGroup.HEART in read) "; the heart rate is ${names.fallbackBpm} bpm" else ""
            val lost = if (data || glucose) "; it cannot follow values while it moves" else ""
            return item(
                WffTier.BAKE, "moves in the wake$copies: frames when it only shows in the wake, else its rest pose$heart$lost", loop = true,
                severity = if (heart.isNotEmpty() || lost.isNotEmpty()) WffSeverity.WARNING else WffSeverity.INFO
            )
        }
        if (data || glucose || NameGroup.CLOCK in read || NameGroup.SECONDS in read || NameGroup.WFF_VALUE in read || NameGroup.OPTION in read) {
            val what = read.joinToString(", ") { it.name.lowercase() }
            val fast = NameGroup.SECONDS in read
            return item(
                WffTier.COMPLICATION, "reads $what$copies: in the data picture" + if (fast) "; it reads the seconds, but the picture changes only with the data" else "", DATA,
                severity = if (fast) WffSeverity.WARNING else WffSeverity.INFO
            )
        }
        return item(WffTier.BAKE, "static picture$copies")
    }

    private fun describe(layer: AnimLayerSpec): String = when (val s = layer.source) {
        is LayerSource.Image   -> s.key
        is LayerSource.Dynamic -> s.name
        is LayerSource.Shape   -> s.kind.name.lowercase()
        is LayerSource.Text    -> "text " + s.text.take(TEXT_LABEL)
        is LayerSource.View    -> "view " + s.key
    }

    private fun union(a: DesignRect, b: DesignRect): DesignRect {
        val left = minOf(a.left, b.left)
        val top = minOf(a.top, b.top)
        return DesignRect(left, top, maxOf(a.right, b.right) - left, maxOf(a.bottom, b.bottom) - top)
    }

    private fun overlaps(a: DesignRect, b: DesignRect): Boolean = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    // ---- what formulas read ----

    /** The groups of names a formula can read. */
    internal enum class NameGroup {

        /** `t`, `ms`, `len`, `env`: the wake; WFF has no such time */
        WAKE,

        /** `noise`, `hash`: our own random numbers */
        RANDOM,

        /** `bpm`: the heart rate during the wake */
        HEART,

        /** `min`, `hour`, the date sources and the clock functions */
        CLOCK,

        /** `sec` and the sources with seconds */
        SECONDS,

        /** Values WFF has itself: the watch battery, steps, heart rate, the date, the moon phase */
        WFF_VALUE,

        /** `v_opt_<name>` and `[CONFIGURATION.<name>]`: a setting of the watch app */
        OPTION,

        /** The glucose, its level and trend */
        GLUCOSE,

        /** The age of the reading */
        AGE,

        /** Sun and moon at the wearer's place */
        SKY,

        /** The weather from the phone */
        WEATHER,

        /** Any other AAPS value */
        AAPS;

        companion object {

            fun ofValue(key: String): NameGroup = when {
                key.startsWith("opt_")    -> OPTION
                key in WFF_VALUES         -> WFF_VALUE
                key in GLUCOSE_VALUES     -> GLUCOSE
                key == "timestamp"        -> AGE
                key in SKY_VALUES         -> SKY
                key.startsWith("weather") -> WEATHER
                else                      -> AAPS
            }
        }
    }

    /** The names of a design's formulas, with its `let` names and own functions written out. */
    private class Names(animation: WakeAnimationSpec?) {

        val fallbackBpm = animation?.fallbackBpm
        private val lets: Map<String, String> = animation?.lets.orEmpty().toMap()
        private val functions: Map<String, String> = animation?.functions.orEmpty().associate { it.name to it.body }

        fun groupsOf(layer: AnimLayerSpec): Set<NameGroup> {
            val texts = layer.props.values + layer.lets.map { it.second } + listOfNotNull((layer.source as? LayerSource.Text)?.text)
            val localLets = layer.lets.toMap()
            val found = HashSet<NameGroup>()
            texts.forEach { scan(it, localLets, found, HashSet()) }
            return found
        }

        fun textReadsClock(text: String): Boolean = CLOCK_FUNCTION.containsMatchIn(text)

        private fun scan(text: String, localLets: Map<String, String>, found: MutableSet<NameGroup>, seen: MutableSet<String>) {
            for (match in TOKEN.findAll(text)) {
                val token = match.value
                when {
                    token.startsWith("[")                                            -> found += sourceGroup(token.removeSurrounding("[", "]"))
                    text.substring(match.range.last + 1).trimStart().startsWith("(") -> {
                        when (token) {
                            "noise", "hash"                                                -> found += NameGroup.RANDOM
                            "clock", "clockHour", "clockMinute", "ampm", "date", "icuText" -> found += NameGroup.CLOCK
                            "glucose"                                                      -> found += NameGroup.GLUCOSE
                        }
                        functions[token]?.let { body -> if (seen.add("f:$token")) scan(body, localLets, found, seen) }
                    }

                    token in WAKE_NAMES                                              -> found += NameGroup.WAKE
                    token == "bpm"                                                   -> found += NameGroup.HEART
                    token == "sec"                                                   -> found += NameGroup.SECONDS
                    token == "min" || token == "hour"                                -> found += NameGroup.CLOCK
                    token.startsWith(LayerVariables.VALUE_PREFIX)                    -> found += NameGroup.ofValue(token.removePrefix(LayerVariables.VALUE_PREFIX))
                    else                                                             -> (localLets[token] ?: lets[token])?.let { body -> if (seen.add(token)) scan(body, localLets, found, seen) }
                }
            }
        }

        private fun sourceGroup(name: String): NameGroup = when {
            name.startsWith("CONFIGURATION.")                                                   -> NameGroup.OPTION
            name in setOf("BATTERY_PERCENT", "STEP_COUNT", "HEART_RATE", "MOON_PHASE_POSITION") -> NameGroup.WFF_VALUE
            name.startsWith("WEATHER.")                                                         -> NameGroup.WEATHER
            "SECOND" in name                                                                    -> NameGroup.SECONDS
            else                                                                                -> NameGroup.CLOCK
        }
    }

    companion object {

        /** WFF allows at most this many complication slots in a face. The plan needs at most 8: 5 of the wearer, glucose, age and the data picture. */
        const val MAX_SLOTS = 8

        /** The fixed slot ids, so an update keeps the wearer's settings (design doc 2.3). */
        const val GLUCOSE_SLOT_ID = 10
        const val AGE_SLOT_ID = 11
        const val DATA_SLOT_ID = 20

        /** Where a part goes, see [WffPlanItem.where]. */
        const val GLUCOSE = "glucose"
        const val AGE = "age"
        const val DATA = "data"

        private const val MOON_IMAGE = "@moon"
        private const val TEXT_LABEL = 14

        val USER_SLOTS = (1..5).map { "complication$it" }.toSet()
        private val STATIC_VIEWS = setOf("cover_plate", "cover_chart", "freetext1", "freetext2", "freetext3", "freetext4")

        /** The views drawn below the cover plate, in their usual order. */
        private val DATA_VIEWS = listOf(
            "cover_chart", "chart", "complication1", "complication2", "complication3", "complication4", "complication5",
            "freetext1", "freetext2", "freetext3", "freetext4", "patient_name", "iob1", "iob2", "cob1", "cob2", "delta", "avg_delta",
            "tempTarget", "reservoir", "uploader_battery", "rig_battery", "basalRate", "bgi", "status", "loop", "time", "hour", "minute",
            "second", "timePeriod", "day_name", "day", "month", "week_number", "direction", "timestamp", "sgv"
        )

        /** The views drawn on top: the cover plate and the hands. */
        private val TOP_VIEWS = listOf("cover_plate", "hour_hand", "minute_hand", "second_hand")

        /** The watch preference that shows or hides a live view, and its value when the design does not set it (`PrefMap` of the face). */
        private val VIEW_PREFS = mapOf(
            "sgv" to ("key_show_bg" to true), "direction" to ("key_show_direction" to true), "delta" to ("key_show_delta" to true),
            "timestamp" to ("key_show_ago" to true), "second" to ("key_show_seconds" to true),
            "day_name" to ("key_show_date" to false), "day" to ("key_show_date" to false), "month" to ("key_show_date" to false)
        )

        /** The keys of a `dynData` block that are not a kind of step. */
        private val DYN_BASE = setOf("valueKey", "minData", "maxData")
        private val COLOUR_KINDS = setOf("fontColor", "invalidFontColor")
        private val TEXT_KINDS = setOf("textvalue", "invalidTextvalue")

        private val DATA_GROUPS = setOf(NameGroup.SKY, NameGroup.WEATHER, NameGroup.AAPS)
        private val WAKE_NAMES = setOf("t", "ms", "len", "env")
        private val WFF_VALUES = setOf("watch_battery", "steps", "heart_rate", "day", "month", "day_name", "week_number", "moon_phase")
        private val GLUCOSE_VALUES = setOf("sgv", "sgvLevel", "direction", "delta", "avg_delta")
        private val SKY_VALUES = setOf("sun_progress", "night_progress", "sun_cycle", "moon_cycle", "moon_limb", "moon_north", "moon_lit", "is_day", "sun_next_time")

        private val TOKEN = Regex("\\[[^\\]]+]|[A-Za-z_][A-Za-z0-9_]*")
        private val CLOCK_FUNCTION = Regex("\\b(clock|clockHour|clockMinute|ampm|date)\\s*\\(")
    }
}
