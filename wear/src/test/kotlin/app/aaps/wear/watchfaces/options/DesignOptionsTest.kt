// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.options

import app.aaps.wear.R
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSetting
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSettings
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for the real org.json and the resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DesignOptionsTest {

    private val design = JSONObject(
        """
        {
          "options": [
            {"name": "santa", "title": "Santa", "choices": ["At Christmas", "Always", "Never"], "default": 0},
            {"name": "bad name", "title": "Left out", "choices": ["a", "b"]},
            {"name": "moon", "title": "Moon", "choices": ["Static", "Moving", "Real"], "default": 2}
          ]
        }
        """
    )

    private fun stored(vararg values: Pair<Int, String>): (Int, String) -> String = { key, default -> values.toMap()[key] ?: default }

    @Test
    fun anOptionThatCannotBeReadKeepsTheOthersInTheirPlace() {
        val options = DesignOptions.read(design)
        assertThat(options.map { it?.name }).containsExactly("santa", null, "moon").inOrder()
    }

    @Test
    fun theValueIsTheChosenNumberOrTheDefault() {
        val options = DesignOptions.read(design)
        assertThat(DesignOptions.value(options, "opt_santa", stored())).isEqualTo(0.0)
        assertThat(DesignOptions.value(options, "opt_moon", stored())).isEqualTo(2.0)
        assertThat(DesignOptions.value(options, "opt_santa", stored(R.string.key_cwf_option_1 to "1"))).isEqualTo(1.0)
        // The moon is the third place, so the third key
        assertThat(DesignOptions.value(options, "opt_moon", stored(R.string.key_cwf_option_3 to "0"))).isEqualTo(0.0)
        // A number the option does not have, and "as designed", give the default
        assertThat(DesignOptions.value(options, "opt_santa", stored(R.string.key_cwf_option_1 to "7"))).isEqualTo(0.0)
        assertThat(DesignOptions.value(options, "opt_moon", stored(R.string.key_cwf_option_3 to DesignOptions.AS_DESIGNED))).isEqualTo(2.0)
    }

    @Test
    fun aKeyThatIsNoOptionHasNoValueHere() {
        val options = DesignOptions.read(design)
        assertThat(DesignOptions.value(options, "opt_snow", stored())).isNull()
        assertThat(DesignOptions.value(options, "sgv", stored())).isNull()
    }

    @Test
    fun badOptionsAreLeftOut() {
        val bad = JSONObject(
            """
            {"options": [
              {"name": "one", "title": "One", "choices": ["only"]},
              {"name": "empty", "title": "Empty", "choices": ["a", ""]},
              {"name": "notitle", "choices": ["a", "b"]},
              {"name": "range", "title": "Range", "choices": ["a", "b"], "default": 5}
            ]}
            """
        )
        val options = DesignOptions.read(bad)
        assertThat(options.take(3)).containsExactly(null, null, null)
        // A default out of range is the first choice
        assertThat(options[3]!!.default).isEqualTo(0)
    }

    @Test
    fun atMostFourOptions() {
        val many = JSONObject().put("options", JSONArray().apply {
            repeat(6) { put(JSONObject().put("name", "o$it").put("title", "O$it").put("choices", JSONArray(listOf("a", "b")))) }
        })
        assertThat(DesignOptions.read(many)).hasSize(DesignOptions.MAX_OPTIONS)
    }

    @Test
    fun theRowsHaveTheTextsOfTheDesign() {
        val rows = DesignOptions.rows(design)
        assertThat(rows.map { it.key }).containsExactly(R.string.key_cwf_option_1, R.string.key_cwf_option_3).inOrder()
        assertThat(rows[0].labels).isEqualTo(ChoiceLabels("Santa", listOf("At Christmas", "Always", "Never"), listOf("0", "1", "2")))
        assertThat(rows[1].defaultValue).isEqualTo("2")
        // Without a design every place has a plain row, so the phone knows every key
        assertThat(DesignOptions.rows(null)).hasSize(DesignOptions.MAX_OPTIONS)
        assertThat(DesignOptions.rows(JSONObject())).isEmpty()
    }

    @Test
    fun thePhoneGetsTheTextsOfTheDesign() {
        val options = CwfSyncedSettings.faceLookFor(design.toString()).filterIsInstance<CwfSyncedSetting.Choice>().filter { it.labels != null }
        assertThat(options.map { it.labels!!.title }).containsExactly("Santa", "Moon").inOrder()
        assertThat(options[1].defaultValue).isEqualTo("2")
        // And none for a design without options
        assertThat(CwfSyncedSettings.faceLookFor("{}").map { it.key }).containsNoneOf(R.string.key_cwf_option_1, R.string.key_cwf_option_2)
    }

    @Test
    fun theOptionsAreReadOncePerDesign() {
        val options = DesignOptions()
        val first = options.of(design)
        assertThat(options.of(design)).isSameInstanceAs(first)
        assertThat(options.of(JSONObject(design.toString()))).isNotSameInstanceAs(first)
    }
}
