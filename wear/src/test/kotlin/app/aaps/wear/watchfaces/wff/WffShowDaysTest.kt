// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import android.graphics.Color
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test
import java.time.MonthDay

internal class WffShowDaysTest {

    private fun days(layer: String, extra: String = ""): Set<MonthDay>? {
        val design = JSONObject(
            """{"options": [{"name": "santa", "choices": ["At Christmas", "Always", "Never"], "default": 0}], $extra
               "animation": {"durationMs": 15000, "let": {"santa": "if(v_opt_santa == 1, 1, if(v_opt_santa == 2, 0, v_month == 12 && (v_day == 24 || v_day == 25)))"},
               "layers": [$layer]}}"""
        )
        val animation = WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }) { null }.read(design)!!
        return WffShowDays.of(design, animation, animation.layers[0])
    }

    @Test
    fun santaShowsAtChristmasWithTheDefaultChoice() {
        val days = days("""{"shape": "rect", "let": {"p": "window(t, 1.5, len / 1000 - 3.5)"}, "visible": "santa && p > 0 && p < 1"}""")
        assertThat(days).containsExactly(MonthDay.of(12, 24), MonthDay.of(12, 25))
        assertThat(WffShowDays.condition(days!!)).isEqualTo("([MONTH] == 12 && [DAY] >= 24 && [DAY] <= 25)")
    }

    @Test
    fun aLayerThatDoesNotReadTheDateHasNoDays() {
        assertThat(days("""{"shape": "rect", "visible": "t > 1"}""")).isNull()
        assertThat(days("""{"shape": "rect"}""")).isNull()
        // It also reads a value of AAPS: not a layer of some days
        assertThat(days("""{"shape": "rect", "visible": "v_month == 12 && v_iob > 1"}""")).isNull()
    }

    @Test
    fun theLayersOwnNamesComeFirst() {
        // The animation's santa says Christmas; the layer's own santa says every day of December
        val days = days("""{"shape": "rect", "let": {"santa": "v_month == 12"}, "visible": "santa"}""")
        assertThat(days).hasSize(31)
    }

    @Test
    fun separateDaysAreSeparateRanges() {
        assertThat(WffShowDays.condition(setOf(MonthDay.of(1, 1), MonthDay.of(12, 31), MonthDay.of(12, 30))))
            .isEqualTo("([MONTH] == 1 && [DAY] == 1) || ([MONTH] == 12 && [DAY] >= 30 && [DAY] <= 31)")
    }
}
