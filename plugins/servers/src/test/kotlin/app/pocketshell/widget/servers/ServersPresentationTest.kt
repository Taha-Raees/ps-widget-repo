package app.pocketshell.widget.servers

import app.pocketshell.widget.probe.ServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filter, sort, and the display formatting every screen shares. */
class ServersPresentationTest {

    private fun server(
        port: Int,
        name: String? = "node",
        cwd: String? = "/home/u/web",
    ) = ServerInfo(
        port = port,
        pid = port,
        processName = name,
        cwd = cwd,
        attribution = ServerInfo.Attribution.ACCEPTOR_MATCH,
    )

    // ------------------------------------------------------------ filtering

    @Test
    fun `blank query filters nothing`() {
        val servers = listOf(server(3000), server(5173))
        assertEquals(servers, ServersPresentation.filter(servers, "  "))
    }

    @Test
    fun `query matches port, name or path case-insensitively`() {
        val servers = listOf(server(3000, "node", "/home/u/web"), server(8000, "python", "/srv/api"))
        assertEquals(listOf(3000), ServersPresentation.filter(servers, "3000").map { it.port })
        assertEquals(listOf(8000), ServersPresentation.filter(servers, "PY").map { it.port })
        assertEquals(listOf(3000), ServersPresentation.filter(servers, "web").map { it.port })
        assertTrue(ServersPresentation.filter(servers, "zzz").isEmpty())
    }

    // --------------------------------------------------------------- sorting

    @Test
    fun `PORT is the stable default`() {
        val sorted = ServersPresentation.sort(
            listOf(server(5173), server(3000)),
            SortMode.PORT,
            lastLatencyMs = { null },
            lastOk = { null },
        )
        assertEquals(listOf(3000, 5173), sorted.map { it.port })
    }

    @Test
    fun `NAME sorts nulls last then alphabetically`() {
        val sorted = ServersPresentation.sort(
            listOf(server(8000, "python"), server(3000, null), server(5173, "vite")),
            SortMode.NAME,
            lastLatencyMs = { null },
            lastOk = { null },
        )
        // "python" < "vite"; the unnamed server (3000) goes last.
        assertEquals(listOf(8000, 5173, 3000), sorted.map { it.port })
    }

    @Test
    fun `LATENCY puts unknowns last and orders the rest ascending`() {
        val sorted = ServersPresentation.sort(
            listOf(server(3000), server(5173), server(8000)),
            SortMode.LATENCY,
            lastLatencyMs = { if (it == 5173) 5L else if (it == 3000) 20L else null },
            lastOk = { null },
        )
        assertEquals(listOf(5173, 3000, 8000), sorted.map { it.port })
    }

    @Test
    fun `STATUS puts the failing server first`() {
        val sorted = ServersPresentation.sort(
            listOf(server(3000), server(5173), server(8000)),
            SortMode.STATUS,
            lastLatencyMs = { 10L },
            lastOk = { it != 8000 }, // only :8000 is failing
        )
        assertEquals(8000, sorted.first().port)
    }

    // ------------------------------------------------------------ formatting

    @Test
    fun `byte formatting stays human`() {
        assertEquals("84 KB", ServersPresentation.formatBytes(84L * 1024))
        assertEquals("20 MB", ServersPresentation.formatBytes(20L * 1024 * 1024))
        assertEquals("1.5 GB", ServersPresentation.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("—", ServersPresentation.formatBytes(-1))
    }

    @Test
    fun `uptime compacts like a status page`() {
        assertEquals("45s", ServersPresentation.formatUptime(45.0))
        assertEquals("2h 14m", ServersPresentation.formatUptime((2 * 3600 + 14 * 60).toDouble()))
        assertEquals("3d 4h", ServersPresentation.formatUptime((3 * 86_400 + 4 * 3_600).toDouble()))
        assertEquals("—", ServersPresentation.formatUptime(-5.0))
    }

    @Test
    fun `load, fraction and cpu format to their places`() {
        assertEquals("0.52", ServersPresentation.formatLoad(0.52f))
        assertEquals("43%", ServersPresentation.formatFraction(0.43f))
        assertEquals("—", ServersPresentation.formatFraction(null))
        assertEquals("2.3%", ServersPresentation.formatCpu(2.3f))
        assertEquals("—", ServersPresentation.formatCpu(null))
        assertEquals("12 ms", ServersPresentation.formatLatency(12L))
        assertEquals("—", ServersPresentation.formatLatency(null))
    }

    // ---------------------------------------------------------- cwd display

    @Test
    fun `cwd inside the rootfs renders as a guest path`() {
        val rootfs = "/data/app/files/rootfs"
        assertEquals(
            "/home/u/app (guest)",
            ServersPresentation.displayCwdPaths("$rootfs/home/u/app", "/data/app", rootfs),
        )
        assertEquals(
            "/ (guest root)",
            ServersPresentation.displayCwdPaths(rootfs, "/data/app", rootfs),
        )
    }

    @Test
    fun `cwd in the app home renders as tilde`() {
        assertEquals(
            "~ (Linux home)",
            ServersPresentation.displayCwdPaths("/data/app/files/home", "/data/app", null),
        )
        assertEquals(
            "~/proj",
            ServersPresentation.displayCwdPaths("/data/app/files/home/proj", "/data/app", null),
        )
        assertEquals(
            "files/other",
            ServersPresentation.displayCwdPaths("/data/app/files/other", "/data/app", null),
        )
    }
}
