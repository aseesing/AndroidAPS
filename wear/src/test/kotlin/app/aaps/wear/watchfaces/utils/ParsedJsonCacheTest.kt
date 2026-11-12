// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.json.JSONException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json is part of Android, so Robolectric runs it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ParsedJsonCacheTest {

    private val design = """{"highColor":"#FFFF00","sgv":{"width":100}}"""

    @Test
    fun aDayOfMinuteTicksParsesTheSameDesignOnce() {
        val cache = ParsedJsonCache()
        val first = cache.parse(design)
        repeat(1439) { assertThat(cache.parse(design)).isSameInstanceAs(first) }
        println("PERF design parse: 1440 minute ticks -> ${cache.parses} parse (before: 1440)")
        assertThat(cache.parses).isEqualTo(1)
        assertThat(first.getJSONObject("sgv").getInt("width")).isEqualTo(100)
    }

    @Test
    fun aNewDesignIsParsedAgain() {
        val cache = ParsedJsonCache()
        cache.parse(design)
        val other = cache.parse("""{"highColor":"#FF0000"}""")
        assertThat(other.getString("highColor")).isEqualTo("#FF0000")
        assertThat(cache.parses).isEqualTo(2)
    }

    @Test
    fun aBrokenDesignThrowsAndIsNotKept() {
        val cache = ParsedJsonCache()
        assertThrows(JSONException::class.java) { cache.parse("{broken") }
        assertThrows(JSONException::class.java) { cache.parse("{broken") }
        assertThat(cache.parse(design).has("sgv")).isTrue()
    }
}
