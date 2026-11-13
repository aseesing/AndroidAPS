// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.daynight

/**
 * The choice between the main face and the optional night face of the Custom watch face (see
 * `CwfNight`). Private patch. Pure functions only.
 */
internal object NightFace {

    /**
     * The face to show: [night] when there is one and it is not day, else [main]. With no night face
     * the main face shows day and night, as before.
     *
     * @param showDay whether it is day, see [DayNightChoice.showDay]; only asked when there is a night face
     */
    fun <T : Any> choose(main: T, night: T?, showDay: () -> Boolean): T =
        if (night != null && !showDay()) night else main
}
