package app.pocketshell.widget.servers

/**
 * HEALTH - is anything misbehaving, and how close is the guest to being killed?
 *
 * What this exists for, from real incidents on the device:
 *   - Android counts every process the app forks and, past 32 across all apps, SIGKILLs the oldest. The oldest is the
 *     desktop's own engine process, so one chatty program ends the whole desktop ([ProcessBudgetFacts]).
 *   - Firefox's child processes crashed and restarted dozens of times a minute while its window looked merely slow
 *     ([FindingKind.CHURN]), and a browser's helpers are ten or more processes that a per-process list hides
 *     ([ProcGroups]).
 *   - A pipeline stuck in the stopped state hung a terminal with nothing on screen saying why ([FindingKind.STOPPED]).
 *
 * Everything here is pure and JVM-tested; findings are facts about the scans, and acting on one is a separate choice.
 */

// ------------------------------------------------------------------ the budget

internal enum class BudgetLevel { OK, WARN, CRITICAL }

/** [count] is every process this app forked, the engine processes included: that is what Android counts. */
internal data class ProcessBudgetFacts(val count: Int) {
    val level: BudgetLevel
        get() = when {
            count >= CRITICAL_AT -> BudgetLevel.CRITICAL
            count >= WARN_AT -> BudgetLevel.WARN
            else -> BudgetLevel.OK
        }

    val fraction: Float get() = (count.toFloat() / LIMIT).coerceIn(0f, 1f)

    companion object {
        /** Android's default cap on phantom processes (AOSP PhantomProcessList), shared by ALL apps on the phone. */
        const val LIMIT = 32
        const val WARN_AT = 24
        const val CRITICAL_AT = 30
    }
}

// ------------------------------------------------------------------ apps, not processes

/** One program as the user thinks of it: a browser and all its helpers. */
internal data class ProcGroup(val root: ProcEntry, val members: List<ProcEntry>, val label: String, val desktop: Boolean) {
    val count: Int get() = members.size
    val footprintBytes: Long get() = members.sumOf { it.rec.footprintBytes }
    val cpuPercent: Float get() = members.fold(0f) { sum, e -> sum + (e.cpuPercent ?: 0f) }
    val family: String get() = Cleanup.family(label)

    /** Set by us (or by the program itself) when every member runs at a lowered priority. */
    val lowPriority: Boolean get() = members.all { it.rec.nice >= ProcPriority.BACKGROUND_NICE }
}

internal object ProcGroups {

    private val DESKTOP_PROGRAMS = setOf("weston", "xwayland", "ps-capture")
    private val WRAPPERS = setOf("sh", "sh -c", "bash", "bash -c", "dash", "dash -c")

    /** A shell or session keeper starts a new app: what runs under a terminal is not "the terminal's helpers". */
    private fun startsApp(e: ProcEntry) = e.kind == ProcKind.SHELL || e.kind == ProcKind.MUX

    fun build(view: ProcView): List<ProcGroup> {
        fun rootOf(e: ProcEntry): ProcEntry {
            var cur = e
            val seen = HashSet<Int>()
            while (seen.add(cur.pid) && !startsApp(cur)) {
                val parent = view.byPid[cur.rec.ppid] ?: break
                if (startsApp(parent)) break
                cur = parent
            }
            return cur
        }
        return view.all.groupBy { rootOf(it).pid }.mapNotNull { (rootPid, members) ->
            val root = view.byPid[rootPid] ?: return@mapNotNull null
            val desktop = members.any { ProcClassifier.program(it.rec).lowercase() in DESKTOP_PROGRAMS }
            // "sh -c ..." is only how something was started: name the group after what does the work.
            val label = when {
                desktop -> "Linux desktop"
                root.label.lowercase() in WRAPPERS -> members.filter { it.label.lowercase() !in WRAPPERS }.maxByOrNull { it.rec.footprintBytes }?.label ?: root.label
                else -> root.label
            }
            ProcGroup(root, members, label, desktop)
        }.sortedWith(compareByDescending<ProcGroup> { it.count }.thenByDescending { it.footprintBytes })
    }
}

// ------------------------------------------------------------------ findings

internal enum class FindingKind { BUDGET, CHURN, SWARM, RUNAWAY, HOG, GROWTH, STOPPED, ZOMBIES }

internal enum class Severity { NOTICE, WARNING, CRITICAL }

internal data class Finding(
    val kind: FindingKind,
    val severity: Severity,
    /** What IGNORE remembers: the program family; null when it cannot be ignored (the budget is not one program's). */
    val family: String?,
    val title: String,
    val detail: String,
    val group: ProcGroup? = null,
    /** STOPPED: the processes RESUME would continue. */
    val stopped: List<ProcEntry> = emptyList(),
) {
    /** Identifies "the same problem" from one scan to the next. */
    val id: String get() = "$kind:${family ?: ""}"
}

/** Remembers what a single scan cannot show: how long, how fast, how much it grew. Fed by every process scan. */
internal class HealthWatch {

    companion object {
        /** A gap in our looking is unknown: a sustained-use timer never spans one. */
        const val MAX_GAP_MS = 3 * 60_000L

        const val CHURN_WINDOW_MS = 5 * 60_000L

        /** This many same-program processes dying young inside the window is a crash loop, not normal closing of tabs. */
        const val CHURN_AT = 6

        /** Died this young counts towards churn. A helper that ran for minutes finished its job. */
        const val CHURN_YOUNG_MS = 2 * 60_000L

        const val GROWTH_WINDOW_MS = 10 * 60_000L
        const val GROWTH_BYTES = 500L * 1024 * 1024

        const val STOPPED_AFTER_MS = 2 * 60_000L
        const val ZOMBIES_AT = 3
    }

    private class Seen(val family: String, val firstSeenAt: Long, val start: Long)
    private class Usage(var busySince: Long?, var lastAt: Long, var baselineAt: Long, var baselineBytes: Long)

    private val alive = HashMap<String, Seen>()
    private val deaths = ArrayList<Pair<String, Long>>()
    private val usage = HashMap<String, Usage>()
    private val stoppedSince = HashMap<String, Long>()
    private var lastObservedAt: Long? = null

    @Synchronized
    fun observe(view: ProcView, groups: List<ProcGroup>, settings: CleanupSettings, nowMs: Long) {
        val gap = lastObservedAt?.let { nowMs - it > MAX_GAP_MS } ?: true

        // Churn: same-program processes, so a build running thousands of compilers is not a "crash loop of make".
        val familyOf = HashMap<String, String>()
        for (g in groups) for (e in g.members) {
            if (Cleanup.family(e.label) == g.family || g.root.pid == e.pid) familyOf[IdleTracker.key(e)] = g.family
        }
        val liveKeys = HashSet<String>()
        for ((key, family) in familyOf) {
            liveKeys += key
            alive.getOrPut(key) { Seen(family, nowMs, 0) }
        }
        // After a gap we cannot say when something died, so it says nothing about a loop.
        val gone = alive.keys - liveKeys
        for (key in gone) {
            val seen = alive.remove(key) ?: continue
            if (!gap && nowMs - seen.firstSeenAt <= CHURN_YOUNG_MS) deaths += seen.family to nowMs
        }
        deaths.removeAll { nowMs - it.second > CHURN_WINDOW_MS }

        // Sustained CPU and memory growth, per app.
        val liveFamilies = HashSet<String>()
        for (g in groups) {
            liveFamilies += g.family
            val u = usage.getOrPut(g.family) { Usage(null, nowMs, nowMs, g.footprintBytes) }
            if (nowMs - u.lastAt > MAX_GAP_MS) {
                u.busySince = null
                u.baselineAt = nowMs
                u.baselineBytes = g.footprintBytes
            }
            u.busySince = if (g.cpuPercent >= settings.cpuAtPercent) u.busySince ?: nowMs else null
            // The baseline only moves forward once it is a whole window old, or when memory fell below it.
            if (nowMs - u.baselineAt > GROWTH_WINDOW_MS || g.footprintBytes < u.baselineBytes) {
                u.baselineAt = nowMs
                u.baselineBytes = g.footprintBytes
            }
            u.lastAt = nowMs
        }
        usage.keys.retainAll(liveFamilies)

        val stoppedNow = view.all.filter { it.rec.state == 'T' }.map(IdleTracker::key).toSet()
        stoppedSince.keys.retainAll(stoppedNow)
        stoppedNow.forEach { stoppedSince.getOrPut(it) { nowMs } }
        lastObservedAt = nowMs
    }

    @Synchronized
    fun deathsOf(family: String): Int = deaths.count { it.first == family }

    @Synchronized
    fun busyForMs(family: String, nowMs: Long): Long? = usage[family]?.busySince?.let { nowMs - it }

    @Synchronized
    fun grewBytes(group: ProcGroup): Long = usage[group.family]?.let { group.footprintBytes - it.baselineBytes } ?: 0L

    @Synchronized
    fun stoppedForMs(e: ProcEntry, nowMs: Long): Long? = stoppedSince[IdleTracker.key(e)]?.let { nowMs - it }
}

internal object Health {

    fun findings(
        view: ProcView,
        groups: List<ProcGroup>,
        budget: ProcessBudgetFacts,
        watch: HealthWatch,
        s: CleanupSettings,
        nowMs: Long,
    ): List<Finding> {
        if (!s.watchHealth) return emptyList()
        val out = ArrayList<Finding>()

        if (budget.level != BudgetLevel.OK) {
            val top = groups.take(3).joinToString(", ") { "${it.label} ${it.count}" }
            out += Finding(
                FindingKind.BUDGET,
                if (budget.level == BudgetLevel.CRITICAL) Severity.CRITICAL else Severity.WARNING,
                family = null,
                title = "${budget.count} of ${ProcessBudgetFacts.LIMIT} processes",
                detail = "Android may kill the oldest process above ${ProcessBudgetFacts.LIMIT}, which can end the Linux desktop. Most: $top.",
            )
        }

        for (g in groups) {
            val deaths = watch.deathsOf(g.family)
            if (deaths >= HealthWatch.CHURN_AT) {
                out += Finding(
                    FindingKind.CHURN, Severity.CRITICAL, g.family,
                    title = "${g.label} keeps crashing or restarting",
                    detail = "$deaths of its processes died young in the last ${HealthWatch.CHURN_WINDOW_MS / 60_000} minutes. Restarting it may help; its own log has the reason.",
                    group = g,
                )
            }
            if (g.count >= s.swarmAt) {
                out += Finding(
                    FindingKind.SWARM, Severity.WARNING, g.family,
                    title = "${g.label} runs ${g.count} processes",
                    detail = "That is ${g.count} of the ${ProcessBudgetFacts.LIMIT} Android allows${if (g.desktop) "." else ". Closing tabs or windows, or stopping it, frees them."}",
                    group = g,
                )
            }
            watch.busyForMs(g.family, nowMs)?.takeIf { it >= s.cpuMinutes * 60_000L }?.let { busy ->
                out += Finding(
                    FindingKind.RUNAWAY, Severity.WARNING, g.family,
                    title = "${g.label} is using ${ServersPresentation.formatCpu(g.cpuPercent)} CPU",
                    detail = "Over ${s.cpuAtPercent} % for ${Cleanup.duration(busy)} (100 % is one core). A lower priority keeps the phone responsive and lets it finish.",
                    group = g,
                )
            }
            if (g.footprintBytes >= s.hogMb * 1024L * 1024L) {
                out += Finding(
                    FindingKind.HOG, Severity.NOTICE, g.family,
                    title = "${g.label} holds ${ServersPresentation.formatBytes(g.footprintBytes)}",
                    detail = "Resident plus swapped memory across its ${g.count} process${if (g.count == 1) "" else "es"}.",
                    group = g,
                )
            }
            val grew = watch.grewBytes(g)
            if (grew >= HealthWatch.GROWTH_BYTES) {
                out += Finding(
                    FindingKind.GROWTH, Severity.NOTICE, g.family,
                    title = "${g.label} grew by ${ServersPresentation.formatBytes(grew)}",
                    detail = "In about ${HealthWatch.GROWTH_WINDOW_MS / 60_000} minutes. It may be working hard, or leaking; it is ${ServersPresentation.formatBytes(g.footprintBytes)} now.",
                    group = g,
                )
            }
            val stopped = g.members.filter { (watch.stoppedForMs(it, nowMs) ?: 0L) >= HealthWatch.STOPPED_AFTER_MS }
            if (stopped.isNotEmpty()) {
                out += Finding(
                    FindingKind.STOPPED, Severity.WARNING, g.family,
                    title = "${g.label} is stopped",
                    detail = "${stopped.size} process${if (stopped.size == 1) " has" else "es have"} been frozen for over ${HealthWatch.STOPPED_AFTER_MS / 60_000} minutes (Ctrl-Z or a stop signal). A terminal waiting on one looks hung.",
                    group = g,
                    stopped = stopped,
                )
            }
            val zombies = g.members.count { it.rec.state == 'Z' }
            if (zombies >= HealthWatch.ZOMBIES_AT) {
                out += Finding(
                    FindingKind.ZOMBIES, Severity.NOTICE, g.family,
                    title = "${g.label} left $zombies dead processes",
                    detail = "They use no memory but still count towards Android's limit, until their parent collects them or stops.",
                    group = g,
                )
            }
        }
        return out.filter { it.family == null || it.family !in s.quiet }
            .sortedWith(compareByDescending<Finding> { it.severity }.thenBy { it.kind })
    }

    /** What AUTO may calm: a CPU runaway at normal priority. The desktop must stay responsive, so it is never lowered. */
    fun autoCalm(findings: List<Finding>, s: CleanupSettings): List<ProcGroup> =
        if (!s.autoCalm) emptyList()
        else findings.filter { it.kind == FindingKind.RUNAWAY }.mapNotNull { it.group }.filter { !it.desktop && !it.lowPriority }

    /** How soon to look again: often only while something is wrong, because that is when a look is worth its cost. */
    fun nextLookMs(budget: ProcessBudgetFacts, findings: List<Finding>): Long =
        if (budget.level != BudgetLevel.OK || findings.any { it.severity >= Severity.WARNING }) ServersApp.HEALTH_FAST_MS else ServersApp.MONITOR_MS

    /** The card's line: only what deserves interrupting a glance. */
    fun cardLine(findings: List<Finding>): String? =
        findings.firstOrNull { it.severity >= Severity.WARNING }?.title?.uppercase()

    fun overviewLine(budget: ProcessBudgetFacts, findings: List<Finding>, s: CleanupSettings): String = when {
        !s.watchHealth -> "Not watching · ${budget.count} of ${ProcessBudgetFacts.LIMIT} processes"
        findings.isEmpty() -> "All good · ${budget.count} of ${ProcessBudgetFacts.LIMIT} processes"
        findings.size == 1 -> findings[0].title
        else -> "${findings.size} things to look at · ${findings[0].title}"
    }
}

// ------------------------------------------------------------------ priority

/**
 * Lowering a program's priority: the gentle action, nothing stops and nothing is lost. A Linux "process priority" is
 * really per thread, so every thread of every process is set; threads started later inherit it from their creator.
 */
internal interface PrioritySetter {
    fun threadsOf(pid: Int): List<Int>

    /** False when the kernel refused. */
    fun set(tid: Int, nice: Int): Boolean
}

internal object AndroidPrioritySetter : PrioritySetter {
    override fun threadsOf(pid: Int): List<Int> =
        java.io.File("/proc/$pid/task").list()?.mapNotNull { it.toIntOrNull() }.orEmpty()

    override fun set(tid: Int, nice: Int): Boolean = try {
        android.os.Process.setThreadPriority(tid, nice)
        true
    } catch (_: Throwable) {
        false
    }
}

internal object ProcPriority {
    const val BACKGROUND_NICE = 10
    const val NORMAL_NICE = 0

    /** Who is in the group is re-checked against identity: a recycled pid is never touched. */
    fun apply(group: ProcGroup, nice: Int, setter: PrioritySetter, identity: (Int) -> ProcIdentityCheck.Identity?): CleanupLogEntry {
        var changed = 0
        var failed = 0
        for (e in group.members) {
            if (ProcIdentityCheck.refusal(e.rec.startTicks, identity(e.pid)) != null) continue
            for (tid in setter.threadsOf(e.pid)) if (setter.set(tid, nice)) changed++ else failed++
        }
        val what = if (nice >= BACKGROUND_NICE) "Lowered the priority of ${group.label}" else "Restored the priority of ${group.label}"
        return when {
            changed == 0 && failed == 0 -> CleanupLogEntry(System.currentTimeMillis(), "${group.label} is already gone", false)
            changed == 0 -> CleanupLogEntry(System.currentTimeMillis(), "$what - refused by the system", false)
            failed > 0 -> CleanupLogEntry(System.currentTimeMillis(), "$what ($changed threads, $failed refused)", true)
            else -> CleanupLogEntry(System.currentTimeMillis(), "$what ($changed threads)", true)
        }
    }
}
