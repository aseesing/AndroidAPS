// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * `icuText` reads an ICU skeleton as the Watch Face Format does. It needs the ICU of Android, so it
 * runs under Robolectric. The texts are the ones the WFF runtime showed on the Wear OS 6 emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IcuTextTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun text(formula: String, locale: Locale = Locale.US): String {
        val setting = FormulaSetting().apply {
            set(locale, true, true, utc)
            nowMs = ZonedDateTime.of(2026, 11, 20, 0, 0, 20, 0, utc).toInstant().toEpochMilli()
        }
        val names = listOf("sec", "min", "hour", LayerVariables.DAY_NAME)
        return TextFormula.parse(formula, setting = setting) { name -> names.indexOf(name).takeIf { it >= 0 } }.eval(doubleArrayOf(20.0, 0.0, 0.0, 20412.0))
    }

    @Test
    fun theSkeletonsShownOnTheEmulator() {
        assertThat(text("icuText('yyyy-MM-dd')")).isEqualTo("11/20/2026")
        assertThat(text("icuText('d.M.')")).isEqualTo("11/20")
        assertThat(text("icuText('EEE d MMM yyyy')")).isEqualTo("Fri, Nov 20, 2026")
        assertThat(text("icuText('HH:mm:ss a')")).isEqualTo("00:00:20")
    }

    @Test
    fun theOrderAndTheSignsComeFromTheLanguage() {
        assertThat(text("icuText('yyyyMMdd')", Locale.GERMANY)).isEqualTo("20.11.2026")
    }
}
