package app.pocketshell.widget.servers

import app.pocketshell.widget.probe.ServerInfo
import app.pocketshell.widget.servers.ServersOps.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The signal op's discipline: refuse first, confirm with the exact command, honest outcome. */
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

    private val serverReq = ServersOps.StopRequest(pid = 4321, processName = "node", port = 3000)

    @Test
    fun `an owner-less endpoint is refused before any dialog`() {
        assertNotNull(ServersOps.refusal(server(pid = null)))
        assertNull(ServersOps.refusal(server(pid = 4321)))
    }

    @Test
    fun `init and kernel pids are never signalled`() {
        assertNotNull(ServersOps.refusal(1))
        assertNotNull(ServersOps.refusal(0))
        assertNotNull(ServersOps.refusal(-1))
        assertNull(ServersOps.refusal(2))
    }

    @Test
    fun `argv keeps pid and signal positional - never interpolated into the script`() {
        val argv = ServersOps.argv(4321)
        assertEquals(listOf("/bin/sh", "-c", "kill -s \"\$2\" \"\$1\"", "sh", "4321", "TERM"), argv)
        assertFalse(argv[2].contains("4321"))
        assertFalse(argv[2].contains("TERM"))
        assertEquals(listOf("4321", "KILL"), ServersOps.argv(4321, Signal.KILL).takeLast(2))
    }

    @Test
    fun `display command is what the user reads`() {
        assertEquals("kill -s TERM 4321", ServersOps.displayCommand(4321))
        assertEquals("kill -s KILL 4321", ServersOps.displayCommand(4321, Signal.KILL))
    }

    @Test
    fun `stop confirm names the port, process, pid and the graceful signal`() {
        val spec = ServersOps.confirmSpec(serverReq)
        assertEquals("Stop :3000?", spec.title)
        assertTrue(spec.body.contains("node"))
        assertTrue(spec.body.contains("4321"))
        assertTrue(spec.body.contains(":3000"))
        assertTrue(spec.body.contains("SIGTERM"))
        assertEquals("STOP", spec.confirmLabel)
        assertEquals("kill -s TERM 4321", spec.command)
    }

    @Test
    fun `force-kill is worded as the stronger thing it is`() {
        val spec = ServersOps.confirmSpec(serverReq.copy(signal = Signal.KILL))
        assertTrue(spec.title.startsWith("Force-kill"))
        assertTrue(spec.body.contains("SIGKILL"))
        assertTrue(spec.body.contains("cannot be caught"))
        assertEquals("FORCE KILL", spec.confirmLabel)
    }

    @Test
    fun `a process without a port is named by what it is`() {
        val spec = ServersOps.confirmSpec(ServersOps.StopRequest(pid = 13925, processName = "Gradle daemon 8.14.5"))
        assertEquals("Stop Gradle daemon 8.14.5?", spec.title)
        assertFalse(spec.body.contains("listening"))
    }

    @Test
    fun `killing a terminal shell warns that the terminal ends`() {
        val spec = ServersOps.confirmSpec(ServersOps.StopRequest(pid = 17417, processName = "sh", endsTerminal = true))
        assertTrue(spec.body.contains("terminal session ends"))
        assertFalse(ServersOps.confirmSpec(serverReq).body.contains("terminal session ends"))
    }

    @Test
    fun `outcome lines state what actually happened`() {
        val ok = ServersOps.Outcome(serverReq, ServersOps.argv(4321), 0, null, null, 0)
        assertTrue(ok.ok)
        assertEquals("Stopped :3000 (kill -s TERM 4321)", ok.line())

        val killed = ServersOps.Outcome(serverReq.copy(signal = Signal.KILL), ServersOps.argv(4321, Signal.KILL), 0, null, null, 0)
        assertEquals("Killed :3000 (kill -s KILL 4321)", killed.line())

        val exited = ServersOps.Outcome(serverReq, ServersOps.argv(4321), 1, "no such process", null, 0)
        assertFalse(exited.ok)
        assertTrue(exited.line().contains("exited with 1"))
        assertTrue(exited.line().contains("no such process"))

        // A refusal reads once: the outcome line owns the "not run — " prefix.
        val refused = ServersOps.Outcome(serverReq, emptyList(), null, null, ServersOps.refusal(1)!!, 0)
        assertEquals(1, Regex("not run").findAll(refused.line()).count())

        val notRun = ServersOps.Outcome(serverReq, emptyList(), null, null, "guest not ready", 0)
        assertFalse(notRun.ok)
        assertEquals("not run — guest not ready", notRun.line())
    }

    @Test
    fun `a delivered signal on a process that is still there is not called stopped`() {
        val sent = ServersOps.Outcome(serverReq, ServersOps.argv(4321), 0, null, null, 0, stillRunning = true)
        assertTrue(sent.ok)
        assertTrue(sent.line().startsWith("Sent TERM to :3000"))
        assertTrue(sent.line().contains("still running"))
        assertTrue(sent.line().contains("graceful shutdown"))
        assertFalse(sent.line().startsWith("Stopped"))
        // A SIGKILL that did not take is not "a graceful shutdown" either.
        val kill = ServersOps.Outcome(serverReq.copy(signal = Signal.KILL), ServersOps.argv(4321, Signal.KILL), 0, null, null, 0, stillRunning = true)
        assertFalse(kill.line().contains("graceful"))
        assertTrue(kill.line().contains("still running"))
    }
}
