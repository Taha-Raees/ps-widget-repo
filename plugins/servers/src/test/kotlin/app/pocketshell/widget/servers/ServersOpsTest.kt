package app.pocketshell.widget.servers

import app.pocketshell.widget.probe.ServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stop op's discipline: refuse first, confirm with the exact command, honest outcome. */
class ServersOpsTest {

    private fun server(pid: Int?) = ServerInfo(
        port = 3000,
        pid = pid,
        processName = "node",
        cwd = null,
        attribution = if (pid == null) {
            ServerInfo.Attribution.CMDLINE_MATCH
        } else {
            ServerInfo.Attribution.ACCEPTOR_MATCH
        },
    )

    @Test
    fun `an owner-less endpoint is refused before any dialog`() {
        assertNotNull(ServersOps.refusal(server(pid = null)))
        assertNull(ServersOps.refusal(server(pid = 4321)))
    }

    @Test
    fun `argv keeps the pid positional — never interpolated into the script`() {
        val argv = ServersOps.argv(4321)
        assertEquals(listOf("/bin/sh", "-c", "kill \"\$1\"", "sh", "4321"), argv)
        assertFalse(argv[2].contains("4321"))
        assertTrue(argv.last() == "4321")
    }

    @Test
    fun `display command is what the user reads`() {
        assertEquals("kill 4321", ServersOps.displayCommand(4321))
    }

    @Test
    fun `confirm spec names port, process, pid and the graceful signal`() {
        val spec = ServersOps.confirmSpec(ServersOps.StopRequest(3000, 4321, "node"))
        assertTrue(spec.title.contains(":3000"))
        assertTrue(spec.body.contains("node"))
        assertTrue(spec.body.contains("4321"))
        assertTrue(spec.body.contains("SIGTERM"))
        assertEquals("STOP", spec.confirmLabel)
    }

    @Test
    fun `outcome lines state what actually happened`() {
        val req = ServersOps.StopRequest(3000, 4321, "node")
        val ok = ServersOps.Outcome(req, ServersOps.argv(4321), 0, null, null, 0)
        assertTrue(ok.ok)
        assertEquals("Stopped :3000 (kill 4321)", ok.line())

        val exited = ServersOps.Outcome(req, ServersOps.argv(4321), 1, "no such process", null, 0)
        assertFalse(exited.ok)
        assertTrue(exited.line().contains("exited with 1"))
        assertTrue(exited.line().contains("no such process"))

        val notRun = ServersOps.Outcome(req, emptyList(), null, null, "guest not ready", 0)
        assertFalse(notRun.ok)
        assertEquals("not run — guest not ready", notRun.line())
    }
}
