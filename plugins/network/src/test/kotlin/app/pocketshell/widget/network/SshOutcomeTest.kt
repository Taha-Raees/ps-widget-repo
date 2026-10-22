package app.pocketshell.widget.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages marked REAL were captured from OpenSSH_10.3 on the device; DOCUMENTED ones are OpenSSH's standard text. */
class SshOutcomeTest {

    private fun c(exit: Int, err: String, out: String = "") = SshOutcome.classify(exit, err, out)

    @Test
    fun `REAL refused`() = assertEquals(SshOutcome.Refused, c(255, "ssh: connect to host 127.0.0.1 port 1: Connection refused\n"))

    @Test
    fun `REAL dns`() = assertEquals(SshOutcome.DnsFailed, c(255, "ssh: Could not resolve hostname nonexistent.invalid: Name does not resolve\n"))

    @Test
    fun `REAL timeout`() = assertEquals(SshOutcome.TimedOut, c(255, "ssh: connect to host 10.255.255.1 port 22: Operation timed out\n"))

    @Test
    fun `REAL unknown host key under strict checking`() =
        assertEquals(SshOutcome.HostKeyUnknown, c(255, "No ED25519 host key is known for github.com and you have requested strict checking.\nHost key verification failed.\n"))

    @Test
    fun `REAL auth failed`() =
        assertEquals(SshOutcome.AuthFailed, c(255, "Warning: Permanently added 'github.com' (ED25519) to the list of known hosts.\ngit@github.com: Permission denied (publickey).\n"))

    @Test
    fun `DOCUMENTED changed host key is loud and is not confused with unknown`() {
        val err = "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @\n" +
            "IT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY!\nHost key verification failed.\n"
        assertEquals(SshOutcome.HostKeyChanged, c(255, err))
        assertEquals(SshOutcome.HostKeyChanged, c(255, "Host key verification failed.\n"))
    }

    @Test
    fun `DOCUMENTED port in use`() {
        val err = "bind [127.0.0.1]:8080: Address already in use\nchannel_setup_fwd_listener_tcpip: cannot listen to port: 8080\nCould not request local forwarding.\n"
        assertEquals(SshOutcome.PortInUse, c(255, err))
    }

    @Test
    fun `DOCUMENTED github greeting counts as success even though exit is 1`() {
        val o = c(1, "Hi octocat! You've successfully authenticated, but GitHub does not provide shell access.\n")
        assertTrue(o is SshOutcome.Ok)
        assertEquals("Hi octocat! You've successfully authenticated, but GitHub does not provide shell access.", (o as SshOutcome.Ok).greeting)
        assertTrue(c(0, "", "Welcome to GitLab, @tester!\n") is SshOutcome.Ok)
    }

    @Test
    fun `a clean exit is ok even with no greeting`() = assertEquals(SshOutcome.Ok(null), c(0, ""))

    @Test
    fun `unknown failures keep the last line and never crash on empty text`() {
        assertEquals(SshOutcome.Other("ssh_dispatch_run_fatal: Connection to 1.2.3.4 port 22: Broken pipe"), c(255, "x\nssh_dispatch_run_fatal: Connection to 1.2.3.4 port 22: Broken pipe\n"))
        assertEquals(SshOutcome.Other("ssh exited with code 7"), c(7, ""))
    }

    @Test
    fun `a missing ssh binary is a sandbox problem not a host problem`() {
        assertTrue(c(127, "sh: ssh: not found\n") is SshOutcome.SandboxProblem)
    }

    @Test
    fun `reachable means the host answered, even if it refused us`() {
        assertTrue(SshOutcome.AuthFailed.reachable)
        assertTrue(SshOutcome.HostKeyChanged.reachable)
        assertTrue(SshOutcome.Ok(null).reachable)
        for (o in listOf(SshOutcome.DnsFailed, SshOutcome.Refused, SshOutcome.TimedOut, SshOutcome.NoRoute)) assertFalse("$o", o.reachable)
    }

    @Test
    fun `every outcome has a sentence`() {
        val all = listOf(SshOutcome.Ok(null), SshOutcome.Ok("Hi x!"), SshOutcome.AuthFailed, SshOutcome.HostKeyUnknown, SshOutcome.HostKeyChanged, SshOutcome.DnsFailed,
            SshOutcome.Refused, SshOutcome.TimedOut, SshOutcome.NoRoute, SshOutcome.PortInUse, SshOutcome.SandboxProblem("x"), SshOutcome.Other("y"))
        all.forEach { assertTrue(it.message().isNotBlank()) }
        assertTrue(SshOutcome.HostKeyChanged.message().startsWith("WARNING"))
    }
}
