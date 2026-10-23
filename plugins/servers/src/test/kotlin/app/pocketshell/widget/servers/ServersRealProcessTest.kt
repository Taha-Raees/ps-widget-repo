package app.pocketshell.widget.servers

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The whole chain on REAL processes and the machine's real /proc: scanner -> CPU measurement -> classification -> idle
 * tracking -> guards -> identity re-check -> an actual SIGTERM. Fake data cannot prove that the readings are right, so
 * this is the audit's ground truth. Skipped where /proc or a POSIX shell is unavailable.
 */
class ServersRealProcessTest {

    private val spawned = ArrayList<Process>()
    private val pidFiles = ArrayList<File>()

    @After
    fun cleanup() {
        spawned.forEach { runCatching { it.destroyForcibly() } }
        pidFiles.forEach { f -> runCatching { f.readText().trim().toIntOrNull()?.let { ProcessBuilder("kill", "-9", "$it").start().waitFor() } }; f.delete() }
    }

    private fun env() {
        assumeTrue("needs /proc", File("/proc/self/stat").canRead())
        assumeTrue("needs sh", File("/bin/sh").exists())
        assumeTrue("needs setsid", File("/usr/bin/setsid").exists() || File("/bin/setsid").exists() || File("/usr/bin/setsid").exists())
    }

    private fun spawn(vararg cmd: String): Process = ProcessBuilder(*cmd).redirectErrorStream(true).start().also { spawned += it }

    private class Child(val process: Process, val pid: Int)

    /** Run [script] under sh and learn its pid portably: the shell prints `$$`, and `exec` keeps that pid. */
    private fun child(script: String): Child {
        val p = spawn("sh", "-c", "echo \$\$; $script")
        val pid = p.inputStream.bufferedReader().readLine().trim().toInt()
        return Child(p, pid)
    }

    private fun myPid(): Int = File("/proc/self/stat").readText().substringBefore(' ').trim().toInt()

    /** A fully detached (own session, reparented to init) look-alike of a Gradle daemon; returns its pid. */
    private fun spawnDetachedDaemon(): Int {
        val pidFile = File.createTempFile("daemon", ".pid").also { pidFiles += it }
        spawn(
            "sh", "-c",
            "setsid sh -c 'echo \$\$ > ${pidFile.path}; sleep 600; : # org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.9.9' </dev/null >/dev/null 2>&1 &",
        ).waitFor(5, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && pidFile.readText().isBlank()) Thread.sleep(50)
        return pidFile.readText().trim().toInt()
    }

    private fun alive(pid: Int) = File("/proc/$pid/stat").let { f -> f.exists() && runCatching { f.readText().substringAfterLast(')').trim().first() != 'Z' }.getOrDefault(false) }

    @Test
    fun `a real process is read correctly - pid, parent, name, argv, state, memory, cwd, identity`() {
        env()
        val p = child("exec sleep 300")
        val scanner = ProcessScanner()
        Thread.sleep(300)
        val mine = myPid()
        val rec = scanner.scan().records.firstOrNull { it.pid == p.pid }
        assertNotNull("the child must be visible to the scanner", rec)
        rec!!
        assertEquals(mine, rec.ppid)
        assertEquals("sleep", rec.comm)
        assertTrue("argv is read in full: ${rec.argv}", rec.argv?.endsWith("sleep 300") == true)
        assertTrue("a sleeping process", rec.state == 'S')
        assertTrue("memory is a plausible positive number: ${rec.rssBytes}", rec.rssBytes in 1..(512L * 1024 * 1024))
        assertTrue("identity is stat's starttime", rec.startTicks > 0)
        assertNotNull(rec.cwd)
        val identity = ProcIdentityCheck.read(rec.pid)
        assertEquals("the identity check agrees with the scan", rec.startTicks, identity?.startTicks)
        assertFalse(identity!!.infrastructure)
    }

    @Test
    fun `cpu percent is right - a busy loop is high, a sleeper is near zero`() {
        env()
        val busy = child("while :; do :; done")
        val idle = child("exec sleep 300")
        val scanner = ProcessScanner()
        scanner.scan(); Thread.sleep(1_200)
        val snap = scanner.scan()
        val busyCpu = snap.cpu[busy.pid]
        val idleCpu = snap.cpu[idle.pid]
        assertNotNull(busyCpu); assertNotNull(idleCpu)
        assertTrue("a spinning shell must read high, got $busyCpu", busyCpu!! > 30f)
        assertTrue("a sleeper must read ~0, got $idleCpu", idleCpu!! < 1f)
        assertTrue("never beyond physical possibility", busyCpu <= 100f * Runtime.getRuntime().availableProcessors() + 1)
    }

    @Test
    fun `a process that exits disappears from the next scan and a new one is picked up`() {
        env()
        val scanner = ProcessScanner()
        val a = child("exec sleep 300"); Thread.sleep(200)
        assertTrue(scanner.scan().records.any { it.pid == a.pid })
        a.process.destroyForcibly().waitFor(); Thread.sleep(300)
        assertFalse("a dead process must vanish", scanner.scan().records.any { it.pid == a.pid && it.state != 'Z' })
        val b = child("exec sleep 301"); Thread.sleep(200)
        assertTrue(scanner.scan().records.any { it.pid == b.pid })
    }

    @Test
    fun `the detached daemon look-alike is classified as a build daemon, detached and reparented`() {
        env()
        val pid = spawnDetachedDaemon()
        Thread.sleep(300)
        val view = ProcViews.build(ProcessScanner().scan().let { it.records }, emptyMap(), emptyMap())
        val e = view.byPid[pid]
        assertNotNull("the daemon must be visible", e); e!!
        assertEquals(ProcKind.BUILD, e.kind)
        assertEquals("Gradle daemon 9.9.9", e.label)
        assumeTrue("needs a no-tty, reparented process in this environment (tty=${e.rec.tty} ppid=${e.rec.ppid})", e.rec.tty == 0 && e.reparented)
        assertEquals(Survival.DETACHED, e.survival)
        assertTrue(e.daemonized)
        assertTrue("it outlives the terminal", view.outlive.any { it.pid == pid })
        assertTrue(view.background.any { it.pid == pid })
    }

    @Test
    fun `end to end - a really idle detached daemon is found idle, passes the guards, is re-identified and stopped`() {
        env()
        val pid = spawnDetachedDaemon()
        Thread.sleep(300)
        val scanner = ProcessScanner(); val tracker = IdleTracker(); val ledger = CleanupLedger()
        val settings = CleanupSettings(builds = CleanupSettings.DEFAULT_BUILDS.copy(mode = CleanupMode.AUTO, idleMinutes = 30))
        val t0 = 10_000_000L
        // Real scans give real CPU readings; the clock is advanced by hand so thirty "minutes" pass in a moment.
        var view = ProcViews.build(scanner.scan().records, scanner.scan().cpu, emptyMap())
        val e0 = view.byPid[pid]
        assumeTrue("needs a no-tty, reparented process here", e0 != null && e0.rec.tty == 0 && e0.reparented)
        for (k in 0..31) {
            Thread.sleep(300)
            val snap = scanner.scan()
            view = ProcViews.build(snap.records, snap.cpu, emptyMap())
            tracker.observe(view, t0 + k * 60_000L)
        }
        val now = t0 + 31 * 60_000L
        val candidates = Cleanup.candidates(view, tracker, ledger, settings, now)
        val c = candidates.firstOrNull { it.entry.pid == pid }
        assertNotNull("the idle daemon must be a candidate", c); c!!
        assertTrue("idle for 30+ minutes of (simulated) observations", c.due)
        assertEquals(CleanupRule.BUILDS, c.rule)

        val signaller = object : CleanupSignaller {
            override fun identity(pid: Int) = ProcIdentityCheck.read(pid)
            override fun terminate(pid: Int): Boolean = ProcessBuilder("kill", "-TERM", "$pid").start().waitFor() == 0
            override fun openSockets(pid: Int): Int? = 0
        }
        val log = CleanupRunner.sweep(candidates.filter { it.entry.pid == pid }, signaller, ledger, now)
        assertTrue("the sweep reports success: $log", log.singleOrNull()?.ok == true)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && alive(pid)) Thread.sleep(50)
        assertFalse("the daemon really stopped", alive(pid))
    }

    @Test
    fun `a busy detached daemon is NOT idle and is left alone`() {
        env()
        val pidFile = File.createTempFile("busy", ".pid").also { pidFiles += it }
        spawn("sh", "-c", "setsid sh -c 'echo \$\$ > ${pidFile.path}; while :; do :; done # org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.9.9' </dev/null >/dev/null 2>&1 &").waitFor(5, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && pidFile.readText().isBlank()) Thread.sleep(50)
        val pid = pidFile.readText().trim().toInt()
        val scanner = ProcessScanner(); val tracker = IdleTracker()
        scanner.scan()
        for (k in 0..5) {
            Thread.sleep(400)
            val snap = scanner.scan()
            val v = ProcViews.build(snap.records, snap.cpu, emptyMap())
            assumeTrue("needs a no-tty, reparented process here", v.byPid[pid]?.let { it.rec.tty == 0 && it.reparented } == true)
            tracker.observe(v, k * 60_000L)
        }
        val v = ProcViews.build(scanner.scan().records, scanner.scan().cpu, emptyMap())
        assertNull("a process burning CPU must never count as idle", v.byPid[pid]?.let { tracker.idleForMs(it, 6 * 60_000L) })
    }

    @Test
    fun `a process attached to the test's own children tree is not a candidate`() {
        env()
        val p = child("exec sleep 300"); Thread.sleep(200)
        val snap = ProcessScanner().scan()
        val v = ProcViews.build(snap.records, snap.cpu, emptyMap())
        val e = v.byPid[p.pid]!!
        assertFalse("its parent (this test run) is alive: it is a child job, not a daemon", e.reparented)
        assertNotNull(Cleanup.veto(e, v, CleanupRule.HEAVY, CleanupSettings()))
    }
}
