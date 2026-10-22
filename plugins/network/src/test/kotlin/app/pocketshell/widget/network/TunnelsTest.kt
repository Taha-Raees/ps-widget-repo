package app.pocketshell.widget.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelsTest {

    private val local = Tunnel("abcd1234", "Postgres", TunnelKind.LOCAL, "prod", 5432, "db.internal", 5432)

    @Test
    fun `local forward binds loopback only and builds a shell-free argv`() {
        val argv = Tunnels.sshArgv(local)
        assertEquals("ssh", argv.first())
        assertEquals(listOf("-L", "127.0.0.1:5432:db.internal:5432", "prod"), argv.takeLast(3))
        assertTrue("BatchMode so it can never hang on a prompt", argv.containsAll(listOf("-o", "BatchMode=yes")))
        assertTrue(argv.contains("ExitOnForwardFailure=yes"))
        assertTrue(argv.contains("StrictHostKeyChecking=yes"))
        assertFalse("never binds all interfaces", argv.any { it.startsWith("0.0.0.0") || it.startsWith("*:") })
    }

    @Test
    fun `socks and remote forms`() {
        val socks = Tunnel("abcd1234", "Proxy", TunnelKind.DYNAMIC, "me@vps.example.com", 1080)
        assertEquals(listOf("-D", "127.0.0.1:1080", "me@vps.example.com"), Tunnels.sshArgv(socks).takeLast(3))
        val remote = Tunnel("abcd1234", "Expose", TunnelKind.REMOTE, "vps", 8080, "localhost", 3000)
        assertEquals(listOf("-R", "127.0.0.1:8080:localhost:3000", "vps"), Tunnels.sshArgv(remote).takeLast(3))
    }

    @Test
    fun `hostile values are refused before they can reach argv`() {
        val bad = listOf(
            local.copy(via = "-oProxyCommand=evil"),
            local.copy(via = "prod; reboot"),
            local.copy(via = "a b"),
            local.copy(destHost = "x:y"),
            local.copy(destHost = "x; id"),
            local.copy(bindPort = 80),
            local.copy(bindPort = 70000),
            local.copy(destPort = 0),
            local.copy(name = "a|b"),
            local.copy(name = "a\nb"),
            local.copy(name = ""),
            local.copy(id = "../x"),
        )
        bad.forEach { assertNotNull("must refuse $it", Tunnels.problem(it)) }
        assertNull(Tunnels.problem(local))
        assertNull("a remote forward may use a low REMOTE port", Tunnels.problem(Tunnel("abcd1234", "r", TunnelKind.REMOTE, "vps", 80, "localhost", 3000)))
    }

    @Test
    fun `the pid wrapper keeps every variable out of the script text`() {
        val w = Tunnels.withPidFile("/tmp/x'; rm -rf /; '", Tunnels.sshArgv(local))
        assertEquals(listOf("sh", "-c"), w.take(2))
        assertEquals("echo \$\$ > \"\$1\" && shift && exec \"\$@\"", w[2])
        assertEquals("/tmp/x'; rm -rf /; '", w[4])
        assertEquals(Tunnels.sshArgv(local), w.drop(5))
    }

    @Test
    fun `storage round trips and skips damaged or unsafe lines`() {
        val list = listOf(local, Tunnel("zzzz9999", "Socks", TunnelKind.DYNAMIC, "vps", 1080))
        assertEquals(list.map { it.copy(destHost = if (it.kind == TunnelKind.DYNAMIC) "localhost" else it.destHost) }, Tunnels.decode(Tunnels.encode(list)))
        val mixed = "garbage\nabcd1234|ok|LOCAL|prod|5432|db|5432\nabcd1234|x|BOGUS|prod|5432|db|5432\nabcd1234|x|LOCAL|prod;x|5432|db|5432\nabcd1234|x|LOCAL|prod|80|db|5432\n"
        assertEquals(listOf("ok"), Tunnels.decode(mixed).map { it.name })
        assertTrue(Tunnels.decode(null).isEmpty())
        assertTrue("an invalid tunnel is never written", Tunnels.encode(listOf(local.copy(via = "a b"))).isEmpty())
    }

    @Test
    fun `only web-ish local forwards get an open button`() {
        assertEquals("http://127.0.0.1:8080", Tunnels.openUrl(local.copy(bindPort = 8080, destPort = 80)))
        assertNull(Tunnels.openUrl(local))
        assertNull(Tunnels.openUrl(Tunnel("abcd1234", "s", TunnelKind.DYNAMIC, "v", 1080)))
    }

    @Test
    fun `ids are unique enough and valid`() {
        val ids = (1..200).map { Tunnels.newId() }
        assertEquals(200, ids.toSet().size)
        ids.forEach { assertTrue(Regex("^[a-z0-9]{8}$").matches(it)) }
    }
}
