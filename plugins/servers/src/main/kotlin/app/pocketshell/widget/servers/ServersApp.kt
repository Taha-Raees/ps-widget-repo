package app.pocketshell.widget.servers

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import app.pocketshell.packages.ExecResult
import app.pocketshell.packages.ProcessBuilderGuestCommandRunner
import app.pocketshell.runtime.GuestExecutionProfile
import app.pocketshell.runtime.RuntimeState
import app.pocketshell.runtime.RuntimeStorage
import app.pocketshell.terminal.ShellEnvironment
import app.pocketshell.widget.HomeAppContext
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.HomeAppSpec
import app.pocketshell.widget.probe.ServerInfo
import app.pocketshell.widget.probe.ServerProbe
import app.pocketshell.widget.servers.ServersOps.Signal
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * SERVERS — what is running on this device, as a Home Application plugin.
 *
 * What it is: the v1 manifest card (verified listeners, six lines)
 * graduated into a process-aware monitor:
 *
 *   CARD (COMPACT/ROOMY)   the at-a-glance answer — "N up · M bg · K ses",
 *                          servers, session keepers and background
 *                          processes as one-line rows, and the headline
 *                          fact: how many OUTLIVE THE TERMINAL.
 *   MAXIMIZED (FULL)       a task manager (ServersStudio.kt): OVERVIEW,
 *                          SERVERS (endpoints, HTTP checks, response
 *                          history), BACKGROUND (Gradle daemons, watchers
 *                          — anything detached), SESSIONS (terminals and
 *                          tmux/screen), with search, sort, process facts
 *                          and the stop / force-kill actions.
 *
 * Fact discipline (inherited, never invented):
 *   - endpoints come ONLY from the host's ServerProbe pipeline (a
 *     successful connect is kernel fact; attribution is the acceptor
 *     diff) — a port is shown because something answered it;
 *   - HTTP checks are app-side GETs on the shared loopback (same netns),
 *     bounded in count, timeout and body; 3xx/4xx/5xx render as what
 *     they are, redirects are never followed;
 *   - process facts are /proc files of the app's own UID (the kernel hides
 *     the rest); CPU is a tick delta — a first sighting says "—", not 0%;
 *     memory is resident + swapped (a big idle daemon often lives in swap);
 *   - "outlives the terminal" is a kernel fact (no controlling tty, or
 *     SIGHUP ignored) combined with "was reparented to init" — never a guess;
 *   - the system strip is labelled DEVICE: load/memory/uptime are the
 *     shared kernel's facts; the disk number is the rootfs image's.
 *
 * Cost discipline: the pid-set idle gate turns an idle card's tick into
 * one /proc readdir; the process scan runs when the pid set changed, on
 * every tick while the maximized page is visible, else every
 * [PROC_SLOW_TICKS]th tick; HTTP checks are capped and parallel-bounded; a
 * paused monitor does nothing at all; the signal op is the ONLY guest exec.
 *
 * This file owns the application object, the process-scoped state and
 * the exec seam. The screens live in ServersScreens/ServersStudio, the
 * shared controls in ServersComponents, the pure rules in ServersProbe/
 * ServersProcesses/HttpCheck/ServersPresentation/ServersOps.
 */
object ServersApp : HomeApplication() {

    /** The registry/catalog id (the plugin replaces the v1 manifest's id). */
    const val SERVERS_PLUGIN_ID = "servers"

    override val spec = HomeAppSpec(
        id = SERVERS_PLUGIN_ID,
        name = "Processes",
        summary = "Everything running in the Linux guest — dev servers, background jobs like Gradle, terminal sessions, and what outlives the terminal",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        val appContext = LocalContext.current.applicationContext
        // The ONE holder: probe wrapper, facts reader, checker results,
        // beat history, monitor controls and the stop pipeline. Leaving
        // Home, swiping the page away, or rotating never resets them.
        val state = remember {
            context.stateStore.forApp(SERVERS_PLUGIN_ID) {
                ServersState(appContext = appContext)
            }
        }
        val lifecycleOwner = LocalLifecycleOwner.current

        // Is the card actually showing? Home keeps several applications composed side by
        // side, so "resumed" is not "visible": scans must not run for an off-screen card.
        val onScreen = remember { mutableStateOf(false) }

        // The monitor's lifecycle-aware tick: while resumed and not
        // paused, the pid-set gate decides each pass — an idle card's
        // only cost is one /proc readdir, a card with servers always
        // re-verifies (the host probe's own rule).
        LaunchedEffect(context.runtimeState) {
            if (context.runtimeState != RuntimeState.READY) {
                state.ui = ServersUi.Unavailable
                return@LaunchedEffect
            }
            lifecycleOwner.lifecycle.currentStateFlow
                .map { it.isAtLeast(Lifecycle.State.RESUMED) }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    // Only while the card is on screen: paging or scrolling away stops the ticks.
                    snapshotFlow { onScreen.value }
                        .distinctUntilChanged()
                        .collectLatest { shown ->
                            if (!shown) return@collectLatest
                            while (true) {
                                if (!state.paused) {
                                    state.tickIfDue()
                                }
                                delay(TICK_MS)
                            }
                        }
                }
        }

        // The manual refresh control: one pass, outside the gate.
        LaunchedEffect(state.refreshTick) {
            if (state.refreshTick == 0 || context.runtimeState != RuntimeState.READY) return@LaunchedEffect
            // The holder outlives the composition: coming back must not re-run the last refresh.
            if (!state.refreshRunOnce.claim(state.refreshTick)) return@LaunchedEffect
            state.scan()
        }

        // THE stop path: request → (confirm) → one exec → honest outcome
        // → forced rescan. Nothing optimistic in between.
        LaunchedEffect(state.stopTick) {
            val req = state.stopInFlight ?: return@LaunchedEffect
            // Consume BEFORE running: this state outlives the composition, so an
            // effect cancelled mid-exec (rotation, leaving Home) must never leave
            // the request behind to fire a second kill when it restarts.
            state.stopInFlight = null
            val outcome = withContext(Dispatchers.IO) { state.runStop(req) }
            state.stopOutcome = outcome
            state.scan()
            // kill exits 0 once the signal is SENT; only the rescan knows whether
            // the same process is still there.
            val stillThere = req.startTicks != null && state.procView.byPid[req.pid]?.rec?.startTicks == req.startTicks
            if (outcome.ok && stillThere && state.stopOutcome === outcome) {
                state.stopOutcome = outcome.copy(stillRunning = true)
            }
        }

        // The application owns its back: from a detail page back to its
        // list; only from the list does back reach Home.
        BackHandler(enabled = state.hasSelection) { state.clearSelection() }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().trackOnScreen(onScreen)) {
            val layout = ServersLayout.from(maxWidth.value, maxHeight.value)

            state.stopPending?.let { pending ->
                StopConfirmDialog(
                    spec = ServersOps.confirmSpec(pending),
                    onConfirm = { state.confirmStop() },
                    onDismiss = { state.dismissStop() },
                )
            }
            if (state.cleanNowPending) {
                val ready = state.candidates.filter { it.due }
                StopConfirmDialog(
                    spec = ServersOps.ConfirmSpec(
                        title = "Stop ${ready.size} idle programs?",
                        body = ready.joinToString(", ") { it.entry.label } + ". Each is checked again and sent a polite stop (SIGTERM).",
                        command = ready.joinToString(" ") { "kill -s TERM ${it.entry.pid}" },
                        confirmLabel = "STOP ALL",
                    ),
                    onConfirm = { state.cleanNow() },
                    onDismiss = { state.cleanNowPending = false },
                )
            }
            state.stopOutcome?.let { outcome ->
                OutcomeBanner(outcome = outcome, onDismiss = { state.stopOutcome = null })
            }

            // The maximized page re-scans processes every tick (CPU / memory must move).
            SideEffect { state.detailed = layout.isMonitor }

            ServersScreens(
                state = state,
                layout = layout,
                maxWidthDp = maxWidth.value,
                actions = ServersActions(
                    onRefresh = { state.refreshTick++ },
                    onTogglePause = { state.paused = !state.paused },
                    onOpenTerminal = { context.nav.openTerminal() },
                    onOpenLinuxShell = { context.nav.openLinuxShell() },
                    onOpenDiagnostics = { context.nav.openDiagnostics() },
                    onOpenCompanion = { context.nav.openCompanion(ServerProbe.companionUrl(it)) },
                    onSignalServer = { server, signal -> state.requestSignal(server, signal) },
                    onSignalProcess = { entry, signal -> state.requestSignal(entry, signal) },
                ),
            )
        }
    }

    /** The card's refresh cadence — the built-in widget's proven 5 s. */
    const val TICK_MS = 5_000L

    /** With nothing changing and the page hidden, the process scan still refreshes every N ticks (30 s). */
    const val PROC_SLOW_TICKS = 6

    /** How often the monitor looks when all is well. */
    const val MONITOR_MS = 60_000L

    /** How often it looks while something is wrong: a crash loop or a process swarm moves in seconds. */
    const val HEALTH_FAST_MS = 15_000L

    /** The monitor never stops anything on a process list older than this. */
    const val MONITOR_MAX_STALE_MS = 90_000L

    /** HTTP checks per pass, hard cap (the canon itself is ≤32 ports). */
    const val HTTP_CHECK_CAP = 12

    /** Parallel HTTP checks in flight — bounded, so a hung port cannot stack. */
    const val HTTP_PARALLELISM = 6
}

/** The application's screen state (probe-driven, never invented). */
internal sealed interface ServersUi {
    data object Probing : ServersUi
    data object Unavailable : ServersUi
    data object ProbeUnavailable : ServersUi
    data class Ready(val servers: List<ServerInfo>) : ServersUi
}

/**
 * The monitor's process-scoped state, owned by the HomeAppStateStore:
 * the probe wrapper + facts reader, the per-port HTTP cells and beat
 * rings, the per-pid process samples (the CPU delta's memory), the
 * system strip, the monitor controls and the stop pipeline. Nothing here
 * needs to survive process death — a fresh process re-probes honestly.
 */
internal class ServersState(private val appContext: Context) {

    private val probe = ServersProbeWrapper()
    private val factsReader = SystemFactsReader(
        rootfsDir = try {
            RuntimeStorage(appContext.noBackupFilesDir).rootfsDir
        } catch (_: Exception) {
            null
        },
    )

    var ui by mutableStateOf<ServersUi>(ServersUi.Probing)
    var system by mutableStateOf<SystemStrip?>(null)
    var lastScanAtMs by mutableStateOf(0L)

    // The maximized monitor's controls.
    var paused by mutableStateOf(false)
    var query by mutableStateOf("")
    var sort by mutableStateOf(SortMode.PORT)

    // The open detail page (null = dashboard).
    var selectedPort by mutableStateOf<Int?>(null)

    // Per-port HTTP answers (last cell) + the session's beat rings.
    val httpByPort = java.util.concurrent.ConcurrentHashMap<Int, HttpResult>()
    val beatsByPort = java.util.concurrent.ConcurrentHashMap<Int, BeatHistory>()

    // The process-fact memory: last sample per pid (CPU delta), rendered
    // facts per pid (what screens read).
    private val prevSamples = HashMap<Int, ProcessSample>()
    val factsByPort = java.util.concurrent.ConcurrentHashMap<Int, ProcessFacts>()

    // The stop pipeline: request → (confirm) → in-flight → outcome.
    var stopPending by mutableStateOf<ServersOps.StopRequest?>(null)
    var stopInFlight by mutableStateOf<ServersOps.StopRequest?>(null)
    var stopOutcome by mutableStateOf<ServersOps.Outcome?>(null)
    var stopTick by mutableIntStateOf(0)

    var refreshTick by mutableIntStateOf(0)

    /** One tap = one scan, never one per re-entry. */
    val refreshRunOnce = RunOnce()

    // ---- processes (the second fact source: everything the guest runs).
    // From the host app a process's cwd is a HOST path; show the guest path.
    private val procScanner = ProcessScanner(
        cwdMapper = run {
            val dataDir = try { appContext.applicationInfo.dataDir } catch (_: Exception) { null }
            val rootfs = try { RuntimeStorage(appContext.noBackupFilesDir).rootfsDir.path } catch (_: Exception) { null }
            ({ cwd: String -> ProcPaths.guestPath(cwd, dataDir, rootfs) })
        },
    )
    var procView by mutableStateOf(ProcView.EMPTY)
    var procAtMs by mutableStateOf(0L)

    /** Set from the layout: the maximized page needs every tick's CPU/memory. */
    @Volatile var detailed: Boolean = false
    private var lastProcPidSet: Set<Int> = emptySet()
    private var lastTickPidSet: Set<Int> = emptySet()
    private var ticksSincePidChange = Int.MAX_VALUE / 2
    private var ticksSinceFullScan = 0
    private var tickCount = 0

    /** When anything on the page was last refreshed (full scan OR process pass) - what "scanned Ns ago" must show. */
    val freshAtMs: Long get() = maxOf(lastScanAtMs, procAtMs)

    // ---- navigation + controls of the maximized task manager.
    var tab by mutableStateOf(StudioTab.OVERVIEW)
    var selectedPid by mutableStateOf<Int?>(null)
    var bgQuery by mutableStateOf("")
    var bgSort by mutableStateOf(BgSort.MEMORY)

    val hasSelection: Boolean get() = selectedPort != null || selectedPid != null

    fun clearSelection() {
        selectedPort = null
        selectedPid = null
    }

    private val scanMutex = Mutex()

    // ---- CLEAN UP: stop what is idle, detached and heavy - safely (see ServersCleanup.kt for the guards).
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracker = IdleTracker()
    private val ledger = CleanupLedger()
    private val signaller: CleanupSignaller = AndroidCleanupSignaller()
    private val monitorLock = Any()
    private var monitor: Job? = null
    private val settingsFile: File? = try {
        File(RuntimeStorage(appContext.noBackupFilesDir).rootfsDir, "root/.config/pocketshell/processes-cleanup.txt")
    } catch (_: Exception) {
        null
    }

    /** The user's rules. Persisted in the Linux home, so a Sync backup carries them to a new phone. */
    var cleanup by mutableStateOf(CleanupSettings())

    /** Everything idle long enough to show, for every rule (OFF rules show too - STOP is always a tap away). */
    var candidates by mutableStateOf<List<CleanupCandidate>>(emptyList())

    /** What the monitor did, newest first. Session-only by design. */
    var cleanupLog by mutableStateOf<List<CleanupLogEntry>>(emptyList())

    // ---- HEALTH: see ServersHealth.kt.
    private val healthWatch = HealthWatch()
    var budget by mutableStateOf(ProcessBudgetFacts(0))
    var groups by mutableStateOf<List<ProcGroup>>(emptyList())
    var findings by mutableStateOf<List<Finding>>(emptyList())
    var freedBytes by mutableStateOf(0L)
    var cleanNowPending by mutableStateOf(false)

    init {
        cleanupScope.launch {
            val text = withContext(Dispatchers.IO) { try { settingsFile?.takeIf { it.isFile }?.readText() } catch (_: Exception) { null } }
            cleanup = CleanupSettingsCodec.decode(text)
            syncMonitor()
        }
    }

    fun updateCleanup(op: (CleanupSettings) -> CleanupSettings) {
        val next = op(cleanup)
        if (next == cleanup) return
        cleanup = next
        cleanupScope.launch { saveCleanup(next) }
        syncMonitor()
        val now = System.currentTimeMillis()
        refreshCandidates(now)
        refreshFindings(now)
    }

    /** "Never touch this program": the family of [entry] joins the keep list. */
    fun keep(entry: ProcEntry) = updateCleanup { it.copy(keep = it.keep + Cleanup.family(entry.label)) }

    fun unkeep(name: String) = updateCleanup { it.copy(keep = it.keep - name) }

    /** Everything ready to stop, on the user's word. The same guards as AUTO; only the rule's mode and limit do not apply. */
    fun cleanNow() {
        cleanNowPending = false
        cleanupScope.launch {
            // Refresh the listeners first, as the monitor does: a new server must never look like an idle program.
            scan()
            val ready = candidates.filter { it.due }.map { it.copy(mode = CleanupMode.AUTO) }
            val now = System.currentTimeMillis()
            recordSweep(ready, CleanupRunner.sweep(ready, signaller, ledger, now, limit = ready.size))
            scanProcesses()
        }
    }

    /** The gentle action. Nothing stops; the system simply gives other programs the phone first. */
    fun setPriority(group: ProcGroup, low: Boolean) {
        cleanupScope.launch {
            val entry = withContext(Dispatchers.IO) {
                ProcPriority.apply(group, if (low) ProcPriority.BACKGROUND_NICE else ProcPriority.NORMAL_NICE, AndroidPrioritySetter) { ProcIdentityCheck.read(it) }
            }
            cleanupLog = CleanupRunner.append(cleanupLog, listOf(entry))
            scanProcesses()
        }
    }

    fun quiet(family: String) = updateCleanup { it.copy(quiet = it.quiet + family) }

    fun unquiet(family: String) = updateCleanup { it.copy(quiet = it.quiet - family) }

    private fun recordSweep(attempted: List<CleanupCandidate>, log: List<CleanupLogEntry>) {
        if (log.isEmpty()) return
        cleanupLog = CleanupRunner.append(cleanupLog, log)
        freedBytes += attempted.filter { c -> log.any { it.ok && it.text.startsWith("Stopped ${c.entry.label} ") } }.sumOf { it.footprintBytes }
    }

    private fun saveCleanup(s: CleanupSettings) {
        val f = settingsFile ?: return
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            tmp.writeText(CleanupSettingsCodec.encode(s))
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // The rules still apply for this session; they just won't survive a restart.
        }
    }

    private fun refreshCandidates(nowMs: Long) {
        candidates = Cleanup.candidates(procView, tracker, ledger, cleanup, nowMs, showFraction = 0.25)
    }

    private fun refreshFindings(nowMs: Long) {
        findings = Health.findings(procView, groups, budget, healthWatch, cleanup, nowMs)
    }

    /** The monitor exists exactly while there is something for it to do. */
    private fun syncMonitor() = synchronized(monitorLock) {
        if (cleanup.monitorNeeded) {
            if (monitor?.isActive != true) monitor = cleanupScope.launch { monitorLoop() }
        } else {
            monitor?.cancel()
            monitor = null
        }
    }

    private suspend fun monitorLoop() {
        var lastSweepAt = 0L
        while (currentCoroutineContext().isActive) {
            val wait = Health.nextLookMs(budget, findings)
            delay(wait)
            if (paused) continue // the user paused the monitor: nothing is stopped behind their back
            var now = System.currentTimeMillis()
            if (now - procAtMs > wait - 5_000) scanProcesses()
            // Stops stay once a minute, however fast the health look is running.
            if (now - lastSweepAt < ServersApp.MONITOR_MS - 5_000) continue
            lastSweepAt = now
            val calm = Health.autoCalm(findings, cleanup)
            for (group in calm) {
                val entry = withContext(Dispatchers.IO) { ProcPriority.apply(group, ProcPriority.BACKGROUND_NICE, AndroidPrioritySetter) { ProcIdentityCheck.read(it) } }
                cleanupLog = CleanupRunner.append(cleanupLog, listOf(entry))
            }
            val budgetPick = Cleanup.budgetPick(Cleanup.candidates(procView, tracker, ledger, cleanup, now), cleanup, budget)
            val memoryOk = Cleanup.memoryAllows(cleanup, system?.mem)
            if (budgetPick.isEmpty() && (!memoryOk || Cleanup.dueForAuto(candidates, cleanup.perSweep).isEmpty())) continue
            // Something is about to be stopped: refresh the LISTENERS first (the quiet passes do not), so a server that
            // started since the last full scan can never look like an idle program.
            scan()
            now = System.currentTimeMillis()
            if (now - procAtMs > ServersApp.MONITOR_MAX_STALE_MS) continue
            // The rescan may have changed the picture: choose again from the fresh list.
            val freshBudget = Cleanup.budgetPick(Cleanup.candidates(procView, tracker, ledger, cleanup, now), cleanup, budget)
            val targets = freshBudget.ifEmpty { if (memoryOk) candidates else emptyList() }
            val log = CleanupRunner.sweep(targets, signaller, ledger, now, cleanup.perSweep)
            if (log.isNotEmpty()) {
                recordSweep(targets, log)
                refreshCandidates(now)
            }
        }
    }

    /**
     * One gated pass. Servers: the host probe's idle gate decides. Processes:
     * when the pid set changed, on every tick while the maximized page is
     * visible, else every [ServersApp.PROC_SLOW_TICKS]th tick.
     */
    suspend fun tickIfDue() {
        val pidSet = withContext(Dispatchers.IO) { probe.peekPidSet() }
        tickCount++
        val changed = pidSet != lastTickPidSet
        if (changed) { ticksSincePidChange = 0; lastTickPidSet = pidSet } else ticksSincePidChange++
        ticksSinceFullScan++
        // The host probe's gate may take its own lock (a scan holds it for the whole
        // exec): ask it OFF the main thread, never from this composition-scoped coroutine.
        val hostDue = lastScanAtMs == 0L || withContext(Dispatchers.IO) { probe.shouldFullScan(pidSet) }
        val plan = TickPlan.decide(
            TickPlan.Inputs(
                firstScan = lastScanAtMs == 0L,
                hostGateDue = hostDue,
                pidSetChanged = changed || pidSet != lastProcPidSet,
                detailed = detailed,
                ticksSincePidChange = ticksSincePidChange,
                ticksSinceFullScan = ticksSinceFullScan,
                tickCount = tickCount,
            ),
        )
        when {
            plan.fullScan -> scan()
            plan.processes -> scanProcesses(refreshSystem = plan.system)
        }
    }

    /** The process pass alone (servers untouched) — mutexed with [scan]. */
    suspend fun scanProcesses(refreshSystem: Boolean = false) = scanMutex.withLock {
        runProcessScan(portsByPid())
        // The system strip rode only on full scans, so a quiet machine's load/memory froze while processes moved.
        if (refreshSystem) system = withContext(Dispatchers.IO) { factsReader.read() }
    }

    private fun portsByPid(): Map<Int, List<Int>> =
        (ui as? ServersUi.Ready)?.servers.orEmpty()
            .filter { it.pid != null }
            .groupBy({ it.pid!! }, { it.port })
            .mapValues { (_, ports) -> ports.sorted() }

    private suspend fun runProcessScan(ports: Map<Int, List<Int>>) {
        val snap = withContext(Dispatchers.IO) { procScanner.scan() }
        lastProcPidSet = snap.pidSet
        procAtMs = snap.atMs
        procView = ProcViews.build(snap.records, snap.cpu, ports)
        // Every process pass - the page's own ticks and the monitor's - feeds the idle tracker.
        tracker.observe(procView, snap.atMs)
        refreshCandidates(snap.atMs)
        budget = ProcessBudgetFacts(snap.forkedCount)
        groups = ProcGroups.build(procView)
        healthWatch.observe(procView, groups, cleanup, snap.atMs)
        refreshFindings(snap.atMs)
        // A selection whose process vanished degrades to the list — never a
        // stale detail page for a dead pid.
        selectedPid?.let { pid -> if (procView.byPid[pid] == null) selectedPid = null }
    }

    /**
     * The full pass: snapshot → HTTP checks (capped, parallel-bounded) →
     * process facts → system strip → state. Every stage degrades alone.
     */
    suspend fun scan() = scanMutex.withLock {
        ticksSinceFullScan = 0
        val snapshot = withContext(Dispatchers.IO) { probe.snapshot() }
        lastScanAtMs = System.currentTimeMillis()
        if (!probe.lastScanSawProcesses) {
            ui = ServersUi.ProbeUnavailable
            return@withLock
        }
        ui = ServersUi.Ready(snapshot)

        // HTTP checks: verified endpoints only, capped, parallel-bounded.
        val toCheck = snapshot.sortedBy { it.port }.take(ServersApp.HTTP_CHECK_CAP)
        coroutineScope {
            val gate = Semaphore(ServersApp.HTTP_PARALLELISM)
            toCheck.forEach { server ->
                launch {
                    gate.withPermit {
                        val result = withContext(Dispatchers.IO) {
                            HttpChecker.check(server.port)
                        }
                        httpByPort[server.port] = result
                        val history = beatsByPort.getOrPut(server.port) { BeatHistory() }
                        // "Up" is what a status page means by it (Uptime
                        // Kuma's default): 2xx/3xx answered. 4xx/5xx and
                        // failures are beats the strip shows in red.
                        val beatOk = when (result) {
                            is HttpResult.Answered -> when (HttpChecker.band(result.code)) {
                                HttpBand.OK, HttpBand.REDIRECT -> true
                                else -> false
                            }
                            is HttpResult.Failed -> false
                        }
                        history.record(
                            when (result) {
                                is HttpResult.Answered -> BeatHistory.Beat(
                                    ok = beatOk,
                                    code = result.code,
                                    latencyMs = result.latencyMs,
                                    atMs = lastScanAtMs,
                                )
                                is HttpResult.Failed -> BeatHistory.Beat(
                                    ok = false,
                                    code = null,
                                    latencyMs = 0,
                                    atMs = lastScanAtMs,
                                )
                            },
                        )
                    }
                }
            }
        }

        // The process layer: everything the guest runs, joined to the
        // listeners by pid (a server is a process that happens to listen).
        runProcessScan(snapshot.filter { it.pid != null }.groupBy({ it.pid!! }, { it.port }).mapValues { (_, p) -> p.sorted() })

        // Process facts per attributed owner: sample, diff, render.
        val now = System.currentTimeMillis()
        for (server in snapshot) {
            val pid = server.pid ?: continue
            val sample = readSample(pid, now)
            if (sample == null) {
                factsByPort.remove(server.port)
                prevSamples.remove(pid)
                continue
            }
            val prev = prevSamples[pid]
            prevSamples[pid] = sample
            val cpu = prev?.let { ProcParsing.cpuPercent(it, sample) }
            factsByPort[server.port] = ProcessFacts(
                pid = pid,
                cpuPercent = cpu,
                rssBytes = sample.rssBytes,
                argv = readArgv(pid),
            )
        }

        // The system strip — a handful of file reads, same cadence.
        system = withContext(Dispatchers.IO) { factsReader.read() }

        // A selection whose server vanished degrades to the dashboard —
        // never a stale detail page for a dead endpoint.
        selectedPort?.let { port ->
            if (snapshot.none { it.port == port }) selectedPort = null
        }
    }

    private fun readSample(pid: Int, nowMs: Long): ProcessSample? = try {
        ProcParsing.parseStat(
            pid = pid,
            text = File("/proc/$pid/stat").readText(),
            atMs = nowMs,
        )
    } catch (_: Exception) {
        null
    }

    private fun readArgv(pid: Int): String? = try {
        ProcParsing.parseArgv(File("/proc/$pid/cmdline").readBytes())
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------- signal pipeline

    /** The UI's ONE way to ask to signal a server; refusals answer instantly. */
    fun requestSignal(server: ServerInfo, signal: Signal) {
        val base = ServersOps.StopRequest(server.pid ?: -1, server.processName, server.port, signal)
        ServersOps.refusal(server)?.let { reason ->
            stopOutcome = refusedOutcome(base, reason)
            return
        }
        // The host probe lists any own-UID listener — including PocketShell's own
        // helper processes. Identify the pid before offering to signal it.
        val identity = ProcIdentityCheck.read(server.pid!!)
        ProcIdentityCheck.refusal(null, identity)?.let { reason ->
            stopOutcome = refusedOutcome(base, reason)
            return
        }
        stopPending = base.copy(startTicks = identity!!.startTicks)
    }

    /** …and to signal any guest process (background job, session keeper, a shell). */
    fun requestSignal(entry: ProcEntry, signal: Signal) {
        val req = ServersOps.StopRequest(
            pid = entry.pid,
            processName = entry.label,
            signal = signal,
            endsTerminal = entry.kind == ProcKind.SHELL,
            startTicks = entry.rec.startTicks,
        )
        ServersOps.refusal(entry.pid)?.let { reason ->
            stopOutcome = refusedOutcome(req, reason)
            return
        }
        stopPending = req
    }

    fun confirmStop() {
        val pending = stopPending ?: return
        stopPending = null
        stopInFlight = pending
        stopTick++
    }

    fun dismissStop() {
        stopPending = null
    }

    /** The one exec: `kill -s <SIG> <pid>` in the guest, positioned, never interpolated. */
    internal fun runStop(req: ServersOps.StopRequest): ServersOps.Outcome {
        // The dialog may have sat open for a while: re-verify WHO this pid is, now.
        ProcIdentityCheck.refusal(req.startTicks, ProcIdentityCheck.read(req.pid))?.let { reason ->
            return refusedOutcome(req, reason)
        }
        val argv = ServersOps.argv(req.pid, req.signal)
        val result = try {
            guestExec(argv, STOP_TIMEOUT_MS)
        } catch (t: Throwable) {
            ExecResult(exitCode = null, stdout = "", stderr = "", error = t.message ?: t.javaClass.simpleName)
        }
        return ServersOps.Outcome(
            req = req,
            argv = argv,
            exitCode = result.exitCode,
            stderr = result.stderr.lineSequence().lastOrNull { it.isNotBlank() },
            error = result.error,
            atMs = System.currentTimeMillis(),
        )
    }

    private fun refusedOutcome(req: ServersOps.StopRequest, reason: String) = ServersOps.Outcome(
        req = req,
        argv = emptyList(),
        exitCode = null,
        stderr = null,
        error = reason, // Outcome.line() adds the "not run — " prefix
        atMs = System.currentTimeMillis(),
    )

    /**
     * The exec seam — the SAME closure shape the Git plugin uses: a proot
     * guest exec against the host runtime, bounded by timeout. The signal
     * op is this application's ONLY use of it.
     */
    private fun guestExec(argv: List<String>, timeoutMs: Long): ExecResult {
        val storage = RuntimeStorage(appContext.noBackupFilesDir)
        // GuestLaunch, not a direct call: buildLaunchSpec$default breaks on a
        // host whose parameter list grew since the API jar was cut.
        val spec = GuestLaunch.spec(
            nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
            rootfsDir = storage.rootfsDir,
            hostCwd = ShellEnvironment.homeDir(appContext),
            prootTmpDir = File(appContext.cacheDir, "proot-tmp").apply { mkdirs() },
            guestCommand = argv,
            profile = GuestExecutionProfile.PACKAGE_OPERATION,
        )
        val process = ProcessBuilderGuestCommandRunner().start(spec)
        try {
            return process.waitFor(timeoutMs)
        } catch (t: Throwable) {
            process.destroy()
            throw t
        }
    }

    companion object {
        /** A kill either lands or it does not; no hang is worth waiting on. */
        const val STOP_TIMEOUT_MS = 10_000L
    }
}
