// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.watchapp

/**
 * Turns bytes moved into a call of [onPercent] for each new percent, with the time left.
 * Used by the watch app update and by the watch log download.
 *
 * @param size of the whole transfer in bytes
 * @param onPercent percent done, and the seconds left at the speed so far (null while too early to tell)
 */
internal class TransferProgress(
    private val size: Long,
    private val nowMs: () -> Long,
    private val onPercent: (percent: Int, secondsLeft: Int?) -> Unit
) {

    private var percent = 0
    private var start = -1L

    fun report(done: Long) {
        // From the first block on: over Wi-Fi the watch may need a while before the first byte
        if (start < 0) start = nowMs()
        val now = (done * 100 / size).toInt()
        if (now == percent) return
        percent = now
        onPercent(now, WatchAppSender.secondsLeft(done, size, nowMs() - start))
    }
}
