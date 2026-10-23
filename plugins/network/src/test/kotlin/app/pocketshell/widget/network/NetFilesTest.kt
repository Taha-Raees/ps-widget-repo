package app.pocketshell.widget.network

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetFilesTest {

    private lateinit var root: File
    private lateinit var files: NetFiles

    private val edPub = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILsD1+Oq0qagntjyZDJ01+jauzjAbVENiz8LsqD1pYVW fixture-ed"
    private val edPriv = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n-----END OPENSSH PRIVATE KEY-----\n"
    private val encPriv = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABB3mvjjoW\n-----END OPENSSH PRIVATE KEY-----\n"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("netfiles").toFile()
        files = NetFiles(root)
    }

    @After
    fun tearDown() { root.deleteRecursively() }

    @Test
    fun `writing the config keeps a one-time backup and replaces atomically`() {
        files.sshDir.mkdirs()
        files.configFile.writeText("# original\nHost a\n    HostName x\n")
        files.writeConfig("Host b\n    HostName y\n")
        assertEquals("Host b\n    HostName y\n", files.readConfig())
        val backup = File(files.sshDir, "config.pre-network")
        assertEquals("# original\nHost a\n    HostName x\n", backup.readText())
        files.writeConfig("Host c\n")
        assertEquals("the backup is the ORIGINAL, not the previous write", "# original\nHost a\n    HostName x\n", backup.readText())
        assertFalse("no temp file is left behind", File(files.sshDir, ".config.tmp").exists())
        val perms = Files.getPosixFilePermissions(files.configFile.toPath())
        assertEquals("config is private", PosixFilePermissions.fromString("rw-------"), perms)
    }

    @Test
    fun `writing into a fresh home creates the ssh folder privately`() {
        files.writeConfig("Host a\n")
        assertTrue(files.sshDir.isDirectory)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(files.sshDir.toPath()))
    }

    @Test
    fun `keys are listed with fingerprint, private presence, passphrase and permissions`() {
        files.sshDir.mkdirs()
        File(files.sshDir, "id_ed25519.pub").writeText("$edPub\n")
        File(files.sshDir, "id_ed25519").apply { writeText(edPriv); Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rw-------")) }
        File(files.sshDir, "locked.pub").writeText(edPub.replace("fixture-ed", "locked") + "\n")
        File(files.sshDir, "locked").apply { writeText(encPriv); Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rw-r--r--")) }
        File(files.sshDir, "orphan.pub").writeText(edPub.replace("fixture-ed", "orphan") + "\n")
        File(files.sshDir, "broken.pub").writeText("not a key\n")
        File(files.sshDir, "known_hosts").writeText("x")

        val keys = files.listKeys().associateBy { it.name }
        assertEquals(setOf("id_ed25519", "locked", "orphan"), keys.keys)
        val ok = keys.getValue("id_ed25519")
        assertEquals("SHA256:a/qfqO4z2jLb3BcrVX4SsFfwyhwIYDLOMpc55IVw2MU", ok.fingerprint)
        assertTrue(ok.hasPrivate); assertFalse(ok.encrypted); assertFalse(ok.permissionsTooOpen)
        assertTrue(keys.getValue("locked").encrypted)
        assertTrue("0644 on a private key is flagged", keys.getValue("locked").permissionsTooOpen)
        assertFalse(keys.getValue("orphan").hasPrivate)
        assertEquals(edPub, ok.publicLine.replace("fixture-ed", "fixture-ed"))
    }

    @Test
    fun `a missing ssh folder lists nothing instead of failing`() {
        assertTrue(files.listKeys().isEmpty())
        assertEquals("", files.readConfig())
        assertTrue(files.readTunnels().isEmpty())
    }

    @Test
    fun `deleting a key removes both halves and nothing else`() {
        files.sshDir.mkdirs()
        File(files.sshDir, "k").writeText("p"); File(files.sshDir, "k.pub").writeText("q"); File(files.sshDir, "keep").writeText("r")
        assertTrue(files.deleteKey("k"))
        assertFalse(File(files.sshDir, "k").exists()); assertFalse(File(files.sshDir, "k.pub").exists())
        assertTrue(File(files.sshDir, "keep").exists())
        for (bad in listOf("../x", "config", "known_hosts", "", ".", "..", "a/b")) assertFalse("must refuse '$bad'", files.deleteKey(bad))
    }

    @Test
    fun `an existing key is never overwritten silently`() {
        files.sshDir.mkdirs()
        File(files.sshDir, "id_x.pub").writeText("q")
        assertTrue(files.keyExists("id_x")); assertFalse(files.keyExists("id_y"))
    }

    @Test
    fun `only plain host key lines are accepted into known_hosts`() {
        val good = "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabc"
        val lines = listOf(
            good,
            "[example.com]:2222 ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBMrS4W8ThdW2LXgZAVZBQrMFLz33k9=",
            "@cert-authority * ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabc",
            "|1|abc=|def= ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabc",
            "host ssh-ed25519 AAAA\nevil ssh-rsa AAAA",
            "host ssh-dss AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabc",
            "# comment",
            "",
        )
        assertEquals(listOf(good, lines[1]), files.acceptableKnownLines(lines))
    }

    @Test
    fun `known_hosts is appended not replaced, and a missing final newline is repaired`() {
        files.sshDir.mkdirs()
        files.knownHosts.writeText("old ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabc")
        files.appendKnownHosts(listOf("new ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsxyz", "garbage"))
        assertEquals(
            "old ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabc\nnew ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsxyz\n",
            files.knownHosts.readText(),
        )
        files.appendKnownHosts(listOf("garbage"))
        assertEquals("nothing acceptable → nothing written", 2, files.knownHosts.readLines().size)
    }

    @Test
    fun `tunnels persist to a file under the user's config folder`() {
        val t = Tunnel("abcd1234", "DB", TunnelKind.LOCAL, "prod", 5432, "db", 5432)
        files.writeTunnels(listOf(t))
        assertEquals(listOf(t), files.readTunnels())
        assertTrue(files.tunnelsFile.path.endsWith("root/.config/pocketshell/network-tunnels.txt"))
    }

    @Test
    fun `guest paths cannot escape the rootfs`() {
        File(root, "root/site").mkdirs()
        assertNotNull(files.hostPath("/root/site"))
        assertEquals(File(root, "root/site").canonicalPath, files.hostPath("/root/site/")!!.canonicalPath)
        assertNull(files.hostPath("relative"))
        // a symlink pointing outside the rootfs resolves outside → refused
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            Files.createSymbolicLink(File(root, "root/evil").toPath(), outside.toPath())
            assertNull(files.hostPath("/root/evil"))
        } finally { outside.deleteRecursively() }
    }

    @Test
    fun `the folder scan sees buried secrets and reports truncation`() {
        val d = File(root, "root/site/a/b").apply { mkdirs() }
        File(d, ".env").writeText("x"); File(root, "root/site/index.html").writeText("x")
        val (names, truncated) = files.scanNames(File(root, "root/site"))
        assertTrue(names.contains(".env")); assertTrue(names.contains("index.html")); assertFalse(truncated)
        assertNotNull("a buried .env makes the folder unshareable", ShareGuard.refusal("/root/site", names, truncated))
        val many = File(root, "root/many").apply { mkdirs() }
        repeat(30) { File(many, "f$it").writeText("") }
        assertTrue(files.scanNames(many, maxEntries = 10).second)
    }
}
