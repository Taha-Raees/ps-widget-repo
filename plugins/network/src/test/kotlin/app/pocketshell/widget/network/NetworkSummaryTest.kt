package app.pocketshell.widget.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSummaryTest {

    private fun key(n: String) = KeyInfo(n, "ED25519", 256, "SHA256:x", "", "ssh-ed25519 AAAA", true, false, false)
    private fun snap(hosts: Int = 0, keys: Int = 0, tunnels: List<Tunnel> = emptyList(), ifaces: List<Iface> = emptyList()) = LocalSnapshot(
        SshConfig.parse((1..hosts).joinToString("\n") { "Host h$it\n    HostName x$it.com" }),
        (1..keys).map { key("k$it") }, tunnels, ifaces,
    )

    @Test
    fun `status line prefers wifi and counts the rest`() {
        assertEquals("reading…", statusLine(null))
        assertEquals("offline", statusLine(emptyList()))
        assertEquals("wlan0 192.168.1.20", statusLine(listOf(Iface("wlan0", "192.168.1.20"))))
        assertEquals("wlan0 192.168.1.20 · +1", statusLine(listOf(Iface("rmnet0", "10.1.1.1"), Iface("wlan0", "192.168.1.20"))))
    }

    @Test
    fun `the four numbers`() {
        val t = listOf(Tunnel("aaaa1111", "a", TunnelKind.LOCAL, "h", 8080, "x", 80), Tunnel("bbbb2222", "b", TunnelKind.DYNAMIC, "h", 1080))
        val items = summaryItems(snap(hosts = 3, keys = 2, tunnels = t), mapOf("aaaa1111" to TunnelState.Up(1), "bbbb2222" to TunnelState.Stopped), ShareState.Off)
        assertEquals(listOf("3", "2", "1/2", "off"), items.map { it.value })
        assertEquals(listOf("hosts", "keys", "tunnels up", "sharing"), items.map { it.label })
        assertTrue(items[2].hot); assertFalse(items[3].hot)
        val sharing = summaryItems(snap(), emptyMap(), ShareState.Running("http://x/", "/d", 1, "1.1.1.1", 8000))
        assertEquals("ON", sharing[3].value); assertTrue(sharing[3].hot)
        assertEquals(listOf("0", "0", "0/0", "off"), summaryItems(null, emptyMap(), ShareState.Off).map { it.value })
    }

    @Test
    fun `first-run hints go in the order a new user needs`() {
        assertNull(hintFor(null))
        assertTrue(hintFor(snap())!!.contains("create a key"))
        assertTrue(hintFor(snap(hosts = 1))!!.contains("No SSH key"))
        assertTrue(hintFor(snap(keys = 1))!!.contains("No saved hosts"))
        assertNull(hintFor(snap(hosts = 1, keys = 1)))
    }

    @Test
    fun `layout is decided by height first and the rail needs width`() {
        assertEquals(NetLayout.FULL, NetLayout.from(410f, 800f))   // portrait phone, maximized
        assertEquals(NetLayout.CARD, NetLayout.from(410f, 260f))   // the Home card
        assertEquals(NetLayout.CARD, NetLayout.from(300f, 800f))   // too narrow for the studio
        assertEquals(NetLayout.FULL, NetLayout.from(800f, 350f))   // landscape
        assertEquals(NetLayout.CARD, NetLayout.from(800f, 250f))
        assertFalse(NetLayout.usesSideRail(410f)); assertTrue(NetLayout.usesSideRail(600f))
    }
}
