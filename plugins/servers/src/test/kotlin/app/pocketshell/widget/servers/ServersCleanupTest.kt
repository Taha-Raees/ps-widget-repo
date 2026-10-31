package app.pocketshell.widget.servers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every guard of the cleanup, one at a time - the danger is stopping something the user meant to keep. */
class ServersCleanupTest {

    private val MB = 1024L * 1024
    private val min = 60_000L

    private val gradleArgv = "/usr/lib/jvm/java-21/bin/java -Xmx512m -cp /root/.gradle/lib/gradle-launcher-8.14.5.jar " +
        "org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.5"
    private val wrapperArgv = "/usr/lib/jvm/java-21/bin/java -classpath gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain build"

    private fun rec(
        pid: Int, comm: String, argv: String?, ppid: Int = 1, tty: Int = 0, hup: Boolean = false, rssMb: Long = 600,
        swapMb: Long = 0, tpgid: Int = -1,
    ) = ProcRecord(
        pid = pid, ppid = ppid, comm = comm, argv = argv, state = 'S', pgrp = pid, sid = pid, tty = tty, tpgid = tpgid, threads = 1,
        cpuTicks = 0, rssBytes = rssMb * MB, swapBytes = swapMb * MB, startedAtMs = 1_000, cwd = "/root", hupIgnored = hup,
        startTicks = pid * 100L,
    )

    private fun view(records: List<ProcRecord>, cpu: Map<Int, Float?> = emptyMap(), ports: Map<Int, List<Int>> = emptyMap()) =
        ProcViews.build(records, records.associate { it.pid to (cpu[it.pid] ?: 0.1f) }, ports)

    private fun settings(builds: CleanupMode = CleanupMode.AUTO, heavy: CleanupMode = CleanupMode.AUTO, keep: Set<String> = emptySet()) =
        CleanupSettings(
            builds = CleanupSettings.DEFAULT_BUILDS.copy(mode = builds),
            heavy = CleanupSettings.DEFAULT_HEAVY.copy(mode = heavy),
            keep = keep,
        )

    private fun entry(v: ProcView, pid: Int) = v.byPid.getValue(pid)

    // ------------------------------------------------------------ the guards

    @Test
    fun `an idle detached gradle daemon is a candidate for the build rule`() {
        val v = view(listOf(rec(10, "java", gradleArgv)))
        assertEquals("Gradle daemon 8.14.5", entry(v, 10).label)
        assertNull(Cleanup.veto(entry(v, 10), v, CleanupRule.BUILDS, settings()))
    }

    @Test
    fun `a gradle daemon is still a candidate although it listens on a loopback port for IPC`() {
        // Real daemons always have a port (the host probe lists it); the guard for servers must not swallow them.
        val v = view(listOf(rec(10, "java", gradleArgv)), ports = mapOf(10 to listOf(41233)))
        assertEquals(ProcKind.BUILD, entry(v, 10).kind)
        assertTrue(entry(v, 10).ports.isNotEmpty())
        assertNull(Cleanup.veto(entry(v, 10), v, CleanupRule.BUILDS, settings()))
    }

    @Test
    fun `a listening non-build process is vetoed even if its kind were something else`() {
        // kind OTHER + port becomes SERVER in the view; the rule must hold at the veto level too.
        val v = view(listOf(rec(11, "python3", "python3 -m http.server 8000")), ports = mapOf(11 to listOf(8000)))
        assertNotNull(Cleanup.veto(entry(v, 11), v, CleanupRule.HEAVY, settings()))
        val forced = entry(v, 11).let { it.copy(identity = it.identity.copy(kind = ProcKind.OTHER)) }
        assertEquals("serves a port", Cleanup.veto(forced, v, CleanupRule.HEAVY, settings()))
    }

    @Test
    fun `only daemons qualify - never the short-lived build clients`() {
        val v = view(listOf(rec(10, "java", wrapperArgv, tty = 0)))
        assertEquals(ProcKind.BUILD, entry(v, 10).kind)
        assertEquals("not a build daemon", Cleanup.veto(entry(v, 10), v, CleanupRule.BUILDS, settings()))
    }

    @Test
    fun `terminal-bound processes are never candidates`() {
        val v = view(listOf(rec(10, "java", gradleArgv, tty = 34816)))
        assertEquals("bound to a terminal", Cleanup.veto(entry(v, 10), v, CleanupRule.BUILDS, settings()))
    }

    @Test
    fun `a nohup job keeps its terminal but is detached - and is still vetoed if it belongs to a live parent`() {
        val v = view(listOf(rec(5, "bash", "bash", ppid = 1, tty = 34816), rec(10, "python3", "python3 job.py", ppid = 5, tty = 34816, hup = true)))
        assertEquals("started from a process that is still running", Cleanup.veto(entry(v, 10), v, CleanupRule.HEAVY, settings()))
    }

    @Test
    fun `anything that serves a port is never stopped by a rule`() {
        val v = view(listOf(rec(10, "node", "node server.js")), ports = mapOf(10 to listOf(3000)))
        assertNotNull(Cleanup.veto(entry(v, 10), v, CleanupRule.HEAVY, settings()))
    }

    @Test
    fun `terminals, session keepers, agents and system helpers are untouchable`() {
        val v = view(
            listOf(
                rec(1, "init", "init"),
                rec(20, "bash", "bash", tty = 34816, tpgid = 20),
                rec(21, "tmux: server", "tmux", tty = 0),
                rec(22, "claude", "claude", tty = 0),
                rec(23, "dbus-daemon", "dbus-daemon --session", tty = 0),
            ),
        )
        for (pid in listOf(20, 21, 22, 23)) {
            assertNotNull("pid $pid must be vetoed", Cleanup.veto(entry(v, pid), v, CleanupRule.HEAVY, settings()))
            assertNotNull("pid $pid must be vetoed for builds too", Cleanup.veto(entry(v, pid), v, CleanupRule.BUILDS, settings()))
        }
    }

    @Test
    fun `transfer remote-access and vcs tools are protected by name`() {
        for (prog in listOf("ssh", "rsync", "rclone", "tar", "zstd", "git", "curl", "wget", "sshd", "autossh", "ffmpeg", "yt-dlp")) {
            val v = view(listOf(rec(10, prog, "/usr/bin/$prog -x", rssMb = 900)))
            assertEquals("$prog must be protected", "protected program ($prog)", Cleanup.veto(entry(v, 10), v, CleanupRule.HEAVY, settings()))
        }
    }

    @Test
    fun `a program the user marked keep is vetoed, with or without its version`() {
        val v = view(listOf(rec(10, "java", gradleArgv)))
        assertEquals("you marked it keep", Cleanup.veto(entry(v, 10), v, CleanupRule.BUILDS, settings(keep = setOf("gradle daemon"))))
        val w = view(listOf(rec(11, "python3", "python3 bot.py")))
        assertEquals("you marked it keep", Cleanup.veto(entry(w, 11), w, CleanupRule.HEAVY, settings(keep = setOf("python bot.py"))))
    }

    @Test
    fun `the memory floor counts resident plus swapped memory`() {
        val small = view(listOf(rec(10, "python3", "python3 job.py", rssMb = 100, swapMb = 100)))
        assertEquals("below the memory floor", Cleanup.veto(entry(small, 10), small, CleanupRule.HEAVY, settings()))
        val swapped = view(listOf(rec(11, "python3", "python3 job.py", rssMb = 100, swapMb = 500)))
        assertNull("a mostly-swapped process still costs 600 MB", Cleanup.veto(entry(swapped, 11), swapped, CleanupRule.HEAVY, settings()))
    }

    @Test
    fun `the build rule has no memory floor by default, the heavy rule always does`() {
        assertEquals(0, CleanupSettings.DEFAULT_BUILDS.minMemoryMb)
        assertTrue(CleanupSettings.DEFAULT_HEAVY.minMemoryMb >= 500)
        assertTrue(CleanupSettingsCodec.HEAVY_MEM_CHOICES.all { it >= 500 })
        assertTrue(CleanupSettingsCodec.HEAVY_IDLE_CHOICES.all { it >= 60 })
    }

    @Test
    fun `plain programs only for the heavy rule, daemons only for the build rule`() {
        val v = view(listOf(rec(10, "java", gradleArgv), rec(11, "python3", "python3 job.py")))
        assertEquals("not a plain program", Cleanup.veto(entry(v, 10), v, CleanupRule.HEAVY, settings()))
        assertEquals("not a build daemon", Cleanup.veto(entry(v, 11), v, CleanupRule.BUILDS, settings()))
        assertNull(Cleanup.veto(entry(v, 11), v, CleanupRule.HEAVY, settings()))
    }

    @Test
    fun `family strips the version`() {
        assertEquals("gradle daemon", Cleanup.family("Gradle daemon 8.14.5"))
        assertEquals("python bot.py", Cleanup.family("python bot.py"))
        assertEquals("kotlin daemon", Cleanup.family("Kotlin daemon"))
    }

    // ------------------------------------------------------------ idleness

    @Test
    fun `idleness starts at the first measured quiet interval and grows with every look`() {
        val t = IdleTracker()
        val r = rec(10, "java", gradleArgv)
        val t0 = 1_000_000L
        t.observe(ProcViews.build(listOf(r), mapOf(10 to null), emptyMap()), t0)
        assertNull("first sighting: no measurement yet", t.idleForMs(entry(ProcViews.build(listOf(r), mapOf(10 to null), emptyMap()), 10), t0))
        var now = t0
        repeat(30) { now += min; t.observe(view(listOf(r), mapOf(10 to 0.2f)), now) }
        val e = entry(view(listOf(r), mapOf(10 to 0.2f)), 10)
        assertEquals("30 quiet minutes since the first look", 30 * min, t.idleForMs(e, now))
    }

    @Test
    fun `any busy look resets idleness`() {
        val t = IdleTracker(); val r = rec(10, "java", gradleArgv)
        var now = 0L
        t.observe(view(listOf(r), mapOf(10 to 0.0f)), now)
        repeat(20) { now += min; t.observe(view(listOf(r), mapOf(10 to 0.0f)), now) }
        now += min; t.observe(view(listOf(r), mapOf(10 to 25f)), now)
        val e = entry(view(listOf(r), mapOf(10 to 0.0f)), 10)
        assertNull(t.idleForMs(e, now))
        now += min; t.observe(view(listOf(r), mapOf(10 to 0.0f)), now)
        assertEquals("and it starts counting again from the next quiet interval", min, t.idleForMs(e, now))
    }

    @Test
    fun `a gap in our observations is unknown - never idle`() {
        val t = IdleTracker(); val r = rec(10, "java", gradleArgv)
        var now = 0L
        t.observe(view(listOf(r), mapOf(10 to 0.0f)), now)
        repeat(10) { now += min; t.observe(view(listOf(r), mapOf(10 to 0.0f)), now) }
        val e = entry(view(listOf(r), mapOf(10 to 0.0f)), 10)
        assertEquals(10 * min, t.idleForMs(e, now))
        now += IdleTracker.MAX_GAP_MS + min          // the app was asleep
        t.observe(view(listOf(r), mapOf(10 to 0.0f)), now)
        assertNull("time we were not watching is not idle time", t.idleForMs(e, now))
    }

    @Test
    fun `a parent is busy while any descendant is busy`() {
        val t = IdleTracker()
        val parent = rec(10, "java", gradleArgv); val child = rec(11, "java", "java -jar worker.jar", ppid = 10)
        var now = 0L
        fun look(childCpu: Float) = view(listOf(parent, child), mapOf(10 to 0.0f, 11 to childCpu)).also { t.observe(it, now) }
        look(0f)
        repeat(5) { now += min; look(0f) }
        assertEquals(5 * min, t.idleForMs(entry(look(0f).let { view(listOf(parent, child)) }, 10), now))
        now += min; val busy = look(40f)
        assertNull("the parent is not idle while its worker burns CPU", t.idleForMs(entry(busy, 10), now))
    }

    @Test
    fun `a descendant that cannot be measured yet does not count as idle`() {
        val t = IdleTracker()
        val parent = rec(10, "java", gradleArgv); val child = rec(11, "java", "java -jar worker.jar", ppid = 10)
        t.observe(ProcViews.build(listOf(parent, child), mapOf(10 to 0.0f, 11 to null), emptyMap()), 0)
        t.observe(ProcViews.build(listOf(parent, child), mapOf(10 to 0.0f, 11 to null), emptyMap()), min)
        assertNull(t.idleForMs(entry(ProcViews.build(listOf(parent, child), mapOf(10 to 0.0f, 11 to null), emptyMap()), 10), min))
    }

    @Test
    fun `a recycled pid does not inherit the old process's idleness`() {
        val t = IdleTracker()
        val old = rec(10, "java", gradleArgv)
        var now = 0L
        t.observe(view(listOf(old), mapOf(10 to 0f)), now)
        repeat(40) { now += min; t.observe(view(listOf(old), mapOf(10 to 0f)), now) }
        val reused = old.copy(startTicks = 999_999)
        now += min; t.observe(view(listOf(reused), mapOf(10 to 0f)), now)
        assertNull(t.idleForMs(entry(view(listOf(reused)), 10), now))
    }

    // ----------------------------------------------------------- candidates

    private fun idleFor(r: ProcRecord, minutes: Int, tracker: IdleTracker = IdleTracker()): Pair<IdleTracker, Long> {
        var now = 0L
        tracker.observe(view(listOf(r), mapOf(r.pid to 0f)), now)
        repeat(minutes) { now += min; tracker.observe(view(listOf(r), mapOf(r.pid to 0f)), now) }
        return tracker to now
    }

    @Test
    fun `a candidate is due once idle for the rule's window, and shows a countdown before`() {
        val r = rec(10, "java", gradleArgv)
        val (tracker, now) = idleFor(r, 20)
        val c = Cleanup.candidates(view(listOf(r)), tracker, CleanupLedger(), settings(), now).single()
        assertFalse(c.due); assertEquals(10 * min, c.remainingMs)
        val (t2, now2) = idleFor(r, 30)
        val due = Cleanup.candidates(view(listOf(r)), t2, CleanupLedger(), settings(), now2).single()
        assertTrue(due.due); assertEquals(0, due.remainingMs)
    }

    @Test
    fun `only AUTO rules are acted on, and never more than three per sweep`() {
        val rs = (10..16).map { rec(it, "java", gradleArgv) }
        val tracker = IdleTracker(); var now = 0L
        fun look() = view(rs, rs.associate { it.pid to 0f }).also { tracker.observe(it, now) }
        look(); repeat(31) { now += min; look() }
        val v = look()
        val auto = Cleanup.candidates(v, tracker, CleanupLedger(), settings(builds = CleanupMode.AUTO), now)
        assertEquals(7, auto.size)
        assertEquals(Cleanup.MAX_PER_SWEEP, Cleanup.dueForAuto(auto).size)
        val ask = Cleanup.candidates(v, tracker, CleanupLedger(), settings(builds = CleanupMode.ASK), now)
        assertTrue("ASK lists them but never stops them", Cleanup.dueForAuto(ask).isEmpty())
        val off = Cleanup.candidates(v, tracker, CleanupLedger(), settings(builds = CleanupMode.OFF), now)
        assertTrue(Cleanup.dueForAuto(off).isEmpty())
    }

    @Test
    fun `a program that keeps coming back is no longer auto-stopped`() {
        val r = rec(10, "java", gradleArgv)
        val ledger = CleanupLedger(); val e = entry(view(listOf(r)), 10)
        assertFalse(ledger.autoPaused("gradle daemon", 0))
        ledger.recordKill(e, 0)
        assertFalse(ledger.autoPaused("gradle daemon", 1000))
        ledger.recordKill(e.copy(rec = r.copy(pid = 11, startTicks = 1)), 5 * min)
        assertTrue(ledger.autoPaused("gradle daemon", 6 * min))
        assertFalse("the window expires", ledger.autoPaused("gradle daemon", 20 * min))
        val (tracker, now) = idleFor(r.copy(pid = 12, startTicks = 12), 31)
        val v = view(listOf(r.copy(pid = 12, startTicks = 12)))
        val c = Cleanup.candidates(v, tracker, ledger.also { it.recordKill(e, now - min); it.recordKill(e, now - 2 * min) }, settings(), now).single()
        assertFalse(c.autoAllowed)
        assertTrue(Cleanup.dueForAuto(listOf(c)).isEmpty())
    }

    @Test
    fun `a process that ignored SIGTERM is left alone, never escalated`() {
        val r = rec(10, "java", gradleArgv)
        val (tracker, now) = idleFor(r, 31)
        val ledger = CleanupLedger(); val e = entry(view(listOf(r)), 10)
        ledger.recordKill(e, now - CleanupLedger.STUBBORN_AFTER_MS - 1)
        val c = Cleanup.candidates(view(listOf(r)), tracker, ledger, settings(), now).single()
        assertFalse("it ignored the signal", c.autoAllowed)
    }

    @Test
    fun `a process signalled moments ago is not listed or signalled again`() {
        val r = rec(10, "java", gradleArgv)
        val (tracker, now) = idleFor(r, 31)
        val ledger = CleanupLedger(); ledger.recordKill(entry(view(listOf(r)), 10), now - 5_000)
        assertTrue(Cleanup.candidates(view(listOf(r)), tracker, ledger, settings(), now).isEmpty())
    }

    @Test
    fun `due candidates come first, biggest first`() {
        val a = rec(10, "java", gradleArgv, rssMb = 300); val b = rec(11, "python3", "python3 big.py", rssMb = 1500)
        val tracker = IdleTracker(); var now = 0L
        fun look() = view(listOf(a, b), mapOf(10 to 0f, 11 to 0f)).also { tracker.observe(it, now) }
        look(); repeat(61) { now += min; look() }
        val c = Cleanup.candidates(look(), tracker, CleanupLedger(), settings(), now)
        assertEquals(listOf(11, 10), c.map { it.entry.pid })
    }

    // ---------------------------------------------------------------- settings

    @Test
    fun `settings round trip`() {
        val s = settings(builds = CleanupMode.ASK, heavy = CleanupMode.AUTO, keep = setOf("gradle daemon", "python bot.py"))
            .with(CleanupRule.BUILDS, RuleSettings(CleanupMode.ASK, 120, 500))
        assertEquals(s, CleanupSettingsCodec.decode(CleanupSettingsCodec.encode(s)))
    }

    @Test
    fun `a hand-edited file cannot make a rule reckless`() {
        val text = "builds=AUTO,1,0\nheavy=AUTO,5,1\nkeep=ok name\nkeep=Bad/Name\nkeep=\nbuilds=garbage\nzzz=1\n\u0000"
        val s = CleanupSettingsCodec.decode(text)
        assertEquals("a 1-minute window is not one of the choices", CleanupSettings.DEFAULT_BUILDS.idleMinutes, s.builds.idleMinutes)
        assertEquals(CleanupMode.AUTO, s.builds.mode)
        assertEquals("heavy keeps its long window and its memory floor", CleanupSettings.DEFAULT_HEAVY.idleMinutes, s.heavy.idleMinutes)
        assertEquals(CleanupSettings.DEFAULT_HEAVY.minMemoryMb, s.heavy.minMemoryMb)
        assertEquals(setOf("ok name"), s.keep)
    }

    @Test
    fun `empty or missing settings mean everything off`() {
        for (text in listOf(null, "", "   \n")) {
            val s = CleanupSettingsCodec.decode(text)
            assertEquals(CleanupMode.OFF, s.builds.mode); assertEquals(CleanupMode.OFF, s.heavy.mode)
            assertFalse(s.watching)
        }
    }

    @Test
    fun `with() re-sanitises so the UI cannot set a value off the menu`() {
        val s = CleanupSettings().with(CleanupRule.HEAVY, RuleSettings(CleanupMode.AUTO, 1, 1))
        assertEquals(CleanupSettings.DEFAULT_HEAVY.copy(mode = CleanupMode.AUTO), s.heavy)
        assertTrue(s.watching)
    }

    @Test
    fun `the keep list is bounded`() {
        val many = (1..80).joinToString("\n") { "keep=name$it" }
        assertEquals(CleanupSettingsCodec.MAX_KEEP, CleanupSettingsCodec.decode(many).keep.size)
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("45 s", Cleanup.duration(45_000)); assertEquals("12 min", Cleanup.duration(12 * min))
        assertEquals("1 h 5 min", Cleanup.duration(65 * min)); assertEquals("0 s", Cleanup.duration(0))
    }
}

/** The runner: what is signalled, what is refused, what is logged. */
class CleanupRunnerTest {

    private val min = 60_000L
    private val MB = 1024L * 1024
    private val gradleArgv = "/usr/lib/jvm/java-21/bin/java -cp x.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.5"

    private class FakeSignaller(
        val identities: Map<Int, ProcIdentityCheck.Identity?>,
        val sendOk: Boolean = true,
        val sockets: Map<Int, Int?> = emptyMap(),
    ) : CleanupSignaller {
        val sent = ArrayList<Int>()
        override fun identity(pid: Int) = identities[pid]
        override fun terminate(pid: Int): Boolean { sent += pid; return sendOk }
        override fun openSockets(pid: Int): Int? = if (pid in sockets) sockets[pid] else 0
    }

    private fun rec(pid: Int) = ProcRecord(
        pid = pid, ppid = 1, comm = "java", argv = gradleArgv, state = 'S', pgrp = pid, sid = pid, tty = 0, tpgid = -1, threads = 1,
        cpuTicks = 0, rssBytes = 900 * MB, swapBytes = 200 * MB, startedAtMs = 0, cwd = "/root", hupIgnored = false, startTicks = pid * 100L,
    )

    private fun dueCandidates(pids: List<Int>, mode: CleanupMode = CleanupMode.AUTO): Pair<List<CleanupCandidate>, Long> {
        val rs = pids.map(::rec)
        val tracker = IdleTracker(); var now = 0L
        fun v() = ProcViews.build(rs, rs.associate { it.pid to 0f }, emptyMap()).also { tracker.observe(it, now) }
        v(); repeat(31) { now += min; v() }
        val s = CleanupSettings(builds = CleanupSettings.DEFAULT_BUILDS.copy(mode = mode))
        return Cleanup.candidates(v(), tracker, CleanupLedger(), s, now) to now
    }

    @Test
    fun `an identified due daemon gets exactly one SIGTERM and a log line`() {
        val (cands, now) = dueCandidates(listOf(10))
        val sig = FakeSignaller(mapOf(10 to ProcIdentityCheck.Identity(1000, false)))
        val ledger = CleanupLedger()
        val log = CleanupRunner.sweep(cands, sig, ledger, now)
        assertEquals(listOf(10), sig.sent)
        assertEquals(1, log.size); assertTrue(log.single().ok)
        assertTrue(log.single().text.startsWith("Stopped Gradle daemon 8.14.5 (idle 31 min, 1 GB)"))
        assertTrue("the ledger remembers it", ledger.recentlySignalled(cands.single().entry, now))
    }

    @Test
    fun `a recycled pid or PocketShell's own process is refused, not signalled`() {
        val (cands, now) = dueCandidates(listOf(10, 11))
        val sig = FakeSignaller(mapOf(10 to ProcIdentityCheck.Identity(999_999, false), 11 to ProcIdentityCheck.Identity(1100, true)))
        val log = CleanupRunner.sweep(cands, sig, CleanupLedger(), now)
        assertTrue("nothing may be signalled", sig.sent.isEmpty())
        assertEquals(2, log.size); assertTrue(log.none { it.ok })
        assertTrue(log.any { it.text.contains("different process") }); assertTrue(log.any { it.text.contains("PocketShell's own") })
    }

    @Test
    fun `a process that vanished is not signalled`() {
        val (cands, now) = dueCandidates(listOf(10))
        val sig = FakeSignaller(mapOf(10 to null))
        val log = CleanupRunner.sweep(cands, sig, CleanupLedger(), now)
        assertTrue(sig.sent.isEmpty()); assertTrue(log.single().text.contains("already gone"))
    }

    @Test
    fun `ASK and OFF rules never signal anything`() {
        for (mode in listOf(CleanupMode.ASK, CleanupMode.OFF)) {
            val (cands, now) = dueCandidates(listOf(10), mode)
            val sig = FakeSignaller(mapOf(10 to ProcIdentityCheck.Identity(1000, false)))
            assertTrue(CleanupRunner.sweep(cands, sig, CleanupLedger(), now).isEmpty())
            assertTrue(sig.sent.isEmpty())
        }
    }

    @Test
    fun `a failed signal is logged as a failure and not remembered as a stop`() {
        val (cands, now) = dueCandidates(listOf(10))
        val ledger = CleanupLedger()
        val log = CleanupRunner.sweep(cands, FakeSignaller(mapOf(10 to ProcIdentityCheck.Identity(1000, false)), sendOk = false), ledger, now)
        assertFalse(log.single().ok); assertTrue(log.single().text.startsWith("Could not signal"))
        assertFalse(ledger.recentlySignalled(cands.single().entry, now))
    }

    @Test
    fun `no more than three are stopped in one sweep`() {
        val pids = (10..16).toList()
        val (cands, now) = dueCandidates(pids)
        val sig = FakeSignaller(pids.associateWith { ProcIdentityCheck.Identity(it * 100L, false) })
        CleanupRunner.sweep(cands, sig, CleanupLedger(), now)
        assertEquals(Cleanup.MAX_PER_SWEEP, sig.sent.size)
    }

    @Test
    fun `the log keeps the newest entries first and is bounded`() {
        var log = emptyList<CleanupLogEntry>()
        repeat(30) { log = CleanupRunner.append(log, listOf(CleanupLogEntry(it.toLong(), "e$it", true))) }
        assertEquals(CleanupRunner.LOG_LIMIT, log.size)
        assertEquals("e29", log.first().text)
    }

    private fun heavyDue(pid: Int): Pair<List<CleanupCandidate>, Long> {
        val r = rec(pid).copy(comm = "python3", argv = "python3 worker.py")
        val tracker = IdleTracker(); var now = 0L
        fun v() = ProcViews.build(listOf(r), mapOf(pid to 0f), emptyMap()).also { tracker.observe(it, now) }
        v(); repeat(61) { now += min; v() }
        val s = CleanupSettings(heavy = CleanupSettings.DEFAULT_HEAVY.copy(mode = CleanupMode.AUTO))
        return Cleanup.candidates(v(), tracker, CleanupLedger(), s, now) to now
    }

    @Test
    fun `a heavy idle program with open network connections is left alone - it is a server, a bot or a client`() {
        val (cands, now) = heavyDue(20)
        assertEquals(CleanupRule.HEAVY, cands.single().rule)
        val sig = FakeSignaller(mapOf(20 to ProcIdentityCheck.Identity(2000, false)), sockets = mapOf(20 to 3))
        val log = CleanupRunner.sweep(cands, sig, CleanupLedger(), now)
        assertTrue("nothing signalled", sig.sent.isEmpty())
        assertTrue(log.single().text.contains("network connections open")); assertFalse(log.single().ok)
    }

    @Test
    fun `when the sockets cannot be read the heavy rule keeps its hands off`() {
        val (cands, now) = heavyDue(20)
        val sig = FakeSignaller(mapOf(20 to ProcIdentityCheck.Identity(2000, false)), sockets = mapOf(20 to null))
        CleanupRunner.sweep(cands, sig, CleanupLedger(), now)
        assertTrue(sig.sent.isEmpty())
    }

    @Test
    fun `a heavy idle program with no sockets is stopped`() {
        val (cands, now) = heavyDue(20)
        val sig = FakeSignaller(mapOf(20 to ProcIdentityCheck.Identity(2000, false)), sockets = mapOf(20 to 0))
        val log = CleanupRunner.sweep(cands, sig, CleanupLedger(), now)
        assertEquals(listOf(20), sig.sent); assertTrue(log.single().ok)
    }

    @Test
    fun `a build daemon is stopped although it holds its IPC socket`() {
        val (cands, now) = dueCandidates(listOf(10))
        val sig = FakeSignaller(mapOf(10 to ProcIdentityCheck.Identity(1000, false)), sockets = mapOf(10 to 5))
        CleanupRunner.sweep(cands, sig, CleanupLedger(), now)
        assertEquals(listOf(10), sig.sent)
    }
}

/** The words the user reads - these are what 'everything updates accordingly' shows up as. */
class CleanupTextTest {

    private val MB = 1024L * 1024
    private fun cand(mode: CleanupMode, idleMin: Int, thresholdMin: Int, rssMb: Long = 600, autoAllowed: Boolean = true): CleanupCandidate {
        val r = ProcRecord(
            pid = 10, ppid = 1, comm = "java", argv = "org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.5", state = 'S', pgrp = 10, sid = 10,
            tty = 0, tpgid = -1, threads = 1, cpuTicks = 0, rssBytes = rssMb * MB, swapBytes = 0, startedAtMs = 0, cwd = "/", hupIgnored = false, startTicks = 1000,
        )
        val e = ProcViews.build(listOf(r), mapOf(10 to 0f), emptyMap()).byPid.getValue(10)
        return CleanupCandidate(e, CleanupRule.BUILDS, mode, idleMin * 60_000L, thresholdMin * 60_000L, autoAllowed)
    }

    @Test
    fun `the card only nudges for ask rules that are ready`() {
        assertNull(CleanupText.cardLine(emptyList()))
        assertNull("AUTO acts quietly", CleanupText.cardLine(listOf(cand(CleanupMode.AUTO, 40, 30))))
        assertNull("OFF says nothing", CleanupText.cardLine(listOf(cand(CleanupMode.OFF, 40, 30))))
        assertNull("not ready yet", CleanupText.cardLine(listOf(cand(CleanupMode.ASK, 10, 30))))
        assertEquals("2 IDLE · 1 GB CAN BE STOPPED", CleanupText.cardLine(listOf(cand(CleanupMode.ASK, 40, 30, 600), cand(CleanupMode.ASK, 40, 30, 500))))
    }

    @Test
    fun `the overview line says whether it is watching and how much is idle`() {
        val off = CleanupSettings(); val on = CleanupSettings(builds = CleanupSettings.DEFAULT_BUILDS.copy(mode = CleanupMode.ASK))
        assertEquals("Rules are off · nothing idle", CleanupText.overviewLine(off, emptyList()))
        assertEquals("Watching · nothing idle", CleanupText.overviewLine(on, emptyList()))
        assertTrue(CleanupText.overviewLine(on, listOf(cand(CleanupMode.ASK, 40, 30))).startsWith("Watching · 1 idle"))
        assertTrue(CleanupText.overviewLine(off, listOf(cand(CleanupMode.OFF, 40, 30))).startsWith("Rules are off · 1 idle"))
    }

    @Test
    fun `a row tells you what will happen to it`() {
        assertTrue(CleanupText.status(cand(CleanupMode.OFF, 40, 30)).endsWith("rule is off"))
        assertTrue(CleanupText.status(cand(CleanupMode.ASK, 40, 30)).endsWith("ready to stop"))
        assertTrue(CleanupText.status(cand(CleanupMode.ASK, 10, 30)).endsWith("20 min to go"))
        assertTrue(CleanupText.status(cand(CleanupMode.AUTO, 10, 30)).endsWith("stops in 20 min"))
        assertTrue(CleanupText.status(cand(CleanupMode.AUTO, 40, 30)).endsWith("stopping at the next check"))
        assertTrue(CleanupText.status(cand(CleanupMode.AUTO, 40, 30, autoAllowed = false)).contains("not stopped automatically"))
    }

    @Test
    fun `the promise on the page matches the rules`() {
        for (word in listOf("terminals", "tmux", "port", "Claude", "ssh", "KEEP", "SIGTERM")) assertTrue(word, CleanupText.NEVER_TOUCHED.contains(word))
    }
}

/** Source pins: the promises a user relies on must not be edited away by accident. */
class CleanupContractTest {

    private val dir = listOf("src/main/kotlin/app/pocketshell/widget/servers", "plugins/servers/src/main/kotlin/app/pocketshell/widget/servers")
        .map(::File).first { it.isDirectory }

    private fun code(name: String): String {
        val raw = File(dir, name).readText()
        return raw.replace(Regex("(?s)/\\*.*?\\*/"), "").lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }
    }

    @Test
    fun `every rule starts OFF - nothing is ever stopped until the user turns it on`() {
        assertEquals(CleanupMode.OFF, CleanupSettings.DEFAULT_BUILDS.mode)
        assertEquals(CleanupMode.OFF, CleanupSettings.DEFAULT_HEAVY.mode)
        assertEquals(CleanupMode.OFF, CleanupSettings().builds.mode)
        assertFalse(CleanupSettings().watching)
        assertFalse("a fresh install has no monitor", CleanupSettingsCodec.decode(null).watching)
    }

    @Test
    fun `only a polite SIGTERM is ever sent automatically - never SIGKILL`() {
        val c = code("ServersCleanup.kt")
        assertTrue(c.contains("sendSignal(pid, 15)"))
        assertFalse("no SIGKILL anywhere in the cleanup", Regex("sendSignal\\(pid, *9\\)").containsMatchIn(c) || c.contains("SIGKILL"))
        assertFalse(c.contains("Signal.KILL"))
        // the automatic path does not go through the manual KILL pipeline either
        assertFalse(c.contains("ServersOps.Signal.KILL"))
    }

    @Test
    fun `the monitor exists only while it has work, honours pause, and refreshes the listeners before it stops anything`() {
        val a = code("ServersApp.kt")
        assertTrue(a.contains("if (cleanup.monitorNeeded)"))
        assertTrue("pause stops the monitor from acting", a.contains("if (paused) continue"))
        val loop = a.substringAfter("private suspend fun monitorLoop()")
        assertTrue("a full scan precedes any stop", loop.indexOf("scan()") in 0 until loop.indexOf("CleanupRunner.sweep("))
        assertTrue("never acts on a stale process list", loop.contains("MONITOR_MAX_STALE_MS"))
        assertEquals("exactly one wait in the loop", 1, Regex("delay\\(").findAll(loop.substringBefore("private ")).count())
    }

    @Test
    fun `the identity is rechecked right before every signal and sockets are checked for the heavy rule`() {
        val c = code("ServersCleanup.kt")
        val sweep = c.substringAfter("fun sweep(")
        assertTrue(sweep.indexOf("ProcIdentityCheck.refusal(") < sweep.indexOf("signaller.terminate("))
        assertTrue(sweep.contains("openSockets("))
        assertTrue(sweep.contains("Cleanup.dueForAuto("))
    }

    @Test
    fun `the protected list covers what other plugins and the user depend on`() {
        for (p in listOf("ssh", "sshd", "rsync", "rclone", "tar", "zstd", "git", "tmux", "screen", "autossh")) assertTrue(p, p in Cleanup.PROTECTED_PROGRAMS)
        assertEquals(3, Cleanup.MAX_PER_SWEEP)
        assertTrue("the monitor does not run faster than once a minute", ServersApp.MONITOR_MS >= 60_000L)
    }

    @Test
    fun `settings live in the Linux home so a backup carries them`() {
        assertTrue(code("ServersApp.kt").contains("root/.config/pocketshell/processes-cleanup.txt"))
    }

    @Test
    fun `the page promises exactly what the rules do`() {
        val screen = code("ServersCleanupScreen.kt")
        assertTrue(screen.contains("CleanupText.NEVER_TOUCHED"))
        assertTrue("manual stop goes through the confirm dialog pipeline", screen.contains("Signal.TERM") && screen.contains("onSignalProcess"))
    }
}
