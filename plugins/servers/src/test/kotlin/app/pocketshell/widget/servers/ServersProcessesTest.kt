package app.pocketshell.widget.servers

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The process layer: parsers against REAL /proc lines captured on the
 * device (Gradle daemon, login shell, dbus), the rule-based classifier, the
 * survive-the-terminal facts, the session/background grouping, and the
 * scanner over a fake /proc plus a smoke test over the machine's own.
 */
class ServersProcessesTest {

    // ----------------------------------------------- real device fixtures

    private val gradleStat =
        "13925 (java) S 1 13925 13925 0 -1 1077936128 428324 83183 227452 134 13949 2312 796 116 10 -10 34 0 149432038 " +
            "7394807808 46176 18446744073709551615 343597383680 343597415704 549423179248 0 0 0 0 0 1090542799 0 0 0 17 4 0 0 0 0 0 " +
            "343597432088 343597432096 343958577152 549423182585 549423183684 549423183684 549423185814 0"

    private val shStat =
        "17417 (sh) S 17412 17417 17412 34816 17445 4194304 297 1810 2 4 0 0 2 0 10 -10 1 0 149476152 1843200 63 " +
            "18446744073709551615 2097152 137438954496 548810575792 0 0 0 0 2637828 65538 1 0 0 17 0 0 0 0 0 0 " +
            "137438953472 137438954496 138486755328 548810579222 548810579233 548810579233 548810579853 0"

    private val gradleArgv = "/usr/lib/jvm/temurin-21.0.12.1+1/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED " +
        "-Xmx512m -cp /root/.gradle/wrapper/dists/gradle-8.14.5-bin/lib/gradle-launcher-8.14.5.jar " +
        "org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.5"

    // ------------------------------------------------------------ builders

    /** A well-formed 52-field stat line; tail field N lives at index N-3. */
    private fun stat(
        pid: Int, comm: String, ppid: Int, state: Char = 'S', pgrp: Int = pid, sid: Int = pid,
        tty: Int = 0, tpgid: Int = -1, utime: Long = 0, stime: Long = 0, threads: Int = 1,
        start: Long = 1000, rssPages: Long = 0,
    ): String {
        val f = MutableList(50) { "0" }
        f[0] = state.toString(); f[1] = ppid.toString(); f[2] = pgrp.toString(); f[3] = sid.toString()
        f[4] = tty.toString(); f[5] = tpgid.toString(); f[11] = utime.toString(); f[12] = stime.toString()
        f[17] = threads.toString(); f[19] = start.toString(); f[21] = rssPages.toString()
        return "$pid ($comm) ${f.joinToString(" ")}"
    }

    private fun rec(
        pid: Int, comm: String, argv: String?, ppid: Int, tty: Int = 0, pgrp: Int = pid, tpgid: Int = -1,
        startedAtMs: Long = 0, rss: Long = 1_000_000, swap: Long = 0, hup: Boolean = false, cwd: String? = "/root",
    ) = ProcRecord(
        pid = pid, ppid = ppid, comm = comm, argv = argv, state = 'S', pgrp = pgrp, sid = pid, tty = tty,
        tpgid = tpgid, threads = 1, cpuTicks = 0, rssBytes = rss, swapBytes = swap, startedAtMs = startedAtMs,
        cwd = cwd, hupIgnored = hup,
    )

    // -------------------------------------------------------------- parsing

    @Test
    fun `the real gradle daemon stat parses - detached, reparented, own session`() {
        val s = ProcRecordParsing.parseStat(gradleStat)!!
        assertEquals("java", s.comm)
        assertEquals('S', s.state)
        assertEquals(1, s.ppid)
        assertEquals(13925, s.pgrp)
        assertEquals(13925, s.sid)
        assertEquals(0, s.tty)
        assertEquals(-1, s.tpgid)
        assertEquals(34, s.threads)
        assertEquals(13949L + 2312L, s.cpuTicks)
        assertEquals(149432038L, s.startTicks)
        assertEquals(46176L * 4096, s.rssBytes)
    }

    @Test
    fun `the real login shell stat parses - it has a terminal and a foreground group`() {
        val s = ProcRecordParsing.parseStat(shStat)!!
        assertEquals("sh", s.comm)
        assertEquals(17412, s.ppid)
        assertEquals(34816, s.tty)
        assertEquals(17445, s.tpgid)
    }

    @Test
    fun `a comm with spaces and parens does not shift the fields`() {
        val s = ProcRecordParsing.parseStat(stat(600, "tmux: server (x)", ppid = 1, tty = 0, threads = 3))!!
        assertEquals("tmux: server (x)", s.comm)
        assertEquals(1, s.ppid)
        assertEquals(3, s.threads)
    }

    @Test
    fun `a truncated or garbled stat is rejected, never guessed`() {
        assertNull(ProcRecordParsing.parseStat(""))
        assertNull(ProcRecordParsing.parseStat("12 (x) S 1 2"))
        assertNull(ProcRecordParsing.parseStat("12 (x) S a b c d e f g h i j k l m n o p q r s t u v w"))
    }

    @Test
    fun `SIGHUP ignored is bit zero of SigIgn`() {
        // Real values from the device: the login shell and dbus do NOT ignore HUP.
        assertFalse(ProcRecordParsing.hupIgnored("Name:\tsh\nSigIgn:\t0000002000284004\n"))
        assertFalse(ProcRecordParsing.hupIgnored("SigIgn:\t0000000000000004\n"))
        assertTrue(ProcRecordParsing.hupIgnored("SigIgn:\t0000000000000005\n"))
        // All bits set overflows a signed Long — must still parse.
        assertTrue(ProcRecordParsing.hupIgnored("SigIgn:\tffffffffffffffff\n"))
        assertFalse(ProcRecordParsing.hupIgnored("no such line"))
        assertFalse(ProcRecordParsing.hupIgnored("SigIgn:\tnot-hex\n"))
    }

    @Test
    fun `VmSwap is read in kB and missing means zero`() {
        val f = ProcRecordParsing.statusFacts("VmRSS:\t  155700 kB\nVmSwap:\t 1042256 kB\nSigIgn:\t0000000000000000\n")
        assertEquals(1042256L * 1024, f.swapBytes)
        assertEquals(0L, ProcRecordParsing.statusFacts("VmRSS:\t 1 kB\n").swapBytes)
    }

    // ------------------------------------------------------ survival facts

    @Test
    fun `survival is a kernel fact - tty, then SIGHUP`() {
        assertEquals(Survival.DETACHED, rec(1, "java", null, 1, tty = 0).survival)
        assertEquals(Survival.NOHUP, rec(2, "node", null, 1, tty = 5, hup = true).survival)
        assertEquals(Survival.ATTACHED, rec(3, "sh", null, 1, tty = 5).survival)
        // A tty-less process that happens to carry the HUP bit is still just detached.
        assertEquals(Survival.DETACHED, rec(4, "x", null, 1, tty = 0, hup = true).survival)
    }

    // ------------------------------------------------------- classification

    private fun id(comm: String, argv: String?, tty: Int = 0) = ProcClassifier.identify(rec(10, comm, argv, 1, tty = tty))

    @Test
    fun `gradle daemon is a build tool with its version - not just java`() {
        val i = id("java", gradleArgv)
        assertEquals(ProcKind.BUILD, i.kind)
        assertEquals("Gradle daemon 8.14.5", i.label)
    }

    @Test
    fun `other build daemons`() {
        assertEquals("Kotlin daemon", id("java", "java -cp x.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon").label)
        assertEquals("Gradle wrapper", id("java", "java -classpath gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assemble").label)
        assertEquals(ProcKind.BUILD, id("java", "java -jar sbt-launch.jar").kind)
    }

    @Test
    fun `plain java names its main class or jar`() {
        assertEquals("java Main", id("java", "java -Xmx1g -cp lib/a.jar:lib/b.jar com.example.Main arg").label)
        assertEquals("java app.jar", id("java", "/usr/bin/java -jar /srv/app.jar --port 80").label)
        assertEquals("java", id("java", "java").label)
    }

    @Test
    fun `interpreters name their script`() {
        assertEquals("node server.js", id("node", "node --inspect /srv/app/server.js").label)
        assertEquals("python manage.py", id("python3.12", "/usr/bin/python3.12 manage.py runserver").label)
        assertEquals("node", id("node", "node").label)
    }

    @Test
    fun `tmux server is a session keeper, an attached tmux is only a client`() {
        val server = ProcClassifier.identify(rec(20, "tmux: server", "tmux: server", 1, tty = 0))
        assertEquals(ProcKind.MUX, server.kind)
        assertEquals("tmux", server.label)
        val client = id("tmux", "tmux attach -t main", tty = 34816)
        assertEquals(ProcKind.OTHER, client.kind)
        assertEquals("tmux client", client.label)
        assertEquals(ProcKind.MUX, id("screen", "SCREEN -S work").kind)
    }

    @Test
    fun `a shell with a tty is a SHELL, without one it is a command runner`() {
        assertEquals(Identity(ProcKind.SHELL, "sh"), id("sh", "/bin/sh -l", tty = 34816))
        assertEquals(Identity(ProcKind.SHELL, "bash"), id("bash", "-bash", tty = 34816))
        assertEquals(Identity(ProcKind.OTHER, "bash -c"), id("bash", "/bin/bash -c source /x/snapshot.sh && run"))
    }

    @Test
    fun `known tools, system helpers, and the unknown`() {
        assertEquals(Identity(ProcKind.TOOL, "Claude Code"), id("claude", "claude", tty = 34816))
        assertEquals(Identity(ProcKind.SYSTEM, "dbus-daemon"), id("dbus-daemon", "dbus-daemon --session --nofork"))
        assertEquals(Identity(ProcKind.OTHER, "mytool"), id("mytool", "/opt/bin/mytool --flag"))
        // Unreadable argv falls back to comm.
        assertEquals(Identity(ProcKind.OTHER, "mystery"), id("mystery", null))
    }

    @Test
    fun `infrastructure is the app, its proot tracer and psx - never guest`() {
        assertTrue(ProcClassifier.isInfrastructure("app.pocketshell", "app.pocketshell"))
        assertTrue(ProcClassifier.isInfrastructure("/data/app/x/lib/arm64/libproot.so --kill-on-exit -r /rootfs", "libproot.so"))
        assertTrue(ProcClassifier.isInfrastructure("/data/app/x/lib/arm64/libpsx.so run", "libpsx.so"))
        assertFalse(ProcClassifier.isInfrastructure("/bin/sh -l", "sh"))
        assertFalse(ProcClassifier.isInfrastructure(null, "java"))
    }

    // ------------------------------------------------------------- grouping

    /**
     * The shape the device showed: a login shell running Claude Code (whose
     * tool shell runs a command), a detached Gradle daemon, dbus, a tmux
     * server, and a detached dev server on :3000.
     */
    private fun deviceLikeView(): ProcView {
        val records = listOf(
            rec(100, "sh", "/bin/sh -l", ppid = 17412, tty = 34816, pgrp = 100, tpgid = 300, startedAtMs = 1_000),
            rec(200, "bash", "-bash", ppid = 100, tty = 34816, pgrp = 200, tpgid = 300, startedAtMs = 2_000),
            rec(300, "claude", "claude", ppid = 200, tty = 34816, pgrp = 300, tpgid = 300, startedAtMs = 3_000),
            rec(400, "java", gradleArgv, ppid = 1, rss = 155_000_000, swap = 1_000_000_000),
            rec(500, "dbus-daemon", "dbus-daemon --session", ppid = 1),
            rec(600, "tmux: server", "tmux: server", ppid = 1),
            rec(700, "bash", "/bin/bash -c run", ppid = 300),
            rec(800, "node", "node /srv/app.js", ppid = 1),
        )
        return ProcViews.build(records, cpu = mapOf(400 to 2.5f, 800 to 0.1f), portsByPid = mapOf(800 to listOf(3000), 400 to listOf(43521)))
    }

    @Test
    fun `sessions are the top-level terminal shell and the session keeper`() {
        val v = deviceLikeView()
        assertEquals(listOf(100, 600), v.sessions.map { it.leader.pid }.sorted())
        val shell = v.sessions.first { it.leader.pid == 100 }
        assertFalse(shell.keeper)
        assertTrue(v.sessions.first { it.leader.pid == 600 }.keeper)
        // The nested bash is a descendant, not a second session.
        assertEquals(setOf(200, 300, 700), shell.descendants.map { it.pid }.toSet())
    }

    @Test
    fun `a session is labelled by the terminal's foreground job`() {
        val shell = deviceLikeView().sessions.first { it.leader.pid == 100 }
        assertEquals("Claude Code", shell.foreground)
    }

    @Test
    fun `an idle shell says idle, no foreground group says nothing`() {
        val idle = ProcViews.build(
            listOf(rec(1, "sh", "/bin/sh -l", ppid = 9, tty = 5, pgrp = 1, tpgid = 1)),
            emptyMap(), emptyMap(),
        ).sessions.single()
        assertEquals("idle", idle.foreground)
        val none = ProcViews.build(
            listOf(rec(1, "sh", "/bin/sh -l", ppid = 9, tty = 5, pgrp = 1, tpgid = -1)),
            emptyMap(), emptyMap(),
        ).sessions.single()
        assertNull(none.foreground)
    }

    @Test
    fun `background is detached processes that are not servers or keepers`() {
        val v = deviceLikeView()
        // gradle, dbus, and the tool's command shell; NOT tmux (a session), NOT :3000 (a server), NOT the attached ones.
        assertEquals(setOf(400, 500, 700), v.background.map { it.pid }.toSet())
    }

    @Test
    fun `what outlives the terminal is daemonized - child jobs and system helpers do not count`() {
        val v = deviceLikeView()
        // gradle, tmux, and the detached server; dbus is SYSTEM and 700 is a child of live claude.
        assertEquals(setOf(400, 600, 800), v.outlive.map { it.pid }.toSet())
    }

    @Test
    fun `listeners by rule-named build or keeper tools are not dev servers`() {
        val v = deviceLikeView()
        // :43521 belongs to the Gradle daemon (IPC), :3000 to the node app.
        assertEquals(setOf(800), v.devServerPids())
        assertEquals(ProcKind.SERVER, v.byPid.getValue(800).kind)
        assertEquals(ProcKind.BUILD, v.byPid.getValue(400).kind)
    }

    @Test
    fun `memory ranks by footprint - a swapped-out daemon is still the big one`() {
        val v = deviceLikeView()
        assertEquals(400, v.topByMemory(1).single().pid)
        assertEquals(listOf(400), v.topByCpu(3, minPercent = 0.5f).map { it.pid })
    }

    @Test
    fun `descendants terminate on a parent cycle`() {
        val cyc = ProcViews.build(
            listOf(rec(1, "a", "a", ppid = 2, tty = 5), rec(2, "b", "b", ppid = 1, tty = 5)),
            emptyMap(), emptyMap(),
        )
        assertEquals(listOf(2), ProcViews.descendants(cyc.all, 1).map { it.pid })
    }

    // --------------------------------------------------------- presentation

    @Test
    fun `filter matches label, pid, cwd and argv`() {
        val e = deviceLikeView().all
        assertEquals(listOf(400), ProcPresentation.filter(e, "gradle").map { it.pid })
        assertEquals(listOf(600), ProcPresentation.filter(e, "600").map { it.pid })
        assertEquals(e.size, ProcPresentation.filter(e, "  ").size)
    }

    @Test
    fun `sorting - memory by footprint, uptime oldest first, name alphabetical`() {
        val e = deviceLikeView().background
        assertEquals(400, ProcPresentation.sort(e, BgSort.MEMORY, 0).first().pid)
        val byName = ProcPresentation.sort(e, BgSort.NAME, 0).map { it.label.lowercase() }
        assertEquals(byName.sorted(), byName)
        val old = rec(1, "a", "a", 1, startedAtMs = 1_000)
        val young = rec(2, "b", "b", 1, startedAtMs = 9_000)
        val two = ProcViews.build(listOf(young, old), emptyMap(), emptyMap()).all
        assertEquals(1, ProcPresentation.sort(two, BgSort.UPTIME, 10_000).first().pid)
    }

    @Test
    fun `swap is only called out when it matters`() {
        val v = deviceLikeView()
        val gradle = v.byPid.getValue(400)
        assertEquals("147.8 MB in RAM · 953.6 MB swapped", ProcPresentation.memorySplit(gradle))
        assertNull(ProcPresentation.memorySplit(v.byPid.getValue(500)))
        assertEquals("2 up · 3 bg · 1 ses", ProcPresentation.summary(2, 3, 1))
    }

    @Test
    fun `the controlling terminal decodes to a readable name`() {
        // 34816 = 0x8800: major 136 (a Unix98 pty), minor 0 — what the device's shell reported.
        assertEquals("pts/0", ProcPresentation.ttyName(34816))
        assertEquals("pts/3", ProcPresentation.ttyName(34816 + 3))
        assertEquals("none", ProcPresentation.ttyName(0))
        assertEquals("tty1", ProcPresentation.ttyName((4 shl 8) or 1))
        assertEquals("tty 12345", ProcPresentation.ttyName(12345))
    }

    // -------------------------------------------------------------- scanner

    private fun fakeProc(): File = Files.createTempDirectory("fakeproc").toFile()

    private fun File.proc(
        pid: Int, statLine: String, argv: String, cwd: String? = "/root/work", status: String = "VmSwap:\t0 kB\nSigIgn:\t0000000000000000\n",
    ) {
        val d = File(this, pid.toString()).apply { mkdirs() }
        File(d, "stat").writeText(statLine)
        File(d, "status").writeText(status)
        File(d, "cmdline").writeBytes(argv.replace(' ', '\u0000').toByteArray())
        if (cwd != null) {
            Files.deleteIfExists(File(d, "cwd").toPath())
            Files.createSymbolicLink(File(d, "cwd").toPath(), File(cwd).toPath())
        }
    }

    @Test
    fun `the scanner reads guest processes, skips infrastructure, derives start and swap`() {
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("1000.00 900.00\n")
            // started 500s after boot ⇒ 500s old at uptime 1000.
            root.proc(10, stat(10, "java", ppid = 1, start = 50_000, rssPages = 100, threads = 7), gradleArgv,
                status = "VmSwap:\t 2048 kB\nSigIgn:\t0000000000000000\n")
            root.proc(11, stat(11, "libproot.so", ppid = 5), "/data/app/x/lib/arm64/libproot.so --kill-on-exit")
            root.proc(12, stat(12, "app.pocketshell", ppid = 1), "app.pocketshell")
            File(root, "uptime2").writeText("not a pid dir")

            val now = 1_000_000L
            val snap = ProcessScanner(root.path).scan(now)
            val r = snap.records.single()
            assertEquals(10, r.pid)
            assertEquals(7, r.threads)
            assertEquals(100L * 4096, r.rssBytes)
            assertEquals(2048L * 1024, r.swapBytes)
            assertEquals("/root/work", r.cwd)
            assertEquals(now - 500_000L, r.startedAtMs!!)
            assertEquals(gradleArgv, r.argv)
            assertEquals(setOf(10, 11, 12), snap.pidSet)
            // First sighting: no CPU answer yet — "—", never 0%.
            assertNull(snap.cpu[10])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cpu percent is the tick delta across two scans`() {
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("1000.00 0\n")
            root.proc(10, stat(10, "java", ppid = 1, utime = 100, stime = 0), "java x")
            val scanner = ProcessScanner(root.path)
            scanner.scan(nowMs = 0)
            // 200 ticks over 10 s of wall time = 2 s of CPU in 10 s = 20%.
            root.proc(10, stat(10, "java", ppid = 1, utime = 300, stime = 0), "java x")
            val second = scanner.scan(nowMs = 10_000)
            assertEquals(20f, second.cpu[10]!!, 0.1f)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a reused pid is not given the old process's argv`() {
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("1000.00 0\n")
            root.proc(10, stat(10, "old", ppid = 1, start = 100), "old-program --a")
            val scanner = ProcessScanner(root.path)
            assertEquals("old-program --a", scanner.scan(0).records.single().argv)
            root.proc(10, stat(10, "new", ppid = 1, start = 90_000), "new-program --b")
            assertEquals("new-program --b", scanner.scan(1).records.single().argv)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable process degrades alone`() {
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("1000.00 0\n")
            root.proc(10, stat(10, "ok", ppid = 1), "ok x")
            File(root, "11").mkdirs() // a pid dir with no readable stat (vanished mid-scan)
            val snap = ProcessScanner(root.path).scan(0)
            assertEquals(listOf(10), snap.records.map { it.pid })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a missing uptime keeps the process but its age is unknown, never zero`() {
        val root = fakeProc()
        try {
            root.proc(10, stat(10, "ok", ppid = 1), "ok x")
            val r = ProcessScanner(root.path).scan(5_000).records.single()
            assertNull(r.startedAtMs)
            assertNull(r.ageSec(5_000))
            assertEquals("—", ProcPresentation.age(r, 5_000))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a stale uptime clock - a start after now - makes the age unknown`() {
        // The real guest case: /proc/uptime is frozen, so a brand-new process "starts in the future".
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("100.00 0\n")
            root.proc(10, stat(10, "fresh", ppid = 1, start = 500_000), "fresh x")
            root.proc(11, stat(11, "old", ppid = 1, start = 5_000), "old x") // 50 s after boot: plausible
            val recs = ProcessScanner(root.path).scan(1_000_000).records.associateBy { it.pid }
            assertNull(recs.getValue(10).startedAtMs)
            assertEquals(1_000_000L - 50_000L, recs.getValue(11).startedAtMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a start within the clock-skew tolerance is just brand new`() {
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("100.00 0\n")
            root.proc(10, stat(10, "x", ppid = 1, start = 10_100), "x") // 1 s "ahead"
            val r = ProcessScanner(root.path).scan(1_000_000).records.single()
            assertEquals(0L, r.ageSec(1_000_000))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the cwd mapper is applied to every cwd read`() {
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("1000.00 0\n")
            root.proc(10, stat(10, "x", ppid = 1), "x", cwd = "/data/user/0/app/no_backup/runtime/rootfs/root/work")
            val scanner = ProcessScanner(root.path, cwdMapper = { "mapped:$it" })
            assertEquals("mapped:/data/user/0/app/no_backup/runtime/rootfs/root/work", scanner.scan(0).records.single().cwd)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `host paths map back to the guest path the user knows`() {
        val data = "/data/user/0/app.pocketshell"
        val rootfs = "$data/no_backup/runtime/rootfs"
        assertEquals("/root/.gradle/daemon/8.14.5", ProcPaths.guestPath("$rootfs/root/.gradle/daemon/8.14.5", data, rootfs))
        assertEquals("/", ProcPaths.guestPath(rootfs, data, rootfs))
        assertEquals("~/Projects/x", ProcPaths.guestPath("$data/files/home/Projects/x", data, rootfs))
        assertEquals("~", ProcPaths.guestPath("$data/files/home", data, rootfs))
        // Unknown anchors or foreign paths are left exactly as read.
        assertEquals("/somewhere/else", ProcPaths.guestPath("/somewhere/else", data, rootfs))
        assertEquals("/root/work", ProcPaths.guestPath("/root/work", null, null))
        // A sibling that merely shares a prefix is not inside the rootfs.
        assertEquals("$rootfs-old/x", ProcPaths.guestPath("$rootfs-old/x", data, rootfs))
    }

    // ------------------------------------------------------- real /proc smoke

    @Test
    fun `the real proc lists this very test process`() {
        assumeTrue("needs a Linux /proc", File("/proc/self/stat").isFile)
        val me = File("/proc/self/stat").readText().substringBefore(' ').toInt()
        val snap = ProcessScanner().scan()
        val mine = snap.records.firstOrNull { it.pid == me }
        // Own-UID processes are always readable; infrastructure filtering must not eat a plain JVM.
        assertNotNull("the test JVM should be a guest-visible process", mine)
        assertTrue(mine!!.rssBytes > 0)
        assertTrue(mine.threads >= 1)
    }

    // ----------------------------------------------- review follow-ups

    @Test
    fun `build tools are matched on argv tokens - a path that merely contains the name is not one`() {
        // node running a server out of a directory called mvnd-app must stay a plain process.
        val node = id("node", "node /home/u/mvnd-app/server.js")
        assertEquals(ProcKind.OTHER, node.kind)
        assertEquals("node server.js", node.label)
        assertEquals(ProcKind.OTHER, id("python3", "python3 /srv/kotlin-daemon-notes/app.py").kind)
        assertEquals(ProcKind.OTHER, id("node", "node --title GradleDaemon-fan app.js").kind)
        // ...while the real thing still is one.
        assertEquals(ProcKind.BUILD, id("java", "java -jar /opt/mvnd-1.0/lib/mvnd-daemon.jar").kind)
        assertEquals(ProcKind.BUILD, id("mvnd", "/usr/bin/mvnd clean").kind)
        assertEquals("Maven", id("java", "java -classpath boot.jar org.codehaus.plexus.classworlds.launcher.Launcher").label)
    }

    @Test
    fun `cwd and argv are read fresh every scan - cd and exec are visible`() {
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("1000.00 0\n")
            root.proc(10, stat(10, "sh", ppid = 1, start = 100), "sh -l", cwd = "/root/a")
            val scanner = ProcessScanner(root.path)
            val first = scanner.scan(0).records.single()
            assertEquals("/root/a", first.cwd)
            assertEquals("sh -l", first.argv)
            // Same pid, same start: the shell cd'd and exec'd a server.
            root.proc(10, stat(10, "node", ppid = 1, start = 100), "node server.js", cwd = "/root/b")
            val second = scanner.scan(1).records.single()
            assertEquals("/root/b", second.cwd)
            assertEquals("node server.js", second.argv)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a reused pid does not inherit the dead process's cpu delta`() {
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("1000.00 0\n")
            root.proc(10, stat(10, "old", ppid = 1, utime = 100, start = 100), "old")
            val scanner = ProcessScanner(root.path)
            scanner.scan(0)
            // Same pid, NEW process (different start ticks): first sighting says "—", not a bogus %.
            root.proc(10, stat(10, "new", ppid = 1, utime = 5, start = 90_000), "new")
            assertNull(scanner.scan(10_000).cpu[10])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `over the cap the newest pids are kept - they are what the user just started`() {
        val root = fakeProc()
        try {
            File(root, "uptime").writeText("1000.00 0\n")
            (1..6).forEach { root.proc(it, stat(it, "p$it", ppid = 1), "p$it") }
            val kept = ProcessScanner(root.path, maxPids = 3).scan(0).records.map { it.pid }
            assertEquals(listOf(4, 5, 6), kept)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `identity check - gone, infrastructure and reused pids are refused, the same process is not`() {
        val root = fakeProc()
        try {
            root.proc(10, stat(10, "java", ppid = 1, start = 777), "java -jar app.jar")
            root.proc(11, stat(11, "libproot.so", ppid = 1, start = 5), "/data/app/x/lib/arm64/libproot.so --kill-on-exit")
            val me = ProcIdentityCheck.read(10, root.path)!!
            assertEquals(777L, me.startTicks)
            assertFalse(me.infrastructure)
            assertNull(ProcIdentityCheck.refusal(777, me))
            assertNull(ProcIdentityCheck.refusal(null, me))
            // The pid was reused by a different process after the confirm.
            assertNotNull(ProcIdentityCheck.refusal(776, me))
            // Gone.
            assertNull(ProcIdentityCheck.read(99, root.path))
            assertNotNull(ProcIdentityCheck.refusal(777, null))
            // PocketShell's own helper is never signalled.
            val infra = ProcIdentityCheck.read(11, root.path)!!
            assertTrue(infra.infrastructure)
            assertTrue(ProcIdentityCheck.refusal(null, infra)!!.contains("PocketShell"))
        } finally {
            root.deleteRecursively()
        }
    }
}
