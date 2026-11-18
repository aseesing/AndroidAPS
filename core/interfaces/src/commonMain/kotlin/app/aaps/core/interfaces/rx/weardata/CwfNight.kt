package app.aaps.core.interfaces.rx.weardata

/**
 * A day face and a night face for the Custom watch face.
 *
 * The watch keeps two designs: the main one (what it always had) and an optional night one. With
 * no night face the main face shows day and night, as before. With a night face, the watch shows
 * the main face in the day and the night face at night.
 *
 * Which face shows follows two settings on the watch: "Day / night look" (automatic, always day,
 * always night) and "Always-on look" for the dim always-on screen. "Automatic" follows a
 * [DayNightRule] that the phone sends only while the watch has a night face. Without a rule the day
 * is 07:00 to 19:00.
 *
 * The phone sends a night face zip on the message path of a normal zip plus [PATH_SUFFIX], so the
 * watch's listener (which filters on the path prefix) receives it without a manifest change.
 */
object CwfNight {

    /** Added to `path_rx_data_bridge` for a night face zip. */
    const val PATH_SUFFIX = "_night"
}
