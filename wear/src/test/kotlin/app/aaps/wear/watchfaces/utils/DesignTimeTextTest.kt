// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DesignTimeTextTest {

    private val asked = mutableListOf<Long>()
    private val timeString: (Long) -> String = { asked += it; "18:48" }

    @Test
    fun theTokenBecomesTheTime() {
        assertThat(DesignTimeText.of("@time", 1_700_000_000_000.0, timeString)).isEqualTo("18:48")
        assertThat(DesignTimeText.of("↓ @time", 1_700_000_000_000.0, timeString)).isEqualTo("↓ 18:48")
        assertThat(asked).containsExactly(1_700_000_000_000L, 1_700_000_000_000L)
    }

    @Test
    fun aNumberFormatIsLeftToTheWatch() {
        assertThat(DesignTimeText.of("%.0f°", 14.0, timeString)).isNull()
        assertThat(asked).isEmpty()
    }
}
