package app.pocketshell.widget.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServersHealthTest {

    private val mb = 1024L * 1024
    private val min = 60_000L

    private fun rec(pid: Int, ppid: Int, comm: String, argv: String = comm, rss: Long = 100 * mb, state: Char = 'S', start: Long = pid.toLong(), tty: Int = 0, nice: Int = 0) = ProcRecord(
        pid = pid, ppid = ppid, comm = comm, argv = argv, state = state, pgrp = pid, sid = pid, tty = tty, tpgid = -1, threads = 1,
        cpuTicks = 0, rssBytes = rss, swapBytes = 0, startedAtMs = 0, cwd = "/root", hupIgnored = false, startTicks = start, nice = nice,
    )

    private fun view(rs: List<ProcRecord>, cpu: Float = 0f) = ProcViews.build(rs, rs.associate { it.pid to cpu }, emptyMap())

    private fun firefox(children: Int, firstChild: Int = 101, cpu: Float = 0f): ProcView =
        view(listOf(rec(100, 1, "firefox-esr")) + (0 until children).map { rec(firstChild + it, 100, "firefox-esr", "firefox-esr -contentproc") }, cpu)

    private fun findings(v: ProcView, s: CleanupSettings = CleanupSettings(), watch: HealthWatch = HealthWatch(), now: Long = 0L, budget: Int = 5): List<Finding> {
        val g = ProcGroups.build(v)
        watch.observe(v, g, s, now)
        return Health.findings(v, g, ProcessBudgetFacts(budget), watch, s, now)
    }

    // ---- grouping

    @Test
    fun `a browser and its helpers are one app`() {
        val groups = ProcGroups.build(firefox(5))
        assertEquals(1, groups.size)
        assertEquals("firefox-esr", groups[0].label)
        assertEquals(6, groups[0].count)
        assertEquals(600 * mb, groups[0].footprintBytes)
    }

    @Test
    fun `what runs under a terminal is its own app, not the shell's helpers`() {
        val v = view(listOf(rec(10, 1, "bash", tty = 34816), rec(11, 10, "python3", "python3 a.py", tty = 34816), rec(12, 10, "node", "node b.js", tty = 34816)))
        assertEquals(setOf("bash", "python a.py", "node b.js"), ProcGroups.build(v).map { it.label }.toSet())
    }

    @Test
    fun `a shell wrapper is named after what does the work`() {
        val v = view(listOf(rec(10, 1, "sh", "sh -c run"), rec(11, 10, "firefox-esr", rss = 500 * mb)))
        assertEquals("firefox-esr", ProcGroups.build(v).single().label)
    }

    @Test
    fun `the desktop is recognised and named whatever started it`() {
        val v = view(listOf(rec(10, 1, "sh", "sh session-desktop.sh 812 1770"), rec(11, 10, "weston", "weston --backend=headless"), rec(12, 11, "Xwayland", "Xwayland :1")))
        val g = ProcGroups.build(v).single()
        assertTrue(g.desktop)
        assertEquals("Linux desktop", g.label)
    }

    @Test
    fun `a parent loop cannot hang the grouping`() {
        assertEquals(2, ProcGroups.build(view(listOf(rec(10, 11, "a"), rec(11, 10, "b")))).sumOf { it.count })
    }

    // ---- budget

    @Test
    fun `the budget levels match where Android starts killing`() {
        assertEquals(BudgetLevel.OK, ProcessBudgetFacts(23).level)
        assertEquals(BudgetLevel.WARN, ProcessBudgetFacts(24).level)
        assertEquals(BudgetLevel.CRITICAL, ProcessBudgetFacts(30).level)
        assertEquals(1f, ProcessBudgetFacts(50).fraction, 0f)
    }

    @Test
    fun `the budget warning names who holds the most`() {
        val f = findings(firefox(5), budget = 30).first()
        assertEquals(FindingKind.BUDGET, f.kind)
        assertEquals(Severity.CRITICAL, f.severity)
        assertTrue(f.detail, f.detail.contains("firefox-esr 6"))
    }

    @Test
    fun `below the warning line nothing is said about the budget`() {
        assertTrue(findings(firefox(1), budget = 23).none { it.kind == FindingKind.BUDGET })
    }

    // ---- the problems

    @Test
    fun `a swarm is measured per app against the user's number`() {
        assertTrue(findings(firefox(11)).any { it.kind == FindingKind.SWARM })
        assertTrue(findings(firefox(10)).none { it.kind == FindingKind.SWARM })
        assertTrue(findings(firefox(10), CleanupSettings(swarmAt = 8)).any { it.kind == FindingKind.SWARM })
    }

    @Test
    fun `helpers dying young over and over is a crash loop`() {
        val w = HealthWatch()
        var now = 0L
        var pid = 200
        repeat(7) {
            findings(firefox(1, firstChild = pid), watch = w, now = now)
            pid++
            now += 15_000
        }
        val f = findings(firefox(1, firstChild = pid), watch = w, now = now)
        val churn = f.first { it.kind == FindingKind.CHURN }
        assertEquals(Severity.CRITICAL, churn.severity)
        assertEquals(f.first(), churn)
    }

    @Test
    fun `a build's short-lived compilers are not a crash loop of the build`() {
        val w = HealthWatch()
        var now = 0L
        repeat(12) { i ->
            val v = view(listOf(rec(100, 1, "make"), rec(200 + i, 100, "cc1", "cc1 a.c")))
            findings(v, watch = w, now = now)
            now += 15_000
        }
        assertTrue(findings(view(listOf(rec(100, 1, "make"))), watch = w, now = now).none { it.kind == FindingKind.CHURN })
    }

    @Test
    fun `a helper that ran for minutes then ended is not churn`() {
        val w = HealthWatch()
        var now = 0L
        repeat(10) { round ->
            repeat(4) { findings(firefox(1, firstChild = 200 + round), watch = w, now = now); now += min }
        }
        assertEquals(0, w.deathsOf("firefox-esr"))
    }

    @Test
    fun `deaths across a gap in looking say nothing`() {
        val w = HealthWatch()
        findings(firefox(1, firstChild = 200), watch = w, now = 0L)
        findings(firefox(1, firstChild = 201), watch = w, now = 10 * min)
        assertEquals(0, w.deathsOf("firefox-esr"))
    }

    @Test
    fun `old deaths age out of the window`() {
        val w = HealthWatch()
        var now = 0L
        repeat(8) { i -> findings(firefox(1, firstChild = 200 + i), watch = w, now = now); now += 15_000 }
        assertTrue(w.deathsOf("firefox-esr") >= HealthWatch.CHURN_AT)
        repeat(30) { findings(firefox(1, firstChild = 300), watch = w, now = now); now += 15_000 }
        assertEquals(0, w.deathsOf("firefox-esr"))
    }

    @Test
    fun `CPU counts only when it was sustained for the whole window`() {
        val w = HealthWatch()
        val s = CleanupSettings(cpuAtPercent = 80, cpuMinutes = 5)
        var now = 0L
        repeat(5) { assertTrue(findings(firefox(1, cpu = 90f), s, w, now).none { it.kind == FindingKind.RUNAWAY }); now += min }
        assertTrue(findings(firefox(1, cpu = 90f), s, w, now).any { it.kind == FindingKind.RUNAWAY })
        now += min
        findings(firefox(1, cpu = 1f), s, w, now)
        now += min
        assertTrue("one quiet look starts it over", findings(firefox(1, cpu = 90f), s, w, now).none { it.kind == FindingKind.RUNAWAY })
    }

    @Test
    fun `a gap in looking restarts the CPU timer`() {
        val w = HealthWatch()
        val s = CleanupSettings(cpuMinutes = 5)
        findings(firefox(1, cpu = 90f), s, w, 0L)
        assertTrue(findings(firefox(1, cpu = 90f), s, w, 30 * min).none { it.kind == FindingKind.RUNAWAY })
    }

    @Test
    fun `memory use is per app against the user's number`() {
        val big = view(listOf(rec(10, 1, "blender", rss = 1500 * mb), rec(11, 10, "blender", rss = 600 * mb)))
        assertTrue(findings(big).any { it.kind == FindingKind.HOG })
        assertTrue(findings(big, CleanupSettings(hogMb = 4000)).none { it.kind == FindingKind.HOG })
    }

    @Test
    fun `steady growth is reported and steady memory is not`() {
        val w = HealthWatch()
        findings(view(listOf(rec(10, 1, "node", rss = 200 * mb))), watch = w, now = 0L)
        assertTrue(findings(view(listOf(rec(10, 1, "node", rss = 300 * mb))), watch = w, now = min).none { it.kind == FindingKind.GROWTH })
        assertTrue(findings(view(listOf(rec(10, 1, "node", rss = 800 * mb))), watch = w, now = 2 * min).any { it.kind == FindingKind.GROWTH })
    }

    @Test
    fun `a process frozen for minutes offers its identity for resuming`() {
        val w = HealthWatch()
        val v = view(listOf(rec(10, 1, "blender", state = 'T')))
        assertTrue(findings(v, watch = w, now = 0L).none { it.kind == FindingKind.STOPPED })
        val f = findings(v, watch = w, now = 3 * min).single { it.kind == FindingKind.STOPPED }
        assertEquals(listOf(10), f.stopped.map { it.pid })
    }

    @Test
    fun `dead processes waiting to be collected are counted`() {
        val v = view(listOf(rec(10, 1, "app")) + (11..13).map { rec(it, 10, "app", state = 'Z') })
        assertTrue(findings(v).any { it.kind == FindingKind.ZOMBIES })
    }

    @Test
    fun `an ignored program stays quiet but the budget never does`() {
        val s = CleanupSettings(quiet = setOf("firefox-esr"))
        val f = findings(firefox(20), s, budget = 30)
        assertEquals(listOf(FindingKind.BUDGET), f.map { it.kind })
    }

    @Test
    fun `watching off means no findings at all`() {
        assertTrue(findings(firefox(20), CleanupSettings(watchHealth = false), budget = 31).isEmpty())
    }

    @Test
    fun `findings put the worst first`() {
        val big = view(listOf(rec(100, 1, "firefox-esr", rss = 3000 * mb)) + (101..115).map { rec(it, 100, "firefox-esr") })
        val f = findings(big, budget = 31)
        assertEquals(Severity.CRITICAL, f.first().severity)
        assertEquals(f.map { it.severity }.sortedDescending(), f.map { it.severity })
    }

    // ---- the pace

    @Test
    fun `the monitor looks often only while something is wrong`() {
        assertEquals(ServersApp.MONITOR_MS, Health.nextLookMs(ProcessBudgetFacts(5), emptyList()))
        assertEquals(ServersApp.HEALTH_FAST_MS, Health.nextLookMs(ProcessBudgetFacts(24), emptyList()))
        assertEquals(ServersApp.HEALTH_FAST_MS, Health.nextLookMs(ProcessBudgetFacts(5), findings(firefox(20))))
    }

    // ---- calming

    private fun runaway(group: ProcGroup) = Finding(FindingKind.RUNAWAY, Severity.WARNING, group.family, "", "", group)

    @Test
    fun `auto calm only when asked, never the desktop, never twice`() {
        val g = ProcGroups.build(firefox(1)).single()
        assertTrue(Health.autoCalm(listOf(runaway(g)), CleanupSettings()).isEmpty())
        assertEquals(listOf(g), Health.autoCalm(listOf(runaway(g)), CleanupSettings(autoCalm = true)))
        val desktop = ProcGroups.build(view(listOf(rec(11, 1, "weston")))).single()
        assertTrue(Health.autoCalm(listOf(runaway(desktop)), CleanupSettings(autoCalm = true)).isEmpty())
        val low = ProcGroups.build(view(listOf(rec(12, 1, "node", nice = 10)))).single()
        assertTrue(Health.autoCalm(listOf(runaway(low)), CleanupSettings(autoCalm = true)).isEmpty())
    }

    private class FakeSetter(val refuse: Boolean = false) : PrioritySetter {
        val set = ArrayList<Pair<Int, Int>>()
        override fun threadsOf(pid: Int) = listOf(pid, pid + 1000)
        override fun set(tid: Int, nice: Int): Boolean { set += tid to nice; return !refuse }
    }

    @Test
    fun `lowering a priority covers every thread of every process`() {
        val g = ProcGroups.build(firefox(1)).single()
        val setter = FakeSetter()
        val log = ProcPriority.apply(g, 10, setter) { pid -> ProcIdentityCheck.Identity(pid.toLong(), false) }
        assertEquals(setOf(100, 1100, 101, 1101), setter.set.map { it.first }.toSet())
        assertTrue(setter.set.all { it.second == 10 })
        assertTrue(log.ok)
    }

    @Test
    fun `a recycled pid is never touched, and a refusal is reported as one`() {
        val g = ProcGroups.build(firefox(0)).single()
        val setter = FakeSetter()
        val log = ProcPriority.apply(g, 10, setter) { ProcIdentityCheck.Identity(999, false) }
        assertTrue(setter.set.isEmpty())
        assertFalse(log.ok)
        assertFalse(ProcPriority.apply(g, 10, FakeSetter(refuse = true)) { pid -> ProcIdentityCheck.Identity(pid.toLong(), false) }.ok)
    }

    @Test
    fun `nice is read from stat and shows as low priority`() {
        val tail = "S 1 2 3 0 -1 4194304 0 0 0 0 7 3 0 0 20 10 4 0 555 0 42 0"
        assertEquals(10, ProcRecordParsing.parseStat("7 (a b) $tail")?.nice)
        assertTrue(ProcGroups.build(view(listOf(rec(10, 1, "node", nice = 10)))).single().lowPriority)
    }

    // ---- the guards this release added

    private val idleProgram = rec(10, 1, "firefox-esr", rss = 900 * mb)

    @Test
    fun `Debian's package tools and the desktop are protected`() {
        for (p in listOf("apt", "apt-get", "dpkg", "weston", "xwayland", "ps-capture")) assertTrue(p, p in Cleanup.PROTECTED_PROGRAMS)
        val v = view(listOf(rec(10, 1, "apt-get", "apt-get install x", rss = 900 * mb)))
        assertNotNull(Cleanup.veto(v.all[0], v, CleanupRule.HEAVY, CleanupSettings()))
    }

    private class Sig(val desktop: Boolean) : CleanupSignaller {
        val sent = ArrayList<Int>()
        override fun identity(pid: Int) = ProcIdentityCheck.Identity(pid.toLong(), false)
        override fun terminate(pid: Int): Boolean { sent += pid; return true }
        override fun openSockets(pid: Int): Int? = 0
        override fun isDesktopApp(pid: Int) = desktop
    }

    private fun due(v: ProcView, rule: CleanupRule) = CleanupCandidate(v.all[0], rule, CleanupMode.AUTO, 2 * 3600_000L, 3600_000L, true)

    @Test
    fun `the heavy rule leaves an idle desktop app alone, once, and stops asking`() {
        val v = view(listOf(idleProgram))
        val sig = Sig(desktop = true)
        val ledger = CleanupLedger()
        val log = CleanupRunner.sweep(listOf(due(v, CleanupRule.HEAVY)), sig, ledger, 0L)
        assertTrue(sig.sent.isEmpty())
        assertTrue(log.single().text, log.single().text.contains("desktop app"))
        assertTrue(ledger.wasRefused(v.all[0]))
    }

    @Test
    fun `a rule naming the program can stop it even as a desktop app`() {
        val v = view(listOf(idleProgram))
        val sig = Sig(desktop = true)
        CleanupRunner.sweep(listOf(due(v, CleanupRule.CUSTOM)), sig, CleanupLedger(), 0L)
        assertEquals(listOf(10), sig.sent)
    }

    // ---- your rules

    private fun tracked(v: ProcView, minutes: Int): Pair<IdleTracker, Long> {
        val t = IdleTracker()
        var now = 0L
        t.observe(v, now)
        repeat(minutes) { now += min; t.observe(v, now) }
        return t to now
    }

    @Test
    fun `a rule of yours finds the program by name, at its own window`() {
        val v = view(listOf(idleProgram))
        val s = CleanupSettings().addCustom(" Firefox-ESR ").withCustom("firefox-esr", RuleSettings(CleanupMode.AUTO, 15, 0))
        val (t, now) = tracked(v, 20)
        val c = Cleanup.candidates(v, t, CleanupLedger(), s, now).single()
        assertEquals(CleanupRule.CUSTOM, c.rule)
        assertTrue(c.due)
        assertEquals(listOf(c), Cleanup.dueForAuto(listOf(c)))
    }

    @Test
    fun `your rule beats the general one, and every guard still applies`() {
        val heavy = view(listOf(rec(10, 1, "python3", "python3 job.py", rss = 900 * mb)))
        val s = CleanupSettings().addCustom("python job.py")
        assertNull(Cleanup.veto(heavy.all[0], heavy, CleanupRule.CUSTOM, s))
        val (t, now) = tracked(heavy, 120)
        assertEquals(CleanupRule.CUSTOM, Cleanup.candidates(heavy, t, CleanupLedger(), s, now).single().rule)

        val named = { name: String -> CleanupSettings().addCustom(name) }
        val tmux = view(listOf(rec(10, 1, "tmux", "tmux")))
        assertNotNull("session keepers", Cleanup.veto(tmux.all[0], tmux, CleanupRule.CUSTOM, named("tmux")))
        val apt = view(listOf(rec(10, 1, "apt-get", "apt-get update")))
        assertNotNull("protected", Cleanup.veto(apt.all[0], apt, CleanupRule.CUSTOM, named("apt-get")))
        val inTerminal = view(listOf(rec(10, 1, "bash", tty = 34816), rec(11, 10, "node", "node a.js")))
        assertNotNull("started from a live terminal", Cleanup.veto(inTerminal.byPid[11]!!, inTerminal, CleanupRule.CUSTOM, named("node a.js")))
        val kept = named("firefox-esr").copy(keep = setOf("firefox-esr"))
        val ff = view(listOf(idleProgram))
        assertNotNull("keep wins", Cleanup.veto(ff.all[0], ff, CleanupRule.CUSTOM, kept))
    }

    @Test
    fun `no rule, no match`() {
        val v = view(listOf(idleProgram))
        assertNotNull(Cleanup.veto(v.all[0], v, CleanupRule.CUSTOM, CleanupSettings()))
    }

    @Test
    fun `bad names, duplicates and too many rules are not added`() {
        assertEquals(CleanupSettings(), CleanupSettings().addCustom("rm -rf /;"))
        val one = CleanupSettings().addCustom("node")
        assertEquals(one, one.addCustom("NODE"))
        var many = CleanupSettings()
        repeat(CleanupSettingsCodec.MAX_CUSTOM + 5) { many = many.addCustom("p$it") }
        assertEquals(CleanupSettingsCodec.MAX_CUSTOM, many.custom.size)
    }

    @Test
    fun `an OFF custom rule does not need the monitor, an ASK one does`() {
        assertFalse(CleanupSettings().watching)
        assertTrue(CleanupSettings().addCustom("node").watching)
        assertTrue("health watching alone keeps the monitor", CleanupSettings().monitorNeeded)
        assertFalse(CleanupSettings(watchHealth = false).monitorNeeded)
    }

    // ---- the budget guard

    private fun candidate(idleMin: Int, memMb: Long, pid: Int = 10, allowed: Boolean = true): CleanupCandidate {
        val v = view(listOf(rec(pid, 1, "node", rss = memMb * mb)))
        return CleanupCandidate(v.all[0], CleanupRule.HEAVY, CleanupMode.OFF, idleMin * min, 60 * min, allowed)
    }

    @Test
    fun `the guard is off until asked for and quiet below its line`() {
        val cs = listOf(candidate(30, 900))
        assertTrue(Cleanup.budgetPick(cs, CleanupSettings(), ProcessBudgetFacts(31)).isEmpty())
        assertTrue(Cleanup.budgetPick(cs, CleanupSettings(budgetMode = CleanupMode.AUTO, budgetAt = 28), ProcessBudgetFacts(27)).isEmpty())
    }

    @Test
    fun `the guard stops one, the biggest, ahead of its rule, and is then due`() {
        val s = CleanupSettings(budgetMode = CleanupMode.AUTO, budgetAt = 28)
        val pick = Cleanup.budgetPick(listOf(candidate(30, 200, 10), candidate(30, 900, 11)), s, ProcessBudgetFacts(28)).single()
        assertEquals(11, pick.entry.pid)
        assertTrue(pick.due)
        assertEquals(listOf(pick), Cleanup.dueForAuto(listOf(pick)))
    }

    @Test
    fun `the guard never takes what worked minutes ago, or what came back`() {
        val s = CleanupSettings(budgetMode = CleanupMode.AUTO, budgetAt = 28)
        assertTrue(Cleanup.budgetPick(listOf(candidate(2, 900)), s, ProcessBudgetFacts(31)).isEmpty())
        assertTrue(Cleanup.budgetPick(listOf(candidate(30, 900, allowed = false)), s, ProcessBudgetFacts(31)).isEmpty())
    }

    // ---- limits

    @Test
    fun `the per-check limit is the user's`() {
        val cs = (1..6).map { candidate(120, 900, pid = it + 10).copy(mode = CleanupMode.AUTO) }
        assertEquals(1, Cleanup.dueForAuto(cs, 1).size)
        assertEquals(5, Cleanup.dueForAuto(cs, 5).size)
    }

    @Test
    fun `the memory condition waits for low memory, and unknown memory means wait`() {
        val s = CleanupSettings(lowMemoryPercent = 20)
        assertTrue(Cleanup.memoryAllows(CleanupSettings(), null))
        assertFalse(Cleanup.memoryAllows(s, null))
        assertTrue(Cleanup.memoryAllows(s, MemInfo(totalKb = 1000, availableKb = 150)))
        assertFalse(Cleanup.memoryAllows(s, MemInfo(totalKb = 1000, availableKb = 500)))
    }

    // ---- the file

    @Test
    fun `everything new survives the file`() {
        val s = CleanupSettings(
            budgetMode = CleanupMode.AUTO, budgetAt = 26, perSweep = 5, lowMemoryPercent = 20, watchHealth = false,
            swarmAt = 8, cpuAtPercent = 150, cpuMinutes = 10, hogMb = 4000, autoCalm = true, quiet = setOf("node"),
        ).addCustom("firefox-esr").withCustom("firefox-esr", RuleSettings(CleanupMode.AUTO, 15, 200))
        assertEquals(s, CleanupSettingsCodec.decode(CleanupSettingsCodec.encode(s)))
    }

    @Test
    fun `a 2_2 file still loads, with the new things at their defaults`() {
        val s = CleanupSettingsCodec.decode("builds=AUTO,30,0\nheavy=OFF,60,500\nkeep=node\n")
        assertEquals(CleanupMode.AUTO, s.builds.mode)
        assertEquals(setOf("node"), s.keep)
        assertEquals(CleanupMode.OFF, s.budgetMode)
        assertTrue(s.watchHealth)
        assertFalse(s.autoCalm)
    }

    @Test
    fun `a hand-edited file cannot make anything reckless`() {
        val s = CleanupSettingsCodec.decode("budget=AUTO,2\nperSweep=999\nheavy=AUTO,1,0\ncustom=AUTO,1,0,bad;name\ncustom=AUTO,1,0,node\nhealth=1,1,1,1,1,0\n")
        assertEquals(28, s.budgetAt)
        assertEquals(Cleanup.MAX_PER_SWEEP, s.perSweep)
        assertEquals(CleanupSettings.DEFAULT_HEAVY.idleMinutes, s.heavy.idleMinutes)
        assertEquals(listOf("node"), s.custom.map { it.name })
        assertEquals(CleanupSettingsCodec.DEFAULT_CUSTOM.idleMinutes, s.custom[0].settings.idleMinutes)
        assertEquals(12, s.swarmAt)
    }
}
