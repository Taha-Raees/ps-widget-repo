package app.pocketshell.widget.sync

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for a `.psbak` payload — authenticated, streaming,
 * built ONLY from platform primitives (`javax.crypto`), because the tools
 * that would do it are not there when it matters: `age` refuses a
 * non-interactive passphrase, `openssl enc` has no authentication, `gpg` is
 * heavy — and a freshly installed phone has none of them (or the network to
 * fetch them) at the moment of restore.
 *
 * Construction — the standard STREAM design (Rogaway; the shape age and Tink
 * use):
 *
 *   key      = PBKDF2-HMAC-SHA256(passphrase, salt, iterations) → 256 bits
 *   stream   = salt[16] | noncePrefix[7] | chunkSize[4] | iterations[4] | chunk…
 *   chunk i  = AES-256-GCM(plaintext ≤ chunkSize)  → ciphertext + 16-byte tag
 *   nonce(i) = noncePrefix | counter(i) as 4 bytes big-endian | lastFlag(1 byte)
 *   AAD      = the 8-byte `.psbak` file header + the stream header above
 *
 * - every chunk has a UNIQUE nonce (the counter), so a key is never reused;
 * - the LAST chunk carries lastFlag = 1, so a stream cut at a chunk boundary
 *   fails authentication instead of silently decrypting a prefix;
 * - the counter makes reordering/duplicating chunks fail authentication;
 * - the AAD binds the header, so flipping the "encrypted"/codec bytes or the
 *   KDF parameters fails too.
 *
 * A wrong passphrase and a damaged file are indistinguishable (GCM fails on
 * the first chunk) and are reported as exactly that — never a guess.
 */
object PsbakCrypto {

    const val SALT_SIZE = 16
    const val NONCE_PREFIX_SIZE = 7
    const val CHUNK_SIZE = 64 * 1024
    const val TAG_SIZE = 16
    const val DEFAULT_ITERATIONS = 600_000

    /** Bytes the stream header occupies before the first chunk. */
    const val STREAM_HEADER_SIZE = SALT_SIZE + NONCE_PREFIX_SIZE + 4 + 4

    private const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val MAX_CHUNK_SIZE = 16 * 1024 * 1024

    /** Wrong passphrase or damaged/tampered data — the same failure, honestly named. */
    class BadPassphraseOrCorruptException(message: String = "wrong passphrase, or the file is damaged") : IOException(message)

    /** Wrap [out]: everything written is encrypted; closing writes the final chunk. */
    fun encryptingStream(
        out: OutputStream,
        passphrase: CharArray,
        aad: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): OutputStream {
        require(passphrase.isNotEmpty()) { "an empty passphrase protects nothing" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "unreasonable KDF iteration count" }
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also(random::nextBytes)
        val header = ByteBuffer.allocate(STREAM_HEADER_SIZE)
            .put(salt).put(prefix).putInt(CHUNK_SIZE).putInt(iterations).array()
        out.write(header)
        return EncryptingStream(out, deriveKey(passphrase, salt, iterations), prefix, aad + header, CHUNK_SIZE)
    }

    /** Wrap [input]: reads decrypt on the fly; a bad chunk throws [BadPassphraseOrCorruptException]. */
    fun decryptingStream(input: InputStream, passphrase: CharArray, aad: ByteArray): InputStream {
        val header = ByteArray(STREAM_HEADER_SIZE)
        try {
            readFully(input, header)
        } catch (_: EOFException) {
            throw BadPassphraseOrCorruptException("the encrypted data is truncated")
        }
        val buf = ByteBuffer.wrap(header)
        val salt = ByteArray(SALT_SIZE).also(buf::get)
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also(buf::get)
        val chunkSize = buf.int
        val iterations = buf.int
        if (chunkSize !in 1..MAX_CHUNK_SIZE || iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw BadPassphraseOrCorruptException("the encryption header is not valid")
        }
        return DecryptingStream(input, deriveKey(passphrase, salt, iterations), prefix, aad + header, chunkSize)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return SecretKeySpec(raw, "AES") // copies the bytes
            } finally {
                raw.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).put(if (last) 1 else 0).array()

    private fun cipher(mode: Int, key: SecretKeySpec, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_SIZE * 8, nonce))
            updateAAD(aad)
        }

    private fun readFully(input: InputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    // ----------------------------------------------------------- encrypt

    private class EncryptingStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val aad: ByteArray,
        private val chunkSize: Int,
    ) : OutputStream() {
        private val buf = ByteArray(chunkSize)
        private var len = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, n: Int) {
            check(!closed) { "stream is closed" }
            var o = off
            var left = n
            while (left > 0) {
                // A FULL buffer is only flushed once more data arrives, so the
                // final chunk is always known at close() and can carry lastFlag.
                if (len == chunkSize) flush(last = false)
                val take = minOf(left, chunkSize - len)
                System.arraycopy(b, o, buf, len, take)
                len += take
                o += take
                left -= take
            }
        }

        private fun flush(last: Boolean) {
            val c = cipher(Cipher.ENCRYPT_MODE, key, nonce(prefix, counter++, last), aad)
            out.write(c.doFinal(buf, 0, len))
            len = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                flush(last = true)
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    // ----------------------------------------------------------- decrypt

    private class DecryptingStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val aad: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        private val cipherChunk = chunkSize + TAG_SIZE
        private var plain = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var finished = false
        private var lookahead = -1

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, n: Int): Int {
            if (n == 0) return 0
            while (pos >= plain.size) {
                if (finished) return -1
                nextChunk()
            }
            val take = minOf(n, plain.size - pos)
            System.arraycopy(plain, pos, b, off, take)
            pos += take
            return take
        }

        private fun nextChunk() {
            val data = ByteArray(cipherChunk)
            var filled = 0
            if (lookahead >= 0) {
                data[0] = lookahead.toByte()
                filled = 1
                lookahead = -1
            }
            while (filled < cipherChunk) {
                val got = input.read(data, filled, cipherChunk - filled)
                if (got < 0) break
                filled += got
            }
            // A short read is the last chunk; a FULL one is last only if nothing follows it.
            val last = if (filled < cipherChunk) {
                true
            } else {
                val next = input.read()
                if (next < 0) true else { lookahead = next; false }
            }
            if (filled < TAG_SIZE) throw BadPassphraseOrCorruptException("the encrypted data is truncated")
            try {
                plain = cipher(Cipher.DECRYPT_MODE, key, nonce(prefix, counter++, last), aad).doFinal(data, 0, filled)
            } catch (_: GeneralSecurityException) {
                throw BadPassphraseOrCorruptException()
            }
            pos = 0
            if (last) finished = true
        }

        override fun close() = input.close()
    }
}
