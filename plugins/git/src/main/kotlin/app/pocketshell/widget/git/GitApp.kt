package app.pocketshell.widget.git

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import app.pocketshell.packages.ProcessBuilderGuestCommandRunner
import app.pocketshell.runtime.GuestExecutionProfile
import app.pocketshell.runtime.RuntimeProcessLauncher
import app.pocketshell.runtime.RuntimeState
import app.pocketshell.runtime.RuntimeStorage
import app.pocketshell.terminal.ShellEnvironment
import app.pocketshell.widget.HomeAppContext
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.HomeAppSpec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * GIT — the PocketShell Git workstation, as a Home Application.
 *
 * What it is: a small-screen Git client in three layers of focus, not one
 * scrolling page.
 *
 *   HOME            the dashboard: every repository git can see under the
 *                   guest home, with branch / dirty / ahead-behind at a
 *                   glance, plus search, filters and favourites.
 *     ↓ open a repository
 *   REPOSITORY      the workspace: six tabs — Overview, Changes, History,
 *                   Branches, Files, Remotes — each a focused screen.
 *     ↓ tap a thing
 *   DRILL-DOWN      a file's diff (with prev/next through the change list),
 *                   a commit's message + files + patch, a branch's actions,
 *                   a file's facts. Android back walks the stack back up.
 *
 * Read discipline: GIT IS THE SOURCE OF TRUTH. The dashboard is one batched,
 * read-only guest exec ([GitProbe]); each deeper screen runs ONE targeted,
 * bounded read when it is opened ([GitReader]) and says how old its answer
 * is; a mutation runs through [GitOpRunner] and is followed by a forced
 * rescan, so no screen ever shows a state git did not report.
 *
 * This file owns the application object, the process-scoped state, the
 * routes and the exec seam. The screens live in GitScreens/GitDetails, the
 * shared controls in GitComponents, the pure rules in GitProbe/GitReader/
 * GitOps/GitFiles/GitDiffParser/GitPresentation/GitStatusParser.
 */
object GitApp : HomeApplication() {

    /** The registry id (HomeApplications registers this application). */
    const val GIT_ID = "git"

    override val spec = HomeAppSpec(
        id = GIT_ID,
        name = "Git",
        summary = "Repositories in the Linux guest — changes, history, branches, files, remotes",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        val appContext = LocalContext.current.applicationContext
        // The ONE holder: the probe (its idle gate IS the cache), the
        // reader, the op runner, the navigation stack and every screen's
        // read slots. Leaving Home, swiping the page away, or rotating
        // never resets them; the exec closure is built once, on first need.
        val state = remember {
            context.stateStore.forApp(GIT_ID) {
                val exec = guestExec(appContext)
                GitState(
                    probe = GitProbe(exec),
                    reader = GitReader(exec),
                    ops = GitOpRunner(exec),
                )
            }
        }
        val lifecycleOwner = LocalLifecycleOwner.current

        // The dashboard's one batched read: a lifecycle-aware tick whose
        // ONLY cost is deciding — the probe's idle gate turns a tick that
        // fires too soon into nothing at all (no guest exec while idle).
        LaunchedEffect(context.runtimeState) {
            if (context.runtimeState != RuntimeState.READY) {
                state.ui = GitUi.Unavailable
                return@LaunchedEffect
            }
            lifecycleOwner.lifecycle.currentStateFlow
                .map { it.isAtLeast(Lifecycle.State.RESUMED) }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    // Re-entry execs NOTHING on top of a usable cache: the
                    // SAME staleness policy as the tick loop decides. Only
                    // a cache-less ui (Probing, a failed probe — hasCache
                    // false) or a cache older than the idle gate re-scans.
                    val hasCache = state.ui is GitUi.Ready
                    if (!hasCache || state.probe.shouldFullScan(System.currentTimeMillis())) {
                        state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
                    }
                    while (true) {
                        delay(GitProbe.TICK_MS)
                        if (!state.probe.shouldFullScan(System.currentTimeMillis())) continue
                        state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
                    }
                }
        }

        // The manual refresh control: one scan, outside the gate.
        LaunchedEffect(state.refreshTick) {
            if (state.refreshTick == 0 || context.runtimeState != RuntimeState.READY) return@LaunchedEffect
            state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
        }

        // THE mutation path: one op at a time, run to completion, then a
        // FORCED rescan + read-epoch bump, so every pixel afterwards is
        // git's own answer — never an optimistic guess.
        LaunchedEffect(state.opTick) {
            val req = state.inFlightOp ?: return@LaunchedEffect
            val outcome = withContext(Dispatchers.IO) { state.ops.run(req.op, req.repoPath) }
            state.opOutcome = outcome
            state.inFlightOp = null
            state.probe.invalidate()
            state.readEpoch++
            state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
        }

        // The application owns its back: the stack's innermost screen pops
        // first; only from the dashboard does back reach the rest of Home.
        BackHandler(enabled = state.stack.isNotEmpty()) { state.back() }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layout = GitLayout.from(maxWidth.value, maxHeight.value)
            val repos = (state.ui as? GitUi.Ready)?.snapshot?.repos.orEmpty()
            state.retain(repos)

            // Any pending confirm renders above everything; nothing runs
            // until it is answered.
            state.pendingOp?.let { pending ->
                pending.confirm?.let { spec ->
                    OpConfirmDialog(
                        spec = spec,
                    command = GitOps.displayCommand(pending.op.argv(pending.repoPath)),
                        onConfirm = { state.confirmPending() },
                        onDismiss = { state.dismissPending() },
                    )
                }
            }
            state.opOutcome?.let { outcome ->
                OpResultBanner(outcome = outcome, onDismiss = { state.dismissOutcome() })
            }

            when (val top = state.stack.lastOrNull()) {
                null -> GitDashboard(
                    ui = state.ui,
                    repos = repos,
                    layout = layout,
                    selectedPath = state.selectedPath,
                    lastScanAtMs = state.probe.lastScanAtMs,
                    onRefresh = { state.refreshTick++ },
                    onSelectRepo = { state.selectedPath = it },
                    onOpenWorkspace = { repo -> state.open(Screen.Workspace(repo.path)) },
                    onOpenCommit = { repo, entry ->
                        state.open(Screen.CommitPage(repoPath = repo.path, hash = entry.hash))
                    },
                    onOpenTerminal = { context.nav.openTerminal() },
                    onOpenLinuxShell = { context.nav.openLinuxShell() },
                    onOpenDiagnostics = { context.nav.openDiagnostics() },
                )

                is Screen.Workspace -> GitWorkspace(
                    repo = state.repo(top.repoPath),
                    tab = top.tab,
                    state = state,
                    onBack = { state.back() },
                    onTab = { tab -> state.replaceTop(top.copy(tab = tab)) },
                    onOpenDiff = { target, title ->
                        state.open(Screen.DiffPage(repoPath = top.repoPath, target = target, title = title))
                    },
                    onOpenCommit = { hash ->
                        state.open(Screen.CommitPage(repoPath = top.repoPath, hash = hash))
                    },
                    onOpenFile = { path ->
                        state.open(Screen.FilePage(repoPath = top.repoPath, path = path))
                    },
                    onOp = { op -> state.requestOp(op, top.repoPath) },
                    onOpenTerminal = { context.nav.openTerminal() },
                )

                is Screen.DiffPage -> GitDiffScreen(
                    state = state,
                    repoPath = top.repoPath,
                    target = top.target,
                    title = top.title,
                    onBack = { state.back() },
                )

                is Screen.CommitPage -> GitCommitScreen(
                    state = state,
                    repoPath = top.repoPath,
                    hash = top.hash,
                    onBack = { state.back() },
                    onOpenDiff = { target, title ->
                        state.open(Screen.DiffPage(repoPath = top.repoPath, target = target, title = title))
                    },
                )

                is Screen.FilePage -> GitFileScreen(
                    state = state,
                    repoPath = top.repoPath,
                    path = top.path,
                    onBack = { state.back() },
                    onOpenDiff = { target, title ->
                        state.open(Screen.DiffPage(repoPath = top.repoPath, target = target, title = title))
                    },
                    onOp = { op -> state.requestOp(op, top.repoPath) },
                    onOpenTerminal = { context.nav.openTerminal() },
                )
            }
        }
    }

    /**
     * The real exec: the sanctioned non-PTY guest path — the SAME
     * buildLaunchSpec + background-runner pairing AlpinePackageManager.runApk
     * uses (single exec infrastructure, no duplicate proot logic). The
     * minimal PACKAGE_OPERATION profile is the right mount configuration,
     * and it is the device-proven safe one.
     */
    private fun guestExec(appContext: Context): GitProbe.GuestExec = GitProbe.GuestExec { argv, timeoutMs ->
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
            process.waitFor(timeoutMs)
        } catch (t: Throwable) {
            process.destroy()
            throw t
        }
    }
}

// --------------------------------------------------------------- routes

/** Every screen the application can show; the stack holds the open ones. */
internal sealed interface Screen {
    val repoPath: String

    /** The repository workspace: tabbed, one instance per repository. */
    data class Workspace(
        override val repoPath: String,
        val tab: RepoTab = RepoTab.OVERVIEW,
    ) : Screen

    /** One bounded patch: a worktree/index side or one commit's file. */
    data class DiffPage(
        override val repoPath: String,
        val target: DiffTarget,
        val title: String,
    ) : Screen

    /** One commit: facts, message, changed files. */
    data class CommitPage(
        override val repoPath: String,
        val hash: String,
    ) : Screen

    /** One file's facts (its status, its history in M3, its blame). */
    data class FilePage(
        override val repoPath: String,
        val path: String,
    ) : Screen
}

/** The repository workspace's tabs. */
internal enum class RepoTab(val label: String) {
    OVERVIEW("Overview"),
    CHANGES("Changes"),
    HISTORY("History"),
    BRANCHES("Branches"),
    FILES("Files"),
    REMOTES("Remotes"),
}

// ----------------------------------------------------------------- reads

/** A screen's read state: honest idle/loading/done/failed — never fake data. */
internal sealed interface ReadUi<out T> {
    data object Idle : ReadUi<Nothing>
    data object Loading : ReadUi<Nothing>
    data class Done<T>(val value: T) : ReadUi<T>
    data class Failed(val reason: String) : ReadUi<Nothing>
}

/**
 * One screen's slot for ONE on-demand read. The request's identity is the
 * cache key (back-and-return re-renders the answer without re-exec'ing);
 * [gen] cancels any in-flight read when the request changes, and
 * [servedEpoch] ties the cache to the mutation epoch — after any op the
 * answer re-fetches, because git may now say something else.
 */
internal class ReadSlot<R, T> {
    var req by mutableStateOf<R?>(null)
    var ui by mutableStateOf<ReadUi<T>>(ReadUi.Idle)
    var served by mutableStateOf<R?>(null)
    var servedEpoch by mutableIntStateOf(-1)

    /** Bumped with every new request; an older exec's result never lands. */
    var gen by mutableIntStateOf(0)

    fun open(request: R) {
        req = request
        gen++
    }

    fun reset() {
        served = null
        servedEpoch = -1
    }
}

internal data class RepoReq(val repoPath: String, val serial: Int)

internal data class HistoryReq(val repoPath: String, val window: Int, val serial: Int)

internal data class CommitReq(val repoPath: String, val hash: String, val serial: Int)

internal data class DiffReq(val repoPath: String, val target: DiffTarget, val serial: Int)

// ------------------------------------------------------------------- ops

/** One op awaiting (or running) — carries the confirm wording it earned. */
internal data class OpRequest(
    val op: GitOp,
    val repoPath: String,
    val confirm: GitOps.ConfirmSpec?,
    val serial: Int,
)

// ----------------------------------------------------------------- state

/**
 * The GIT application's process-scoped state, owned by the
 * HomeAppStateStore: the probe, the reader, the op runner, the navigation
 * stack, the dashboard ui and every screen's read slots. Nothing here
 * needs to survive process death (a fresh process re-probes honestly), so
 * no DataStore is involved.
 */
internal class GitState(
    val probe: GitProbe,
    val reader: GitReader,
    val ops: GitOpRunner,
) {
    // The dashboard's batched answer (probe-driven, never invented).
    var ui by mutableStateOf<GitUi>(GitUi.Probing)
    var refreshTick by mutableStateOf(0)
    var selectedPath by mutableStateOf<String?>(null)

    // The navigation stack (innermost last); empty = the dashboard.
    val stack = mutableStateListOf<Screen>()
    var navSerial by mutableIntStateOf(0)

    fun open(screen: Screen) {
        stack.add(screen)
        navSerial++
    }

    fun back() {
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.lastIndex)
            navSerial++
        }
    }

    /** Tab switches mutate the workspace in place — never a new page. */
    fun replaceTop(screen: Screen) {
        if (stack.isNotEmpty()) {
            stack[stack.lastIndex] = screen
            navSerial++
        }
    }

    /** The open workspace's repo, or null when it vanished (rescan). */
    fun repo(path: String): RepoSnapshot? =
        (ui as? GitUi.Ready)?.snapshot?.repos?.firstOrNull { it.path == path }

    /**
     * A repository the stack references that no longer exists (deleted
     * directory, failed scan) degrades gracefully: the stack unwinds to
     * the dashboard instead of rendering a stale page.
     */
    fun retain(repos: List<RepoSnapshot>) {
        if (stack.isEmpty()) return
        val paths = repos.mapTo(HashSet()) { it.path }
        while (stack.isNotEmpty() && stack.last().repoPath !in paths) {
            stack.removeAt(stack.lastIndex)
            navSerial++
        }
    }

    // The on-demand read slots — one per screen need (M3 adds more).
    val history = ReadSlot<HistoryReq, List<LogEntry>>()
    val remoteBranches = ReadSlot<RepoReq, Bounded<Branch>>()
    val stashes = ReadSlot<RepoReq, Bounded<StashEntry>>()
    val files = ReadSlot<RepoReq, FileList>()
    val commit = ReadSlot<CommitReq, CommitDetail>()
    val diff = ReadSlot<DiffReq, DiffText>()

    /**
     * The mutation epoch: every completed op bumps it, and every read slot
     * re-fetches — the forced-rescan rule extends to the deeper reads.
     */
    var readEpoch by mutableIntStateOf(0)

    // The op pipeline: request → (confirm) → run → outcome banner.
    var pendingOp by mutableStateOf<OpRequest?>(null)
    var opOutcome by mutableStateOf<GitOpOutcome?>(null)
    var inFlightOp by mutableStateOf<OpRequest?>(null)
    var opTick by mutableIntStateOf(0)

    private var opSerial = 0

    /**
     * The UI's ONE way to ask for a mutation. The runner re-validates, but
     * a refusal here (before any dialog) gives instant, specific feedback;
     * a confirm-worthy op stops for its dialog first.
     */
    fun requestOp(op: GitOp, repoPath: String, context: GitOps.ConfirmContext = GitOps.ConfirmContext()) {
        GitOps.refusal(op)?.let { reason ->
            opOutcome = refusedOutcome(op, repoPath, reason)
            return
        }
        val confirm = GitOps.confirm(op, context)
        if (confirm == null) {
            launchOp(op, repoPath)
        } else {
            pendingOp = OpRequest(op, repoPath, confirm, serial = ++opSerial)
        }
    }

    fun confirmPending() {
        val pending = pendingOp
        pendingOp = null
        pending?.let { launchOp(it.op, it.repoPath) }
    }

    fun dismissPending() {
        pendingOp = null
    }

    fun dismissOutcome() {
        opOutcome = null
    }

    private fun launchOp(op: GitOp, repoPath: String) {
        inFlightOp = OpRequest(op, repoPath, confirm = null, serial = ++opSerial)
        opTick++
    }

    private fun refusedOutcome(op: GitOp, repoPath: String, reason: String): GitOpOutcome =
        GitOpOutcome(
            op = op,
            argv = op.argv(repoPath),
            exitCode = null,
            stdout = "",
            stderr = "",
            error = "not run — $reason",
            atMs = System.currentTimeMillis(),
        )
}

/** The dashboard's screen state (probe-driven, never invented). */
internal sealed interface GitUi {
    data object Probing : GitUi
    data object Unavailable : GitUi
    data class ProbeFailed(val reason: String) : GitUi
    data class Ready(val snapshot: GitSnapshot) : GitUi
}

internal fun scanToUi(result: ScanResult): GitUi = when (result) {
    is ScanResult.Done -> GitUi.Ready(result.snapshot)
    is ScanResult.Failed -> GitUi.ProbeFailed(result.reason)
}
