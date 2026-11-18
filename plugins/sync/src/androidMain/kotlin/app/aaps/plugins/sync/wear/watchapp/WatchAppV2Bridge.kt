// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.watchapp

import app.aaps.shared.impl.weardata.WatchAppUpdateProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/*
 * The bridge for the watch app that is installed on the user's watch now.
 *
 * The personal watch app before the reviewed upstream code knows only the update format version 2.
 * The reviewed code sends version 4, so that watch app answers "Unknown update format", and the
 * phone would ask for one install with adb. That watch has no adb set up, so the phone then sends
 * the same update once more, with a version 2 header. Version 2 is the same as version 4 without
 * the APK size, so all the rest of the upstream sender is used as it is: the body over Wi-Fi with
 * the same one-time server and token, or over the channel, the watchdog, and the answers.
 *
 * In version 2 the watch checks the SHA-256 of the body, but knows no APK size limit. That is
 * acceptable for this bridge: the header still comes over the Wear OS channel, which only the
 * paired phone can open.
 *
 * After the first update through the bridge the watch runs the new watch app, which knows
 * version 4, and the bridge is never used again. The watch log download (WatchLogProtocol
 * version 2) also works only once the watch runs the new watch app.
 */

/**
 * Tries the update once more with a version 2 header, when the watch did not know the current one.
 *
 * It takes the same settings as the [WatchAppSender] it belongs to.
 *
 * @param retryDelayMs the wait before the second try: the personal watch app takes a new update
 *   only 10 seconds after it answered the last one
 */
internal class WatchAppV2Bridge(
    private val source: EmbeddedWatchAppSource,
    private val opener: WatchChannelOpener,
    private val wifiServers: WifiBodyServerFactory?,
    private val ioDispatcher: CoroutineDispatcher,
    private val answerTimeoutMs: Long,
    private val stallTimeoutMs: Long,
    private val installTimeoutMs: Long,
    private val wifiTimeoutMs: Long,
    private val nowMs: () -> Long,
    private val retryDelayMs: Long = RETRY_DELAY_MS
) {

    /**
     * @param first the result of the try with the current header
     * @param closeFirst frees what the first try still holds, its Wi-Fi server
     * @param onState gets the steps of the second try, but not its end: the caller reports the result
     * @return [first], or the result of the try with version 2 when the watch only knew version 2
     */
    suspend fun orRetry(first: WatchAppUpdateState, closeFirst: () -> Unit, onState: (WatchAppUpdateState) -> Unit): WatchAppUpdateState {
        // The sender of the second try has a bridge too: it must never start a third try
        if (opener is Version2Opener) return first
        if (first !is WatchAppUpdateState.Failed || first.reason != WatchAppUpdateState.Reason.OLD_WATCH_APP) return first
        closeFirst()
        delay(retryDelayMs)
        val second = WatchAppSender(
            source, Version2Opener(opener), wifiServers, ioDispatcher,
            answerTimeoutMs = answerTimeoutMs, stallTimeoutMs = stallTimeoutMs, installTimeoutMs = installTimeoutMs, wifiTimeoutMs = wifiTimeoutMs,
            nowMs = nowMs
        ).send { state -> if (state !is WatchAppUpdateState.Sent && state !is WatchAppUpdateState.Failed) onState(state) }
        // A watch app that knows neither version: the first answer says what to do (install once with adb)
        val notKnown = second is WatchAppUpdateState.Failed &&
            (second.reason == WatchAppUpdateState.Reason.OLD_WATCH_APP || second.reason == WatchAppUpdateState.Reason.OLD_PHONE_APP)
        return if (notKnown) first else second
    }

    companion object {

        /** The personal watch app closes the channel 10 seconds after its answer, and only then takes a new one */
        const val RETRY_DELAY_MS = 12_000L

        /** The update format of the personal watch app */
        const val VERSION_2 = 2

        /**
         * [header] in the format of version 2: magic, version, label, body size, SHA-256 and the
         * optional Wi-Fi offer. The APK size of version 4 is not in it.
         */
        fun version2Header(header: WatchAppUpdateProtocol.Header): ByteArray {
            val bytes = ByteArrayOutputStream()
            val data = DataOutputStream(bytes)
            data.writeInt(WatchAppUpdateProtocol.MAGIC)
            data.writeInt(VERSION_2)
            data.writeUTF(header.label)
            data.writeLong(header.bodySize)
            data.writeUTF(header.sha256)
            val wifi = header.wifi
            data.writeBoolean(wifi != null)
            if (wifi != null) {
                data.writeInt(wifi.port)
                data.writeUTF(wifi.token)
                val addresses = wifi.addresses.take(WatchAppUpdateProtocol.MAX_ADDRESSES)
                data.writeInt(addresses.size)
                addresses.forEach { data.writeUTF(it) }
            }
            data.flush()
            return bytes.toByteArray()
        }
    }
}

/** Opens the channels of [opener], but the header the sender writes goes out as version 2 */
internal class Version2Opener(private val opener: WatchChannelOpener) : WatchChannelOpener {

    override suspend fun open(path: String): WatchChannel? = opener.open(path)?.let { Version2Channel(it) }
}

private class Version2Channel(private val channel: WatchChannel) : WatchChannel {

    override val watchName: String get() = channel.watchName
    override val input: InputStream get() = channel.input
    override val output: OutputStream = Version2HeaderOutput(channel.output)

    override fun close() = channel.close()
}

/**
 * Holds back all that is written until the first [flush]. That is the header: the sender writes it
 * and flushes, see `WatchAppUpdateProtocol.writeHeader`. It goes out as version 2, and all after
 * it, the body, goes out as it is.
 */
internal class Version2HeaderOutput(private val output: OutputStream) : OutputStream() {

    private var header: ByteArrayOutputStream? = ByteArrayOutputStream()

    override fun write(b: Int) {
        val pending = header ?: return output.write(b)
        if (pending.size() >= MAX_HEADER_BYTES) throw IOException("Watch app update header too long")
        pending.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val pending = header ?: return output.write(b, off, len)
        if (pending.size() + len > MAX_HEADER_BYTES) throw IOException("Watch app update header too long")
        pending.write(b, off, len)
    }

    override fun flush() {
        header?.let { pending ->
            header = null
            val current = WatchAppUpdateProtocol.readHeader(ByteArrayInputStream(pending.toByteArray()))
            output.write(WatchAppV2Bridge.version2Header(current))
        }
        output.flush()
    }

    override fun close() = output.close()

    companion object {

        /** A real header is a few hundred bytes */
        const val MAX_HEADER_BYTES = 64 * 1024
    }
}
