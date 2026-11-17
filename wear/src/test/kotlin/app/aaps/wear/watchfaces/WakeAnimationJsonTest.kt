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
import app.aaps.wear.watchfaces.animation.LayerMask
import app.aaps.wear.watchfaces.animation.LayerProp
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.LayerSource
import app.aaps.wear.watchfaces.animation.PulseSpec
import app.aaps.wear.watchfaces.animation.RainSpec
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
    fun theDayNightSettingsAreOnlyOfferedForADesignWithADayLook() {
        fun keys(json: String?) = CustomWatchface.settingRows(json, systemEditor = false).map { it.key }
        val dayNight = listOf(R.string.key_day_night_look, R.string.key_day_night_always_on)
        assertThat(keys("""{"dayLook": {}}""")).containsAtLeastElementsIn(dayNight)
        // "day" is the view of the day of the month, not the day look
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
    fun missingKeysGetTheDefaults() {
        val spec = read(
            """{"animation": {"fps": 20, "rain": {"count": 50, "color": "#80FF0000"}, "pulse": {"image": "heart", "top": 30}}}"""
        )!!
        assertThat(spec.fps).isEqualTo(20)
        assertThat(spec.durationMs).isEqualTo(WakeAnimationSpec().durationMs)
        assertThat(spec.fadeMs).isEqualTo(WakeAnimationSpec().fadeMs)
        assertThat(spec.rain).isEqualTo(RainSpec(count = 50, color = Color.argb(0x80, 0xFF, 0, 0)))
        val pulse = spec.pulse!!
        assertThat(pulse.drawable).isSameInstanceAs(heart)
        assertThat(pulse.copy(drawable = null)).isEqualTo(PulseSpec(image = "heart", rect = PulseSpec().rect.copy(top = 30f)))
    }

    @Test
    fun everyKeyIsRead() {
        val spec = read(
            """{"animation": {"durationMs": 4000, "fps": 30, "fadeMs": 300,
               "rain": {"count": 70, "seed": 3, "speed": 400, "angle": -10, "length": 30, "lengthVariation": 0.2,
                        "thickness": 2.5, "color": "#FFFFFF", "alpha": 100,
                        "region": {"left": 10, "top": 20, "width": 300, "height": 200}},
               "pulse": {"image": "heart", "left": 180, "top": 60, "width": 40, "height": 36, "amplitude": 0.2,
                         "secondBeat": 0.5, "glowColor": "#00FF00", "glowAlpha": 90, "glowRadius": 6, "fallbackBpm": 70}}}"""
        )!!
        assertThat(spec.withoutImage()).isEqualTo(
            WakeAnimationSpec(
                durationMs = 4000, fps = 30, fadeMs = 300,
                rain = RainSpec(70, 3, 400f, -10f, 30f, 0.2f, 2.5f, Color.WHITE, 100, DesignRect(10f, 20f, 300f, 200f)),
                pulse = PulseSpec("heart", null, DesignRect(180f, 60f, 40f, 36f), 0.2f, 0.5f, Color.GREEN, 90, 6f, 70)
            )
        )
    }

    @Test
    fun brokenValuesFallBackInsteadOfBreakingTheDesign() {
        val spec = read("""{"animation": {"fps": "fast", "rain": {"color": "blue-ish", "count": "many"}, "pulse": {"image": "nothere"}}}""")!!
        assertThat(spec.fps).isEqualTo(WakeAnimationSpec().fps)
        assertThat(spec.rain).isEqualTo(RainSpec())
        // an image that is not in the zip gives no heart, but the rain still plays
        val normalized = spec.normalized()
        assertThat(normalized.pulse).isNull()
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
        assertThat(read("""{"animation": {"rain": {}}}""")!!.layers).isEmpty()
        assertThat(layers()).isEmpty()
    }

    @Test
    fun aLayerWithOnlyASourceGetsTheDefaults() {
        assertThat(layer("""{"shape": "dot"}""")).isEqualTo(AnimLayerSpec(LayerSource.Shape(ShapeKind.DOT, listOf(Color.WHITE))))
        val spec = layer("""{"view": "chart"}""")
        assertThat(spec.source).isEqualTo(LayerSource.View("chart"))
        assertThat(spec.slot).isEqualTo(LayerSlot.BACK)
        assertThat(spec.blend).isEqualTo(LayerBlend.NORMAL)
        assertThat(spec.copies).isEqualTo(LayerCopies.One)
        assertThat(spec.props).isEmpty()
        assertThat(spec.clip).isEqualTo(LayerClip())
        assertThat(spec.glow).isNull()
        assertThat(spec.maxScale).isEqualTo(1f)
        assertThat(spec.restSeconds).isNull()
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
    fun aMoonLayerIsReadWithItsRect() {
        val spec = layer("""{"moon": true, "rect": {"left": 316, "top": 78, "width": 44, "height": 44}, "rest": 0, "blend": "screen", "x": 2}""")
        assertThat(spec.source).isEqualTo(LayerSource.Moon(DesignRect(316f, 78f, 44f, 44f)))
        assertThat(spec.blend).isEqualTo(LayerBlend.SCREEN)
        assertThat(spec.props[LayerProp.X]).isEqualTo("2")
        // a rest layer, also after normalizing: it stays on the face like a picture
        assertThat(spec.normalized()!!.restSeconds).isEqualTo(0f)
        // without a rect the moon fills the face; a rect that is not square draws in the middle of it
        assertThat(layer("""{"moon": true}""").source).isEqualTo(LayerSource.Moon(DesignRect.FULL))
        assertThat(LayerSource.Moon(DesignRect(10f, 20f, 60f, 40f)).square).isEqualTo(DesignRect(20f, 20f, 40f, 40f))
    }

    @Test
    fun aMoonLayerNeedsTrueAndNoOtherSource() {
        val spec = read(
            """{"animation": {"layers": [
                 {"moon": false},
                 {"moon": true, "image": "heart"},
                 {"moon": true, "shape": "dot"},
                 {"moon": false, "shape": "dot", "x": 1},
                 {"moon": "yes"},
                 {"moon": true, "x": 2}
               ]}}"""
        )!!
        assertThat(spec.layers.map { it.source }).containsExactly(LayerSource.Shape(ShapeKind.DOT), LayerSource.Moon(DesignRect.FULL)).inOrder()
        // a moon of no size can never draw
        assertThat(layer("""{"moon": true, "rect": {"width": 0}}""").normalized()).isNull()
    }

    @Test
    fun everyShapeAndItsColoursAreRead() {
        val kinds = layers("""{"shape": "line"}""", """{"shape": "rect"}""", """{"shape": "band"}""", """{"shape": "DOT"}""", """{"shape": "circle"}""")
            .map { (it.source as LayerSource.Shape).kind }
        assertThat(kinds).containsExactly(ShapeKind.LINE, ShapeKind.RECT, ShapeKind.BAND, ShapeKind.DOT, ShapeKind.CIRCLE).inOrder()
        assertThat((layer("""{"shape": "dot", "color": "#80FF0000"}""").source as LayerSource.Shape).colors)
            .containsExactly(Color.argb(0x80, 0xFF, 0, 0))
        assertThat((layer("""{"shape": "dot", "colors": ["#FF0000", "#00FF00", "#0000FF"], "color": "#FFFFFF"}""").source as LayerSource.Shape).colors)
            .containsExactly(Color.RED, Color.GREEN, Color.BLUE).inOrder()
        // a colour that is not a colour is white
        assertThat((layer("""{"shape": "dot", "colors": ["#FF0000", "pink-ish"]}""").source as LayerSource.Shape).colors)
            .containsExactly(Color.RED, Color.WHITE).inOrder()
        assertThat((layer("""{"shape": "dot", "colors": []}""").source as LayerSource.Shape).colors).containsExactly(Color.WHITE)
    }

    @Test
    fun slotAndBlendAreRead() {
        val spec = layer("""{"shape": "dot", "slot": "front", "blend": "add"}""")
        assertThat(spec.slot).isEqualTo(LayerSlot.FRONT)
        assertThat(spec.blend).isEqualTo(LayerBlend.ADD)
        assertThat(layer("""{"shape": "dot", "slot": "views", "blend": "screen"}""").let { it.slot to it.blend })
            .isEqualTo(LayerSlot.VIEWS to LayerBlend.SCREEN)
        // an unknown name keeps the default
        assertThat(layer("""{"shape": "dot", "slot": "middle", "blend": "multiply"}""").let { it.slot to it.blend })
            .isEqualTo(LayerSlot.BACK to LayerBlend.NORMAL)
    }

    @Test
    fun theCopiesAreRead() {
        assertThat(layer("""{"shape": "dot", "count": 40, "seed": 9}""").copies).isEqualTo(LayerCopies.Count(40, 9))
        assertThat(layer("""{"shape": "dot", "count": 40}""").copies).isEqualTo(LayerCopies.Count(40, 1))
        assertThat(layer("""{"image": "heart", "slices": 30}""").copies).isEqualTo(LayerCopies.Slices(30, vertical = true))
        assertThat(layer("""{"image": "heart", "slices": 30, "slicesAlong": "x"}""").copies).isEqualTo(LayerCopies.Slices(30, vertical = true))
        assertThat(layer("""{"image": "heart", "slices": 30, "slicesAlong": "y"}""").copies).isEqualTo(LayerCopies.Slices(30, vertical = false))
        assertThat(layer("""{"shape": "circle", "points": [[10, 20], [30.5, 40], [1], "x", [50, 60]]}""").copies)
            .isEqualTo(LayerCopies.Points(listOf(DesignPoint(10f, 20f), DesignPoint(30.5f, 40f), DesignPoint(50f, 60f))))
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
    fun glowMaxScaleAndRestAreRead() {
        val spec = layer("""{"image": "heart", "glow": {"color": "#00FF00", "radius": 12}, "maxScale": 2.5, "rest": 3.5}""")
        assertThat(spec.glow).isEqualTo(LayerGlow(Color.GREEN, 12f))
        assertThat(spec.maxScale).isEqualTo(2.5f)
        assertThat(spec.restSeconds).isEqualTo(3.5f)
        assertThat(layer("""{"image": "heart", "glow": {}}""").glow).isEqualTo(LayerGlow(Color.WHITE, 10f))
        assertThat(layer("""{"image": "heart", "rest": "later"}""").restSeconds).isNull()
    }

    @Test
    fun everyPropertyIsReadAsText() {
        val entry = LayerProp.entries.joinToString(", ", prefix = """{"shape": "dot", """, postfix = "}") { """"${it.key}": "${it.key}_formula"""" }
        val props = layer(entry).props
        assertThat(props.keys).containsExactlyElementsIn(LayerProp.entries)
        LayerProp.entries.forEach { assertThat(props[it]).isEqualTo("${it.key}_formula") }
    }

    @Test
    fun numbersAreKeptAsTheirDecimalTextAndFormulasAsTheyAre() {
        val props = layer("""{"shape": "dot", "x": 200, "y": 12.5, "alpha": 1.0, "radius": -3, "rotation": "t * 90 + sin(i)", "angle": true, "width": ""}""").props
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
            """{"animation": {"rain": {}, "layers": [
                 {"shape": "dot", "x": 1},
                 5,
                 {"x": 10},
                 {"shape": "star"},
                 {"shape": "dot", "image": "heart"},
                 {"shape": "dot", "count": 3, "points": [[1, 2]]},
                 {"view": "chart", "x": 2}
               ]}}"""
        )!!
        assertThat(spec.layers.map { it.source }).containsExactly(LayerSource.Shape(ShapeKind.DOT), LayerSource.View("chart")).inOrder()
        assertThat(spec.layers.map { it.props[LayerProp.X] }).containsExactly("1", "2").inOrder()
        // the rest of the animation still plays
        assertThat(spec.rain).isEqualTo(RainSpec())
        // a layers key that is not a list gives no layers, and costs nothing else
        assertThat(read("""{"animation": {"fps": 20, "layers": {"shape": "dot"}}}""")!!.let { it.fps to it.layers }).isEqualTo(20 to emptyList<AnimLayerSpec>())
    }
}
