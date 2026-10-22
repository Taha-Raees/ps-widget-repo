package app.pocketshell.widget.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The passphrase layer's attack matrix. Fast KDF iterations are used where
 * the KDF is not the subject; one test runs the production iteration count.
 */
class BackupCryptoTest {

    private val pass = "correct horse battery".toCharArray()
    private val aad = PsbakHeader(PsbakHeader.VERSION, encrypted = true, codec = BackupCodec.ZSTD).encode()
    private val fast = 1_000
    private val chunk = PsbakCrypto.CHUNK_SIZE
    private val rnd = SecureRandom()

    private fun bytes(n: Int) = ByteArray(n).also(rnd::nextBytes)

    private fun encrypt(plain: ByteArray, p: CharArray = pass, a: ByteArray = aad, iterations: Int = fast, pieces: Int = 0): ByteArray {
        val sink = ByteArrayOutputStream()
        PsbakCrypto.encryptingStream(sink, p, a, iterations).use { out ->
            if (pieces <= 0) {
                out.write(plain)
            } else {
                var off = 0
                val step = maxOf(1, plain.size / pieces)
                while (off < plain.size) {
                    val n = minOf(step, plain.size - off)
                    out.write(plain, off, n)
                    off += n
                }
            }
        }
        return sink.toByteArray()
    }

    private fun decrypt(cipher: ByteArray, p: CharArray = pass, a: ByteArray = aad): ByteArray =
        PsbakCrypto.decryptingStream(ByteArrayInputStream(cipher), p, a).use { it.readBytes() }

    private fun assertRejected(cipher: ByteArray, p: CharArray = pass, a: ByteArray = aad) {
        try {
            decrypt(cipher, p, a)
            fail("decryption must be rejected")
        } catch (_: PsbakCrypto.BadPassphraseOrCorruptException) {
            // expected — and the only failure type callers have to handle
        }
    }

    // ------------------------------------------------------------ round trips

    @Test
    fun `round trip across the chunk boundaries - empty, tiny, exact, just over, multi-chunk`() {
        listOf(0, 1, 100, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 17, 5 * chunk + 3).forEach { n ->
            val plain = bytes(n)
            assertArrayEquals("size $n", plain, decrypt(encrypt(plain)))
        }
    }

    @Test
    fun `the write granularity does not change the plaintext`() {
        val plain = bytes(3 * chunk + 123)
        assertArrayEquals(plain, decrypt(encrypt(plain, pieces = 1)))
        assertArrayEquals(plain, decrypt(encrypt(plain, pieces = 7)))
        assertArrayEquals(plain, decrypt(encrypt(plain, pieces = 1000)))
        // single-byte writes through the OutputStream.write(int) path
        val sink = ByteArrayOutputStream()
        PsbakCrypto.encryptingStream(sink, pass, aad, fast).use { out -> plain.take(500).forEach { out.write(it.toInt()) } }
        assertArrayEquals(plain.copyOf(500), decrypt(sink.toByteArray()))
    }

    @Test
    fun `reading one byte at a time yields the same plaintext`() {
        val plain = bytes(chunk + 50)
        val input = PsbakCrypto.decryptingStream(ByteArrayInputStream(encrypt(plain)), pass, aad)
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
        }
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun `the production KDF iteration count round-trips`() {
        val plain = bytes(1000)
        assertArrayEquals(plain, decrypt(encrypt(plain, iterations = PsbakCrypto.DEFAULT_ITERATIONS)))
    }

    @Test
    fun `ciphertext is randomized and does not contain the plaintext`() {
        val plain = "SECRET-MARKER-SECRET-MARKER".toByteArray()
        val a = encrypt(plain)
        val b = encrypt(plain)
        assertNotEquals("fresh salt and nonce prefix every time", a.toList(), b.toList())
        assertFalse(String(a, Charsets.ISO_8859_1).contains("SECRET-MARKER"))
        assertEquals(PsbakCrypto.STREAM_HEADER_SIZE + plain.size + PsbakCrypto.TAG_SIZE, a.size)
    }

    // ----------------------------------------------------------------- attacks

    @Test
    fun `a wrong passphrase is rejected on the first byte read`() {
        val cipher = encrypt(bytes(5000))
        assertRejected(cipher, p = "wrong".toCharArray())
        assertRejected(cipher, p = "correct horse battery ".toCharArray())
        assertRejected(cipher, p = "CORRECT HORSE BATTERY".toCharArray())
    }

    @Test
    fun `an empty passphrase cannot encrypt`() {
        try {
            encrypt(bytes(10), p = CharArray(0))
            fail("empty passphrase must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `flipping any byte anywhere is detected`() {
        val plain = bytes(2 * chunk + 100)
        val good = encrypt(plain)
        // header (salt, prefix, chunk size, iterations), first chunk, a chunk tag, the last chunk, the last tag byte.
        val spots = listOf(
            0, PsbakCrypto.SALT_SIZE + 2, PsbakCrypto.STREAM_HEADER_SIZE - 6, PsbakCrypto.STREAM_HEADER_SIZE - 1,
            PsbakCrypto.STREAM_HEADER_SIZE, PsbakCrypto.STREAM_HEADER_SIZE + chunk - 1,
            PsbakCrypto.STREAM_HEADER_SIZE + chunk + 3, good.size - 1, good.size - 20,
        )
        spots.forEach { i ->
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertRejected(bad)
        }
    }

    @Test
    fun `truncation is detected - at a chunk boundary, inside a chunk, and into the header`() {
        val good = encrypt(bytes(3 * chunk + 10))
        val cipherChunk = chunk + PsbakCrypto.TAG_SIZE
        // Exactly after the first and second chunk: every remaining chunk is individually valid,
        // but the stream never carries its last-chunk flag — the classic truncation attack.
        assertRejected(good.copyOf(PsbakCrypto.STREAM_HEADER_SIZE + cipherChunk))
        assertRejected(good.copyOf(PsbakCrypto.STREAM_HEADER_SIZE + 2 * cipherChunk))
        assertRejected(good.copyOf(PsbakCrypto.STREAM_HEADER_SIZE + cipherChunk + 100))
        assertRejected(good.copyOf(good.size - 1))
        assertRejected(good.copyOf(PsbakCrypto.STREAM_HEADER_SIZE))
        assertRejected(good.copyOf(PsbakCrypto.STREAM_HEADER_SIZE - 1))
        assertRejected(ByteArray(0))
    }

    @Test
    fun `reordering or duplicating chunks is detected`() {
        val good = encrypt(bytes(3 * chunk))
        val cc = chunk + PsbakCrypto.TAG_SIZE
        val h = PsbakCrypto.STREAM_HEADER_SIZE
        val c0 = good.copyOfRange(h, h + cc)
        val c1 = good.copyOfRange(h + cc, h + 2 * cc)
        val rest = good.copyOfRange(h + 2 * cc, good.size)
        val header = good.copyOf(h)
        assertRejected(header + c1 + c0 + rest)
        assertRejected(header + c0 + c0 + c1 + rest)
        assertRejected(header + c0 + rest)
    }

    @Test
    fun `the file header is authenticated - a swapped codec or flag is detected`() {
        val good = encrypt(bytes(300))
        val other = PsbakHeader(PsbakHeader.VERSION, encrypted = true, codec = BackupCodec.GZIP).encode()
        assertRejected(good, a = other)
        assertRejected(good, a = ByteArray(0))
    }

    @Test
    fun `an absurd header is refused before any key derivation work`() {
        val good = encrypt(bytes(10))
        // iterations field → 0 and → huge; chunk size → 0.
        val iterOff = PsbakCrypto.SALT_SIZE + PsbakCrypto.NONCE_PREFIX_SIZE + 4
        listOf(0, Int.MAX_VALUE).forEach { bogus ->
            val bad = good.copyOf()
            java.nio.ByteBuffer.wrap(bad).putInt(iterOff, bogus)
            assertRejected(bad)
        }
        val badChunk = good.copyOf()
        java.nio.ByteBuffer.wrap(badChunk).putInt(PsbakCrypto.SALT_SIZE + PsbakCrypto.NONCE_PREFIX_SIZE, 0)
        assertRejected(badChunk)
    }

    @Test
    fun `a large stream stays correct and the wrong-passphrase failure is the only error type`() {
        val plain = bytes(3 * 1024 * 1024 + 7)
        val cipher = encrypt(plain)
        assertArrayEquals(plain, decrypt(cipher))
        assertTrue(cipher.size > plain.size)
        assertRejected(cipher, p = "nope".toCharArray())
    }
}
