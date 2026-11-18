// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.values

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FaceValuesTest {

    @Test
    fun aKeyGivesTheValueOfItsReader() {
        var battery: Double? = 40.0
        val values = FaceValues(listOf("", "sgv", "watch_battery")) { index ->
            when (index) {
                1    -> { -> 121.0 }
                else -> { -> battery }
            }
        }
        assertThat(values.value("sgv")).isEqualTo(121.0)
        assertThat(values.value("watch_battery")).isEqualTo(40.0)
        // Read again each time: a value is never kept here
        battery = 12.5
        assertThat(values.value("watch_battery")).isEqualTo(12.5)
        battery = null
        assertThat(values.value("watch_battery")).isNull()
    }

    @Test
    fun anUnknownOrEmptyKeyHasNoValue() {
        var made = 0
        val values = FaceValues(listOf("", "sgv")) { made++; { -> 1.0 } }
        assertThat(values.value("")).isNull()
        assertThat(values.value("nothing")).isNull()
        assertThat(made).isEqualTo(0)
    }

    @Test
    fun aReaderIsMadeOncePerKeyAndOnlyWhenRead() {
        val made = ArrayList<Int>()
        val values = FaceValues(listOf("a", "b", "c")) { index -> made += index; { -> index.toDouble() } }
        repeat(3) { assertThat(values.value("b")).isEqualTo(1.0) }
        assertThat(values.value("c")).isEqualTo(2.0)
        assertThat(made).containsExactly(1, 2).inOrder()
    }

    @Test
    fun theFirstOfTwoEqualKeysWins() {
        val values = FaceValues(listOf("a", "a")) { index -> { -> index.toDouble() } }
        assertThat(values.value("a")).isEqualTo(0.0)
    }

    @Test
    fun aMissingValueStaysNotANumberWhenTheReaderSaysSo() {
        val values = FaceValues(listOf("x")) { { -> Double.NaN } }
        assertThat(values.value("x")!!.isNaN()).isTrue()
    }
}
