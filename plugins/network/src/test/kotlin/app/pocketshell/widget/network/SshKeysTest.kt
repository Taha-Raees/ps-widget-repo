package app.pocketshell.widget.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are throwaway keys generated on the device; the expected fingerprints are what
 * `ssh-keygen -lf` printed for them, so the pure-Kotlin fingerprint is checked against OpenSSH itself.
 */
class SshKeysTest {

    private val ed = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILsD1+Oq0qagntjyZDJ01+jauzjAbVENiz8LsqD1pYVW fixture-ed"
    private val rsa = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDpfTEgnUfwFB+T6lmaEpMmodGcoPwjEMl32DnF1T5wg4RRLm+j0vTRrwopTpMcfG8QXtuBxbvpceJtqrC+QpgYokOTrLnWsa0NrToCRxs1hDD0WjdpCDYx3nj6Dd9iqPtj+yGEAmrg9WkwOiopyZvahyNxWauLjyjbALeU39KRj+a1IP843NQDptIyFNBgu9tg/bUMaGjD78QeJ86n7haXebZmt48p+bLY/2NnzzVQ5j/amyi2+QvuGK3F322rXInKVUNjSmuVDdAAJHt5pIkszbp6C3rWnWf9ZaG3xUqBIR6fGJkjWOBWKCVMuguw79Crcak7OIUfMAJmyv65Sn3d fixture-rsa"
    private val ec = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBMrS4W8ThdW2LXgZAVZBQrMFLz33k9zlgWl4ta6OewPcKmx7/6efNTmVdx6anNqjEc4rAgidKjg3qFaLT57SIZE= fixture-ec"

    @Test
    fun `fingerprints match what ssh-keygen printed`() {
        val e = SshKeys.parsePublic(ed)!!
        assertEquals("SHA256:a/qfqO4z2jLb3BcrVX4SsFfwyhwIYDLOMpc55IVw2MU", e.fingerprint)
        assertEquals("ED25519", e.type); assertEquals(256, e.bits); assertEquals("fixture-ed", e.comment)

        val r = SshKeys.parsePublic(rsa)!!
        assertEquals("SHA256:tKMDBPbBk8e094XmY/wjZoZ1uHdcOKz17E3luk3SvhU", r.fingerprint)
        assertEquals("RSA", r.type); assertEquals(2048, r.bits)

        val c = SshKeys.parsePublic(ec)!!
        assertEquals("SHA256:Crf7T3jEYmCoKDpHxR1G31p7MIth28OOwizumBi1WpE", c.fingerprint)
        assertEquals("ECDSA", c.type); assertEquals(256, c.bits)
    }

    @Test
    fun `a key without a comment still parses`() {
        val p = SshKeys.parsePublic(ed.substringBeforeLast(" "))!!
        assertEquals("", p.comment)
    }

    @Test
    fun `garbage is not a key`() {
        assertNull(SshKeys.parsePublic(""))
        assertNull(SshKeys.parsePublic("ssh-ed25519"))
        assertNull(SshKeys.parsePublic("ssh-ed25519 !!!notbase64!!! x"))
        assertNull("type must match the blob", SshKeys.parsePublic(ed.replace("ssh-ed25519", "ssh-rsa")))
        assertNull(SshKeys.parsePublic("ssh-ed25519 AAAA x"))
    }

    // Only the header of an OpenSSH private key is needed to tell if it is encrypted.
    private fun fakePrivate(cipher: String): String {
        fun str(s: String) = byteArrayOf(0, 0, 0, s.length.toByte()) + s.toByteArray()
        val body = "openssh-key-v1\u0000".toByteArray() + str(cipher) + str("bcrypt") + ByteArray(20)
        val b64 = java.util.Base64.getEncoder().encodeToString(body).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    @Test
    fun `passphrase protection is read from the cipher name`() {
        assertFalse(SshKeys.isEncrypted(fakePrivate("none")))
        assertTrue(SshKeys.isEncrypted(fakePrivate("aes256-ctr")))
        // the real header of a key made with a passphrase on the device
        assertTrue(SshKeys.isEncrypted("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABB3mvjjoW\n-----END OPENSSH PRIVATE KEY-----"))
        assertFalse(SshKeys.isEncrypted("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n-----END OPENSSH PRIVATE KEY-----"))
        assertTrue("legacy PEM marks encryption in a header", SshKeys.isEncrypted("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n"))
        assertFalse(SshKeys.isEncrypted(""))
        assertFalse(SshKeys.isEncrypted("-----BEGIN OPENSSH PRIVATE KEY-----\n!!!\n-----END OPENSSH PRIVATE KEY-----"))
    }

    @Test
    fun `key names cannot escape the ssh folder or hit config files`() {
        for (ok in listOf("id_ed25519", "work-key", "github.2026")) assertTrue(ok, SshKeys.validName(ok))
        for (bad in listOf("", "../x", "a/b", "known_hosts", "config", "authorized_keys", "x.pub", "a b", ".", "..", "a\nb")) assertFalse(bad, SshKeys.validName(bad))
    }
}
