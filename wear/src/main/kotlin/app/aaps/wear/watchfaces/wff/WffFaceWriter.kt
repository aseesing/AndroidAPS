// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.Formula
import app.aaps.wear.watchfaces.animation.FormulaException
import app.aaps.wear.watchfaces.animation.LayerProp
import app.aaps.wear.watchfaces.animation.LayerSource
import app.aaps.wear.watchfaces.animation.TextAlign
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Writes the `watchface.xml` of a converted face from its [WffPlan] (design doc section 2.2, step 4).
 * The face is written in the 400 x 400 space of the design; the watch scales it.
 *
 * First version: the baked picture of the static parts ([BACKGROUND]), the clock and date views,
 * the clock text layers, the hands, the user slots, and the glucose and age slots with the views
 * and text layers that show the glucose. Every planned part it cannot write yet is listed in
 * [Face.notWritten], so the lint can say so; nothing is left out without a line.
 *
 * @param providerApp the package of the wear app whose complications feed the glucose and age slots
 * @param lowMgdl the low glucose threshold of the wearer, in mg/dl
 * @param highMgdl the high glucose threshold of the wearer, in mg/dl
 */
class WffFaceWriter(
    private val providerApp: String,
    private val lowMgdl: Double = 70.0,
    private val highMgdl: Double = 180.0
) {

    /**
     * The written face.
     *
     * @param xml the text of `res/raw/watchface.xml`
     * @param strings the string resources the xml names (`@string/<key>`), with their text
     * @param fonts the fonts of the zip the xml uses: zip key to resource name
     * @param hands the hand pictures of the zip the xml uses: zip key to resource name
     * @param notWritten the planned parts this version does not write, one line each
     */
    data class Face(val xml: String, val strings: Map<String, String>, val fonts: Map<String, String>, val hands: Map<String, String>, val notWritten: List<String>)

    fun write(design: JSONObject, animation: WakeAnimationSpec?, plan: WffPlan): Face = Writing(design, animation, plan).run()

    private inner class Writing(private val design: JSONObject, private val animation: WakeAnimationSpec?, private val plan: WffPlan) {

        private val out = StringBuilder()
        private val strings = LinkedHashMap<String, String>()
        private val fonts = LinkedHashMap<String, String>()
        private val hands = LinkedHashMap<String, String>()
        private val notWritten = ArrayList<String>()

        fun run(): Face {
            strings["app_name"] = plan.title
            line(0, """<WatchFace width="$SIZE" height="$SIZE" clipShape="CIRCLE">""")
            line(1, """<Metadata key="CLOCK_TYPE" value="DIGITAL" />""")
            line(1, """<Scene backgroundColor="#ff000000">""")
            // The baked static parts, hidden in always-on as on our code face
            line(2, """<PartImage x="0" y="0" width="$SIZE" height="$SIZE">""")
            line(3, """<Variant mode="AMBIENT" target="alpha" value="0" />""")
            line(3, """<Image resource="$BACKGROUND" />""")
            line(2, "</PartImage>")
            for (slot in plan.slots) slot(slot)
            for (item in plan.items) if (item.where == null || item.tier != WffTier.COMPLICATION) part(item)
            line(1, "</Scene>")
            line(0, "</WatchFace>")
            return Face(out.toString(), strings, fonts, hands, notWritten)
        }

        // ---- parts outside the slots ----

        private fun part(item: WffPlanItem) {
            if (item.tier != WffTier.LIVE) return
            if (item.where != null && item.where in USER_SLOTS) return
            val view = item.view
            val layer = item.layer?.let { animation?.layers?.getOrNull(it) }
            when {
                view != null && view in CLOCK_VIEWS -> clockView(view)
                view != null && view in DATE_VIEWS  -> dateView(view)
                view != null && view in HAND_VIEWS  -> hand(view)
                layer != null && layer.source is LayerSource.Text && CLOCK_TEXT.matches((layer.source as LayerSource.Text).text.trim()) ->
                    if (!clockLayer(layer.source as LayerSource.Text, layer.props)) notWritten += "${item.name}: its place is not a fixed number"
                else                                -> notWritten += "${item.name}: ${item.note} (not written yet)"
            }
        }

        private fun clockView(key: String) {
            val v = design.optJSONObject(key) ?: return
            val box = box(v)
            val format = when (key) {
                "hour"       -> "hh"
                "minute"     -> "mm"
                "second"     -> "ss"
                "timePeriod" -> return ampmView(v, box)
                else         -> "hh:mm"
            }
            line(2, """<DigitalClock ${rect(box)}>""")
            line(3, """<TimeText format="$format" hourFormat="SYNC_TO_DEVICE" align="${align(v.optString("gravity"))}" x="0" y="0" width="${box.width.roundToInt()}" height="${box.height.roundToInt()}">""")
            line(4, font(v.optString("font"), v.optDouble("textsize", 20.0), color(v.optString("fontColor"), DEFAULT_TEXT)) + " />")
            line(3, "</TimeText>")
            line(2, "</DigitalClock>")
        }

        private fun ampmView(v: JSONObject, box: DesignRect) = text(box, v, "[AMPM_STRING]")

        private fun dateView(key: String) {
            val v = design.optJSONObject(key) ?: return
            val long = if (key == "month") v.optString("monthFormat") == "MMMM" else v.optString("dayNameFormat") == "EEEE"
            val source = when (key) {
                "day_name"    -> if (long) "[DAY_OF_WEEK_F]" else "[DAY_OF_WEEK_S]"
                "day"         -> "[DAY_Z]"
                "month"       -> if (long) "[MONTH_F]" else "[MONTH_S]"
                else          -> "[WEEK_IN_YEAR]"
            }
            text(box(v), v, source)
        }

        private fun text(box: DesignRect, v: JSONObject, source: String) {
            line(2, """<PartText ${rect(box)}>""")
            line(3, """<Text align="${align(v.optString("gravity"))}">""")
            line(4, font(v.optString("font"), v.optDouble("textsize", 20.0), color(v.optString("fontColor"), DEFAULT_TEXT)) + ">")
            val upper = v.optBoolean("allCaps", false)
            if (upper) line(5, "<Upper>")
            line(5, """<Template>%s<Parameter expression="$source" /></Template>""")
            if (upper) line(5, "</Upper>")
            line(4, "</Font>")
            line(3, "</Text>")
            line(2, "</PartText>")
        }

        private fun hand(key: String) {
            val (file, element) = when (key) {
                "hour_hand"   -> "HourHand" to "HourHand"
                "minute_hand" -> "MinuteHand" to "MinuteHand"
                else          -> "SecondHand" to "SecondHand"
            }
            val resource = "hand_" + key.removeSuffix("_hand")
            hands[file] = resource
            val box = design.optJSONObject(key)?.let { box(it) } ?: DesignRect.FULL
            line(2, """<AnalogClock ${rect(box)}>""")
            line(3, """<$element resource="$resource" x="0" y="0" width="${box.width.roundToInt()}" height="${box.height.roundToInt()}" pivotX="0.5" pivotY="0.5" />""")
            line(2, "</AnalogClock>")
        }

        /** A text layer that shows the clock, at its fixed place. False when the place is not a fixed number. */
        private fun clockLayer(source: LayerSource.Text, props: Map<LayerProp, String>): Boolean {
            val x = constant(props[LayerProp.X]) ?: return false
            val y = constant(props[LayerProp.Y]) ?: return false
            val box = textBox(source, x, y)
            line(2, """<DigitalClock ${rect(box)}>""")
            line(3, """<TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="${align(source.align)}" x="0" y="0" width="${box.width.roundToInt()}" height="${box.height.roundToInt()}">""")
            line(4, font(source.font, source.size.toDouble(), source.colors.firstOrNull()?.let { argb(it) } ?: DEFAULT_TEXT) + " />")
            line(3, "</TimeText>")
            line(2, "</DigitalClock>")
            return true
        }

        // ---- the slots ----

        private fun slot(slot: WffPlanSlot) {
            when {
                slot.role in USER_SLOTS     -> userSlot(slot)
                slot.id == WffPlanner.GLUCOSE_SLOT_ID -> glucoseSlot(slot)
                slot.id == WffPlanner.AGE_SLOT_ID     -> ageSlot(slot)
                else                        -> notWritten += "slot ${slot.id} ${slot.role}: its complication is not made yet, so it is left out"
            }
        }

        private fun userSlot(slot: WffPlanSlot) {
            val v = design.optJSONObject(slot.role) ?: JSONObject()
            val w = slot.box.width.roundToInt()
            val h = slot.box.height.roundToInt()
            val n = slot.id
            strings["slot_$n"] = String.format(Locale.ROOT, "Slot %d", n)
            val fontColor = color(v.optString("fontColor"), DEFAULT_TEXT)
            val back = color(v.optString("color"), "#00000000")
            val size = (h * 0.4).coerceIn(10.0, 30.0)
            line(2, """<ComplicationSlot slotId="$n" name="Slot$n" displayName="@string/slot_$n" isCustomizable="TRUE" supportedTypes="SHORT_TEXT RANGED_VALUE SMALL_IMAGE MONOCHROMATIC_IMAGE EMPTY" ${rect(slot.box)}>""")
            line(3, """<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" />""")
            line(3, """<BoundingBox x="0" y="0" width="$w" height="$h" />""")
            for (type in listOf("SHORT_TEXT", "RANGED_VALUE")) {
                line(3, """<Complication type="$type">""")
                line(4, """<PartDraw x="0" y="0" width="$w" height="$h"><RoundRectangle x="0" y="0" width="$w" height="$h" cornerRadiusX="${v.optInt("borderRadius", 0)}" cornerRadiusY="${v.optInt("borderRadius", 0)}"><Fill color="$back" /></RoundRectangle></PartDraw>""")
                line(4, """<PartText x="0" y="0" width="$w" height="$h">""")
                line(5, """<Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="${size.roundToInt()}" color="$fontColor">""")
                line(6, """<Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template>""")
                line(5, "</Font></Text>")
                line(4, "</PartText>")
                line(3, "</Complication>")
            }
            for (type in listOf("SMALL_IMAGE", "MONOCHROMATIC_IMAGE")) {
                val side = minOf(w, h)
                line(3, """<Complication type="$type">""")
                line(4, """<PartImage x="${(w - side) / 2}" y="${(h - side) / 2}" width="$side" height="$side"><Image resource="[COMPLICATION.$type]" /></PartImage>""")
                line(3, "</Complication>")
            }
            line(3, """<Complication type="EMPTY" />""")
            line(2, "</ComplicationSlot>")
        }

        private fun glucoseSlot(slot: WffPlanSlot) {
            strings["glucose"] = "Glucose"
            dataSlot(slot, "Glucose", "glucose", "app.aaps.wear.complications.wff.WffGlucoseComplication") {
                for (item in plan.items.filter { it.where == "glucose" && it.tier == WffTier.COMPLICATION }) glucosePart(item, slot.box)
            }
        }

        private fun ageSlot(slot: WffPlanSlot) {
            strings["glucose_age"] = "Glucose age"
            dataSlot(slot, "GlucoseAge", "glucose_age", "app.aaps.wear.complications.wff.WffGlucoseAgeComplication") {
                for (item in plan.items.filter { it.where == "age" && it.tier == WffTier.COMPLICATION }) {
                    val v = item.view?.let { design.optJSONObject(it) }
                    if (item.view == "timestamp" && v != null && v.optString("dynData").isEmpty()) inSlot(slot.box) { text(box(v), v, "[COMPLICATION.TEXT]") }
                    else notWritten += "${item.name}: ${item.note} (not written yet)"
                }
            }
        }

        private fun dataSlot(slot: WffPlanSlot, name: String, label: String, provider: String, content: () -> Unit) {
            val w = slot.box.width.roundToInt()
            val h = slot.box.height.roundToInt()
            line(2, """<ComplicationSlot slotId="${slot.id}" name="$name" displayName="@string/$label" isCustomizable="FALSE" supportedTypes="RANGED_VALUE EMPTY" ${rect(slot.box)}>""")
            line(3, """<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" primaryProvider="$providerApp/$provider" primaryProviderType="RANGED_VALUE" />""")
            line(3, """<BoundingBox x="0" y="0" width="$w" height="$h" />""")
            line(3, """<Complication type="RANGED_VALUE">""")
            content()
            line(3, "</Complication>")
            line(3, """<Complication type="EMPTY" />""")
            line(2, "</ComplicationSlot>")
        }

        private fun glucosePart(item: WffPlanItem, slotBox: DesignRect) {
            val v = item.view?.let { design.optJSONObject(it) }
            val layer = item.layer?.let { animation?.layers?.getOrNull(it) }
            when {
                item.view == "sgv" && v != null                        -> inSlot(slotBox) { glucoseText(box(v), v.optString("font"), v.optDouble("textsize", 40.0), align(v.optString("gravity")), "[COMPLICATION.TEXT]") }
                item.view == "direction" && v != null                  -> inSlot(slotBox) {
                    line(2, """<PartImage ${rect(box(v))}><Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" /></PartImage>""")
                }

                (item.view == "delta" || item.view == "avg_delta") && v != null && v.optString("dynData").isEmpty() ->
                    inSlot(slotBox) { text(box(v), v, "[COMPLICATION.TITLE]") }

                layer != null && layer.source is LayerSource.Text && GLUCOSE_TEXT.containsMatchIn((layer.source as LayerSource.Text).text) -> {
                    val source = layer.source as LayerSource.Text
                    val x = constant(layer.props[LayerProp.X])
                    val y = constant(layer.props[LayerProp.Y])
                    if (x == null || y == null) notWritten += "${item.name}: its place is not a fixed number"
                    else inSlot(slotBox) { glucoseText(textBox(source, x, y), source.font, source.size.toDouble(), align(source.align), "[COMPLICATION.TEXT]") }
                }

                else                                                   -> notWritten += "${item.name}: ${item.note} (not written yet)"
            }
        }

        /** A glucose text coloured by the wearer's thresholds: the design's low, middle and high colour. */
        private fun glucoseText(box: DesignRect, font: String, size: Double, align: String, source: String) {
            val colours = listOf(
                "[COMPLICATION.RANGED_VALUE_VALUE] &gt; ${num(highMgdl)}" to color(design.optString("highColor"), "#FFFFFF00"),
                "[COMPLICATION.RANGED_VALUE_VALUE] &lt; ${num(lowMgdl)}" to color(design.optString("lowColor"), "#FFFF0000")
            )
            line(2, "<Condition>")
            line(3, "<Expressions>")
            colours.forEachIndexed { i, (expression, _) -> line(4, """<Expression name="level$i">$expression</Expression>""") }
            line(3, "</Expressions>")
            colours.forEachIndexed { i, (_, colour) ->
                line(3, """<Compare expression="level$i">""")
                plainText(box, font, size, align, colour, source)
                line(3, "</Compare>")
            }
            line(3, "<Default>")
            plainText(box, font, size, align, color(design.optString("midColor"), "#FF00FF00"), source)
            line(3, "</Default>")
            line(2, "</Condition>")
        }

        private fun plainText(box: DesignRect, font: String, size: Double, align: String, colour: String, source: String) {
            line(4, """<PartText ${rect(box)}><Text align="$align">""")
            line(5, font(font, size, colour) + """><Template>%s<Parameter expression="$source" /></Template></Font>""")
            line(4, "</Text></PartText>")
        }

        /** The corner that places are measured from: the face, or the box of the slot being written. */
        private var originX = 0f
        private var originY = 0f

        /** Writes [content] with its places made relative to the box of its slot. */
        private fun inSlot(slotBox: DesignRect, content: () -> Unit) {
            originX = slotBox.left
            originY = slotBox.top
            try {
                content()
            } finally {
                originX = 0f
                originY = 0f
            }
        }

        private fun rect(r: DesignRect) =
            """x="${(r.left - originX).roundToInt()}" y="${(r.top - originY).roundToInt()}" width="${r.width.roundToInt()}" height="${r.height.roundToInt()}""""

        // ---- small helpers ----

        private fun font(key: String, size: Double, colour: String): String {
            val family = fontResource(key) ?: "SYNC_TO_DEVICE"
            return """<Font family="$family" size="${size.roundToInt()}" color="$colour""""
        }

        /** The resource of a font of the zip, or null for a font of the system. */
        private fun fontResource(key: String): String? {
            if (key.isEmpty() || key in SYSTEM_FONTS) return null
            return fonts.getOrPut(key) { "font_" + key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_") }
        }

        private fun line(depth: Int, text: String) {
            repeat(depth) { out.append("    ") }
            out.append(text).append('\n')
        }
    }

    private companion object {

        /** The face is written in the design's own space. */
        const val SIZE = 400

        /** The resource of the baked static picture. */
        const val BACKGROUND = "background"

        const val DEFAULT_TEXT = "#FFFFFFFF"

        val USER_SLOTS = (1..5).map { "complication$it" }.toSet()
        val CLOCK_VIEWS = setOf("time", "hour", "minute", "second", "timePeriod")
        val DATE_VIEWS = setOf("day_name", "day", "month", "week_number")
        val HAND_VIEWS = setOf("hour_hand", "minute_hand", "second_hand")
        val SYSTEM_FONTS = setOf("default", "default_bold", "sans_serif", "serif", "monospace", "roboto_condensed_bold", "roboto_condensed_light", "roboto_condensed_regular", "roboto_slab_light")
        val CLOCK_TEXT = Regex("clock\\(\\s*\\)")
        val GLUCOSE_TEXT = Regex("\\b(glucose|bg)\\s*\\(|v_sgv\\b")

        fun box(v: JSONObject) = DesignRect(v.optDouble("leftmargin", 0.0).toFloat(), v.optDouble("topmargin", 0.0).toFloat(), v.optDouble("width", 0.0).toFloat(), v.optDouble("height", 0.0).toFloat())

        /**
         * The box of a one line text at [x], [y] (the place of a text layer: [LayerSource.Text.align]
         * sets which end or the middle is at x, y is the base line). WFF puts a text in a box, so the box
         * is the width of the face and the height of the font, with the base line at about 80 %.
         */
        fun textBox(source: LayerSource.Text, x: Double, y: Double): DesignRect {
            val height = source.size * 1.25f
            val top = (y - source.size * 0.95).toFloat()
            val left = when (source.align) {
                TextAlign.LEFT   -> x.toFloat()
                TextAlign.CENTER -> (x - SIZE / 2.0).toFloat()
                TextAlign.RIGHT  -> (x - SIZE).toFloat()
            }
            return DesignRect(left, top, SIZE.toFloat(), height)
        }

        fun align(gravity: String) = when (gravity) {
            "left"  -> "START"
            "right" -> "END"
            else    -> "CENTER"
        }

        fun align(a: TextAlign) = when (a) {
            TextAlign.LEFT   -> "START"
            TextAlign.CENTER -> "CENTER"
            TextAlign.RIGHT  -> "END"
        }

        /** A colour of the design (`#RRGGBB` or `#AARRGGBB`) as WFF writes it, `#AARRGGBB`. */
        fun color(text: String, default: String): String {
            val t = text.trim()
            return when {
                Regex("#[0-9A-Fa-f]{8}").matches(t) -> t.uppercase(Locale.ROOT)
                Regex("#[0-9A-Fa-f]{6}").matches(t) -> "#FF" + t.substring(1).uppercase(Locale.ROOT)
                else                                -> default
            }
        }

        fun argb(colour: Int) = String.format(Locale.ROOT, "#%08X", colour)

        fun num(x: Double) = WffExpressionPrinter.number(x) ?: "0"

        /** [text] as a fixed number, or null when it is not one. */
        fun constant(text: String?): Double? = try {
            text?.let { Formula.parse(it) { null } }?.takeIf { it.isConstant }?.eval(DoubleArray(0))
        } catch (_: FormulaException) {
            null
        }
    }
}
