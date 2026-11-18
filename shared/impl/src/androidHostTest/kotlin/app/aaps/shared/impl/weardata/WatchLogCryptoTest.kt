package app.aaps.shared.impl.weardata

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class WatchLogCryptoTest {

    private val plain = ByteArray(100_000) { (it * 13).toByte() }
    private val secret = WatchLogCrypto.newSecret()
    private val body = WatchLogCrypto.encrypt(plain, secret)

    @Test
    fun `round trip gives the same bytes, and the body is not readable`() {
        assertThat(body.size).isEqualTo(plain.size + WatchLogCrypto.TAG_BYTES)
        assertThat(body.copyOf(plain.size)).isNotEqualTo(plain)
        assertThat(WatchLogCrypto.decrypt(body, secret, plain.size.toLong())).isEqualTo(plain)
    }

    @Test
    fun `a wrong key is refused`() {
        val other = WatchLogCrypto.Secret(WatchLogCrypto.newSecret().key, secret.nonce)
        assertThrows<IOException> { WatchLogCrypto.decrypt(body, other, plain.size.toLong()) }
    }

    @Test
    fun `a flipped bit in the body is refused`() {
        val changed = body.copyOf().also { it[1_000] = (it[1_000].toInt() xor 1).toByte() }
        assertThrows<IOException> { WatchLogCrypto.decrypt(changed, secret, plain.size.toLong()) }
    }

    @Test
    fun `a flipped bit in the tag is refused`() {
        val changed = body.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
        assertThrows<IOException> { WatchLogCrypto.decrypt(changed, secret, plain.size.toLong()) }
    }

    @Test
    fun `a truncated body is refused`() {
        assertThrows<IOException> { WatchLogCrypto.decrypt(body.copyOf(body.size - 1), secret, plain.size.toLong()) }
        assertThrows<IOException> { WatchLogCrypto.decrypt(body.copyOf(WatchLogCrypto.TAG_BYTES - 1), secret, plain.size.toLong()) }
    }

    @Test
    fun `a body over the limit is refused before it is decrypted`() {
        val e = assertThrows<IOException> { WatchLogCrypto.decrypt(body, secret, plain.size - 1L) }
        assertThat(e.message).contains("larger than")
    }

    @Test
    fun `every secret is new, and it never shows in a text`() {
        assertThat(WatchLogCrypto.newSecret()).isNotEqualTo(secret)
        assertThat(secret.toString()).doesNotContain(secret.key.joinToString())
        assertThat(secret.toString()).isEqualTo("Secret(hidden)")
    }
}
