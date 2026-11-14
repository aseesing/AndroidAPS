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
 * The day face of "Grand Complication" (`Grand_Complication_Day.zip`): a cream guilloché dial with blued Breguet hands and navy printing, a blue sky in the sky disc. See [GrandComplicationDesignBase].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfGrandDayDesignTest : GrandComplicationDesignBase() {

    override val displayName = "Grand Complication Day"
    override val fileName = "Grand_Complication_Day"
    override val previewInstant = AFTERNOON_MS
    override val palette = Palette(
            case = "#B9A77C", dial = "#F3EDE0", dialRing = "#E7DDC8", ink = "#1C2A5A", gold = "#9A7B2F", hand = "#1D3F9E", window = "#FFFDF7",
            bandLow = "#B03030", bandMid = "#2F7A48", bandHigh = "#C58A1A", sgvLow = "#A0001F", sgvMid = "#1E6B3A", sgvHigh = "#8A4A00",
            alarm = "#A0001F", noValue = "#8A8A8A",
            skyTop = "#5E9FD8", skyLow = "#CFE6F6", skyCloudy = "#AEB9C3", skyRain = "#8E9AA6", stars = false,
            land = "#4E6B33", landFar = "#7C9566", cloud = "#FFFFFF", cloudShade = "#C8CED6", rain = "#2C6FD1"
        )
    override val looks = listOf(
        Look("afternoon", AFTERNOON_MS, EventData.Weather.CLEAR), Look("partly", AFTERNOON_MS, EventData.Weather.PARTLY),
        Look("cloudy", AFTERNOON_MS, EventData.Weather.CLOUDY), Look("rain", AFTERNOON_MS, EventData.Weather.RAIN),
        Look("morning 09:00", MORNING_MS, null), Look("evening 21:10", EVENING_MS, null)
    )
}
