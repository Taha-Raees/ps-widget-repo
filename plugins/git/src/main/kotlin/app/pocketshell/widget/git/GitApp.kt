package app.pocketshell.widget.git

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
 * M9 — GIT, redesigned from a dashboard card into a real small-screen Git
 * workstation (the BlackBerry information architecture, not its look):
 *
 *   HOME is a launch surface — the repository list (name · branch · dirty
 *     dot · ahead/behind), honest whole-card states, refresh/search/menu.
 *   REPO opens a focused surface with five tabs — CHANGES (staged /
 *     unstaged / untracked / conflicts, stage-unstage-discard-commit),
 *     HISTORY (paged log → commit detail → per-file diff), BRANCHES
 *     (checkout / create / merge / delete), FILES (worktree browser →
 *     read-only preview), REMOTES (endpoints + fetch/pull/push).
 *   Detail flows — DIFF (prev/next across the opened file list, colored
 *     from the shared theme), COMMIT (--numstat facts + per-file diffs),
 *     VIEWER (capped honest preview), STASH (list / push / pop / drop).
 *
 * The card owns a navigation STACK ([GitState.stack]): every screen is a
 * value, back pops the innermost, and only the root's back reaches the
 * rest of Home. Scrolling exists only where a LIST is longer than the
 * viewport; navigation replaces the old single-scroll dashboard.
 *
 * Git stays the source of truth. The SCAN (repositories, status, recent
 * commits, branches, remotes) is the same ONE batched read-only guest
 * exec per refresh as before, behind its idle gate. Every OPERATION
 * (stage/commit/checkout/…) is one explicit, user-confirmed tap = one
 * bounded exec ([GitOps]), its result shown verbatim in the banner, and
 * a successful op re-scans so the next render comes from git, not from
 * the op's word. Inspections (diffs, commit pages, history pages, file
 * previews, stash lists) stay one-tap-one-exec with last-viewed caches
 * and the serial guard. Truthful degradation everywhere: real reasons,
 * never invented clean/dirty/success states.
 */
object GitApp : HomeApplication() {

    /** The registry id (HomeApplications registers this application). */
    const val GIT_ID = "git"

    override val spec = HomeAppSpec(
        id = GIT_ID,
        name = "Git",
        summary = "Repositories in the Linux guest — changes, history, branches, sync",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        val appContext = LocalContext.current.applicationContext
        // The ONE holder: probe, ops, navigation stack and every page's
        // state live in the process-scoped store — returning to the card
        // re-renders the cached truth; nothing survives process death.
        val state = remember {
            context.stateStore.forApp(GIT_ID) {
                val exec = guestExec(appContext)
                GitState(GitProbe(exec), GitOps(exec))
            }
        }
        val lifecycleOwner = LocalLifecycleOwner.current

        // ---- the scan: runtime gate + lifecycle-gated idle-gated loop ----
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
                    // SAME staleness policy as the tick loop decides.
                    val hasCache = state.ui is GitUi.Ready
                    if (!hasCache || state.probe.shouldFullScan(System.currentTimeMillis())) {
                        state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
                        state.dataTick++
                    }
                    while (true) {
                        delay(GitProbe.TICK_MS)
                        if (!state.probe.shouldFullScan(System.currentTimeMillis())) continue
                        state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
                        state.dataTick++
                    }
                }
        }

        LaunchedEffect(state.refreshTick) {
            if (state.refreshTick == 0 || context.runtimeState != RuntimeState.READY) return@LaunchedEffect
            state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
            state.dataTick++
        }

        // ---- the operation engine: one queued op at a time, one exec,
        // its real result in the banner, a re-scan after success so no
        // success indicator can outlive git's truth. ----
        LaunchedEffect(state.opReq) {
            val req = state.opReq ?: return@LaunchedEffect
            if (state.opRunning != null) return@LaunchedEffect
            state.opRunning = describeOp(req)
            val result = withContext(Dispatchers.IO) { executeOp(state.ops, req) }
            state.opRunning = null
            state.opResult = result
            if (result is GitOps.OpResult.Ok) {
                state.onMutationApplied(req.repoPath)
                state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
                state.dataTick++
            }
        }

        // ---- the commit page: one bounded read-only `git show --numstat`,
        // served from the last-viewed cache until the page or the data
        // changes, the result guarded by the navigation serial. ----
        val top = state.stack.lastOrNull()
        val commitReq = top as? GitScreen.Commit
        LaunchedEffect(commitReq, state.dataTick) {
            val req = commitReq ?: return@LaunchedEffect
            val servedSame = state.commitServed == req && state.servedAtTick == state.dataTick
            if (servedSame && state.commitUi is CommitUi.Done) return@LaunchedEffect
            val gen = state.navSerial
            state.commitUi = CommitUi.Loading
            val result = withContext(Dispatchers.IO) {
                state.probe.showCommit(req.repoPath, req.hash)
            }
            if (state.navSerial == gen) {
                when (result) {
                    is CommitResult.Done -> {
                        state.commitUi = CommitUi.Done(result.detail)
                        state.commitServed = req
                        state.servedAtTick = state.dataTick
                    }
                    is CommitResult.Failed -> state.commitUi = CommitUi.Failed(result.reason)
                }
            }
        }

        // ---- the diff page: `git diff [--cached]` for a working change,
        // `git show <hash> -- <path>` for a commit's file. An untracked
        // path needs no exec at all — the page says so honestly. ----
        val diffReq = top as? GitScreen.Diff
        LaunchedEffect(diffReq, state.dataTick) {
            val req = diffReq ?: return@LaunchedEffect
            val servedSame = state.diffServed == req && state.servedAtTick == state.dataTick
            if (servedSame && (state.diffUi is DiffUi.Done || state.diffUi is DiffUi.Untracked)) {
                return@LaunchedEffect
            }
            if (req.untracked) {
                state.diffUi = DiffUi.Untracked
                state.diffServed = req
                state.servedAtTick = state.dataTick
                return@LaunchedEffect
            }
            val gen = state.navSerial
            state.diffUi = DiffUi.Loading
            val result = withContext(Dispatchers.IO) {
                val hash = req.atCommit
                when {
                    hash != null -> state.probe.commitFileDiff(req.repoPath, hash, req.path)
                    else -> state.probe.diffFile(req.repoPath, req.path, req.staged)
                }
            }
            if (state.navSerial == gen) {
                when (result) {
                    is DiffResult.Done -> {
                        state.diffUi = DiffUi.Done(result.text)
                        state.diffServed = req
                        state.servedAtTick = state.dataTick
                    }
                    is DiffResult.Failed -> state.diffUi = DiffUi.Failed(result.reason)
                }
            }
        }

        // ---- the file preview: one bounded read of one worktree file. ----
        val viewerReq = top as? GitScreen.Viewer
        LaunchedEffect(viewerReq, state.dataTick) {
            val req = viewerReq ?: return@LaunchedEffect
            val servedSame = state.viewerServed == req && state.servedAtTick == state.dataTick
            if (servedSame && state.viewerUi is ViewerUi.Done) return@LaunchedEffect
            val gen = state.navSerial
            state.viewerUi = ViewerUi.Loading
            val result = withContext(Dispatchers.IO) {
                state.probe.readHead(viewerFilePath(req))
            }
            if (state.navSerial == gen) {
                when (result) {
                    is ReadHeadResult.Done -> {
                        state.viewerUi = ViewerUi.Done(result.page)
                        state.viewerServed = req
                        state.servedAtTick = state.dataTick
                    }
                    is ReadHeadResult.Failed -> state.viewerUi = ViewerUi.Failed(result.reason)
                }
            }
        }

        // ---- the stash screen: one bounded `git stash list` per open. ----
        val stashReq = top as? GitScreen.Stash
        LaunchedEffect(stashReq, state.dataTick) {
            val req = stashReq ?: return@LaunchedEffect
            val servedSame = state.stashServed == req && state.servedAtTick == state.dataTick
            if (servedSame && state.stashUi is StashUi.Done) return@LaunchedEffect
            val gen = state.navSerial
            state.stashUi = StashUi.Loading
            val result = withContext(Dispatchers.IO) {
                state.probe.stashList(req.repoPath)
            }
            if (state.navSerial == gen) {
                when (result) {
                    is StashListResult.Done -> {
                        state.stashUi = StashUi.Done(result.entries)
                        state.stashServed = req
                        state.servedAtTick = state.dataTick
                    }
                    is StashListResult.Failed -> state.stashUi = StashUi.Failed(result.reason)
                }
            }
        }

        // ---- the History tab: page 0 loads when a repo's history screen
        // is shown without one; LOAD MORE appends (one tap = one exec). ----
        val repoReq = top as? GitScreen.Repo
        if (repoReq != null && state.repoTab(repoReq.path) == RepoTab.HISTORY) {
            LaunchedEffect(repoReq.path, state.dataTick) {
                val existing = state.history[repoReq.path]
                if (existing == null) {
                    state.loadHistoryPage(repoReq.path, append = false)
                    state.runHistoryExec(repoReq.path)
                }
            }
        }

        // ---- the Files tab: the open directory lists when it changes. ----
        if (repoReq != null && state.repoTab(repoReq.path) == RepoTab.FILES) {
            LaunchedEffect(repoReq.path, state.files[repoReq.path]?.dir, state.dataTick) {
                val filesUi = state.files[repoReq.path]
                if (filesUi == null || (filesUi.entries == null && filesUi.error == null)) {
                    state.runListExec(repoReq.path, filesUi?.dir ?: repoReq.path)
                }
            }
        }

        // A navigation change abandons nothing (execs are guarded) but the
        // banner's result belongs to the screen it happened on — a new
        // screen starts clean.
        LaunchedEffect(state.stack) { if (state.stack.isNotEmpty()) state.opResult = null }

        // Back pops the stack; only the root's back leaves the card.
        BackHandler(enabled = top != null) { state.pop() }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layout = GitLayout.from(maxWidth.value, maxHeight.value)
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (top) {
                        null -> GitHomeScreen(
                            state = state,
                            layout = layout,
                            onOpenLinuxShell = context.nav::openLinuxShell,
                            onOpenDiagnostics = context.nav::openDiagnostics,
                        )
                        is GitScreen.Repo -> GitRepoScreen(
                            state = state,
                            repoPath = top.path,
                            layout = layout,
                            onOpenTerminal = context.nav::openTerminal,
                        )
                        is GitScreen.Diff -> GitDiffScreen(
                            state = state,
                            screen = top,
                            layout = layout,
                        )
                        is GitScreen.Commit -> GitCommitScreen(
                            state = state,
                            screen = top,
                            layout = layout,
                        )
                        is GitScreen.Viewer -> GitViewerScreen(state = state, screen = top)
                        is GitScreen.Stash -> GitStashScreen(state = state, screen = top)
                    }
                }
                OpBanner(
                    running = state.opRunning,
                    result = state.opResult,
                    onDismiss = { state.opResult = null },
                )
            }
        }
    }

    /**
     * The real exec: the sanctioned non-PTY guest path — the SAME
     * buildLaunchSpec + background-runner pairing AlpinePackageManager.runApk
     * uses (single exec infrastructure, no duplicate proot logic). The
     * minimal PACKAGE_OPERATION profile is the right mount configuration
     * for read-only probes AND bounded user-confirmed operations, and it
     * is the device-proven safe one. No DNS/workspace repair: nothing
     * here mutates the rootfs itself.
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

/**
 * The GIT application's process-scoped state, owned by the HomeAppStateStore:
 * the probe (its idle gate IS the cache), the ops layer, the navigation
 * stack, the per-repo tab memory, the per-repo History/Files tab data, the
 * four inspection pages' states with their last-viewed caches, and the
 * operation engine's request/running/result. [dataTick] bumps whenever the
 * underlying data MAY have changed (a scan landed, an op completed) — the
 * page caches are valid only within one tick, so a staged file's diff or a
 * fresh commit's page can never render from a stale cache. [navSerial]
 * bumps on every navigation change — the stale-exec guard: an inspection
 * result may land only while its serial is still current.
 */
internal class GitState(val probe: GitProbe, val ops: GitOps) {
    var ui by mutableStateOf<GitUi>(GitUi.Probing)
    var refreshTick by mutableStateOf(0)

    // The navigation stack — Home is the implicit root; back pops.
    var stack by mutableStateOf<List<GitScreen>>(emptyList())
    var repoTabs by mutableStateOf<Map<String, Int>>(emptyMap())
    var navSerial by mutableStateOf(0)

    // Data generation: bumps when scans or successful ops land.
    var dataTick by mutableStateOf(0)
    var servedAtTick by mutableStateOf(-1)

    // Per-repo tab data.
    var history by mutableStateOf<Map<String, HistoryUi>>(emptyMap())
    var files by mutableStateOf<Map<String, FilesUi>>(emptyMap())
    var branches by mutableStateOf<Map<String, BranchesUi>>(emptyMap())

    // The inspection pages' states + last-viewed caches.
    var commitUi by mutableStateOf<CommitUi>(CommitUi.Idle)
    var commitServed by mutableStateOf<GitScreen.Commit?>(null)
    var diffUi by mutableStateOf<DiffUi>(DiffUi.Idle)
    var diffServed by mutableStateOf<GitScreen.Diff?>(null)
    var viewerUi by mutableStateOf<ViewerUi>(ViewerUi.Idle)
    var viewerServed by mutableStateOf<GitScreen.Viewer?>(null)
    var stashUi by mutableStateOf<StashUi>(StashUi.Idle)
    var stashServed by mutableStateOf<GitScreen.Stash?>(null)

    // The operation engine: one queued op at a time.
    var opReq by mutableStateOf<OpReq?>(null)
    var opRunning by mutableStateOf<String?>(null)
    var opResult by mutableStateOf<GitOps.OpResult?>(null)

    fun push(screen: GitScreen) {
        stack = stack + screen
        navSerial++
    }

    fun pop() {
        if (stack.isNotEmpty()) stack = stack.dropLast(1)
        navSerial++
    }

    fun replaceTop(screen: GitScreen) {
        if (stack.isNotEmpty()) stack = stack.dropLast(1) + screen
        navSerial++
    }

    fun repoTab(path: String): RepoTab =
        RepoTab.entries.getOrElse(repoTabs[path] ?: 0) { RepoTab.CHANGES }

    fun setRepoTab(path: String, tab: RepoTab) {
        repoTabs = repoTabs + (path to tab.ordinal)
    }

    /** The dirty flag the checkout confirmation needs. */
    fun repoDirty(path: String): Boolean =
        (ui as? GitUi.Ready)?.snapshot?.repos?.firstOrNull { it.path == path }?.status?.dirty == true

    /** Queue one operation; a second is refused while one is in flight. */
    fun queueOp(kind: OpKind, repoPath: String, arg: String?) {
        if (opRunning != null) return
        opResult = null
        opReq = OpReq(kind, repoPath, arg, serial = 0)
    }

    /**
     * After a SUCCESSFUL mutation: the tab caches for that repo are facts
     * about the OLD tree — dropped so the next view re-asks git.
     */
    fun onMutationApplied(repoPath: String) {
        if (history.containsKey(repoPath)) history = history - repoPath
        if (branches.containsKey(repoPath)) branches = branches - repoPath
    }

    /** Load (or append) one history page request — marks loading, then the
     * caller executes [runHistoryExec] on its IO-capable coroutine. */
    fun loadHistoryPage(repoPath: String, append: Boolean) {
        val current = if (append) history[repoPath] ?: return else HistoryUi()
        if (current.loading) return
        history = history + (repoPath to current.copy(loading = true, error = null))
    }

    /**
     * The async half of history paging: executes the page request and
     * lands it only if the repo's history state still expects it (its
     * loading page matches the request's skip).
     */
    suspend fun runHistoryExec(repoPath: String) {
        val current = history[repoPath] ?: return
        val result = withContext(Dispatchers.IO) {
            probe.logPage(repoPath, skip = current.nextSkip, count = GitProbe.HISTORY_PAGE_SIZE)
        }
        val expecting = history[repoPath]
        if (expecting != null && expecting.nextSkip == current.nextSkip) {
            history = history + (repoPath to when (result) {
                is LogPageResult.Done -> HistoryUi(
                    entries = expecting.entries + result.page.entries,
                    hasMore = result.page.hasMore,
                    loading = false,
                )
                is LogPageResult.Failed -> HistoryUi(
                    entries = expecting.entries,
                    hasMore = expecting.hasMore,
                    loading = false,
                    error = result.reason,
                )
            })
        }
    }

    /** Open (or re-open) one directory in the Files tab — one exec. */
    suspend fun runListExec(repoPath: String, dir: String) {
        files = files + (repoPath to FilesUi(dir = dir, loading = true))
        val result = withContext(Dispatchers.IO) { probe.listDir(dir) }
        val expecting = files[repoPath]
        if (expecting?.dir == dir) {
            files = files + (repoPath to when (result) {
                is ListDirResult.Done -> FilesUi(dir = dir, entries = result.entries)
                is ListDirResult.Failed -> FilesUi(dir = dir, error = result.reason)
            })
        }
    }
}

/** The absolute guest path of a viewer page's file. */
internal fun viewerFilePath(screen: GitScreen.Viewer): String =
    if (screen.path.startsWith("/")) screen.path
    else "${screen.repoPath}/${screen.path}"

/**
 * The operation executor — the ONE when-dispatch from an [OpReq] to its
 * single git exec. Arguments are the UI's own validated values: paths
 * ride after `--`, names passed [GitOps.validRefName], indexes Ints.
 */
internal fun executeOp(ops: GitOps, req: OpReq): GitOps.OpResult = when (req.kind) {
    OpKind.STAGE -> ops.stage(req.repoPath, req.arg.orEmpty())
    OpKind.STAGE_ALL -> ops.stageAll(req.repoPath)
    OpKind.UNSTAGE -> ops.unstage(req.repoPath, req.arg.orEmpty())
    OpKind.UNSTAGE_ALL -> ops.unstageAll(req.repoPath)
    OpKind.DISCARD -> ops.discard(req.repoPath, req.arg.orEmpty())
    OpKind.COMMIT -> ops.commit(req.repoPath, req.arg.orEmpty())
    OpKind.CHECKOUT -> ops.checkout(req.repoPath, req.arg.orEmpty())
    OpKind.CREATE_BRANCH -> ops.createBranch(req.repoPath, req.arg.orEmpty())
    OpKind.DELETE_BRANCH -> ops.deleteBranch(req.repoPath, req.arg.orEmpty())
    OpKind.MERGE -> ops.merge(req.repoPath, req.arg.orEmpty())
    OpKind.FETCH -> ops.fetch(req.repoPath)
    OpKind.PULL -> ops.pull(req.repoPath)
    OpKind.PUSH -> ops.push(req.repoPath)
    OpKind.STASH_PUSH -> ops.stashPush(req.repoPath, req.arg?.ifBlank { null })
    OpKind.STASH_POP -> ops.stashPop(req.repoPath, req.arg?.toIntOrNull() ?: -1)
    OpKind.STASH_DROP -> ops.stashDrop(req.repoPath, req.arg?.toIntOrNull() ?: -1)
}

/** The banner's running line: the exact command the tap is executing. */
internal fun describeOp(req: OpReq): String {
    val arg = req.arg
    return when (req.kind) {
        OpKind.STAGE -> "git add -- $arg"
        OpKind.STAGE_ALL -> "git add -A"
        OpKind.UNSTAGE -> "git restore --staged -- $arg"
        OpKind.UNSTAGE_ALL -> "git reset"
        OpKind.DISCARD -> "git restore -- $arg"
        OpKind.COMMIT -> "git commit -m …"
        OpKind.CHECKOUT -> "git checkout $arg"
        OpKind.CREATE_BRANCH -> "git checkout -b $arg"
        OpKind.DELETE_BRANCH -> "git branch -d $arg"
        OpKind.MERGE -> "git merge $arg"
        OpKind.FETCH -> "git fetch"
        OpKind.PULL -> "git pull"
        OpKind.PUSH -> "git push"
        OpKind.STASH_PUSH -> "git stash push"
        OpKind.STASH_POP -> "git stash pop stash@{$arg}"
        OpKind.STASH_DROP -> "git stash drop stash@{$arg}"
    }
}

/**
 * The responsive contract — same geometry as ServersLayout (the card
 * dimensions are identical, so the device-derived thresholds carry over):
 * COMPACT keeps one-line rows; ROOMY adds sublines (authors, times, full
 * paths) where the extra line earns its space. Pure + JVM-tested.
 */
internal enum class GitLayout(
    val showsSublines: Boolean,
) {
    COMPACT(showsSublines = false),
    ROOMY(showsSublines = true);

    companion object {
        const val ROOMY_MIN_WIDTH_DP = 420f
        const val ROOMY_MIN_HEIGHT_DP = 200f

        fun from(widthDp: Float, heightDp: Float): GitLayout =
            if (widthDp >= ROOMY_MIN_WIDTH_DP && heightDp >= ROOMY_MIN_HEIGHT_DP) ROOMY else COMPACT
    }
}
