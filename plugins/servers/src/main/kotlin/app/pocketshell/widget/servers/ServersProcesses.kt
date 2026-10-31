package app.pocketshell.widget.servers

import java.io.File
import java.nio.file.Files

/**
 * The process layer — everything the app's own UID may read about the
 * guest's processes, as facts:
 *
 *   - `/proc/<pid>/stat`   identity, parent, session, controlling tty,
 *                          foreground group, threads, start time, CPU ticks, RSS
 *   - `/proc/<pid>/status` SigIgn (does it ignore SIGHUP — `nohup`) + VmSwap
 *   - `/proc/<pid>/cmdline` full argv, `/proc/<pid>/cwd` (already a GUEST path)
 *
 * What "survives closing the terminal" means here is a kernel fact, not a
 * guess: a process with NO controlling terminal ([Survival.DETACHED]) is not
 * sent SIGHUP when a terminal closes; one that keeps its tty but ignores
 * SIGHUP ([Survival.NOHUP]) shrugs it off; every other process is
 * [Survival.ATTACHED] and goes down with its terminal.
 *
 * Kinds ([ProcKind]) are rule-based labels over comm/argv. They name what
 * the rule matched and nothing more; an unmatched process is simply OTHER.
 *
 * Pure parsing + classification + grouping (JVM-tested); the only IO is
 * [ProcessScanner], whose root is injectable so tests read a fake /proc.
 */

// ------------------------------------------------------------------ model

internal enum class Survival(val label: String, val explanation: String) {
    DETACHED("NO TERMINAL", "no controlling terminal — closing a terminal does not signal it"),
    NOHUP("IGNORES HANGUP", "keeps its terminal but ignores SIGHUP (nohup) — closing the terminal does not stop it"),
    ATTACHED("TERMINAL-BOUND", "bound to a terminal — closing that terminal ends it"),
}

internal enum class ProcKind(val label: String) {
    SERVER("SERVER"),
    BUILD("BUILD"),
    MUX("SESSION KEEPER"),
    SHELL("SHELL"),
    TOOL("TOOL"),
    SYSTEM("SYSTEM"),
    OTHER("PROCESS"),
}

/** One guest process, as /proc stated it at scan time. */
internal data class ProcRecord(
    val pid: Int,
    val ppid: Int,
    val comm: String,
    /** Full argv, NUL-joined to spaces; null when unreadable. */
    val argv: String?,
    val state: Char,
    val pgrp: Int,
    val sid: Int,
    /** tty_nr — 0 means no controlling terminal. */
    val tty: Int,
    /** Foreground process group of this process's terminal (-1 without one). */
    val tpgid: Int,
    val threads: Int,
    val cpuTicks: Long,
    val rssBytes: Long,
    /** VmSwap — memory the kernel pushed out of RAM (zram); a big idle daemon often lives here. */
    val swapBytes: Long,
    /** Wall-clock start, derived from uptime − start ticks; null when the uptime clock is untrustworthy. */
    val startedAtMs: Long?,
    /** Guest-visible cwd; null when unreadable. */
    val cwd: String?,
    /** SigIgn bit for SIGHUP (only meaningful with a tty — see [survival]). */
    val hupIgnored: Boolean,
    /** stat's starttime — with the pid, the process's IDENTITY (a reused pid has a new one). */
    val startTicks: Long = 0,
    /** The main thread's nice value: above 0 means it runs at a lowered priority. */
    val nice: Int = 0,
) {
    val hasTty: Boolean get() = tty != 0

    /** Resident + swapped: the process's real memory cost, not just what is in RAM right now. */
    val footprintBytes: Long get() = rssBytes + swapBytes

    val survival: Survival
        get() = when {
            tty == 0 -> Survival.DETACHED
            hupIgnored -> Survival.NOHUP
            else -> Survival.ATTACHED
        }

    /** Seconds since start; null when the start time is unknown — never a made-up 0. */
    fun ageSec(nowMs: Long): Long? = startedAtMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) }
}

internal data class Identity(val kind: ProcKind, val label: String)

// ---------------------------------------------------------------- parsing

internal object ProcRecordParsing {

    /** The fields /proc/<pid>/stat gives us beyond [ProcParsing.parseStat]'s CPU/RSS. */
    data class Stat(
        val comm: String,
        val state: Char,
        val ppid: Int,
        val pgrp: Int,
        val sid: Int,
        val tty: Int,
        val tpgid: Int,
        val threads: Int,
        val cpuTicks: Long,
        val startTicks: Long,
        val rssBytes: Long,
        val nice: Int = 0,
    )

    /**
     * comm is parenthesised and may hold spaces/parens, so split on the
     * FIRST '(' and LAST ')'. Tail field N maps to tail[N-3]: state=3→0,
     * ppid=4→1, pgrp=5→2, session=6→3, tty_nr=7→4, tpgid=8→5, utime=14→11,
     * stime=15→12, nice=19→16, num_threads=20→17, starttime=22→19, rss=24→21.
     */
    fun parseStat(text: String): Stat? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val tail = text.substring(close + 1).trim().split(' ')
        if (tail.size < 22) return null
        return Stat(
            comm = text.substring(open + 1, close),
            state = tail[0].firstOrNull() ?: return null,
            ppid = tail[1].toIntOrNull() ?: return null,
            pgrp = tail[2].toIntOrNull() ?: return null,
            sid = tail[3].toIntOrNull() ?: return null,
            tty = tail[4].toIntOrNull() ?: return null,
            tpgid = tail[5].toIntOrNull() ?: return null,
            threads = tail[17].toIntOrNull() ?: return null,
            cpuTicks = (tail[11].toLongOrNull() ?: return null) + (tail[12].toLongOrNull() ?: return null),
            startTicks = tail[19].toLongOrNull() ?: return null,
            rssBytes = (tail[21].toLongOrNull() ?: return null) * ProcParsing.PAGE_BYTES,
            nice = tail[16].toIntOrNull() ?: 0,
        )
    }

    /** What /proc/<pid>/status adds: SIGHUP handling and swapped-out memory. */
    data class StatusFacts(val hupIgnored: Boolean, val swapBytes: Long)

    fun statusFacts(statusText: String): StatusFacts {
        val swapKb = statusText.lineSequence()
            .firstOrNull { it.startsWith("VmSwap:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L
        return StatusFacts(hupIgnored = hupIgnored(statusText), swapBytes = swapKb * 1024)
    }

    /** True when `SigIgn:` has the SIGHUP bit (signal 1 → bit 0) set. */
    fun hupIgnored(statusText: String): Boolean {
        val hex = statusText.lineSequence()
            .firstOrNull { it.startsWith("SigIgn:") }
            ?.substringAfter(':')?.trim() ?: return false
        val mask = try {
            java.lang.Long.parseUnsignedLong(hex, 16)
        } catch (_: NumberFormatException) {
            return false
        }
        return (mask and 1L) != 0L
    }

    /** The /proc/<pid>/cmdline bytes → "a b c"; null when empty. */
    fun argv(bytes: ByteArray): String? = ProcParsing.parseArgv(bytes)
}

// ---------------------------------------------------------- classification

internal object ProcClassifier {

    private val SHELLS = setOf("sh", "bash", "zsh", "fish", "ash", "dash", "ksh", "mksh")
    private val MUXES = setOf("tmux", "screen", "zellij", "dtach", "abduco")
    private val SYSTEMS = setOf("dbus-daemon", "dbus-launch", "ssh-agent", "gpg-agent", "dirmngr", "crond", "udevd")
    private val INTERPRETERS = setOf("node", "nodejs", "python", "ruby", "php", "deno", "bun", "perl")
    private val TOOLS = mapOf(
        "claude" to "Claude Code",
        "codex" to "Codex",
        "opencode" to "OpenCode",
        "kilo" to "Kilo Code",
        "kilocode" to "Kilo Code",
        "cline" to "Cline",
        "zcode" to "ZCode",
        "hermes" to "Hermes Agent",
        "aider" to "Aider",
        "gemini" to "Gemini CLI",
    )
    private val VERSION = Regex("""\d+(\.\d+)+""")
    private val PYTHON = Regex("""python[0-9.]*""")

    private fun tokens(argv: String?): List<String> =
        argv?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()

    private fun baseName(path: String): String = path.substringAfterLast('/')

    /** argv[0]'s program name: login-shell dash and tmux's "name:" title stripped. */
    fun program(r: ProcRecord): String {
        val first = tokens(r.argv).firstOrNull()
        val raw = (first ?: r.comm).removePrefix("-").removeSuffix(":")
        val base = baseName(raw).ifEmpty { r.comm }
        return if (PYTHON.matches(base)) "python" else base
    }

    /** The first positional (non-dash) argument after argv[0] — a script or main class. */
    private fun firstPositional(tokens: List<String>): String? {
        var skipNext = false
        for (t in tokens.drop(1)) {
            if (skipNext) { skipNext = false; continue }
            when {
                t == "-cp" || t == "-classpath" || t == "--class-path" || t == "-p" || t == "--module-path" -> skipNext = true
                t == "-jar" -> skipNext = false
                t.startsWith("-") -> Unit
                else -> return t
            }
        }
        return null
    }

    fun identify(r: ProcRecord): Identity {
        val toks = tokens(r.argv)
        val prog = program(r)
        val lower = prog.lowercase()

        // Build tools first: a JVM running them is a build tool, not "java". Matched
        // against whole ARGV TOKENS (a main class, a jar's name) — never a substring
        // of the line, so `node server.js` in a directory called mvnd-app is not Maven.
        fun tokenEnds(vararg suffixes: String) = toks.any { t -> suffixes.any { t == it || t.endsWith(".$it") } }
        fun jarNamed(prefix: String) = toks.any { baseName(it).let { b -> b.startsWith(prefix) && b.endsWith(".jar") } }
        when {
            tokenEnds("GradleDaemon") -> {
                val version = toks.dropWhile { !(it == "GradleDaemon" || it.endsWith(".GradleDaemon")) }.drop(1).firstOrNull { VERSION.matches(it) }
                return Identity(ProcKind.BUILD, if (version != null) "Gradle daemon $version" else "Gradle daemon")
            }
            tokenEnds("GradleWrapperMain") -> return Identity(ProcKind.BUILD, "Gradle wrapper")
            toks.any { it.startsWith("org.gradle.launcher.") } -> return Identity(ProcKind.BUILD, "Gradle")
            tokenEnds("KotlinCompileDaemon") || jarNamed("kotlin-daemon") -> return Identity(ProcKind.BUILD, "Kotlin daemon")
            jarNamed("sbt-launch") -> return Identity(ProcKind.BUILD, "sbt")
            lower == "mvnd" || jarNamed("mvnd") -> return Identity(ProcKind.BUILD, "Maven daemon")
            tokenEnds("Launcher") && toks.any { it.startsWith("org.codehaus.plexus.classworlds") } ->
                return Identity(ProcKind.BUILD, "Maven")
        }

        if (lower in MUXES) {
            // The server detaches (no tty); an attached `tmux attach` is only a client.
            return if (!r.hasTty || r.comm.startsWith("tmux: server")) {
                Identity(ProcKind.MUX, lower)
            } else {
                Identity(ProcKind.OTHER, "$lower client")
            }
        }
        TOOLS[lower]?.let { return Identity(ProcKind.TOOL, it) }
        if (lower in SYSTEMS) return Identity(ProcKind.SYSTEM, lower)
        if (lower in SHELLS) {
            return if (r.hasTty) {
                Identity(ProcKind.SHELL, lower)
            } else {
                // A tty-less shell is a script/command runner, not a terminal.
                Identity(ProcKind.OTHER, if (toks.contains("-c")) "$lower -c" else lower)
            }
        }
        if (lower in INTERPRETERS) {
            val script = firstPositional(toks)?.let { baseName(it) }
            return Identity(ProcKind.OTHER, if (script != null) "$lower $script" else lower)
        }
        if (lower == "java") {
            val jarIdx = toks.indexOf("-jar")
            val target = if (jarIdx >= 0) toks.getOrNull(jarIdx + 1)?.let { baseName(it) } else firstPositional(toks)?.substringAfterLast('.')
            return Identity(ProcKind.OTHER, if (target != null) "java $target" else "java")
        }
        return Identity(ProcKind.OTHER, prog)
    }

    /** The Android app's own process (and its service processes): forked by Android, so never counted against the limit. */
    fun isAppProcess(argv: String?, comm: String): Boolean {
        val first = argv?.split(' ')?.firstOrNull { it.isNotEmpty() } ?: comm
        return baseName(first).startsWith("app.pocketshell") || comm.startsWith("app.pocketshell")
    }

    /** True for the app's own process and its tracer helpers — never guest processes. */
    fun isInfrastructure(argv: String?, comm: String): Boolean {
        val first = argv?.split(' ')?.firstOrNull { it.isNotEmpty() } ?: comm
        val base = baseName(first)
        return base.startsWith("app.pocketshell") ||
            base.startsWith("libproot") ||
            base.startsWith("libpsx") ||
            comm.startsWith("app.pocketshell")
    }
}

// ----------------------------------------------------------------- views

/** One guest process with everything a row needs, resolved once per scan. */
internal data class ProcEntry(
    val rec: ProcRecord,
    val identity: Identity,
    val survival: Survival,
    /** Listening ports owned by this pid (the host probe's verified list). */
    val ports: List<Int>,
    val cpuPercent: Float?,
    /** True when its parent is not a live guest process — it was reparented to init. */
    val reparented: Boolean,
) {
    val pid: Int get() = rec.pid
    val kind: ProcKind get() = identity.kind
    val label: String get() = identity.label

    /** Survives a terminal close AND is not a child job of something still running. */
    val daemonized: Boolean get() = survival != Survival.ATTACHED && reparented
}

/** A terminal session (a top-level shell with a tty) or a session keeper (tmux/screen). */
internal data class Session(
    val leader: ProcEntry,
    /** What the terminal's foreground job is; null for keepers (their windows are not visible). */
    val foreground: String?,
    val descendants: List<ProcEntry>,
) {
    val keeper: Boolean get() = leader.kind == ProcKind.MUX
}

internal data class ProcView(
    val all: List<ProcEntry>,
    /** Detached/nohup processes that are not session keepers or servers. */
    val background: List<ProcEntry>,
    val sessions: List<Session>,
    /** Daemonized processes (system helpers excluded) — what outlives the terminal. */
    val outlive: List<ProcEntry>,
) {
    val kindByPid: Map<Int, ProcKind> by lazy { all.associate { it.pid to it.kind } }
    val byPid: Map<Int, ProcEntry> by lazy { all.associateBy { it.pid } }

    fun topByMemory(n: Int): List<ProcEntry> = all.sortedByDescending { it.rec.footprintBytes }.take(n)

    fun topByCpu(n: Int, minPercent: Float = 0.5f): List<ProcEntry> =
        all.filter { (it.cpuPercent ?: 0f) >= minPercent }.sortedByDescending { it.cpuPercent }.take(n)

    /** Listeners the user would call "my dev servers": build/system/keeper ports are IPC noise. */
    fun devServerPids(): Set<Int> = all.filter { it.ports.isNotEmpty() && it.kind !in NON_DEV }.map { it.pid }.toSet()

    fun descendantsOf(pid: Int): List<ProcEntry> = ProcViews.descendants(all, pid)

    companion object {
        val EMPTY = ProcView(emptyList(), emptyList(), emptyList(), emptyList())
        private val NON_DEV = setOf(ProcKind.BUILD, ProcKind.SYSTEM, ProcKind.MUX)
    }
}

internal object ProcViews {

    fun build(
        records: List<ProcRecord>,
        cpu: Map<Int, Float?>,
        portsByPid: Map<Int, List<Int>>,
    ): ProcView {
        val pids = records.mapTo(HashSet()) { it.pid }
        val entries = records.map { r ->
            val ports = portsByPid[r.pid].orEmpty()
            val id = ProcClassifier.identify(r).let {
                // A listener we could not name by rule is, plainly, a server.
                if (it.kind == ProcKind.OTHER && ports.isNotEmpty()) it.copy(kind = ProcKind.SERVER) else it
            }
            ProcEntry(
                rec = r,
                identity = id,
                survival = r.survival,
                ports = ports,
                cpuPercent = cpu[r.pid],
                reparented = r.ppid <= 1 || r.ppid !in pids,
            )
        }
        val byPid = entries.associateBy { it.pid }

        val background = entries.filter {
            it.survival != Survival.ATTACHED &&
                it.kind != ProcKind.MUX &&
                it.kind != ProcKind.SERVER
        }

        val sessions = entries.mapNotNull { e ->
            when {
                e.kind == ProcKind.MUX && e.survival != Survival.ATTACHED ->
                    Session(e, foreground = null, descendants = descendants(entries, e.pid))
                // A top-level shell: has a tty and its parent is not another shell.
                e.kind == ProcKind.SHELL && byPid[e.rec.ppid]?.kind != ProcKind.SHELL -> {
                    val below = descendants(entries, e.pid)
                    Session(e, foreground = foregroundLabel(e, below), descendants = below)
                }
                else -> null
            }
        }

        val outlive = entries.filter { it.daemonized && it.kind != ProcKind.SYSTEM && it.kind != ProcKind.SHELL }
        return ProcView(all = entries, background = background, sessions = sessions, outlive = outlive)
    }

    /** Every process below [pid], breadth-first by start time (stable, acyclic). */
    fun descendants(entries: List<ProcEntry>, pid: Int): List<ProcEntry> {
        val children = entries.groupBy { it.rec.ppid }
        val out = ArrayList<ProcEntry>()
        val seen = HashSet<Int>().apply { add(pid) }
        val queue = ArrayDeque<Int>().apply { add(pid) }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            for (child in children[next].orEmpty().sortedBy { it.rec.startedAtMs ?: 0L }) {
                if (seen.add(child.pid)) {
                    out += child
                    queue.addLast(child.pid)
                }
            }
        }
        return out
    }

    /**
     * The terminal's foreground job: the newest process whose group is the
     * tty's foreground group (tpgid); "idle" when that group is the shell's
     * own; null when the kernel gave no foreground group.
     */
    fun foregroundLabel(leader: ProcEntry, below: List<ProcEntry>): String? {
        val fg = leader.rec.tpgid
        if (fg <= 0) return null
        if (fg == leader.rec.pgrp) return "idle"
        val job = below.filter { it.rec.pgrp == fg }.maxByOrNull { it.rec.startedAtMs ?: 0L } ?: return null
        return job.label
    }
}

/** Background list ordering. */
internal enum class BgSort(val label: String) {
    MEMORY("MEMORY"),
    CPU("CPU"),
    UPTIME("UPTIME"),
    NAME("NAME"),
}

internal object ProcPresentation {

    fun filter(entries: List<ProcEntry>, query: String): List<ProcEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.label.contains(q, ignoreCase = true) ||
                it.pid.toString().contains(q) ||
                it.rec.cwd?.contains(q, ignoreCase = true) == true ||
                it.rec.argv?.contains(q, ignoreCase = true) == true
        }
    }

    fun sort(entries: List<ProcEntry>, mode: BgSort, nowMs: Long): List<ProcEntry> = when (mode) {
        BgSort.MEMORY -> entries.sortedByDescending { it.rec.footprintBytes }
        BgSort.CPU -> entries.sortedWith(compareByDescending<ProcEntry> { it.cpuPercent ?: -1f }.thenByDescending { it.rec.footprintBytes })
        BgSort.UPTIME -> entries.sortedByDescending { it.rec.ageSec(nowMs) ?: -1L }
        BgSort.NAME -> entries.sortedBy { it.label.lowercase() }
    }

    /**
     * tty_nr → a name. The device number packs major (bits 8-19) and minor
     * (bits 0-7 + 20-31); Unix98 ptys are majors 136..143 ("pts/N"), the
     * virtual consoles are major 4.
     */
    fun ttyName(tty: Int): String {
        if (tty == 0) return "none"
        val major = (tty shr 8) and 0xfff
        val minor = (tty and 0xff) or ((tty shr 12) and 0xfff00)
        return when (major) {
            in 136..143 -> "pts/${minor + (major - 136) * 256}"
            4 -> "tty$minor"
            else -> "tty $tty"
        }
    }

    /** Swap below this is noise; above it the row says the process is partly swapped out. */
    const val SWAP_NOTABLE_BYTES = 64L * 1024 * 1024

    /** "1.2 GB" — resident + swapped; the number a memory ranking should use. */
    fun memory(e: ProcEntry): String = ServersPresentation.formatBytes(e.rec.footprintBytes)

    /** "155 MB in RAM · 1 GB swapped" detail line; null when nothing is swapped. */
    fun memorySplit(e: ProcEntry): String? =
        if (e.rec.swapBytes >= SWAP_NOTABLE_BYTES) {
            "${ServersPresentation.formatBytes(e.rec.rssBytes)} in RAM · ${ServersPresentation.formatBytes(e.rec.swapBytes)} swapped"
        } else {
            null
        }

    /** "7m 12s", or "—" when the start time is unknown. */
    fun age(rec: ProcRecord, nowMs: Long): String =
        rec.ageSec(nowMs)?.let { ServersPresentation.formatUptime(it.toDouble()) } ?: "—"

    /** "1.2 GB · 7m 12s" — the right-hand facts of a row. */
    fun facts(e: ProcEntry, nowMs: Long): String = "${memory(e)} · ${age(e.rec, nowMs)}"

    /** The one-line headline the card and overview share: "2 up · 3 bg · 1 ses". */
    fun summary(servers: Int, background: Int, sessions: Int): String = "$servers up · $background bg · $sessions ses"
}

// ---------------------------------------------------------------- scanner

/** One scan: the guest's records plus the CPU% the tick delta gave each. */
internal data class ProcSnapshot(
    val records: List<ProcRecord>,
    val cpu: Map<Int, Float?>,
    val pidSet: Set<Int>,
    val atMs: Long,
    /** What Android counts against its limit: every own-UID process but the app's own, engine processes included. */
    val forkedCount: Int = records.size,
)

/**
 * The only IO in this file. One pass reads every numeric /proc dir (own UID
 * only — the kernel hides the rest), parses stat, and resolves the
 * argv and cwd FRESH every scan — a shell that `cd`s or `exec`s another
 * program must not keep showing its old directory / name. CPU samples are
 * keyed by identity (pid + start ticks), so a reused pid never inherits a
 * dead process's delta. Bounded (the NEWEST pids are kept when over the
 * cap); every read degrades alone.
 */
internal class ProcessScanner(
    private val procRoot: String = "/proc",
    private val maxPids: Int = 400,
    /** From the host a cwd is a HOST path; this maps it to the guest path the user knows. */
    private val cwdMapper: (String) -> String = { it },
) {
    private companion object {
        /** Tolerated disagreement between a start tick and the uptime read a moment later. */
        const val CLOCK_SKEW_SEC = 2.0
    }

    private val prevSamples = HashMap<String, ProcessSample>()

    fun scan(nowMs: Long = System.currentTimeMillis()): ProcSnapshot {
        val dir = File(procRoot)
        val pids = (dir.list { _, n -> n.isNotEmpty() && n.all { it in '0'..'9' } }
            ?.mapNotNull { it.toIntOrNull() }.orEmpty())
            .sorted()
        val uptimeSec = try {
            SystemParsing.parseUptimeSeconds(File(dir, "uptime").readText())
        } catch (_: Exception) {
            null
        }
        val records = ArrayList<ProcRecord>()
        val cpu = HashMap<Int, Float?>()
        val liveKeys = HashSet<String>()
        var appProcesses = 0

        // Over the cap, keep the NEWEST pids — they are what the user just started.
        for (pid in pids.takeLast(maxPids)) {
            val stat = try {
                ProcRecordParsing.parseStat(File(dir, "$pid/stat").readText())
            } catch (_: Exception) {
                null
            } ?: continue

            val argv = try {
                ProcRecordParsing.argv(File(dir, "$pid/cmdline").readBytes())
            } catch (_: Exception) {
                null
            }
            if (ProcClassifier.isAppProcess(argv, stat.comm)) appProcesses++
            if (ProcClassifier.isInfrastructure(argv, stat.comm)) continue
            val key = "$pid:${stat.startTicks}"
            liveKeys += key

            val status = try {
                ProcRecordParsing.statusFacts(File(dir, "$pid/status").readText())
            } catch (_: Exception) {
                ProcRecordParsing.StatusFacts(hupIgnored = false, swapBytes = 0)
            }

            // A start AFTER "now" means the uptime clock is not this kernel's (a
            // virtualized /proc/uptime, as in the guest): the age is UNKNOWN, not 0.
            val startedAt = uptimeSec?.let { up ->
                val elapsed = up - stat.startTicks.toDouble() / ProcParsing.USER_HZ
                if (elapsed < -CLOCK_SKEW_SEC) null else nowMs - (elapsed.coerceAtLeast(0.0) * 1000).toLong()
            }
            records += ProcRecord(
                pid = pid,
                ppid = stat.ppid,
                comm = stat.comm,
                argv = argv,
                state = stat.state,
                pgrp = stat.pgrp,
                sid = stat.sid,
                tty = stat.tty,
                tpgid = stat.tpgid,
                threads = stat.threads,
                cpuTicks = stat.cpuTicks,
                rssBytes = stat.rssBytes,
                swapBytes = status.swapBytes,
                startedAtMs = startedAt,
                cwd = readCwd(pid),
                hupIgnored = status.hupIgnored,
                startTicks = stat.startTicks,
                nice = stat.nice,
            )

            val sample = ProcessSample(pid, stat.cpuTicks, stat.rssBytes, nowMs)
            cpu[pid] = prevSamples[key]?.let { ProcParsing.cpuPercent(it, sample) }
            prevSamples[key] = sample
        }

        // Forget processes that are gone — the memory must not grow with churn.
        prevSamples.keys.retainAll(liveKeys)

        return ProcSnapshot(records = records, cpu = cpu, pidSet = pids.toSet(), atMs = nowMs, forkedCount = (pids.size - appProcesses).coerceAtLeast(0))
    }

    private fun readCwd(pid: Int): String? = try {
        cwdMapper(Files.readSymbolicLink(File("$procRoot/$pid/cwd").toPath()).toString())
    } catch (_: Exception) {
        null
    }
}

/** Host-visible cwd → the guest path the user knows. Pure; the host anchors are injected. */
internal object ProcPaths {

    /**
     * From the host app a process's cwd is a HOST path: inside the rootfs it
     * is `<rootfs>/<guest path>`, and the Linux home is bound from
     * `<dataDir>/files/home`. Anything else is left exactly as read.
     */
    fun guestPath(cwd: String, dataDir: String?, rootfsPath: String?): String {
        val rootfs = rootfsPath?.removeSuffix("/")
        if (!rootfs.isNullOrEmpty()) {
            if (cwd == rootfs) return "/"
            if (cwd.startsWith("$rootfs/")) return "/" + cwd.removePrefix("$rootfs/")
        }
        val home = dataDir?.removeSuffix("/")?.let { "$it/files/home" }
        if (home != null) {
            if (cwd == home) return "~"
            if (cwd.startsWith("$home/")) return "~/" + cwd.removePrefix("$home/")
        }
        return cwd
    }
}

/**
 * Who a pid is RIGHT NOW — read at request time and again just before a
 * signal is sent, so a pid that was reused (or is PocketShell's own process)
 * between the confirm dialog and the exec is never signalled.
 */
internal object ProcIdentityCheck {

    data class Identity(val startTicks: Long, val infrastructure: Boolean)

    /** Null when the process is gone or unreadable. */
    fun read(pid: Int, procRoot: String = "/proc"): Identity? {
        val stat = try {
            ProcRecordParsing.parseStat(File(procRoot, "$pid/stat").readText())
        } catch (_: Exception) {
            null
        } ?: return null
        val argv = try {
            ProcRecordParsing.argv(File(procRoot, "$pid/cmdline").readBytes())
        } catch (_: Exception) {
            null
        }
        return Identity(stat.startTicks, ProcClassifier.isInfrastructure(argv, stat.comm))
    }

    /** Why [req] must not run now, or null. Pure over the two reads. */
    fun refusal(requestedStart: Long?, now: Identity?): String? = when {
        now == null -> "the process is already gone"
        now.infrastructure -> "that is PocketShell's own process — refusing to signal it"
        requestedStart != null && now.startTicks != requestedStart ->
            "the pid now belongs to a different process than the one you confirmed"
        else -> null
    }
}
