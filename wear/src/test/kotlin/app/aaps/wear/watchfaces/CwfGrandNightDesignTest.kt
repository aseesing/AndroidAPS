// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import app.aaps.core.interfaces.rx.weardata.EventData
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The night face of "Grand Complication" (`Grand_Complication_Night.zip`): a midnight blue guilloché dial with golden hands and printing, a starry sky in the sky disc. Dark, so it also suits the always-on screen. See [GrandComplicationDesignBase].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfGrandNightDesignTest : GrandComplicationDesignBase() {

    override val displayName = "Grand Complication Night"
    override val fileName = "Grand_Complication_Night"
    override val previewInstant = EVENING_MS
    override val palette = Palette(
            case = "#3A3020", dial = "#141A33", dialRing = "#1A2142", ink = "#E2D3A0", gold = "#C9A54A", hand = "#E8D6A0", window = "#0A0E1E",
            bandLow = "#C24848", bandMid = "#4E9E6A", bandHigh = "#D49A2A", sgvLow = "#FF7A7A", sgvMid = "#7FD69A", sgvHigh = "#F2C14E",
            alarm = "#FF7A7A", noValue = "#6B6F80",
            skyTop = "#0B1430", skyLow = "#26365E", skyCloudy = "#2A2F3E", skyRain = "#22262F", stars = true,
            land = "#172317", landFar = "#243326", cloud = "#5A6178", cloudShade = "#3E4458", rain = "#8FB4FF"
        )
    override val looks = listOf(
        Look("evening", EVENING_MS, EventData.Weather.CLEAR), Look("partly", EVENING_MS, EventData.Weather.PARTLY),
        Look("cloudy", EVENING_MS, EventData.Weather.CLOUDY), Look("rain", EVENING_MS, EventData.Weather.RAIN),
        Look("23:00, young moon down", NIGHT_MS, null), Look("afternoon", AFTERNOON_MS, null)
    )
}
