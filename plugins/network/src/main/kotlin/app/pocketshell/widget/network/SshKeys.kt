package app.pocketshell.widget.network

import java.security.MessageDigest
import java.util.Base64

/** A key pair as shown in the KEYS tab. Built from the `.pub` file; the private half is only inspected, never read into a screen. */
data class KeyInfo(
    /** File name without `.pub`, e.g. `id_ed25519`. */
    val name: String,
    val type: String,
    val bits: Int?,
    /** `SHA256:…`, the same string `ssh-keygen -lf` prints. */
    val fingerprint: String,
    val comment: String,
    /** The one-line public key (safe to copy and share). */
    val publicLine: String,
    val hasPrivate: Boolean,
    val encrypted: Boolean,
    /** Private key readable by group/others — ssh refuses such a key. */
    val permissionsTooOpen: Boolean,
)

object SshKeys {

    private val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,99}$")

    fun validName(s: String): Boolean = NAME.matches(s) && !s.endsWith(".pub") && s != "known_hosts" && s != "config" && s != "authorized_keys"

    class Parsed(val type: String, val bits: Int?, val fingerprint: String, val comment: String)

    /** Parse one `.pub` line; null if it is not a public key this app understands. */
    fun parsePublic(line: String): Parsed? {
        val parts = line.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return null
        val blob = try {
            Base64.getDecoder().decode(parts[1])
        } catch (_: IllegalArgumentException) {
            return null
        }
        val r = Reader(blob)
        val inner = r.string() ?: return null
        val type = String(inner, Charsets.US_ASCII)
        if (type != parts[0]) return null
        val bits = when {
            type == "ssh-ed25519" -> 256
            type == "ssh-rsa" -> {
                r.mpint() ?: return null // exponent
                r.mpint()?.let { n -> bitLength(n) } ?: return null
            }
            type.startsWith("ecdsa-sha2-nistp") -> type.removePrefix("ecdsa-sha2-nistp").toIntOrNull()
            else -> null
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val fp = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        return Parsed(typeLabel(type), bits, fp, parts.getOrElse(2) { "" })
    }

    private fun typeLabel(t: String) = when {
        t == "ssh-ed25519" -> "ED25519"
        t == "ssh-rsa" -> "RSA"
        t.startsWith("ecdsa-") -> "ECDSA"
        t.startsWith("sk-ssh-ed25519") -> "ED25519-SK"
        t.startsWith("sk-ecdsa") -> "ECDSA-SK"
        else -> t.uppercase()
    }

    /** True when an OpenSSH private key is passphrase-protected (its cipher is not `none`). Unknown formats → false. */
    fun isEncrypted(privateKeyText: String): Boolean {
        val lines = privateKeyText.trim().lines()
        if (lines.firstOrNull()?.trim() != "-----BEGIN OPENSSH PRIVATE KEY-----") {
            // Legacy PEM keys mark encryption in a header.
            return privateKeyText.contains("ENCRYPTED")
        }
        val raw = lines.drop(1).takeWhile { !it.startsWith("-----END") }.joinToString("").filter { !it.isWhitespace() }
        // Only the header is needed, and the file may have been read in part: whole 4-character groups only.
        val body = raw.substring(0, raw.length - raw.length % 4)
        val bytes = try {
            Base64.getDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (bytes.size < magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) return false
        val cipher = Reader(bytes, magic.size).string() ?: return false
        return String(cipher, Charsets.US_ASCII) != "none"
    }

    private fun bitLength(mpint: ByteArray): Int {
        val v = java.math.BigInteger(1, mpint)
        return v.bitLength()
    }

    private class Reader(private val b: ByteArray, private var p: Int = 0) {
        fun string(): ByteArray? {
            if (p + 4 > b.size) return null
            val n = ((b[p].toInt() and 0xff) shl 24) or ((b[p + 1].toInt() and 0xff) shl 16) or
                ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)
            if (n < 0 || p + 4 + n > b.size) return null
            val out = b.copyOfRange(p + 4, p + 4 + n)
            p += 4 + n
            return out
        }

        fun mpint(): ByteArray? = string()
    }
}
