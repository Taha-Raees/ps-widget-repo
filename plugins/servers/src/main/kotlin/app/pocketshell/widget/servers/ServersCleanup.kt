package app.pocketshell.widget.servers

/**
 * CLEAN UP - stopping what is idle, detached and heavy, without ever stopping what you meant to keep.
 *
 * The danger is real: "no terminal" is exactly what defines the background work this app exists to show (Gradle daemons,
 * `nohup` builds, dev servers, tmux, agents, ssh tunnels, backups). So the design is a stack of independent guards, each
 * of which can veto, and nothing happens unless the user switched a rule on:
 *
 *   1. A RULE must be on. Both start OFF. A rule is OFF, ASK (list it, you tap) or AUTO (stop it when due).
 *   2. Only two kinds can ever be candidates: build DAEMONS (Gradle / Kotlin / Maven daemons - they restart on demand), and
 *      plain detached programs the user opted into under "heavy".
 *   3. NEVER candidates, whatever the rule says: terminals and their shells, session keepers (tmux/screen), servers
 *      (anything listening), agents (Claude, Codex…), system helpers, ssh/rsync/tar/zstd/git and other transfer tools,
 *      anything the user marked KEEP, anything still a child of a live process (started from an open terminal).
 *   4. IDLE means the process AND everything below it used under 1 % CPU in every observation for the whole window.
 *      A gap in our observations is "unknown", never "idle".
 *   5. HEAVY means resident + swapped memory over the rule's floor.
 *   6. AUTO sends SIGTERM only (never SIGKILL), re-checks the process's identity first, stops at most 3 per sweep, and
 *      if the same program keeps coming back it stops auto-stopping it. A process that ignores SIGTERM is left alone.
 *
 * Everything in this file is pure and JVM-tested; the clock, the scan and the signal are injected by the state holder.
 */

internal enum class CleanupMode(val label: String) {
    OFF("Off"),
    ASK("Ask me"),
    AUTO("Auto"),
}

internal enum class CleanupRule(val title: String, val blurb: String) {
    BUILDS("Build daemons", "Gradle, Kotlin and Maven daemons left running after a build. They start again by themselves next time."),
    HEAVY("Heavy and idle", "Programs running without a terminal that use a lot of memory and have done nothing for a long time."),
}

internal data class RuleSettings(val mode: CleanupMode, val idleMinutes: Int, val minMemoryMb: Int)

internal data class CleanupSettings(
    val builds: RuleSettings = DEFAULT_BUILDS,
    val heavy: RuleSettings = DEFAULT_HEAVY,
    /** Program families the user said never to touch (lower-case, version stripped). */
    val keep: Set<String> = emptySet(),
) {
    fun rule(rule: CleanupRule): RuleSettings = if (rule == CleanupRule.BUILDS) builds else heavy

    /** Any rule that needs us to watch. */
    val watching: Boolean get() = builds.mode != CleanupMode.OFF || heavy.mode != CleanupMode.OFF

    fun with(rule: CleanupRule, s: RuleSettings): CleanupSettings =
        if (rule == CleanupRule.BUILDS) copy(builds = CleanupSettingsCodec.sanitize(rule, s)) else copy(heavy = CleanupSettingsCodec.sanitize(rule, s))

    companion object {
        val DEFAULT_BUILDS = RuleSettings(CleanupMode.OFF, idleMinutes = 30, minMemoryMb = 0)
        val DEFAULT_HEAVY = RuleSettings(CleanupMode.OFF, idleMinutes = 60, minMemoryMb = 500)
    }
}

/** The choices the UI offers - and the only values accepted from disk, so a hand-edited file cannot make a rule reckless. */
internal object CleanupSettingsCodec {

    val BUILD_IDLE_CHOICES = listOf(15, 30, 60, 120, 240)
    val BUILD_MEM_CHOICES = listOf(0, 200, 500, 1000)

    /** The heavy rule is stricter by construction: a long idle window and a real memory floor. */
    val HEAVY_IDLE_CHOICES = listOf(60, 120, 240)
    val HEAVY_MEM_CHOICES = listOf(500, 1000, 2000)

    fun idleChoices(rule: CleanupRule) = if (rule == CleanupRule.BUILDS) BUILD_IDLE_CHOICES else HEAVY_IDLE_CHOICES
    fun memChoices(rule: CleanupRule) = if (rule == CleanupRule.BUILDS) BUILD_MEM_CHOICES else HEAVY_MEM_CHOICES

    fun sanitize(rule: CleanupRule, s: RuleSettings): RuleSettings {
        val def = if (rule == CleanupRule.BUILDS) CleanupSettings.DEFAULT_BUILDS else CleanupSettings.DEFAULT_HEAVY
        return RuleSettings(
            mode = s.mode,
            idleMinutes = s.idleMinutes.takeIf { it in idleChoices(rule) } ?: def.idleMinutes,
            minMemoryMb = s.minMemoryMb.takeIf { it in memChoices(rule) } ?: def.minMemoryMb,
        )
    }

    private val KEEP_NAME = Regex("^[a-z0-9][a-z0-9 ._-]{0,59}$")
    const val MAX_KEEP = 50

    fun validKeep(name: String) = KEEP_NAME.matches(name)

    fun encode(s: CleanupSettings): String = buildString {
        appendLine("builds=${s.builds.mode.name},${s.builds.idleMinutes},${s.builds.minMemoryMb}")
        appendLine("heavy=${s.heavy.mode.name},${s.heavy.idleMinutes},${s.heavy.minMemoryMb}")
        s.keep.filter(::validKeep).sorted().take(MAX_KEEP).forEach { appendLine("keep=$it") }
    }

    fun decode(text: String?): CleanupSettings {
        var out = CleanupSettings()
        val keep = LinkedHashSet<String>()
        for (line in text.orEmpty().lines()) {
            val key = line.substringBefore('=', "").trim()
            val value = line.substringAfter('=', "").trim()
            when (key) {
                "builds", "heavy" -> {
                    val p = value.split(',')
                    val mode = CleanupMode.entries.firstOrNull { it.name == p.getOrNull(0) } ?: continue
                    val rule = if (key == "builds") CleanupRule.BUILDS else CleanupRule.HEAVY
                    val s = sanitize(rule, RuleSettings(mode, p.getOrNull(1)?.toIntOrNull() ?: -1, p.getOrNull(2)?.toIntOrNull() ?: -1))
                    out = if (rule == CleanupRule.BUILDS) out.copy(builds = s) else out.copy(heavy = s)
                }
                "keep" -> if (validKeep(value) && keep.size < MAX_KEEP) keep += value
            }
        }
        return out.copy(keep = keep)
    }
}

// ------------------------------------------------------------------ tracking

/**
 * Remembers, per process identity, since when it - and everything below it - has been idle.
 * Fed by every process scan (the page's own ticks and the monitor's), so it needs no scan of its own.
 */
internal class IdleTracker {

    companion object {
        /** Under this combined CPU% a process (with its descendants) counts as idle in an observation. */
        const val IDLE_CPU_PERCENT = 1.0f

        /** If we did not look for longer than this, the time in between is UNKNOWN - never counted as idle. */
        const val MAX_GAP_MS = 3 * 60_000L

        fun key(e: ProcEntry) = "${e.pid}:${e.rec.startTicks}"
    }

    private class Track(val firstSeenAt: Long) {
        var lastSeenAt: Long = firstSeenAt
        var idleSince: Long? = null
    }

    private val tracks = HashMap<String, Track>()

    @Synchronized
    fun observe(view: ProcView, nowMs: Long) {
        val children = view.all.groupBy { it.rec.ppid }
        val seen = HashSet<String>()
        for (e in view.all) {
            val k = key(e)
            seen += k
            val existing = tracks[k]
            val t = existing ?: Track(nowMs).also { tracks[k] = it }
            val previousLook = t.lastSeenAt
            if (existing != null && nowMs - previousLook > MAX_GAP_MS) t.idleSince = null
            when (subtreeBusy(e, children)) {
                true -> t.idleSince = null
                // The CPU% just measured covers the interval since the last look, so idleness began then.
                false -> if (t.idleSince == null && existing != null && nowMs - previousLook <= MAX_GAP_MS) t.idleSince = previousLook
                null -> Unit // not measurable yet (first sighting): neither busy nor idle
            }
            t.lastSeenAt = nowMs
        }
        tracks.keys.retainAll(seen)
    }

    /** True: used CPU. False: idle. Null: some part is not measurable yet. */
    private fun subtreeBusy(root: ProcEntry, children: Map<Int, List<ProcEntry>>): Boolean? {
        var sum = 0f
        var unknown = false
        val seen = HashSet<Int>().apply { add(root.pid) }
        val queue = ArrayDeque<ProcEntry>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            val cpu = e.cpuPercent
            if (cpu == null) unknown = true else sum += cpu
            for (c in children[e.pid].orEmpty()) if (seen.add(c.pid)) queue.addLast(c)
        }
        return when {
            sum >= IDLE_CPU_PERCENT -> true
            unknown -> null
            else -> false
        }
    }

    @Synchronized
    fun idleForMs(e: ProcEntry, nowMs: Long): Long? = tracks[key(e)]?.idleSince?.let { (nowMs - it).coerceAtLeast(0) }

    @Synchronized
    fun seenForMs(e: ProcEntry, nowMs: Long): Long? = tracks[key(e)]?.firstSeenAt?.let { (nowMs - it).coerceAtLeast(0) }
}

/** What the cleanup has done, so it never fights a program that keeps coming back or one that ignores SIGTERM. */
internal class CleanupLedger {

    companion object {
        /** Two stops of the same program inside this window pause AUTO for it. */
        const val RESPAWN_WINDOW_MS = 10 * 60_000L

        /** A stopped process still alive after this long ignored the signal. */
        const val STUBBORN_AFTER_MS = 90_000L
    }

    private data class Kill(val key: String, val family: String, val atMs: Long)

    private val kills = ArrayList<Kill>()

    @Synchronized
    fun recordKill(e: ProcEntry, nowMs: Long) {
        kills += Kill(IdleTracker.key(e), Cleanup.family(e.label), nowMs)
        if (kills.size > 60) kills.removeAt(0)
    }

    /** The same program was stopped twice recently: something restarts it, so auto-stopping it is pointless. */
    @Synchronized
    fun autoPaused(family: String, nowMs: Long): Boolean =
        kills.count { it.family == family && nowMs - it.atMs <= RESPAWN_WINDOW_MS } >= 2

    /** This exact process was signalled long ago and is still here. */
    @Synchronized
    fun stubborn(e: ProcEntry, nowMs: Long): Boolean =
        kills.any { it.key == IdleTracker.key(e) && nowMs - it.atMs >= STUBBORN_AFTER_MS }

    @Synchronized
    fun recentlySignalled(e: ProcEntry, nowMs: Long): Boolean =
        kills.any { it.key == IdleTracker.key(e) && nowMs - it.atMs < STUBBORN_AFTER_MS }
}

// ---------------------------------------------------------------- the rules

internal data class CleanupCandidate(
    val entry: ProcEntry,
    val rule: CleanupRule,
    val mode: CleanupMode,
    val idleForMs: Long,
    val thresholdMs: Long,
    /** AUTO only: not paused by repeated restarts and not stubborn. */
    val autoAllowed: Boolean,
) {
    val due: Boolean get() = idleForMs >= thresholdMs
    val remainingMs: Long get() = (thresholdMs - idleForMs).coerceAtLeast(0)
    val footprintBytes: Long get() = entry.rec.footprintBytes
}

internal object Cleanup {

    /** Programs that must never be stopped by a rule: transfers, remote access, backups, VCS, session keepers. */
    val PROTECTED_PROGRAMS: Set<String> = setOf(
        "ssh", "sshd", "ssh-agent", "autossh", "scp", "sftp", "ssh-keygen",
        "rsync", "rclone", "tar", "zstd", "gzip", "bzip2", "xz", "zip", "unzip", "7z",
        "apk", "git", "git-remote-https", "curl", "wget", "aria2c", "yt-dlp", "ffmpeg", "dd", "cp", "mv",
        "tmux", "screen", "zellij", "dtach", "abduco",
        "gpg", "gpg-agent", "dbus-daemon", "crond",
    )

    private val VERSION_SUFFIX = Regex("""\s+\d+(\.\d+)+$""")

    /** "Gradle daemon 8.14.5" -> "gradle daemon": the program family the user keeps or the ledger counts. */
    fun family(label: String): String = label.lowercase().replace(VERSION_SUFFIX, "").trim()

    /** Daemon labels only - never the short-lived clients ("Gradle wrapper", "Gradle", "sbt", "Maven"). */
    fun isBuildDaemon(e: ProcEntry): Boolean {
        if (e.kind != ProcKind.BUILD) return false
        val f = family(e.label)
        return f == "gradle daemon" || f == "kotlin daemon" || f == "maven daemon"
    }

    private fun programName(e: ProcEntry): String = ProcClassifier.program(e.rec).lowercase()

    private fun ancestors(e: ProcEntry, view: ProcView): List<ProcEntry> {
        val out = ArrayList<ProcEntry>()
        var cur = view.byPid[e.rec.ppid]
        val seen = HashSet<Int>()
        while (cur != null && seen.add(cur.pid)) { out += cur; cur = view.byPid[cur.rec.ppid] }
        return out
    }

    /**
     * Why [e] may NOT be a candidate for [rule], or null when it may. The first veto wins; each is a separate guard
     * so the tests can pin them one by one.
     */
    fun veto(e: ProcEntry, view: ProcView, rule: CleanupRule, s: CleanupSettings): String? {
        val r = s.rule(rule)
        if (e.pid <= 1) return "not a guest process"
        if (e.survival == Survival.ATTACHED) return "bound to a terminal"
        if (!e.reparented) return "started from a process that is still running"
        // A build daemon's listener is its own IPC with the build client (Gradle opens a loopback port), not a service
        // anyone uses - so it must not veto the daemon. Everything else that listens is a server: hands off.
        if (e.ports.isNotEmpty() && e.kind != ProcKind.BUILD) return "serves a port"
        if (e.kind in setOf(ProcKind.SERVER, ProcKind.SHELL, ProcKind.MUX, ProcKind.TOOL, ProcKind.SYSTEM)) return "${e.kind.label.lowercase()} - never stopped by a rule"
        when (rule) {
            CleanupRule.BUILDS -> if (!isBuildDaemon(e)) return "not a build daemon"
            CleanupRule.HEAVY -> if (e.kind != ProcKind.OTHER) return "not a plain program"
        }
        val prog = programName(e)
        if (prog in PROTECTED_PROGRAMS || e.rec.comm.lowercase() in PROTECTED_PROGRAMS) return "protected program ($prog)"
        if (family(e.label) in s.keep || prog in s.keep) return "you marked it keep"
        if (ancestors(e, view).any { it.kind in setOf(ProcKind.MUX, ProcKind.TOOL, ProcKind.SHELL) }) return "belongs to a live session"
        if (e.rec.footprintBytes < r.minMemoryMb * 1024L * 1024L) return "below the memory floor"
        return null
    }

    /** Everything idle long enough to be worth showing, for every rule (OFF rules show too - STOP is always manual). */
    fun candidates(
        view: ProcView,
        tracker: IdleTracker,
        ledger: CleanupLedger,
        settings: CleanupSettings,
        nowMs: Long,
        showFraction: Double = 0.0,
    ): List<CleanupCandidate> {
        val out = ArrayList<CleanupCandidate>()
        for (rule in CleanupRule.entries) {
            val r = settings.rule(rule)
            val threshold = r.idleMinutes * 60_000L
            for (e in view.all) {
                if (veto(e, view, rule, settings) != null) continue
                val idle = tracker.idleForMs(e, nowMs) ?: continue
                if (idle < (threshold * showFraction).toLong()) continue
                if (ledger.recentlySignalled(e, nowMs)) continue
                out += CleanupCandidate(
                    entry = e,
                    rule = rule,
                    mode = r.mode,
                    idleForMs = idle,
                    thresholdMs = threshold,
                    autoAllowed = !ledger.stubborn(e, nowMs) && !ledger.autoPaused(family(e.label), nowMs),
                )
            }
        }
        // One row per process: if both rules match, the build rule (stricter semantics) wins.
        return out.distinctBy { it.entry.pid }.sortedWith(compareByDescending<CleanupCandidate> { it.due }.thenByDescending { it.footprintBytes })
    }

    /** What the monitor may stop right now: AUTO rules, due, allowed, at most [MAX_PER_SWEEP]. */
    fun dueForAuto(candidates: List<CleanupCandidate>): List<CleanupCandidate> =
        candidates.filter { it.mode == CleanupMode.AUTO && it.due && it.autoAllowed }.take(MAX_PER_SWEEP)

    const val MAX_PER_SWEEP = 3

    /** "1 h 5 min", "12 min", "45 s". */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}

// ------------------------------------------------------------------ acting

/** The two things the cleanup needs from the system - injected, so the runner is testable without a phone. */
internal interface CleanupSignaller {
    /** Who the pid is right now; null when gone/unreadable. */
    fun identity(pid: Int): ProcIdentityCheck.Identity?

    /** Send SIGTERM. True when it was sent (not a promise it stopped). */
    fun terminate(pid: Int): Boolean

    /** How many network sockets the process holds open; null when that cannot be read (treated as "unknown" = hands off). */
    fun openSockets(pid: Int): Int?
}

internal class AndroidCleanupSignaller : CleanupSignaller {
    override fun identity(pid: Int): ProcIdentityCheck.Identity? = ProcIdentityCheck.read(pid)

    override fun openSockets(pid: Int): Int? = try {
        java.io.File("/proc/$pid/fd").list()?.count { fd ->
            try { java.nio.file.Files.readSymbolicLink(java.io.File("/proc/$pid/fd/$fd").toPath()).toString().startsWith("socket:") } catch (_: Exception) { false }
        }
    } catch (_: Exception) {
        null
    }

    // The guest has no pid namespace: a guest pid IS the host pid, and these are our own UID's processes.
    override fun terminate(pid: Int): Boolean = try {
        android.os.Process.sendSignal(pid, 15)
        true
    } catch (_: Throwable) {
        false
    }
}

internal data class CleanupLogEntry(val atMs: Long, val text: String, val ok: Boolean)

internal object CleanupRunner {

    const val LOG_LIMIT = 20

    /**
     * Stop what is due under AUTO rules. Each process is re-identified immediately before the signal (a recycled pid, or
     * PocketShell's own process, is refused), only SIGTERM is ever sent, and what happened is returned for the log.
     */
    fun sweep(candidates: List<CleanupCandidate>, signaller: CleanupSignaller, ledger: CleanupLedger, nowMs: Long): List<CleanupLogEntry> {
        val out = ArrayList<CleanupLogEntry>()
        for (c in Cleanup.dueForAuto(candidates)) {
            val e = c.entry
            val refusal = ProcIdentityCheck.refusal(e.rec.startTicks, signaller.identity(e.pid))
            if (refusal != null) {
                out += CleanupLogEntry(nowMs, "Left ${e.label} alone - $refusal", false)
                continue
            }
            // A plain program holding network sockets is a server, a bot or a client - exactly what must not vanish. Port
            // attribution can miss a listener, so the heavy rule also asks the kernel directly. (Build daemons keep an
            // IPC socket by design, so they are exempt from this check.)
            if (c.rule == CleanupRule.HEAVY) {
                val sockets = signaller.openSockets(e.pid)
                if (sockets == null || sockets > 0) {
                    out += CleanupLogEntry(nowMs, "Left ${e.label} alone - it has network connections open", false)
                    continue
                }
            }
            val what = "${e.label} (idle ${Cleanup.duration(c.idleForMs)}, ${ServersPresentation.formatBytes(c.footprintBytes)})"
            if (signaller.terminate(e.pid)) {
                ledger.recordKill(e, nowMs)
                out += CleanupLogEntry(nowMs, "Stopped $what", true)
            } else {
                out += CleanupLogEntry(nowMs, "Could not signal $what", false)
            }
        }
        return out
    }

    fun append(log: List<CleanupLogEntry>, new: List<CleanupLogEntry>): List<CleanupLogEntry> = (new.asReversed() + log).take(LOG_LIMIT)
}

// ------------------------------------------------------------------ wording

/** The words the card, the overview and the CLEAN UP page share - pure, so they are tested. */
internal object CleanupText {

    /** The card's one-line nudge: only for ASK rules (AUTO acts quietly, OFF says nothing). Null when there is nothing to say. */
    fun cardLine(candidates: List<CleanupCandidate>): String? {
        val ready = candidates.filter { it.mode == CleanupMode.ASK && it.due }
        if (ready.isEmpty()) return null
        val bytes = ready.sumOf { it.footprintBytes }
        return "${ready.size} IDLE · ${ServersPresentation.formatBytes(bytes)} CAN BE STOPPED"
    }

    /** The overview row: what the page is doing about idle processes. */
    fun overviewLine(settings: CleanupSettings, candidates: List<CleanupCandidate>): String {
        val idle = candidates.size
        val bytes = candidates.sumOf { it.footprintBytes }
        return when {
            !settings.watching && idle == 0 -> "Rules are off · nothing idle"
            !settings.watching -> "Rules are off · $idle idle (${ServersPresentation.formatBytes(bytes)})"
            idle == 0 -> "Watching · nothing idle"
            else -> "Watching · $idle idle (${ServersPresentation.formatBytes(bytes)})"
        }
    }

    /** What a candidate row says about its future. */
    fun status(c: CleanupCandidate): String {
        val idle = "idle ${Cleanup.duration(c.idleForMs)}"
        return when (c.mode) {
            CleanupMode.OFF -> "$idle · rule is off"
            CleanupMode.ASK -> if (c.due) "$idle · ready to stop" else "$idle · ${Cleanup.duration(c.remainingMs)} to go"
            CleanupMode.AUTO -> when {
                !c.autoAllowed -> "$idle · not stopped automatically (it came back, or ignored the stop)"
                c.due -> "$idle · stopping at the next check"
                else -> "$idle · stops in ${Cleanup.duration(c.remainingMs)}"
            }
        }
    }

    const val NEVER_TOUCHED =
        "Never touched by a rule: terminals and their shells, tmux/screen, anything that listens on a port, agents " +
            "(Claude, Codex…), ssh/rsync/tar/git and other transfers, anything started from a terminal that is still open, " +
            "and programs you mark KEEP. Auto only ever sends a polite stop (SIGTERM)."
}
