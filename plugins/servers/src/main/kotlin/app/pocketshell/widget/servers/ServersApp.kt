package app.pocketshell.widget.servers

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import app.pocketshell.packages.ExecResult
import app.pocketshell.packages.ProcessBuilderGuestCommandRunner
import app.pocketshell.runtime.GuestExecutionProfile
import app.pocketshell.runtime.RuntimeProcessLauncher
import app.pocketshell.runtime.RuntimeState
import app.pocketshell.runtime.RuntimeStorage
import app.pocketshell.terminal.ShellEnvironment
import app.pocketshell.widget.HomeAppContext
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.HomeAppSpec
import app.pocketshell.widget.probe.ServerInfo
import app.pocketshell.widget.probe.ServerProbe
import java.io.File
import kotlinx.coroutines.Dispatchers
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
 * SERVERS — the server monitor, as a Home Application plugin.
 *
 * What it is: the v1 manifest card (verified listeners, six lines)
 * graduated into the three-tier monitor:
 *
 *   CARD (COMPACT/ROOMY)   the at-a-glance answer — N up, verified rows,
 *                          and (roomy) each endpoint's HTTP chip + beat
 *                          strip. Everything needed immediately.
 *   MAXIMIZED (FULL)       the desktop-class monitor — system health
 *                          strip, search + sort, response history with
 *                          session uptime, process facts, and the stop
 *                          action. The extra features live here, one
 *                          toggle away.
 *
 * Fact discipline (inherited, never invented):
 *   - endpoints come ONLY from the host's ServerProbe pipeline (a
 *     successful connect is kernel fact; attribution is the acceptor
 *     diff) — a port is shown because something answered it;
 *   - HTTP checks are app-side GETs on the shared loopback (same netns),
 *     bounded in count, timeout and body; 3xx/4xx/5xx render as what
 *     they are, redirects are never followed;
 *   - process facts are the attributed owner's /proc files (own-UID,
 *     device-verifiably readable); CPU is a tick delta, RSS is stat's
 *     rss field — a first sighting says "—", not 0%;
 *   - the system strip is labelled DEVICE: load/memory/uptime are the
 *     shared kernel's facts; the disk number is the rootfs image's.
 *
 * Cost discipline: the pid-set idle gate turns an idle card's tick into
 * one /proc readdir; HTTP checks are capped and parallel-bounded; a
 * paused monitor does nothing at all; the stop op is the ONLY guest exec.
 *
 * This file owns the application object, the process-scoped state and
 * the exec seam. The screens live in ServersScreens, the shared controls
 * in ServersComponents, the pure rules in ServersProbe/HttpCheck/
 * ServersPresentation/ServersOps.
 */
object ServersApp : HomeApplication() {

    /** The registry/catalog id (the plugin replaces the v1 manifest's id). */
    const val SERVERS_PLUGIN_ID = "servers"

    override val spec = HomeAppSpec(
        id = SERVERS_PLUGIN_ID,
        name = "Servers",
        summary = "Dev servers in the Linux guest — live endpoints, HTTP checks, process facts, system health",
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
                    while (true) {
                        if (!state.paused) {
                            state.tickIfDue()
                        }
                        delay(TICK_MS)
                    }
                }
        }

        // The manual refresh control: one pass, outside the gate.
        LaunchedEffect(state.refreshTick) {
            if (state.refreshTick == 0 || context.runtimeState != RuntimeState.READY) return@LaunchedEffect
            state.scan()
        }

        // THE stop path: request → (confirm) → one exec → honest outcome
        // → forced rescan. Nothing optimistic in between.
        LaunchedEffect(state.stopTick) {
            val req = state.stopInFlight ?: return@LaunchedEffect
            val outcome = withContext(Dispatchers.IO) { state.runStop(req) }
            state.stopOutcome = outcome
            state.stopInFlight = null
            state.scan()
        }

        // The application owns its back: from a detail page back to the
        // dashboard; only from the dashboard does back reach Home.
        BackHandler(enabled = state.selectedPort != null) { state.selectedPort = null }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layout = ServersLayout.from(maxWidth.value, maxHeight.value)
            val servers = (state.ui as? ServersUi.Ready)?.servers.orEmpty()

            state.stopPending?.let { pending ->
                StopConfirmDialog(
                    spec = ServersOps.confirmSpec(pending),
                    onConfirm = { state.confirmStop() },
                    onDismiss = { state.dismissStop() },
                )
            }
            state.stopOutcome?.let { outcome ->
                OutcomeBanner(outcome = outcome, onDismiss = { state.stopOutcome = null })
            }

            val selected = state.selectedPort?.let { port -> servers.firstOrNull { it.port == port } }
            ServersScreens(
                ui = state.ui,
                layout = layout,
                maxWidthDp = maxWidth.value,
                servers = servers,
                system = state.system,
                httpByPort = state.httpByPort,
                beatsByPort = state.beatsByPort,
                factsByPort = state.factsByPort,
                paused = state.paused,
                query = state.query,
                sort = state.sort,
                selected = selected,
                lastScanAtMs = state.lastScanAtMs,
                onRefresh = { state.refreshTick++ },
                onTogglePause = { state.paused = !state.paused },
                onQueryChange = { state.query = it },
                onSortChange = { state.sort = it },
                onSelect = { state.selectedPort = it },
                onBack = { state.selectedPort = null },
                onOpenTerminal = { context.nav.openTerminal() },
                onOpenLinuxShell = { context.nav.openLinuxShell() },
                onOpenDiagnostics = { context.nav.openDiagnostics() },
                onOpenCompanion = { context.nav.openCompanion(ServerProbe.companionUrl(it)) },
                onStopRequest = { state.requestStop(it) },
            )
        }
    }

    /** The card's refresh cadence — the built-in widget's proven 5 s. */
    const val TICK_MS = 5_000L

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

    private val scanMutex = Mutex()

    /** One gated pass: the idle gate first, then the full fact sweep. */
    suspend fun tickIfDue() {
        val pidSet = withContext(Dispatchers.IO) { probe.peekPidSet() }
        // Never scanned → scan. Otherwise the gate decides: an unchanged
        // pid set over a serverless last snapshot costs one /proc readdir.
        if (lastScanAtMs != 0L && !probe.shouldFullScan(pidSet)) return
        scan()
    }

    /**
     * The full pass: snapshot → HTTP checks (capped, parallel-bounded) →
     * process facts → system strip → state. Every stage degrades alone.
     */
    suspend fun scan() = scanMutex.withLock {
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

    // ------------------------------------------------------ stop pipeline

    /** The UI's ONE way to ask for a stop; refusals answer instantly. */
    fun requestStop(server: ServerInfo) {
        ServersOps.refusal(server)?.let { reason ->
            stopOutcome = refusedOutcome(server, reason)
            return
        }
        stopPending = ServersOps.StopRequest(
            port = server.port,
            pid = server.pid!!,
            processName = server.processName,
        )
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

    /** The one exec: `kill <pid>` in the guest, positioned, never interpolated. */
    internal fun runStop(req: ServersOps.StopRequest): ServersOps.Outcome {
        val argv = ServersOps.argv(req.pid)
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

    private fun refusedOutcome(server: ServerInfo, reason: String) = ServersOps.Outcome(
        req = ServersOps.StopRequest(server.port, server.pid ?: -1, server.processName),
        argv = emptyList(),
        exitCode = null,
        stderr = null,
        error = "not run — $reason",
        atMs = System.currentTimeMillis(),
    )

    /**
     * The exec seam — the SAME closure shape the Git plugin uses: a proot
     * guest exec against the host runtime, bounded by timeout. The stop
     * op is this application's ONLY use of it.
     */
    private fun guestExec(argv: List<String>, timeoutMs: Long): ExecResult {
        val storage = RuntimeStorage(appContext.noBackupFilesDir)
        val spec = RuntimeProcessLauncher.buildLaunchSpec(
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
