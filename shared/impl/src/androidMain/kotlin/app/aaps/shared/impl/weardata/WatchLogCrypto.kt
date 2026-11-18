package app.aaps.shared.impl.weardata

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the watch log for the way over Wi-Fi: AES-GCM, in one piece.
 *
 * The phone makes a new [Secret] for every download and sends it over the Wear OS channel, which
 * only the paired apps with the same signing key can use. Over Wi-Fi only the cipher text and the
 * tag travel. Somebody on the Wi-Fi network, or somebody who takes the place of the phone there,
 * gets nothing readable, and a body from anybody without the secret is refused.
 *
 * In one piece: the whole body is in memory once on each side, so the caller limits its size. The
 * tag is checked before any of the body is given back.
 */
object WatchLogCrypto {

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** A key and a nonce for one transfer. [toString] never shows them, so they cannot end up in a log. */
    class Secret(val key: ByteArray, val nonce: ByteArray) {

        init {
            require(key.size == KEY_BYTES) { "Wrong key size" }
            require(nonce.size == NONCE_BYTES) { "Wrong nonce size" }
        }

        override fun equals(other: Any?): Boolean = other is Secret && key.contentEquals(other.key) && nonce.contentEquals(other.nonce)
        override fun hashCode(): Int = 31 * key.contentHashCode() + nonce.contentHashCode()
        override fun toString(): String = "Secret(hidden)"
    }

    /** A new random key and nonce, for one transfer only */
    fun newSecret(random: SecureRandom = SecureRandom()): Secret =
        Secret(ByteArray(KEY_BYTES).also { random.nextBytes(it) }, ByteArray(NONCE_BYTES).also { random.nextBytes(it) })

    /** @return the cipher text with the tag at its end, [TAG_BYTES] longer than [plain] */
    fun encrypt(plain: ByteArray, secret: Secret): ByteArray = cipher(Cipher.ENCRYPT_MODE, secret).doFinal(plain)

    /**
     * @param body what [encrypt] made
     * @param maxPlainSize a larger body is refused before it is decrypted
     * @return the plain text, only when the tag matches
     * @throws IOException when the body is too large or too short, or was not made with [secret], or was changed
     */
    fun decrypt(body: ByteArray, secret: Secret, maxPlainSize: Long): ByteArray {
        if (body.size < TAG_BYTES) throw IOException("The encrypted log is too short")
        if (body.size - TAG_BYTES > maxPlainSize) throw IOException("The encrypted log is larger than $maxPlainSize bytes")
        return try {
            cipher(Cipher.DECRYPT_MODE, secret).doFinal(body)
        } catch (e: GeneralSecurityException) {
            throw IOException("The encrypted log is not the one the phone asked for", e)
        }
    }

    private fun cipher(mode: Int, secret: Secret): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(mode, SecretKeySpec(secret.key, "AES"), GCMParameterSpec(TAG_BYTES * 8, secret.nonce)) }
}
