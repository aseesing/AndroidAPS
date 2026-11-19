package app.aaps.wear.watchfaces.utils

/**
 * A time in the text of a Custom watch face design.
 *
 * The text of a view with `dynData` is a format for a number, like `"%.0f°"`. For a value that is a
 * moment (a time in milliseconds, like the next sunrise or sunset), a design writes [TOKEN] in the
 * text instead: the watch puts the time there, in the 12 or 24 hour format of the watch.
 */
object DesignTimeText {

    const val TOKEN = "@time"

    /**
     * [format] with [TOKEN] replaced by [epochMs] written by [timeString], or null when [format] has no
     * [TOKEN] (then it is a number format, as before).
     */
    fun of(format: String, epochMs: Double, timeString: (Long) -> String): String? =
        if (TOKEN in format) format.replace(TOKEN, timeString(epochMs.toLong())) else null
}
