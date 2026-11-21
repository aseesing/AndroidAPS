// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A `use` may carry `wff`, how its layers are made in the Watch Face Format. It is no parameter of
 * the template: the layers the template makes get it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TemplatesWffTest {

    private val own = JSONObject(
        $$"""
        {
          "twin": {"params": {"x": 10}, "layers": [{"shape": "circle", "x": "$x"}, {"shape": "circle", "x": 20, "wff": {"only": false}}]},
          "outer": {"params": {}, "layers": [{"use": "twin"}]},
          "named": {"params": {"wff": 1}, "layers": [{"shape": "circle", "alpha": "$wff"}]}
        }
        """
    )

    private fun expand(layers: String, errors: MutableList<String>): List<JSONObject> = Templates(own).expand(JSONArray(layers), errors)

    @Test
    fun aUseGivesItsWffToTheLayersItMakes() {
        val errors = ArrayList<String>()
        val layers = expand("""[{"use": "twin", "x": 5, "wff": {"only": true}}]""", errors)
        assertThat(errors).isEmpty()
        assertThat(layers).hasSize(2)
        assertThat(layers[0].getJSONObject("wff").getBoolean("only")).isTrue()
        assertThat(layers[0].opt("x")).isEqualTo(5)
        // A layer with its own wff keeps it
        assertThat(layers[1].getJSONObject("wff").getBoolean("only")).isFalse()
    }

    @Test
    fun theWffReachesTheLayersOfATemplateInside() {
        val errors = ArrayList<String>()
        val layers = expand("""[{"use": "outer", "wff": {"only": true}}]""", errors)
        assertThat(errors).isEmpty()
        assertThat(layers.map { it.getJSONObject("wff").getBoolean("only") }).containsExactly(true, false).inOrder()
    }

    @Test
    fun withoutWffTheLayersStayAsTheyWere() {
        val errors = ArrayList<String>()
        val layers = expand("""[{"use": "twin"}]""", errors)
        assertThat(errors).isEmpty()
        assertThat(layers[0].has("wff")).isFalse()
    }

    @Test
    fun aParameterNamedWffStaysAParameter() {
        val errors = ArrayList<String>()
        val layers = expand("""[{"use": "named", "wff": 0.5}]""", errors)
        assertThat(errors).isEmpty()
        assertThat(layers.single().opt("alpha")).isEqualTo(0.5)
        assertThat(layers.single().has("wff")).isFalse()
    }
}
