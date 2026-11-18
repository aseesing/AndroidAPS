// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import app.aaps.wear.R
import app.aaps.wear.watchfaces.animation.AnimLayerSpec
import app.aaps.wear.watchfaces.animation.DesignPoint
import app.aaps.wear.watchfaces.animation.DesignRect
import app.aaps.wear.watchfaces.animation.DesignRing
import app.aaps.wear.watchfaces.animation.LayerBlend
import app.aaps.wear.watchfaces.animation.LayerClip
import app.aaps.wear.watchfaces.animation.LayerCopies
import app.aaps.wear.watchfaces.animation.LayerGlow
import app.aaps.wear.watchfaces.animation.LayerGradient
import app.aaps.wear.watchfaces.animation.LayerMask
import app.aaps.wear.watchfaces.animation.LayerProp
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.LayerSource
import app.aaps.wear.watchfaces.animation.ShapeKind
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The optional `animation` key of a design: absent means no animation, missing keys get the defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WakeAnimationJsonTest {

    private val face = CustomWatchface()
    private val heart: Drawable = ColorDrawable(Color.RED)
    private val images = mapOf("heart" to heart)

    private fun read(json: String): WakeAnimationSpec? = face.readWakeAnimation(JSONObject(json)) { images[it] }

    @Test
    fun theWakeUpSettingIsOnlyOfferedForADesignWithAnAnimation() {
        fun keys(json: String?) = CustomWatchface.settingRows(json, systemEditor = false).map { it.key }
        assertThat(keys("""{"animation": {}}""")).contains(R.string.key_wake_animation_length)
        assertThat(keys("""{"metadata": {}}""")).doesNotContain(R.string.key_wake_animation_length)
        // nothing loaded yet: every row is kept
        assertThat(keys(null)).contains(R.string.key_wake_animation_length)
    }

    @Test
    fun theDayNightSettingsAreOnlyOfferedForADesignWithALookOfTheDay() {
        fun keys(json: String?) = CustomWatchface.settingRows(json, systemEditor = false).map { it.key }
        val dayNight = listOf(R.string.key_day_night_look, R.string.key_day_night_always_on)
        assertThat(keys("""{"looks": [{"when": "v_is_day == 1", "patch": {}}]}""")).containsAtLeastElementsIn(dayNight)
        // the old day look too, through the translation
        assertThat(keys("""{"dayLook": {}}""")).containsAtLeastElementsIn(dayNight)
        // a look of the weather only, and "day", the view of the day of the month, are not a day look
        assertThat(keys("""{"looks": [{"when": "v_weather_kind == 3", "patch": {}}]}""")).containsNoneIn(dayNight)
        assertThat(keys("""{"day": {}}""")).containsNoneIn(dayNight)
        // nothing loaded yet: every row is kept
        assertThat(keys(null)).containsAtLeastElementsIn(dayNight)
    }

    @Test
    fun theDayNightSettingsAreOfferedWithANightFaceEvenWithoutADayLook() {
        val dayNight = listOf(R.string.key_day_night_look, R.string.key_day_night_always_on)
        val design = """{"metadata": {}}"""
        assertThat(CustomWatchface.settingRows(design, systemEditor = false, hasNightFace = true).map { it.key }).containsAtLeastElementsIn(dayNight)
        assertThat(CustomWatchface.settingRows(design, systemEditor = false, hasNightFace = false).map { it.key }).containsNoneIn(dayNight)
    }

    @Test
    fun noAnimationKeyMeansNoAnimation() {
        assertThat(read("""{"metadata": {}, "time": {"width": 100}}""")).isNull()
    }

    @Test
    fun anEmptyAnimationHasTheDefaultsAndNothingToDraw() {
        val spec = read("""{"animation": {}}""")
        assertThat(spec).isEqualTo(WakeAnimationSpec())
        assertThat(spec!!.normalized().hasContent).isFalse()
    }

    @Test
    fun theKeysOfTheAnimationAreRead() {
        val spec = read(
            """{"animation": {"durationMs": 4000, "fps": 30, "fadeMs": 300, "fallbackBpm": 70,
               "let": {"a": "sin(t)", "b": 2, "c": "a * b"}}}"""
        )!!
        assertThat(spec).isEqualTo(WakeAnimationSpec(4000, 30, 300, 70, listOf("a" to "sin(t)", "b" to "2", "c" to "a * b")))
    }

    @Test
    fun aTemplateIsExpandedWithItsDefaultsAndPictures() {
        val spec = read("""{"animation": {"fps": 20, "layers": [{"use": "rain", "count": 50, "color": "#80FF0000"}, {"use": "pulse", "image": "heart", "top": 30}]}}""")!!
        assertThat(spec.fps).isEqualTo(20)
        assertThat(spec.errors).isEmpty()
        val (rain, pulse) = spec.layers
        assertThat(rain.source).isEqualTo(LayerSource.Shape(ShapeKind.LINE, listOf(Color.argb(0x80, 0xFF, 0, 0))))
        assertThat(rain.copies).isEqualTo(LayerCopies.Count(50, 7))
        val image = pulse.source as LayerSource.Image
        assertThat(image.drawable).isSameInstanceAs(heart)
        assertThat(image.rect).isEqualTo(DesignRect(175f, 30f, 50f, 50f))
        assertThat(pulse.slot).isEqualTo(LayerSlot.FRONT)
        assertThat(pulse.maxScale).isEqualTo(1.12f)
        assertThat(pulse.glow).isEqualTo(LayerGlow(Color.rgb(255, 48, 80), 10f))
    }

    @Test
    fun aDesignTemplateComesBeforeTheBuiltInOneAndUsesOthers() {
        val spec = read(
            $$"""{"animation": {
                 "templates": {
                   "spark": {"params": {"size": {"default": 3, "min": 1, "max": 5}, "where": {"left": 0, "top": 0}},
                             "layers": [{"shape": "circle", "radius": "$size", "x": "$where.left", "y": "$where.top + $size"}]},
                   "two": {"params": {"size": 2}, "layers": [{"use": "spark", "size": "$size"}, {"use": "spark", "size": 9, "where": {"top": 50}}]},
                   "rain": {"layers": [{"view": "chart"}]}
                 },
                 "layers": [{"use": "two"}, {"use": "rain"}, {"use": "nothing"}, {"use": "spark", "colour": 1}]
               }}"""
        )!!
        assertThat(spec.layers.map { it.props }).containsExactly(
            mapOf(LayerProp.X to "0", LayerProp.Y to "0 + 2", LayerProp.RADIUS to "2"),
            // the limit, and an object laid over the default key by key
            mapOf(LayerProp.X to "0", LayerProp.Y to "50 + 5", LayerProp.RADIUS to "5"),
            emptyMap<LayerProp, String>(),
            mapOf(LayerProp.X to "0", LayerProp.Y to "0 + 3", LayerProp.RADIUS to "3")
        ).inOrder()
        assertThat(spec.layers[2].source).isEqualTo(LayerSource.View("chart"))
        assertThat(spec.errors).containsExactly("layer 2: no template 'nothing'", "layer 3: 'spark' has no parameter 'colour'")
    }

    @Test
    fun aTemplateCannotUseItself() {
        val spec = read("""{"animation": {"templates": {"loop": {"layers": [{"use": "loop"}]}}, "layers": [{"use": "loop"}]}}""")!!
        assertThat(spec.layers).isEmpty()
        assertThat(spec.errors.single()).contains("uses itself")
    }

    @Test
    fun brokenValuesFallBackInsteadOfBreakingTheDesign() {
        val spec = read("""{"animation": {"fps": "fast", "layers": [{"use": "rain", "count": "many"}, {"use": "pulse", "image": "nothere"}]}}""")!!
        assertThat(spec.fps).isEqualTo(WakeAnimationSpec().fps)
        // a number parameter that is not a number keeps its default
        assertThat(spec.layers.first().copies).isEqualTo(LayerCopies.Count(90, 7))
        // an image that is not in the zip gives no heart, but the rain still plays
        val normalized = spec.normalized()
        assertThat(normalized.layers).hasSize(1)
        assertThat(normalized.hasContent).isTrue()
        // not an object at all
        assertThat(read("""{"animation": 5}""")).isNull()
    }

    // ---- the `layers` list ----

    /** The layers of an animation with the given layer entries. */
    private fun layers(vararg entries: String): List<AnimLayerSpec> =
        read("""{"animation": {"layers": [${entries.joinToString(",")}]}}""")!!.layers

    private fun layer(entry: String): AnimLayerSpec = layers(entry).single()

    @Test
    fun noLayersKeyMeansNoLayers() {
        assertThat(read("""{"animation": {"fps": 10}}""")!!.layers).isEmpty()
        assertThat(layers()).isEmpty()
    }

    @Test
    fun aLayerWithOnlyASourceGetsTheDefaults() {
        assertThat(layer("""{"shape": "circle"}""")).isEqualTo(AnimLayerSpec(LayerSource.Shape(ShapeKind.CIRCLE, listOf(Color.WHITE))))
        val spec = layer("""{"view": "chart"}""")
        assertThat(spec.source).isEqualTo(LayerSource.View("chart"))
        // a view layer is always in the views slot
        assertThat(spec.slot).isEqualTo(LayerSlot.VIEWS)
        assertThat(spec.blend).isEqualTo(LayerBlend.NORMAL)
        assertThat(spec.copies).isEqualTo(LayerCopies.One)
        assertThat(spec.props).isEmpty()
        assertThat(spec.lets).isEmpty()
        assertThat(spec.clip).isEqualTo(LayerClip())
        assertThat(spec.glow).isNull()
        assertThat(spec.maxScale).isEqualTo(1f)
        assertThat(spec.restSeconds).isNull()
        assertThat(spec.normalized()).isEqualTo(spec)
    }

    @Test
    fun anImageLayerFindsItsPictureAndItsRect() {
        val source = layer("""{"image": "heart", "rect": {"left": 10, "top": 20, "width": 30}}""").source as LayerSource.Image
        assertThat(source.key).isEqualTo("heart")
        assertThat(source.drawable).isSameInstanceAs(heart)
        // a missing side of the rect keeps the full face
        assertThat(source.rect).isEqualTo(DesignRect(10f, 20f, 30f, 400f))
        assertThat((layer("""{"image": "heart"}""").source as LayerSource.Image).rect).isEqualTo(DesignRect.FULL)
    }

    @Test
    fun anImageThatIsNotInTheZipIsReadButDroppedLater() {
        val spec = layer("""{"image": "nothere"}""")
        assertThat(spec.source).isEqualTo(LayerSource.Image("nothere", null, DesignRect.FULL))
        assertThat(spec.normalized()).isNull()
    }

    @Test
    fun aPictureOfTheAppIsReadWithItsRect() {
        val spec = layer("""{"image": "@moon", "rect": {"left": 316, "top": 78, "width": 44, "height": 44}, "rest": 0, "blend": "screen", "x": 2}""")
        assertThat(spec.source).isEqualTo(LayerSource.Dynamic("moon", DesignRect(316f, 78f, 44f, 44f)))
        assertThat(spec.blend).isEqualTo(LayerBlend.SCREEN)
        assertThat(spec.props[LayerProp.X]).isEqualTo("2")
        // a rest layer, also after normalizing: it stays on the face like a picture
        assertThat(spec.normalized()!!.restSeconds).isEqualTo(0f)
        // without a rect it fills the face; a rect that is not square draws in the middle of it
        assertThat(layer("""{"image": "@moon"}""").source).isEqualTo(LayerSource.Dynamic("moon", DesignRect.FULL))
        assertThat(LayerSource.Dynamic("moon", DesignRect(10f, 20f, 60f, 40f)).square).isEqualTo(DesignRect(20f, 20f, 40f, 40f))
        // of no size it can never draw
        assertThat(layer("""{"image": "@moon", "rect": {"width": 0}}""").normalized()).isNull()
    }

    @Test
    fun everyShapeItsColoursAndItsGradientAreRead() {
        val kinds = layers("""{"shape": "line"}""", """{"shape": "RECT"}""", """{"shape": "circle"}""").map { (it.source as LayerSource.Shape).kind }
        assertThat(kinds).containsExactly(ShapeKind.LINE, ShapeKind.RECT, ShapeKind.CIRCLE).inOrder()
        assertThat((layer("""{"shape": "circle", "color": "#80FF0000"}""").source as LayerSource.Shape).colors)
            .containsExactly(Color.argb(0x80, 0xFF, 0, 0))
        assertThat((layer("""{"shape": "circle", "colors": ["#FF0000", "#00FF00", "#0000FF"], "color": "#FFFFFF"}""").source as LayerSource.Shape).colors)
            .containsExactly(Color.RED, Color.GREEN, Color.BLUE).inOrder()
        // a colour that is not a colour is white
        assertThat((layer("""{"shape": "circle", "colors": ["#FF0000", "pink-ish"]}""").source as LayerSource.Shape).colors)
            .containsExactly(Color.RED, Color.WHITE).inOrder()
        assertThat((layer("""{"shape": "circle", "colors": []}""").source as LayerSource.Shape).colors).containsExactly(Color.WHITE)
        // the gradient, its stops cut to 0..1
        assertThat((layer("""{"shape": "circle", "gradient": {"type": "radial", "stops": [[0, 1], [0.25, 2], [1, 0]]}}""").source as LayerSource.Shape).gradient)
            .isEqualTo(LayerGradient(LayerGradient.Type.RADIAL, listOf(LayerGradient.Stop(0f, 1f), LayerGradient.Stop(0.25f, 1f), LayerGradient.Stop(1f, 0f))))
        // a shape that is not known, or a broken gradient, is no layer
        assertThat(layers("""{"shape": "star"}""", """{"shape": "rect", "gradient": {"type": "conic", "stops": [[0, 1], [1, 0]]}}""", """{"shape": "rect", "gradient": {"type": "linear", "stops": [[0, 1]]}}""")).isEmpty()
    }

    @Test
    fun slotAndBlendAreRead() {
        val spec = layer("""{"shape": "circle", "slot": "front", "blend": "add"}""")
        assertThat(spec.slot).isEqualTo(LayerSlot.FRONT)
        assertThat(spec.blend).isEqualTo(LayerBlend.ADD)
        assertThat(layer("""{"shape": "circle", "slot": "views", "blend": "screen"}""").let { it.slot to it.blend })
            .isEqualTo(LayerSlot.VIEWS to LayerBlend.SCREEN)
        // an unknown name keeps the default
        assertThat(layer("""{"shape": "circle", "slot": "middle", "blend": "multiply"}""").let { it.slot to it.blend })
            .isEqualTo(LayerSlot.BACK to LayerBlend.NORMAL)
    }

    @Test
    fun theCopiesAreRead() {
        assertThat(layer("""{"shape": "circle", "count": 40, "seed": 9}""").copies).isEqualTo(LayerCopies.Count(40, 9))
        assertThat(layer("""{"shape": "circle", "count": 40}""").copies).isEqualTo(LayerCopies.Count(40, 1))
        // a count may be a formula of fixed numbers, as a template gives it
        assertThat(layer("""{"shape": "circle", "count": "4 * 10"}""").copies).isEqualTo(LayerCopies.Count(40, 1))
        assertThat(layer("""{"image": "heart", "slices": 30}""").copies).isEqualTo(LayerCopies.Slices(30, vertical = true))
        assertThat(layer("""{"image": "heart", "slices": 30, "slicesAlong": "x"}""").copies).isEqualTo(LayerCopies.Slices(30, vertical = true))
        assertThat(layer("""{"image": "heart", "slices": 30, "slicesAlong": "y"}""").copies).isEqualTo(LayerCopies.Slices(30, vertical = false))
        assertThat(layer("""{"shape": "circle", "points": [[10, 20], [30.5, 40, 0.25, 7], [1], "x", [50, 60]]}""").copies)
            .isEqualTo(LayerCopies.Points(listOf(DesignPoint(10f, 20f), DesignPoint(30.5f, 40f, listOf(0.25, 7.0)), DesignPoint(50f, 60f))))
        // more than one kind of copies is no layer
        assertThat(layers("""{"shape": "circle", "count": 3, "points": [[1, 2]]}""")).isEmpty()
    }

    @Test
    fun theClipIsRead() {
        val clip = layer(
            """{"shape": "rect", "clip": {"rect": {"left": 5, "top": 6, "width": 7, "height": 8}, "round": false,
                "rings": [{"cx": 100, "cy": 110, "inner": 20, "outer": 30}], "boxes": [{"left": 1, "top": 2, "width": 3, "height": 4}], "cornerRadius": 6}}"""
        ).clip
        assertThat(clip).isEqualTo(
            LayerClip(DesignRect(5f, 6f, 7f, 8f), round = false, rings = listOf(DesignRing(100f, 110f, 20f, 30f)), boxes = listOf(DesignRect(1f, 2f, 3f, 4f)), cornerRadius = 6f)
        )
        assertThat(layer("""{"shape": "rect", "clip": {}}""").clip).isEqualTo(LayerClip())
        // shapes that are given but empty leave no room at all
        assertThat(layer("""{"shape": "rect", "clip": {"rings": [], "boxes": []}}""").clip.isEmpty).isTrue()
        assertThat(layer("""{"shape": "rect", "clip": {}}""").clip.isEmpty).isFalse()
    }

    @Test
    fun theMaskOfTheClipIsRead() {
        val mask = layer("""{"shape": "rect", "clip": {"mask": "heart", "maskRect": {"left": 10, "top": 20, "width": 300, "height": 150}}}""").clip.mask!!
        assertThat(mask.key).isEqualTo("heart")
        assertThat(mask.drawable).isSameInstanceAs(heart)
        assertThat(mask.rect).isEqualTo(DesignRect(10f, 20f, 300f, 150f))
        // without a maskRect: the full face; a missing side of the rect keeps the full face
        assertThat(layer("""{"shape": "rect", "clip": {"mask": "heart"}}""").clip.mask!!.rect).isEqualTo(DesignRect.FULL)
        assertThat(layer("""{"shape": "rect", "clip": {"mask": "heart", "maskRect": {"height": 200}}}""").clip.mask!!.rect)
            .isEqualTo(DesignRect(0f, 0f, 400f, 200f))
        // no mask, or a json null: no mask
        assertThat(layer("""{"shape": "rect", "clip": {"round": false}}""").clip.mask).isNull()
        assertThat(layer("""{"shape": "rect", "clip": {"mask": null}}""").clip.mask).isNull()
    }

    @Test
    fun aMaskThatIsNotInTheZipIsReadButDropsTheLayerLater() {
        val spec = layer("""{"shape": "rect", "clip": {"mask": "nothere"}}""")
        assertThat(spec.clip.mask).isEqualTo(LayerMask("nothere", null, DesignRect.FULL))
        assertThat(spec.normalized()).isNull()
        // an empty key is not a picture either
        assertThat(layer("""{"shape": "rect", "clip": {"mask": ""}}""").normalized()).isNull()
        // with the picture in the zip the layer is kept
        assertThat(layer("""{"shape": "rect", "clip": {"mask": "heart"}}""").normalized()).isNotNull()
    }

    @Test
    fun glowMaxScaleRestAndLetAreRead() {
        val spec = layer("""{"image": "heart", "glow": {"color": "#00FF00", "radius": 12}, "maxScale": "2 + 0.5", "rest": 3.5, "let": {"a": "i * 2", "b": 1.5}}""")
        assertThat(spec.glow).isEqualTo(LayerGlow(Color.GREEN, 12f))
        assertThat(spec.maxScale).isEqualTo(2.5f)
        assertThat(spec.restSeconds).isEqualTo(3.5f)
        assertThat(spec.lets).containsExactly("a" to "i * 2", "b" to "1.5").inOrder()
        assertThat(layer("""{"image": "heart", "glow": {}}""").glow).isEqualTo(LayerGlow(Color.WHITE, 10f))
        assertThat(layer("""{"image": "heart", "rest": "later"}""").restSeconds).isNull()
    }

    @Test
    fun everyPropertyIsReadAsText() {
        val entry = LayerProp.entries.joinToString(", ", prefix = """{"shape": "circle", """, postfix = "}") { """"${it.key}": "${it.key}_formula"""" }
        val props = layer(entry).props
        assertThat(props.keys).containsExactlyElementsIn(LayerProp.entries)
        LayerProp.entries.forEach { assertThat(props[it]).isEqualTo("${it.key}_formula") }
    }

    @Test
    fun numbersAreKeptAsTheirDecimalTextAndFormulasAsTheyAre() {
        val props = layer("""{"shape": "circle", "x": 200, "y": 12.5, "alpha": 1.0, "radius": -3, "rotation": "t * 90 + sin(i)", "angle": true, "width": ""}""").props
        assertThat(props).containsExactly(
            LayerProp.X, "200",
            LayerProp.Y, "12.5",
            LayerProp.ALPHA, "1",
            LayerProp.RADIUS, "-3",
            LayerProp.ROTATION, "t * 90 + sin(i)"
        )
    }

    @Test
    fun scaleSetsBothScalesUnlessOneIsGiven() {
        assertThat(layer("""{"image": "heart", "scale": "1 + env"}""").props)
            .containsExactly(LayerProp.SCALE_X, "1 + env", LayerProp.SCALE_Y, "1 + env")
        assertThat(layer("""{"image": "heart", "scale": 2, "scaleY": 0.5}""").props)
            .containsExactly(LayerProp.SCALE_X, "2", LayerProp.SCALE_Y, "0.5")
    }

    @Test
    fun brokenLayersAreLeftOutAndTheOthersKept() {
        val spec = read(
            """{"animation": {"fps": 12, "layers": [
                 {"shape": "circle", "x": 1},
                 5,
                 {"x": 10},
                 {"shape": "star"},
                 {"shape": "circle", "image": "heart"},
                 {"shape": "circle", "count": 3, "points": [[1, 2]]},
                 {"view": "chart", "x": 2}
               ]}}"""
        )!!
        assertThat(spec.layers.map { it.source }).containsExactly(LayerSource.Shape(ShapeKind.CIRCLE), LayerSource.View("chart")).inOrder()
        assertThat(spec.layers.map { it.props[LayerProp.X] }).containsExactly("1", "2").inOrder()
        // the rest of the animation still plays, and the reasons are kept
        assertThat(spec.fps).isEqualTo(12)
        assertThat(spec.errors).hasSize(4)
        // a layers key that is not a list gives no layers, and costs nothing else
        assertThat(read("""{"animation": {"fps": 20, "layers": {"shape": "circle"}}}""")!!.let { it.fps to it.layers }).isEqualTo(20 to emptyList<AnimLayerSpec>())
    }
}
