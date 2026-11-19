// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.AnimLayerSpec
import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.LayerCopies
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.LayerSource
import app.aaps.wear.watchfaces.animation.LayerVariables
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Makes the [WffPlan] of a design: which tier each view and layer gets, which complication slots
 * the face needs, how many static pictures and wake frames it bakes (design doc sections 4 to 7).
 *
 * It only reads; it draws nothing and builds nothing. So it runs in the design tests too, and its
 * [WffPlan.lint] tells a designer what a WFF watch will show.
 *
 * What a formula reads decides where it can live (section 3). The planner reads the names in the
 * formula texts (with the `let` names and own functions of the design written out) and sorts them
 * into the groups of [NameGroup].
 *
 * Glucose is never lost: when the glucose, its age, or the user slots cannot all get a slot, the
 * plan has an error and the build stops, so the wearer keeps the face they have.
 *
 * Known simplifications of this first version: the paint order of the classic views is the usual
 * one (background, views, cover plate, hands), not worked out per design; the box of a layer is
 * the picture's box or the whole face; `dynPref` and `twinView` are not resolved yet; wake loops
 * are counted as one loop of at most [LOOP_SECONDS] at [DEFAULT_FPS] per slot; AAPS values that a
 * live part needs as a number (design doc 5.3) are drawn in a block picture for now.
 *
 * @param version the WFF version of the watch (4 on Wear OS 6)
 */
class WffPlanner(private val version: Int = 4) {

    /** The plan of [design] (the design json) with its read animation [animation], or null when it has none. */
    fun plan(design: JSONObject, animation: WakeAnimationSpec?): WffPlan {
        val title = design.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotEmpty() } ?: "design"
        val names = Names(animation)
        val items = ArrayList<Item>()

        // Paint order: background, back layers, the views, the views layers, the cover plate and the hands, front layers
        view(design, "background")?.let { items += Item(it, WffTier.BAKE, "static picture") }
        items += layers(animation, LayerSlot.BACK, names, design)
        for (key in DATA_VIEWS) view(design, key)?.let { items += viewItem(design, it) }
        items += layers(animation, LayerSlot.VIEWS, names, design)
        for (key in TOP_VIEWS) view(design, key)?.let { items += viewItem(design, it) }
        items += layers(animation, LayerSlot.FRONT, names, design)

        // A view moved by a layer that needs the place goes with that layer
        val movedToBlock = items.filter { it.view == null && it.movesView != null && it.where != null }.associate { it.movesView!! to it.where!! }
        val placed = items.map { item -> item.view?.let { movedToBlock[it] }?.let { block -> item.copy(tier = WffTier.COMPLICATION, where = block, note = "placed by a layer, drawn by the $block block") } ?: item }

        val (slots, merged) = slots(placed)
        val notes = ArrayList<String>()
        val final = placed.map { item ->
            val block = item.where?.let { merged[it] ?: it }
            item.copy(where = block)
        }.toMutableList()
        val usedSlots = slots.size
        if (usedSlots > MAX_SLOTS) {
            final += Item(View("slots", null, null), WffTier.OMIT, "the glucose, its age and the user slots need $usedSlots slots, more than $MAX_SLOTS", WffSeverity.ERROR)
        }
        if (merged.isNotEmpty()) notes += "blocks merged to fit $MAX_SLOTS slots: " + merged.entries.joinToString { "${it.key} into ${it.value}" }

        // Static runs: a new picture starts where a live part lies between two baked parts and overlaps the later one
        val runs = staticRuns(final)
        val loops = final.filter { it.loop != null }.groupBy { it.loop!! }
        val fps = DEFAULT_FPS
        val seconds = min(LOOP_SECONDS, (animation?.durationMs ?: 0L) / 1000f)
        val frames = loops.size * (seconds * fps).roundToInt()
        if (frames > MAX_FRAMES) notes += "the loops need $frames frames, more than $MAX_FRAMES: the frame rate goes down"

        return WffPlan(
            title = title,
            version = version,
            items = final.map { it.toPlanItem() },
            slots = slots,
            staticRuns = runs,
            frames = min(frames, MAX_FRAMES),
            maxFrames = MAX_FRAMES,
            notes = notes
        )
    }

    // ---- the classic views ----

    private class View(val key: String, val box: DesignRect?, val dynKey: String?)

    private fun view(design: JSONObject, key: String): View? {
        val json = design.optJSONObject(key) ?: return null
        if (json.optString("visibility") != "visible") return null
        val box = DesignRect(json.optDouble("leftmargin", 0.0).toFloat(), json.optDouble("topmargin", 0.0).toFloat(), json.optDouble("width", 0.0).toFloat(), json.optDouble("height", 0.0).toFloat())
        val dynKey = json.optString("dynData").takeIf { it.isNotEmpty() }?.let { design.optJSONObject("dynData")?.optJSONObject(it)?.optString("valueKey") }
        return View(key, box, dynKey)
    }

    private fun viewItem(design: JSONObject, view: View): Item {
        view.dynKey?.let { key ->
            // A view that shows a value by pictures, colours or texts goes where its value is
            return when (val group = NameGroup.ofValue(key)) {
                NameGroup.WFF_VALUE -> Item(view, WffTier.LIVE, "${key}: one Condition branch per range")
                NameGroup.GLUCOSE   -> Item(view, WffTier.COMPLICATION, "$key, inside the glucose slot", where = GLUCOSE)
                NameGroup.AGE       -> Item(view, WffTier.COMPLICATION, "$key, inside the age slot", where = AGE)
                else                -> Item(view, WffTier.COMPLICATION, "$key in a block picture", where = blockOf(group))
            }
        }
        return when (view.key) {
            "sgv"                                                    -> Item(view, WffTier.COMPLICATION, "text and range colour", where = GLUCOSE)
            "direction"                                              -> Item(view, WffTier.COMPLICATION, "the design's arrow, monochromatic image", where = GLUCOSE)
            "delta", "avg_delta"                                     -> Item(view, WffTier.COMPLICATION, "the title of the glucose slot", where = GLUCOSE)
            "timestamp"                                              -> Item(view, WffTier.COMPLICATION, "minutes that keep counting", where = AGE)
            "chart"                                                  -> Item(view, WffTier.COMPLICATION, "the graph, drawn by our code", where = "graph")
            in USER_SLOTS                                            -> Item(view, WffTier.LIVE, "user slot ${view.key.last()}", where = view.key)
            "time", "hour", "minute", "second", "timePeriod"         -> Item(view, WffTier.LIVE, "DigitalClock")
            "day_name", "day", "month", "week_number"                -> Item(view, WffTier.LIVE, "date text")
            "hour_hand", "minute_hand", "second_hand"                -> Item(view, WffTier.LIVE, "AnalogClock hand")
            "cover_plate", "cover_chart"                             -> Item(view, WffTier.BAKE, "transparent static picture")
            in STATIC_TEXTS                                          ->
                if (design.optJSONObject(view.key)?.has("image") == true) Item(view, WffTier.BAKE, "picture of the view")
                else Item(view, WffTier.BAKE, "static text")

            else                                                     -> Item(view, WffTier.COMPLICATION, "AAPS data in a block picture", where = STATUS)
        }
    }

    // ---- the layers ----

    private fun layers(animation: WakeAnimationSpec?, slot: LayerSlot, names: Names, design: JSONObject): List<Item> =
        animation?.layers.orEmpty().withIndex().filter { slotOf(it.value) == slot }.map { (index, layer) -> layerItem(index, layer, names, design) }

    /** The engine draws a view layer with the views, whatever slot it names. */
    private fun slotOf(layer: AnimLayerSpec): LayerSlot = if (layer.source is LayerSource.View) LayerSlot.VIEWS else layer.slot

    private fun layerItem(index: Int, layer: AnimLayerSpec, names: Names, design: JSONObject): Item {
        val label = "L$index ${describe(layer)}"
        val box = (layer.source as? LayerSource.Image)?.rect
        val read = names.groupsOf(layer)
        val copies = layer.copies.size
        val viewKey = (layer.source as? LayerSource.View)?.key
        val base = Item(view = null, name = label, tier = WffTier.BAKE, note = "", box = box, viewBox = viewKey?.let { view(design, it)?.box }, movesView = viewKey)
        val source = layer.source

        if (source is LayerSource.View) return viewLayerItem(base, source.key, read, design)
        if (source is LayerSource.Image && source.key == MOON_IMAGE) return base.copy(tier = WffTier.COMPLICATION, note = "the moon photo, drawn by the sky block", where = SKY)
        if (NameGroup.GLUCOSE in read && NameGroup.AGE in read)
            return base.copy(tier = WffTier.COMPLICATION, note = "reads the glucose and its age: inside the glucose slot, the age through [REFERENCE]", where = GLUCOSE, needsAge = true)
        if (NameGroup.GLUCOSE in read) return base.copy(tier = WffTier.COMPLICATION, note = "reads the glucose: inside the glucose slot", where = GLUCOSE)
        if (NameGroup.AGE in read) return base.copy(tier = WffTier.COMPLICATION, note = "reads the age: inside the age slot", where = AGE)
        if (NameGroup.SKY in read) return base.copy(tier = WffTier.COMPLICATION, note = "reads the sky at the wearer's place", where = SKY)
        if (NameGroup.WEATHER in read) return base.copy(tier = WffTier.COMPLICATION, note = "reads the weather", where = WEATHER)
        if (NameGroup.AAPS in read) return base.copy(tier = WffTier.COMPLICATION, note = "reads AAPS data", where = STATUS)

        val clockText = source is LayerSource.Text && names.textReadsClock(source.text)
        if (clockText) return base.copy(tier = WffTier.LIVE, note = "clock text: DigitalClock or PartText with the zip font")

        if (NameGroup.WAKE in read || NameGroup.RANDOM in read || NameGroup.HEART in read) {
            val heart = if (NameGroup.HEART in read) "; the heart rate is baked at ${names.fallbackBpm} bpm" else ""
            val many = if (layer.copies is LayerCopies.Slices) " (slices)" else if (copies > 1) " ($copies copies)" else ""
            return base.copy(tier = WffTier.BAKE, note = "rest pose in a static picture, the motion$many in the ${slotName(slotOf(layer))} loop$heart", loop = slotName(slotOf(layer)),
                severity = if (NameGroup.HEART in read) WffSeverity.WARNING else WffSeverity.INFO)
        }
        if (NameGroup.CLOCK in read || NameGroup.WFF_VALUE in read || NameGroup.OPTION in read) {
            val what = listOfNotNull(
                "the clock".takeIf { NameGroup.CLOCK in read }, "watch values".takeIf { NameGroup.WFF_VALUE in read }, "options".takeIf { NameGroup.OPTION in read }
            ).joinToString(" and ")
            if (copies > MAX_LIVE_COPIES) return base.copy(tier = WffTier.BAKE, note = "reads $what, but $copies copies are too many to be live: baked still", severity = WffSeverity.WARNING)
            return base.copy(tier = WffTier.LIVE, note = "reads $what: live parts" + if (copies > 1) " ($copies copies unrolled)" else "")
        }
        return base.copy(tier = WffTier.BAKE, note = "static picture" + if (copies > 1) " ($copies copies)" else "")
    }

    /**
     * A layer that moves a view of the face (design doc 4.4). A place on the sky takes the view into
     * the sky block; a move during the wake only is left out (the view stays at its rest place); a
     * move by the clock is a `Transform`; a fixed move is the view's new place.
     */
    private fun viewLayerItem(base: Item, key: String, read: Set<NameGroup>, design: JSONObject): Item = when {
        view(design, key) == null                           -> base.copy(tier = WffTier.OMIT, note = "$key is not shown", movesView = null)
        NameGroup.SKY in read                               -> base.copy(tier = WffTier.COMPLICATION, note = "$key placed on the sky: drawn by the sky block", where = SKY)
        NameGroup.WAKE in read || NameGroup.RANDOM in read  -> base.copy(tier = WffTier.OMIT, note = "$key moves only during the wake: it stays at its rest place", severity = WffSeverity.WARNING, movesView = null)
        NameGroup.CLOCK in read                             -> base.copy(tier = WffTier.LIVE, note = "$key moved by a Transform on the clock", movesView = null)
        else                                                -> base.copy(tier = WffTier.EXPAND, note = "$key at its new place", movesView = null)
    }

    private fun describe(layer: AnimLayerSpec): String = when (val s = layer.source) {
        is LayerSource.Image   -> s.key
        is LayerSource.Dynamic -> s.name
        is LayerSource.Shape   -> s.kind.name.lowercase()
        is LayerSource.Text    -> "text " + s.text.take(TEXT_LABEL)
        is LayerSource.View    -> "view " + s.key
    }

    private fun slotName(slot: LayerSlot): String = slot.name.lowercase()

    // ---- the slots ----

    /** The slots in id order, and the blocks that were merged into another one. */
    private fun slots(items: List<Item>): Pair<List<WffPlanSlot>, Map<String, String>> {
        val boxes = LinkedHashMap<String, DesignRect>()
        for (item in items) {
            val where = item.where ?: continue
            if (item.tier != WffTier.COMPLICATION && where !in USER_SLOTS) continue
            val box = item.box ?: item.viewBox ?: DesignRect.FULL
            boxes[where] = boxes[where]?.let { union(it, box) } ?: box
        }
        // The age read through [REFERENCE] by a part of the glucose slot: the age slot itself draws nothing
        if (AGE !in boxes && items.any { it.needsAge }) boxes[AGE] = DesignRect(0f, 0f, 1f, 1f)
        val fixed = boxes.keys.filter { it == GLUCOSE || it == AGE || it in USER_SLOTS }
        val blocks = boxes.keys.filter { it !in fixed }.toMutableList()
        val merged = LinkedHashMap<String, String>()
        // Merge the two nearest blocks until the face fits, or no block is left to merge
        while (fixed.size + blocks.size > MAX_SLOTS && blocks.size > 1) {
            var best: Pair<String, String>? = null
            var bestGap = Float.MAX_VALUE
            for (a in blocks) for (b in blocks) if (a < b) {
                val gap = gap(boxes.getValue(a), boxes.getValue(b))
                if (gap < bestGap) { bestGap = gap; best = a to b }
            }
            val (keep, gone) = best!!
            boxes[keep] = union(boxes.getValue(keep), boxes.getValue(gone))
            blocks.remove(gone)
            merged[gone] = keep
            merged.entries.filter { it.value == gone }.forEach { it.setValue(keep) }
        }
        val slots = ArrayList<WffPlanSlot>()
        for (key in fixed) slots += when (key) {
            GLUCOSE -> WffPlanSlot(GLUCOSE_SLOT_ID, GLUCOSE, "RANGED_VALUE", boxes.getValue(key))
            AGE     -> WffPlanSlot(AGE_SLOT_ID, AGE, "RANGED_VALUE", boxes.getValue(key))
            else    -> WffPlanSlot(key.last().digitToInt(), key, "wearer's choice", boxes.getValue(key))
        }
        blocks.forEachIndexed { i, block -> slots += WffPlanSlot(FIRST_BLOCK_ID + i, "block $block", "SMALL_IMAGE", boxes.getValue(block)) }
        return slots.sortedBy { it.id } to merged
    }

    private fun union(a: DesignRect, b: DesignRect): DesignRect {
        val left = min(a.left, b.left)
        val top = min(a.top, b.top)
        return DesignRect(left, top, max(a.right, b.right) - left, max(a.bottom, b.bottom) - top)
    }

    /** The room between two boxes, 0 when they touch or overlap. */
    private fun gap(a: DesignRect, b: DesignRect): Float {
        val dx = max(0f, max(a.left, b.left) - min(a.right, b.right))
        val dy = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        return dx + dy
    }

    private fun overlaps(a: DesignRect, b: DesignRect): Boolean = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun staticRuns(items: List<Item>): Int {
        var runs = 0
        var open = false
        val between = ArrayList<DesignRect>()
        for (item in items) {
            val box = item.box ?: item.viewBox ?: DesignRect.FULL
            if (item.tier == WffTier.BAKE) {
                if (!open || between.any { overlaps(it, box) }) {
                    runs++
                    open = true
                    between.clear()
                }
            } else if (item.tier == WffTier.LIVE || item.tier == WffTier.COMPLICATION) {
                if (open) between += box
            }
        }
        return runs
    }

    // ---- what formulas read ----

    /** The groups of names a formula can read, and so where it can live (design doc section 3). */
    internal enum class NameGroup {

        /** `t`, `ms`, `len`, `env`: the wake; WFF has no such time */
        WAKE,

        /** `noise`, `hash`: our own random numbers, baked */
        RANDOM,

        /** `bpm`: the heart rate during the wake */
        HEART,

        /** `sec`, `min`, `hour`, the clock and date sources and functions */
        CLOCK,

        /** Values WFF has itself: the watch battery, steps, heart rate, the date, the moon phase */
        WFF_VALUE,

        /** `v_opt_<name>` and `[CONFIGURATION.<name>]` */
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
                key.startsWith("opt_")  -> OPTION
                key in WFF_VALUES       -> WFF_VALUE
                key in GLUCOSE_VALUES   -> GLUCOSE
                key == "timestamp"      -> AGE
                key in SKY_VALUES       -> SKY
                key.startsWith("weather") -> WEATHER
                else                    -> AAPS
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
                    token.startsWith("[")         -> found += sourceGroup(token.removeSurrounding("[", "]"))
                    match.range.last + 1 < text.length && text.substring(match.range.last + 1).trimStart().startsWith("(") -> {
                        when (token) {
                            "noise", "hash"                                           -> found += NameGroup.RANDOM
                            "clock", "clockHour", "clockMinute", "ampm", "date", "icuText" -> found += NameGroup.CLOCK
                            "glucose"                                                 -> found += NameGroup.GLUCOSE
                        }
                        functions[token]?.let { body -> if (seen.add("f:$token")) scan(body, localLets, found, seen) }
                    }

                    token in WAKE_NAMES           -> found += NameGroup.WAKE
                    token == "bpm"                -> found += NameGroup.HEART
                    token in CLOCK_NAMES          -> found += NameGroup.CLOCK
                    token.startsWith(LayerVariables.VALUE_PREFIX) -> found += NameGroup.ofValue(token.removePrefix(LayerVariables.VALUE_PREFIX))
                    else                          -> (localLets[token] ?: lets[token])?.let { body -> if (seen.add(token)) scan(body, localLets, found, seen) }
                }
            }
        }

        private fun sourceGroup(name: String): NameGroup = when {
            name.startsWith("CONFIGURATION.")                                  -> NameGroup.OPTION
            name in setOf("BATTERY_PERCENT", "STEP_COUNT", "HEART_RATE", "MOON_PHASE_POSITION") -> NameGroup.WFF_VALUE
            name.startsWith("WEATHER.")                                        -> NameGroup.WEATHER
            else                                                               -> NameGroup.CLOCK
        }
    }

    /** One part of the plan while it is made. */
    private data class Item(
        val view: String?,
        val name: String,
        val tier: WffTier,
        val note: String,
        val severity: WffSeverity = WffSeverity.INFO,
        val where: String? = null,
        val box: DesignRect? = null,
        val viewBox: DesignRect? = null,
        val loop: String? = null,
        val movesView: String? = null,
        val needsAge: Boolean = false
    ) {

        /** A classic view of the design. */
        constructor(view: View, tier: WffTier, note: String, severity: WffSeverity = WffSeverity.INFO, where: String? = null) :
            this(view.key, view.key, tier, note, severity, where, view.box)

        fun toPlanItem() = WffPlanItem(name, tier, note, severity, where, box ?: viewBox)
    }

    companion object {

        /** WFF allows at most this many complication slots in a face. */
        const val MAX_SLOTS = 8

        /** All baked wake frames together, by default (hint `frames`). */
        const val MAX_FRAMES = 150

        /** The default frame rate of a loop, WFF's own default. */
        const val DEFAULT_FPS = 15

        /** The longest loop of a layer that shows for the whole wake, in seconds (the prototype's snow loop). */
        const val LOOP_SECONDS = 2f

        /** More copies than this are not made live element by element (186 tree lights would cost more than one picture). */
        const val MAX_LIVE_COPIES = 24

        /** The fixed slot ids of the role table, so an update keeps the wearer's settings (section 2.3). */
        const val GLUCOSE_SLOT_ID = 10
        const val AGE_SLOT_ID = 11
        const val FIRST_BLOCK_ID = 20

        private const val GLUCOSE = "glucose"
        private const val AGE = "age"
        private const val SKY = "sky"
        private const val WEATHER = "weather"
        private const val STATUS = "status"
        private const val MOON_IMAGE = "@moon"
        private const val TEXT_LABEL = 14

        private fun blockOf(group: NameGroup): String = when (group) {
            NameGroup.SKY     -> SKY
            NameGroup.WEATHER -> WEATHER
            else              -> STATUS
        }

        private val USER_SLOTS = (1..5).map { "complication$it" }.toSet()
        private val STATIC_TEXTS = setOf("freetext1", "freetext2", "freetext3", "freetext4")

        /** The views drawn below the cover plate, in their usual order. */
        private val DATA_VIEWS = listOf(
            "cover_chart", "chart", "complication1", "complication2", "complication3", "complication4", "complication5",
            "freetext1", "freetext2", "freetext3", "freetext4", "patient_name", "iob1", "iob2", "cob1", "cob2", "delta", "avg_delta",
            "tempTarget", "reservoir", "uploader_battery", "rig_battery", "basalRate", "bgi", "status", "loop", "time", "hour", "minute",
            "second", "timePeriod", "day_name", "day", "month", "week_number", "direction", "timestamp", "sgv"
        )

        /** The views drawn on top: the cover plate and the hands. */
        private val TOP_VIEWS = listOf("cover_plate", "hour_hand", "minute_hand", "second_hand")

        private val WAKE_NAMES = setOf("t", "ms", "len", "env")
        private val CLOCK_NAMES = setOf("sec", "min", "hour")
        private val WFF_VALUES = setOf("watch_battery", "steps", "heart_rate", "day", "month", "day_name", "week_number", "moon_phase")
        private val GLUCOSE_VALUES = setOf("sgv", "sgvLevel", "direction", "delta", "avg_delta")
        private val SKY_VALUES = setOf("sun_progress", "night_progress", "sun_cycle", "moon_cycle", "moon_limb", "moon_north", "moon_lit", "is_day", "sun_next_time")

        private val TOKEN = Regex("\\[[^\\]]+]|[A-Za-z_][A-Za-z0-9_]*")
        private val CLOCK_FUNCTION = Regex("\\b(clock|clockHour|clockMinute|ampm|date)\\s*\\(")
    }
}
