package app.pocketshell.widget.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConfigTest {

    private val sample = """
        # my machines
        Include ~/.ssh/config.d/*

        Host *
            ServerAliveInterval 60

        Host pi
            HostName 192.168.1.20
            User pi
            Port 2222
            # the old key
            IdentityFile ~/.ssh/id_ed25519
            ForwardAgent yes

        Host work-a work-b
            HostName work.example.com

        Match host db
            User admin

        Host github
            HostName github.com
            User git
    """.trimIndent() + "\n"

    @Test
    fun `plain single-alias hosts are modelled, the rest is read-only`() {
        val cfg = SshConfig.parse(sample)
        assertEquals(listOf("pi", "github"), cfg.hosts.map { it.alias })
        val pi = cfg.hosts.first { it.alias == "pi" }
        assertEquals("192.168.1.20", pi.hostName)
        assertEquals("pi", pi.user)
        assertEquals(2222, pi.port)
        assertEquals("~/.ssh/id_ed25519", pi.identityFile)
        assertEquals("pi@192.168.1.20:2222", pi.summary)
        assertTrue("unmodelled directives and comments are kept", pi.extra.containsAll(listOf("ForwardAgent yes", "# the old key")))
        // Host *, the two-alias block and Match are shown but not editable.
        assertEquals(3, cfg.readOnly.size)
        assertTrue(cfg.readOnly.any { it.isMatch })
    }

    @Test
    fun `editing one host changes only its lines`() {
        val edited = (SshConfig.upsert(sample, SshHost("pi", "10.0.0.5", "pi", 22, "~/.ssh/id_ed25519", extra = listOf("ForwardAgent yes"))) as SshConfig.Edit.Done).text
        val before = sample.lines()
        val after = edited.lines()
        // everything outside the block is byte-identical
        assertEquals(before.takeWhile { it != "Host pi" }, after.takeWhile { it != "Host pi" })
        assertEquals(before.dropWhile { it != "Host work-a work-b" }, after.dropWhile { it != "Host work-a work-b" })
        assertTrue(edited.contains("    HostName 10.0.0.5"))
        assertFalse("the old value is gone", edited.contains("192.168.1.20"))
        assertTrue("an unmodelled directive survives the edit", edited.contains("ForwardAgent yes"))
    }

    @Test
    fun `adding appends after a blank line and never disturbs the file`() {
        val added = (SshConfig.upsert(sample, SshHost("nas", "nas.local", "admin")) as SshConfig.Edit.Done).text
        assertTrue(added.startsWith(sample.trimEnd('\n')))
        assertTrue(added.endsWith("Host nas\n    HostName nas.local\n    User admin\n"))
        assertEquals(listOf("pi", "github", "nas"), SshConfig.parse(added).hosts.map { it.alias })
        // into an empty file
        assertEquals("Host a\n    HostName b.c\n", (SshConfig.upsert("", SshHost("a", "b.c")) as SshConfig.Edit.Done).text)
    }

    @Test
    fun `removing deletes exactly the block`() {
        val removed = (SshConfig.remove(sample, "pi") as SshConfig.Edit.Done).text
        val cfg = SshConfig.parse(removed)
        assertEquals(listOf("github"), cfg.hosts.map { it.alias })
        assertTrue(removed.contains("Host *"))
        assertTrue(removed.contains("Match host db"))
        assertFalse(removed.contains("192.168.1.20"))
        assertFalse("no double blank line is left behind", removed.contains("\n\n\n"))
    }

    @Test
    fun `edit remove round trip leaves other hosts untouched`() {
        val a = (SshConfig.upsert(sample, SshHost("tmp", "t.example.com")) as SshConfig.Edit.Done).text
        val b = (SshConfig.remove(a, "tmp") as SshConfig.Edit.Done).text
        assertEquals(sample, b)
    }

    @Test
    fun `an alias that belongs to a block we do not edit is refused`() {
        assertTrue(SshConfig.upsert(sample, SshHost("work-a", "x.y")) is SshConfig.Edit.Refused)
        assertTrue(SshConfig.remove(sample, "work-a") is SshConfig.Edit.Refused)
        assertTrue(SshConfig.remove(sample, "nope") is SshConfig.Edit.Refused)
    }

    @Test
    fun `values that could inject directives are refused`() {
        val bad = listOf(
            SshHost("a", hostName = "x.com\n    ProxyCommand evil"),
            SshHost("a", user = "root\nProxyCommand x"),
            SshHost("a\nHost b"),
            SshHost("*"),
            SshHost("a b"),
            SshHost("a", hostName = "x y"),
            SshHost("a", port = 0),
            SshHost("a", port = 70000),
            SshHost("a", identityFile = "/etc/shadow"),
            SshHost("a", identityFile = "~/.ssh/../../etc/passwd"),
            SshHost("a", proxyJump = "x;rm -rf /"),
            SshHost("a", extra = listOf("ok\nProxyCommand evil")),
        )
        for (h in bad) {
            assertNotNull("must refuse $h", SshConfig.problem(h))
            assertTrue(SshConfig.upsert(sample, h) is SshConfig.Edit.Refused)
        }
        assertNull(SshConfig.problem(SshHost("pi", "192.168.1.20", "pi", 2222, "~/.ssh/id_ed25519", "jump1,jump2")))
        assertNull(SshConfig.problem(SshHost("v6", "fe80::1")))
    }

    @Test
    fun `key equals value syntax and quotes are understood`() {
        val h = SshConfig.parse("Host a\n  HostName=\"x.example.com\"\n  Port = 2200\n").hosts.single()
        assertEquals("x.example.com", h.hostName)
        assertEquals(2200, h.port)
    }

    @Test
    fun `empty and comment-only files parse to nothing`() {
        assertTrue(SshConfig.parse("").blocks.isEmpty())
        assertTrue(SshConfig.parse("# nothing\n\n").blocks.isEmpty())
    }
}
