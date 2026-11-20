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
import app.aaps.wear.watchfaces.animation.TextAnchor
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Writes the `watchface.xml` of a converted face from its [WffPlan] and the baked pictures
 * ([WffAssets]), in the paint order of the plan. The places of the design (400 x 400) are written
 * in a face of [faceSize] units: the size the pictures are baked at, so that a picture is drawn one
 * to one. A face of 400 units with pictures of 454 px is drawn soft on a watch of 454 px (seen on the
 * emulator with the parity suite).
 *
 * Everything but the clock, the hands, the glucose and its age is hidden in always-on, as on our
 * own face. A planned part that cannot be written is listed in [Face.notWritten]; nothing is left
 * out without a line.
 *
 * @param providerApp the package of the wear app whose complications feed the face
 * @param lowMgdl the low glucose threshold of the wearer, in mg/dl
 * @param highMgdl the high glucose threshold of the wearer, in mg/dl
 * @param measure the heights of the fonts of the design, to place its texts
 * @param faceSize the width and height of the face, in pixels of the baked pictures
 */
class WffFaceWriter(
    private val providerApp: String,
    private val lowMgdl: Double = 70.0,
    private val highMgdl: Double = 180.0,
    private val measure: WffFontMeasure = WffFontMeasure { _, _ -> WffFontMetrics.ROBOTO },
    private val faceSize: Int = SIZE
) {

    /** Face units per design pixel. */
    private val scale = faceSize / SIZE.toDouble()

    /** A length of the design in face units. */
    private fun px(designPx: Double): Int = (designPx * scale).roundToInt()
    private fun px(designPx: Float): Int = px(designPx.toDouble())

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

    fun write(design: JSONObject, animation: WakeAnimationSpec?, plan: WffPlan, assets: WffAssets = WffAssets()): Face = Writing(design, animation, plan, assets).run()

    private inner class Writing(private val design: JSONObject, private val animation: WakeAnimationSpec?, private val plan: WffPlan, private val assets: WffAssets) {

        private val out = StringBuilder()
        private val strings = LinkedHashMap<String, String>()
        private val fonts = LinkedHashMap<String, String>()
        private val hands = LinkedHashMap<String, String>()
        private val notWritten = ArrayList<String>()
        private val done = HashSet<String>()

        /** The corner that places are measured from: the face, or the box of the slot being written. */
        private var originX = 0f
        private var originY = 0f

        fun run(): Face {
            strings["app_name"] = plan.title
            line(0, """<WatchFace width="$faceSize" height="$faceSize" clipShape="CIRCLE">""")
            line(1, """<Metadata key="CLOCK_TYPE" value="DIGITAL" />""")
            line(1, """<Scene backgroundColor="#ff000000">""")
            for (item in plan.items) part(item)
            line(1, "</Scene>")
            line(0, "</WatchFace>")
            return Face(out.toString(), strings, fonts, hands, notWritten)
        }

        /** Writes what [item] stands for, once: a picture, a loop and a slot hold many items. */
        private fun part(item: WffPlanItem) {
            val layer = item.layer?.let { animation?.layers?.getOrNull(it) }
            when {
                item.tier == WffTier.OMIT                              -> Unit
                item.tier == WffTier.BAKE && item.loop                 ->
                    assets.loops.withIndex().firstOrNull { it.value.overRun == null && item.layer in it.value.layers }?.let { (i, loop) -> if (done.add("loop$i")) loop(loop) }

                item.tier == WffTier.BAKE                              -> if (done.add("static${item.run}")) {
                    assets.statics[item.run]?.let { picture(it) }
                    // The same picture in motion during the wake, right over it
                    assets.loops.filter { it.overRun == item.run }.forEach { loop(it) }
                }
                item.where in WffPlanner.USER_SLOTS                    -> userSlot(item)
                item.where == WffPlanner.GLUCOSE                       -> if (done.add("glucose")) glucoseSlot()
                item.where == WffPlanner.AGE                           -> if (done.add("age")) ageSlot()
                item.where == WffPlanner.DATA                          -> if (done.add("data")) dataSlot()
                item.view != null && item.view in CLOCK_VIEWS          -> clockView(item.view)
                item.view != null && item.view in DATE_VIEWS           -> dateView(item.view)
                item.view != null && item.view in HAND_VIEWS           -> hand(item.view)
                layer != null && layer.source is LayerSource.Text      -> if (!clockLayer(layer.source as LayerSource.Text, layer.props)) notWritten += "${item.name}: its place is not a fixed number"
                else                                                   -> notWritten += "${item.name}: ${item.note}"
            }
        }

        // ---- pictures ----

        private fun picture(picture: WffAssets.Picture) {
            line(2, """<PartImage ${rect(picture.box)}>""")
            line(3, HIDE_IN_AMBIENT)
            line(3, """<Image resource="${picture.resource}" />""")
            line(2, "</PartImage>")
        }

        private fun loop(loop: WffAssets.Loop) {
            loop.condition?.let {
                line(2, """<Condition><Expressions><Expression name="days">${xml(it)}</Expression></Expressions><Compare expression="days">""")
                loop(loop.copy(condition = null))
                line(2, "</Compare></Condition>")
                return
            }
            line(2, """<PartAnimatedImage ${rect(loop.box)}${loop.blend?.let { " blendMode=\"$it\"" } ?: ""}>""")
            line(3, HIDE_IN_AMBIENT)
            line(3, """<AnimationController play="ON_VISIBLE" beforePlaying="HIDE" afterPlaying="HIDE" loopCount="${loop.loopCount}" delayPlay="${num(loop.delaySeconds.toDouble())}" />""")
            line(3, """<SequenceImages frameRate="${loop.fps}">""")
            loop.frames.forEach { line(4, """<Image resource="$it" />""") }
            line(3, "</SequenceImages>")
            line(2, "</PartAnimatedImage>")
        }

        // ---- live parts ----

        private fun clockView(key: String) {
            val v = design.optJSONObject(key) ?: return
            val box = viewTextBox(v)
            val format = when (key) {
                "hour"   -> "hh"
                "minute" -> "mm"
                "second" -> "ss"
                else     -> if (seconds()) "hh:mm:ss" else "hh:mm"
            }
            // The time with seconds shows without them in always-on, as our face: a second clock for always-on only
            if (format == "hh:mm:ss") {
                digitalClock(box, v, format, ambient = false)
                digitalClock(box, v, "hh:mm", ambient = true)
            } else digitalClock(box, v, format, ambient = null)
        }

        /** A clock text. [ambient]: true shows it only in always-on, false hides it there, null shows it in both. */
        private fun digitalClock(box: DesignRect, v: JSONObject, format: String, ambient: Boolean?) {
            line(2, """<DigitalClock ${rect(box)}${if (ambient == true) " alpha=\"0\"" else ""}>""")
            when (ambient) {
                true  -> line(3, """<Variant mode="AMBIENT" target="alpha" value="255" />""")
                false -> line(3, HIDE_IN_AMBIENT)
                null  -> Unit
            }
            line(3, """<TimeText format="$format" hourFormat="SYNC_TO_DEVICE" align="${align(v.optString("gravity"))}" x="0" y="0" width="${px(box.width)}" height="${px(box.height)}">""")
            line(4, font(v) + " />")
            line(3, "</TimeText>")
            line(2, "</DigitalClock>")
        }

        /** The time shows seconds: the design enables them and the wearer's preference (as the design sets it) allows them. */
        private fun seconds(): Boolean =
            design.optBoolean("enableSecond") && design.optJSONObject("metadata")?.optString("key_show_seconds")?.takeIf { it.isNotEmpty() }?.let { it == "true" } ?: true

        private fun dateView(key: String) {
            val v = design.optJSONObject(key) ?: return
            // The formats are keys of the design itself, as our engine reads them
            val dayName = design.optString("dayNameFormat", "E").takeIf { it.matches(Regex("E{1,4}")) } ?: "E"
            val month = design.optString("monthFormat", "MMM").takeIf { it.matches(Regex("M{1,4}")) } ?: "MMM"
            val source = when (key) {
                "day_name" -> if (dayName.length == 4) "[DAY_OF_WEEK_F]" else "[DAY_OF_WEEK_S]"
                "day"      -> "[DAY_Z]"
                else       -> when (month.length) {
                    1    -> "[MONTH]"
                    2    -> "[MONTH_Z]"
                    3    -> "[MONTH_S]"
                    else -> "[MONTH_F]"
                }
            }
            text(viewTextBox(v), align(v.optString("gravity")), font(v), "%s", source, upper = v.optBoolean("allCaps", false))
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
            // Small hands are a part of the art (a tower clock): hidden with it. Large hands are the clock.
            if (box.width < SIZE / 2) line(3, HIDE_IN_AMBIENT)
            line(3, """<$element resource="$resource" x="0" y="0" width="${px(box.width)}" height="${px(box.height)}" pivotX="0.5" pivotY="0.5" />""")
            line(2, "</AnalogClock>")
        }

        /** A text layer that shows the clock, at its fixed place. False when the place is not a fixed number. */
        private fun clockLayer(source: LayerSource.Text, props: Map<LayerProp, String>): Boolean {
            val box = textBox(source, props, metricsOf(source)) ?: return false
            line(2, """<DigitalClock ${rect(box)}>""")
            line(3, """<TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="${align(source.align)}" x="0" y="0" width="${px(box.width)}" height="${px(box.height)}">""")
            line(4, font(source.font, source.size.toDouble(), source.colors.firstOrNull()?.let { argb(it) } ?: DEFAULT_TEXT) + " />")
            line(3, "</TimeText>")
            line(2, "</DigitalClock>")
            return true
        }

        /** A text: [template] with `%s` for the value of [source], or a fixed text when [source] is null. */
        private fun text(box: DesignRect, align: String, font: String, template: String, source: String?, upper: Boolean = false) {
            val content = if (source == null) "<![CDATA[$template]]>" else """<Template>${xml(template)}<Parameter expression="$source" /></Template>"""
            line(2, """<PartText ${rect(box)}><Text align="$align">""")
            line(3, "$font>" + (if (upper) "<Upper>" else "") + content + (if (upper) "</Upper>" else "") + "</Font>")
            line(2, "</Text></PartText>")
        }

        // ---- the slots ----

        /**
         * A slot of the wearer, in the style of the design: its fill, its ring for a value with a range,
         * its icon and its text. Until the wearer picks something it shows what the code-based face
         * shows there: heart rate, steps, carbs and insulin from AAPS in slots 1 to 4.
         */
        private fun userSlot(item: WffPlanItem) {
            val key = item.where ?: return
            val v = design.optJSONObject(key) ?: JSONObject()
            val box = item.box ?: return
            val w = px(box.width)
            val h = px(box.height)
            val n = key.last().digitToInt()
            strings["slot_$n"] = String.format(Locale.ROOT, "Slot %d", n)
            val back = color(v.optString("color"), CLEAR)
            val radius = px(v.optDouble("borderRadius", 0.0))
            val ring = px(v.optDouble("ringWidth", 0.0))
            val ringOn = color(v.optString("ringPrimaryColor"), CLEAR)
            val ringOff = color(v.optString("ringSecondaryColor"), CLEAR)
            val iconColor = color(v.optString("iconColor"), CLEAR)
            // A wide slot keeps room for its icon before the text, also when the icon has no colour
            // (the design then draws its own icon there); a small or round one has only the text
            val icon = if (w < h * 2) 0 else h
            // The watch shows a slot only when its preference is on, which the design sets: only then
            // the slot starts with a source. The wearer can still pick one for any slot.
            val on = design.optJSONObject("metadata")?.optString("key_show_complication$n") == "true"
            val font = font(v.optString("font"), minOf(v.optDouble("textsize", box.height * 0.5), box.height * 0.9), color(v.optString("fontColor"), DEFAULT_TEXT))
            val policy = USER_SLOT_DEFAULTS[n]?.takeIf { on }?.let { (provider, type) -> """ primaryProvider="$providerApp/$provider" primaryProviderType="$type"""" } ?: ""
            line(2, """<ComplicationSlot slotId="$n" name="Slot$n" displayName="@string/slot_$n" isCustomizable="TRUE" supportedTypes="RANGED_VALUE SHORT_TEXT SMALL_IMAGE MONOCHROMATIC_IMAGE EMPTY" ${rect(box)}>""")
            line(3, """<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"$policy />""")
            line(3, if (radius * 2 >= minOf(w, h)) """<BoundingOval x="0" y="0" width="$w" height="$h" />""" else """<BoundingBox x="0" y="0" width="$w" height="$h" />""")
            for (type in listOf("RANGED_VALUE", "SHORT_TEXT")) {
                line(3, """<Complication type="$type">""")
                line(4, """<PartDraw x="0" y="0" width="$w" height="$h">""")
                line(5, """<RoundRectangle x="0" y="0" width="$w" height="$h" cornerRadiusX="$radius" cornerRadiusY="$radius"><Fill color="$back" /></RoundRectangle>""")
                if (type == "RANGED_VALUE" && ring > 0 && !ringOn.startsWith("#00")) {
                    val arc = """<Arc centerX="${w / 2f}" centerY="${h / 2f}" width="${w - ring}" height="${h - ring}" startAngle="0" endAngle="360">"""
                    if (!ringOff.startsWith("#00")) line(5, """$arc<Stroke color="$ringOff" thickness="$ring" /></Arc>""")
                    line(5, arc + """<Transform target="endAngle" value="360 * ([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / ([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN])" /><Stroke color="$ringOn" thickness="$ring" cap="ROUND" /></Arc>""")
                }
                line(4, "</PartDraw>")
                if (icon > 0 && !iconColor.startsWith("#00")) line(4, """<PartImage x="0" y="0" width="$icon" height="$icon" tintColor="$iconColor"><Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" /></PartImage>""")
                line(4, """<PartText x="$icon" y="0" width="${w - icon}" height="$h"><Text align="${align(v.optString("gravity"))}">$font><Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text></PartText>""")
                line(3, "</Complication>")
            }
            for (type in listOf("SMALL_IMAGE", "MONOCHROMATIC_IMAGE")) {
                val side = minOf(w, h)
                line(3, """<Complication type="$type"><PartImage x="${(w - side) / 2}" y="${(h - side) / 2}" width="$side" height="$side"><Image resource="[COMPLICATION.$type]" /></PartImage></Complication>""")
            }
            line(3, """<Complication type="EMPTY" />""")
            line(2, "</ComplicationSlot>")
        }

        /** One full-face picture of everything the watch draws from AAPS data, see `WffDataComplication`. */
        private fun dataSlot() {
            strings["data"] = "AAPS data"
            line(2, """<ComplicationSlot slotId="${WffPlanner.DATA_SLOT_ID}" name="Data" displayName="@string/data" isCustomizable="FALSE" supportedTypes="SMALL_IMAGE EMPTY" x="0" y="0" width="$faceSize" height="$faceSize">""")
            line(3, """<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" primaryProvider="$providerApp/$PROVIDERS.WffDataComplication" primaryProviderType="SMALL_IMAGE" />""")
            line(3, """<BoundingBox x="0" y="0" width="$faceSize" height="$faceSize" />""")
            line(3, """<Complication type="SMALL_IMAGE">""")
            line(4, """<PartImage x="0" y="0" width="$faceSize" height="$faceSize">""")
            line(5, HIDE_IN_AMBIENT)
            line(5, """<Image resource="[COMPLICATION.SMALL_IMAGE]" />""")
            line(4, "</PartImage>")
            line(3, "</Complication>")
            line(3, """<Complication type="EMPTY" />""")
            line(2, "</ComplicationSlot>")
        }

        /** One part inside a data slot: its box on the face, and what writes it (with and without a reading). */
        private inner class SlotPart(val box: DesignRect, val write: () -> Unit, val writeEmpty: (() -> Unit)? = null)

        private fun glucoseSlot() {
            strings["glucose"] = "Glucose"
            val parts = ArrayList<SlotPart>()
            for (item in plan.items.filter { it.where == WffPlanner.GLUCOSE }) {
                val v = item.view?.let { design.optJSONObject(it) }
                val source = item.layer?.let { animation?.layers?.getOrNull(it) }
                when {
                    item.view == "sgv" && v != null       -> {
                        val colours = sgvColours(v)
                        parts += SlotPart(union(box(v), viewTextBox(v)), { byLevel(colours) { colour -> text(viewTextBox(v), align(v.optString("gravity")), font(v, colour), "%s", "[COMPLICATION.TEXT]") } },
                            { text(viewTextBox(v), align(v.optString("gravity")), font(v, colours.invalid), "---", null) })
                    }

                    // The face tints the arrow with the range colour of the glucose
                    item.view == "direction" && v != null -> parts += SlotPart(box(v), {
                        byLevel(rangeColours()) { colour -> line(2, """<PartImage ${rect(box(v))} tintColor="$colour"><Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" /></PartImage>""") }
                    })
                    item.view == "delta" && v != null     -> parts += SlotPart(union(box(v), viewTextBox(v)), { text(viewTextBox(v), align(v.optString("gravity")), font(v), "%s", "[COMPLICATION.TITLE]") })
                    source != null && source.source is LayerSource.Text -> {
                        val text = source.source as LayerSource.Text
                        val box = textBox(text, source.props, metricsOf(text))
                        if (box == null) notWritten += "${item.name}: its place is not a fixed number"
                        else {
                            val colours = layerColours(text, source.props[LayerProp.COLOR])
                            parts += SlotPart(box, { byLevel(colours) { colour -> text(box, align(text.align), font(text.font, text.size.toDouble(), colour), "%s", "[COMPLICATION.TEXT]") } },
                                { if ("'---'" in text.text) text(box, align(text.align), font(text.font, text.size.toDouble(), colours.invalid), "---", null) })
                        }
                    }

                    else                                  -> notWritten += "${item.name}: ${item.note}"
                }
            }
            slot(WffPlanner.GLUCOSE_SLOT_ID, "Glucose", "glucose", "WffGlucoseComplication", parts)
        }

        private fun ageSlot() {
            strings["glucose_age"] = "Glucose age"
            val parts = ArrayList<SlotPart>()
            for (item in plan.items.filter { it.where == WffPlanner.AGE }) {
                val v = item.view?.let { design.optJSONObject(it) }
                if (item.view != "timestamp" || v == null) {
                    notWritten += "${item.name}: ${item.note}"
                    continue
                }
                val dyn = v.optString("dynData").takeIf { it.isNotEmpty() }?.let { design.optJSONObject("dynData")?.optJSONObject(it) }
                // The face shows whole minutes and a sign after them: 5'
                val template = (dyn?.optString("textvalue1")?.takeIf { "%.0f" in it } ?: "%.0f'").replace("%.0f", "%s").replace("%%", "%")
                val minutes = "floor([COMPLICATION.RANGED_VALUE_VALUE])"
                val steps = dyn?.let { steps(it, "fontColor") }.orEmpty()
                parts += SlotPart(union(box(v), viewTextBox(v)), {
                    if (steps.size < 2) text(viewTextBox(v), align(v.optString("gravity")), font(v, steps.firstOrNull()), template, minutes)
                    else bySteps(dyn!!, steps) { colour -> text(viewTextBox(v), align(v.optString("gravity")), font(v, colour), template, minutes) }
                }, { text(viewTextBox(v), align(v.optString("gravity")), font(v, dyn?.optString("invalidFontColor")?.let { color(it, DEFAULT_TEXT) }), dyn?.optString("invalidTextvalue")?.takeIf { it.isNotEmpty() } ?: "--", null) })
            }
            slot(WffPlanner.AGE_SLOT_ID, "GlucoseAge", "glucose_age", "WffGlucoseAgeComplication", parts)
        }

        private fun slot(id: Int, name: String, label: String, provider: String, parts: List<SlotPart>) {
            if (parts.isEmpty()) return
            val box = parts.map { it.box }.reduce(::union)
            val w = px(box.width)
            val h = px(box.height)
            line(2, """<ComplicationSlot slotId="$id" name="$name" displayName="@string/$label" isCustomizable="FALSE" supportedTypes="RANGED_VALUE EMPTY" ${rect(box)}>""")
            line(3, """<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" primaryProvider="$providerApp/$PROVIDERS.$provider" primaryProviderType="RANGED_VALUE" />""")
            line(3, """<BoundingBox x="0" y="0" width="$w" height="$h" />""")
            originX = box.left
            originY = box.top
            line(3, """<Complication type="RANGED_VALUE">""")
            parts.forEach { it.write() }
            line(3, "</Complication>")
            line(3, """<Complication type="EMPTY">""")
            parts.forEach { it.writeEmpty?.invoke() }
            line(3, "</Complication>")
            originX = 0f
            originY = 0f
            line(2, "</ComplicationSlot>")
        }

        // ---- colours by value ----

        /** The colours of a glucose text: below the low threshold, in range, above the high one, and without a reading. */
        private inner class LevelColours(val low: String, val mid: String, val high: String, val invalid: String)

        /** `sgv` follows the range colours of the design, or the three `fontColor` steps of its `dynData` on `sgvLevel`. */
        private fun sgvColours(v: JSONObject): LevelColours {
            val dyn = v.optString("dynData").takeIf { it.isNotEmpty() }?.let { design.optJSONObject("dynData")?.optJSONObject(it) }
            val steps = dyn?.let { steps(it, "fontColor") }.orEmpty()
            return if (steps.size == 3) LevelColours(steps[0], steps[1], steps[2], dyn?.optString("invalidFontColor")?.let { color(it, steps[1]) } ?: steps[1]) else rangeColours()
        }

        /** The range colours of the design: `lowColor`, `midColor` and `highColor`. */
        private fun rangeColours(): LevelColours {
            val mid = color(design.optString("midColor"), "#FF00FF00")
            return LevelColours(color(design.optString("lowColor"), "#FFFF0000"), mid, color(design.optString("highColor"), "#FFFFFF00"), mid)
        }

        /** The colours of a text layer: its `colorIndex` formula worked out for each glucose level. */
        private fun layerColours(text: LayerSource.Text, colorIndex: String?): LevelColours {
            fun at(level: Double): String {
                val index = try {
                    colorIndex?.let { Formula.parse(it) { name -> if (name == "v_sgvLevel") 0 else if (name == "v_sgv") 1 else null }.eval(doubleArrayOf(level, 120.0)) } ?: 0.0
                } catch (_: FormulaException) {
                    0.0
                }
                return argb(text.colors.getOrElse(if (index.isNaN()) 0 else index.roundToInt()) { text.colors.firstOrNull() ?: -1 })
            }
            return LevelColours(at(-1.0), at(0.0), at(1.0), at(Double.NaN))
        }

        private fun byLevel(colours: LevelColours, write: (String) -> Unit) {
            if (colours.low == colours.mid && colours.mid == colours.high) return write(colours.mid)
            line(2, "<Condition><Expressions>")
            line(3, """<Expression name="high">[COMPLICATION.RANGED_VALUE_VALUE] &gt; ${num(highMgdl)}</Expression>""")
            line(3, """<Expression name="low">[COMPLICATION.RANGED_VALUE_VALUE] &lt; ${num(lowMgdl)}</Expression>""")
            line(2, "</Expressions>")
            line(2, """<Compare expression="high">"""); write(colours.high); line(2, "</Compare>")
            line(2, """<Compare expression="low">"""); write(colours.low); line(2, "</Compare>")
            line(2, "<Default>"); write(colours.mid); line(2, "</Default>")
            line(2, "</Condition>")
        }

        /** The values of the numbered keys [kind]1, [kind]2, ... of a `dynData` block, as colours. */
        private fun steps(dyn: JSONObject, kind: String): List<String> =
            generateSequence(1) { it + 1 }.map { dyn.optString("$kind$it") }.takeWhile { it.isNotEmpty() }.map { color(it, DEFAULT_TEXT) }.toList()

        /** One branch per step of a `dynData` range, as the face picks them: equal parts of `minData` to `maxData`. */
        private fun bySteps(dyn: JSONObject, colours: List<String>, write: (String) -> Unit) {
            val min = dyn.optDouble("minData", 0.0)
            val max = dyn.optDouble("maxData", 0.0)
            line(2, "<Condition><Expressions>")
            for (i in 1 until colours.size) line(3, """<Expression name="step$i">[COMPLICATION.RANGED_VALUE_VALUE] &lt; ${num(min + i * (max - min) / colours.size)}</Expression>""")
            line(2, "</Expressions>")
            for (i in 1 until colours.size) {
                line(2, """<Compare expression="step$i">"""); write(colours[i - 1]); line(2, "</Compare>")
            }
            line(2, "<Default>"); write(colours.last()); line(2, "</Default>")
            line(2, "</Condition>")
        }

        // ---- small helpers ----

        /** The box of the text of a classic text view, moved so that WFF shows the text at the height where the view shows it. */
        private fun viewTextBox(v: JSONObject): DesignRect {
            val box = box(v)
            val metrics = measure.metrics(v.optString("font"), "bold" in v.optString("fontStyle"))
            val shift = WffTextPlace.viewShift(metrics, v.optDouble("textsize", DEFAULT_TEXT_SIZE), box.height.toDouble(), v.optString("gravity", "center") == "center")
            return DesignRect(box.left, (box.top + shift).toFloat(), box.width, box.height)
        }

        private fun metricsOf(text: LayerSource.Text): WffFontMetrics = measure.metrics(text.font, false)

        private fun rect(r: DesignRect) =
            """x="${px(r.left - originX)}" y="${px(r.top - originY)}" width="${px(r.width)}" height="${px(r.height)}""""

        /** The font of a text view, in [colour] or its own. */
        private fun font(v: JSONObject, colour: String? = null): String {
            val style = v.optString("fontStyle")
            // The font default_bold carries its own weight (see the other font())
            val weight = if ("bold" in style && v.optString("font") != BOLD_FONT) """ weight="BOLD"""" else ""
            val slant = if ("italic" in style) """ slant="ITALIC"""" else ""
            return font(v.optString("font"), v.optDouble("textsize", DEFAULT_TEXT_SIZE), colour ?: color(v.optString("fontColor"), DEFAULT_TEXT)) + weight + slant
        }

        private fun font(key: String, size: Double, colour: String): String {
            val family = if (key.isEmpty() || key in SYSTEM_FONTS) "SYNC_TO_DEVICE" else fonts.getOrPut(key) { "font_" + key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_") }
            val weight = if (key == BOLD_FONT) """ weight="BOLD"""" else ""
            return """<Font family="$family" size="${px(size)}" color="$colour"$weight"""
        }

        private fun line(depth: Int, text: String) {
            repeat(depth) { out.append("    ") }
            out.append(text).append('\n')
        }
    }

    private companion object {

        /** The face is written in the design's own space. */
        const val SIZE = 400

        const val DEFAULT_TEXT = "#FFFFFFFF"

        /** The system font that is bold: WFF has only the font of the device, with a weight. */
        const val BOLD_FONT = "default_bold"

        /** The text size of a classic view that sets none, as `CustomWatchface` uses. */
        const val DEFAULT_TEXT_SIZE = 22.0
        const val CLEAR = "#00000000"

        /** What a slot of the wearer shows until they pick something, as on the code-based face (`customSlotDefaultSource`). */
        val USER_SLOT_DEFAULTS = mapOf(
            1 to ("app.aaps.wear.health.HeartRateComplication" to "RANGED_VALUE"),
            2 to ("app.aaps.wear.health.StepsComplication" to "RANGED_VALUE"),
            3 to ("app.aaps.wear.complications.CobIconComplication" to "SHORT_TEXT"),
            4 to ("app.aaps.wear.complications.IobIconComplication" to "SHORT_TEXT")
        )
        const val PROVIDERS = "app.aaps.wear.complications.wff"
        const val HIDE_IN_AMBIENT = """<Variant mode="AMBIENT" target="alpha" value="0" />"""

        val CLOCK_VIEWS = setOf("time", "hour", "minute", "second")
        val DATE_VIEWS = setOf("day_name", "day", "month")
        val HAND_VIEWS = setOf("hour_hand", "minute_hand", "second_hand")
        val SYSTEM_FONTS = setOf("default", "default_bold", "sans_serif", "serif", "monospace", "roboto_condensed_bold", "roboto_condensed_light", "roboto_condensed_regular", "roboto_slab_light")

        fun box(v: JSONObject) = DesignRect(v.optDouble("leftmargin", 0.0).toFloat(), v.optDouble("topmargin", 0.0).toFloat(), v.optDouble("width", 0.0).toFloat(), v.optDouble("height", 0.0).toFloat())

        fun union(a: DesignRect, b: DesignRect): DesignRect {
            val left = minOf(a.left, b.left)
            val top = minOf(a.top, b.top)
            return DesignRect(left, top, maxOf(a.right, b.right) - left, maxOf(a.bottom, b.bottom) - top)
        }

        /**
         * The box of a one line text layer whose place is a fixed number, or null when it is not. A text
         * layer has a point ([LayerSource.Text.align] says which end is at x, y is the base line); WFF
         * puts a text in a box, so the box is as wide as the face and as high as the font.
         */
        fun textBox(source: LayerSource.Text, props: Map<LayerProp, String>, metrics: WffFontMetrics): DesignRect? {
            val x = constant(props[LayerProp.X]) ?: return null
            val y = constant(props[LayerProp.Y]) ?: return null
            val left = when (source.align) {
                TextAlign.LEFT   -> x
                TextAlign.CENTER -> x - SIZE / 2.0
                TextAlign.RIGHT  -> x - SIZE
            }
            // y is the base line, or the top, middle or bottom of the text as its valign says (as LayerAnimator draws it)
            val size = source.size.toDouble()
            val baseline = y - size * when (source.anchor) {
                TextAnchor.BASELINE -> 0.0
                TextAnchor.TOP      -> metrics.ascent
                TextAnchor.MIDDLE   -> (metrics.ascent + metrics.descent) / 2
                TextAnchor.BOTTOM   -> metrics.descent
            }
            val height = size * LINE_HEIGHT
            return DesignRect(left.toFloat(), (baseline - WffTextPlace.wffBaseline(metrics, size, height)).toFloat(), SIZE.toFloat(), height.toFloat())
        }

        /** How high the box of a text layer is, in parts of the font size. */
        const val LINE_HEIGHT = 1.25

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

        fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** [text] as a fixed number, or null when it is not one. */
        fun constant(text: String?): Double? = try {
            text?.let { Formula.parse(it) { null } }?.takeIf { it.isConstant }?.eval(DoubleArray(0))
        } catch (_: FormulaException) {
            null
        }
    }
}
