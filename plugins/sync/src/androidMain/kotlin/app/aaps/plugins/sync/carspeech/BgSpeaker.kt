// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.carspeech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/**
 * Says a text out loud with Android's text-to-speech.
 *
 * A port of xDrip+ `SpeechUtil` by jamorham (GPLv3, NightscoutFoundation/xDrip):
 * - the engine is made once and kept, because binding to it is slow,
 * - the phone language is used, with English as the fall back,
 * - nothing is said during a phone call,
 * - a failed attempt is tried again a few times.
 *
 * One change: xDrip+ waits up to 40 seconds for music to stop. In a car the music hardly ever
 * stops, so here the music is made quieter for the moment of speaking (audio focus with ducking),
 * and the text is played as navigation guidance. Android Auto mixes that over the music on the car
 * speakers, like the voice of a navigation app.
 */
class BgSpeaker(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val mutex = Mutex()
    private var tts: TextToSpeech? = null

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Lazy: made on the first speech, not when the plugin is made at app start
    private val audioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    /**
     * Says [text]. Returns false when nothing was said. Call it from a background coroutine.
     *
     * Never throws (only cancellation) and never takes longer than [TOTAL_TIMEOUT_MS], so a broken
     * text-to-speech engine can not stop the caller.
     */
    suspend fun say(text: String): Boolean =
        try {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) { mutex.withLock { sayLocked(text) } }
                ?: false.also { aapsLogger.error(LTag.CORE, "Car speech: gave up, speaking took too long") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            aapsLogger.error(LTag.CORE, "Car speech: speaking failed", e)
            false
        }

    private suspend fun sayLocked(text: String): Boolean {
        if (isOngoingCall()) {
            aapsLogger.debug(LTag.CORE, "Car speech: not speaking during a call")
            return false
        }
        repeat(MAX_RETRIES) { attempt ->
            val engine = engine()
            if (engine != null && speak(engine, text)) return true
            aapsLogger.warn(LTag.CORE, "Car speech: speaking failed, attempt ${attempt + 1}")
            // A broken engine is made new on the next attempt
            shutdownEngine()
            delay(RETRY_DELAY_MS)
        }
        aapsLogger.error(LTag.CORE, "Car speech: could not speak after $MAX_RETRIES attempts")
        return false
    }

    /** Frees the engine. It is made again on the next [say]. */
    suspend fun shutdown() = mutex.withLock { shutdownEngine() }

    private fun shutdownEngine() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Car speech: error when stopping the engine", e)
        }
        tts = null
    }

    /** The engine, made and set to a language on first use. Null when the phone has no usable engine. */
    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        val ready = CompletableDeferred<Int>()
        val engine = TextToSpeech(context.applicationContext) { status -> ready.complete(status) }
        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() }
        if (status != TextToSpeech.SUCCESS) {
            aapsLogger.error(LTag.CORE, "Car speech: engine did not start, status $status")
            engine.shutdown()
            return null
        }
        if (!setLanguage(engine, Locale.getDefault()) && !setLanguage(engine, Locale.ENGLISH)) {
            aapsLogger.error(LTag.CORE, "Car speech: neither the phone language nor English is available")
            engine.shutdown()
            return null
        }
        engine.setAudioAttributes(audioAttributes)
        tts = engine
        return engine
    }

    private fun setLanguage(engine: TextToSpeech, locale: Locale): Boolean {
        val result = try {
            engine.setLanguage(locale)
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Car speech: cannot set language $locale", e)
            TextToSpeech.LANG_MISSING_DATA
        }
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** Speaks with the music turned down, and waits until the text is done. */
    private suspend fun speak(engine: TextToSpeech, text: String): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .build()
        audioManager.requestAudioFocus(focusRequest)
        try {
            val done = CompletableDeferred<Boolean>()
            val utteranceId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) done.complete(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) done.complete(false)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) done.complete(false)
                }
            })
            if (engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId) != TextToSpeech.SUCCESS) return false
            return withTimeoutOrNull(SPEAK_TIMEOUT_MS) { done.await() } ?: false
        } finally {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private fun isOngoingCall(): Boolean =
        audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    companion object {

        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 2_000L
        private const val INIT_TIMEOUT_MS = 10_000L
        private const val SPEAK_TIMEOUT_MS = 30_000L

        /** All attempts together. A reading comes every 5 minutes; an older one is not worth saying. */
        private const val TOTAL_TIMEOUT_MS = 90_000L
    }
}
