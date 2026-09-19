package app.pocketshell.widget.git

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import app.pocketshell.packages.ProcessBuilderGuestCommandRunner
import app.pocketshell.runtime.GuestExecutionProfile
import app.pocketshell.runtime.RuntimeProcessLauncher
import app.pocketshell.runtime.RuntimeState
import app.pocketshell.runtime.RuntimeStorage
import app.pocketshell.terminal.ShellEnvironment
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme
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
 * M8.3 — GIT: the second PocketShell Home Application (ServersApp is the
 * reference; this file copies its discipline). The card IS the screen,
 * with the information hierarchy of the mature terminal TUIs (lazygit,
 * tig) borrowed, not their UI:
 *
 *   overview (repo chips when several — selection swaps the pane IN
 *     PLACE; the selected repo's pane: branch + tracking glyphs + worktree
 *     marker, then CONFLICTS / STAGED / UNSTAGED groups, then the RECENT
 *     commit list, the BRANCHES survey and the REMOTES list)
 *     ↓ tap the pane / a commit / a file in the detail page
 *   detail (branch/upstream/diverge table, the full porcelain rows — each
 *     row opens the file's DIFF — and TERMINAL / LINUX / REFRESH)
 *   commit page (one bounded read-only `git show --stat`: full hash,
 *     author, date, subject, the stat list)
 *     ↓ back (the card's OWN back handler — only from the overview does
 *       back reach the rest of Home)
 *
 * The questions the card answers: "what is happening in my repositories
 * right now, and what has been happening in them?" — answered with REAL
 * guest data only. One batched read-only guest exec per refresh
 * ([GitProbe]) reports the git binary, repositories under the guest home,
 * each repo's `status --porcelain=v1 -b`, its last 5 commits, its local
 * branches and its remotes. There are still NO staging/commit/push/
 * checkout controls: the app does not own the repositories, a terminal is
 * where git work happens, and the only manual execs are read-only
 * inspection (`git show --stat` for one commit, `git diff` for one file —
 * one tap = one bounded exec, the Sync dry-run discipline) plus the
 * existing navigation seams (open the terminal, enter the Linux guest)
 * and refresh.
 *
 * Honest degradation everywhere: runtime not READY → "Linux not ready";
 * git absent in the guest → "Git unavailable" + how to get it; no repos
 * → "No repositories" + where they would appear; a failed exec → the real
 * reason, never "no repositories"; one unreadable repo degrades alone; a
 * failed inspection shows the tool's real stderr tail. M8.4.2: the probe
 * instance, the last scan's ui, the detail page and the selected repo
 * live in the process-scoped holder ([GitState] via stateStore.forApp) —
 * returning to the card renders the cached snapshot instantly, and the
 * probe's idle gate stays the only periodic scan path. M8.4.3: the commit
 * and diff pages live there too, with a last-viewed cache so back-and-
 * return does not re-exec unless the target changed, and a serial guard
 * so a stale exec can never overwrite a newer one.
 */
object GitApp : HomeApplication() {

    /** The registry id (HomeApplications registers this application). */
    const val GIT_ID = "git"

    override val spec = HomeAppSpec(
        id = GIT_ID,
        name = "Git",
        summary = "Repositories in the Linux guest — branch, dirty state, ahead/behind",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        val appContext = LocalContext.current.applicationContext
        // M8.4.2 — the ONE holder: the probe (its idle gate IS the cache),
        // the last scan's ui, the open detail page and the selected repo.
        // Leaving Home, swiping the page away, or rotating never resets
        // them; the exec closure is built once, on first need.
        val state = remember {
            context.stateStore.forApp(GIT_ID) { GitState(GitProbe(guestExec(appContext))) }
        }
        val lifecycleOwner = LocalLifecycleOwner.current

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
                    // false) or a cache older than the idle gate re-scans;
                    // swiping away and back re-renders, never re-probes.
                    val hasCache = state.ui is GitUi.Ready
                    if (!hasCache || state.probe.shouldFullScan(System.currentTimeMillis())) {
                        state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
                    }
                    while (true) {
                        delay(GitProbe.TICK_MS)
                        // The idle gate: a tick that fires too soon after the
                        // last scan does NOTHING — no guest exec while idle.
                        if (!state.probe.shouldFullScan(System.currentTimeMillis())) continue
                        state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
                    }
                }
        }

        LaunchedEffect(state.refreshTick) {
            if (state.refreshTick == 0 || context.runtimeState != RuntimeState.READY) return@LaunchedEffect
            state.ui = scanToUi(withContext(Dispatchers.IO) { state.probe.snapshot() })
        }

        // M8.4.3 — the commit page: one tap = one bounded, read-only
        // `git show --stat`. A request whose repo+hash already rendered a
        // DONE page is served from the last-viewed cache (back-and-return
        // re-executes nothing); anything else execs once, and the result
        // lands only if its request is still the newest — a stale exec
        // (superseded by another tap, or abandoned by navigation) can
        // never overwrite the page.
        LaunchedEffect(state.commitReq) {
            val req = state.commitReq ?: return@LaunchedEffect
            val served = state.commitServed
            if (state.commitUi is CommitUi.Done &&
                served != null &&
                served.repoPath == req.repoPath &&
                served.hash == req.hash
            ) {
                return@LaunchedEffect
            }
            val gen = req.serial
            state.commitUi = CommitUi.Loading
            val result = withContext(Dispatchers.IO) {
                state.probe.showCommit(req.repoPath, req.hash)
            }
            if (state.reqSerial == gen) {
                when (result) {
                    is CommitResult.Done -> {
                        state.commitUi = CommitUi.Done(result.detail)
                        state.commitServed = req
                    }
                    is CommitResult.Failed -> state.commitUi = CommitUi.Failed(result.reason)
                }
            }
        }

        // M8.4.3 — the diff page: the same one-tap-one-exec shape for
        // `git diff [--cached] -- <path>`. An untracked path needs no exec
        // at all: git has no diff for it, and the page says so honestly.
        LaunchedEffect(state.diffReq) {
            val req = state.diffReq ?: return@LaunchedEffect
            val served = state.diffServed
            val servedSame = served != null &&
                served.repoPath == req.repoPath &&
                served.path == req.path &&
                served.staged == req.staged &&
                served.untracked == req.untracked
            if (servedSame && (state.diffUi is DiffUi.Done || state.diffUi is DiffUi.Untracked)) {
                return@LaunchedEffect
            }
            if (req.untracked) {
                state.diffUi = DiffUi.Untracked
                state.diffServed = req
                return@LaunchedEffect
            }
            val gen = req.serial
            state.diffUi = DiffUi.Loading
            val result = withContext(Dispatchers.IO) {
                state.probe.diffFile(req.repoPath, req.path, req.staged)
            }
            if (state.reqSerial == gen) {
                when (result) {
                    is DiffResult.Done -> {
                        state.diffUi = DiffUi.Done(result.text)
                        state.diffServed = req
                    }
                    is DiffResult.Failed -> state.diffUi = DiffUi.Failed(result.reason)
                }
            }
        }

        val repos = (state.ui as? GitUi.Ready)?.snapshot?.repos.orEmpty()
        // The pane's repository: the chip selection, defaulting to — and
        // degrading to — the first repo; a vanished selection never leaves
        // a stale pane.
        val paneRepo = repos.firstOrNull { it.path == state.selectedPath } ?: repos.firstOrNull()
        // A detail selection whose repo vanished degrades to the overview —
        // never a stale detail page for a deleted directory.
        val selected = state.detailPath?.let { path -> repos.firstOrNull { it.path == path } }
        // The innermost open page closes first: commit page → diff page →
        // repo detail; only from the overview does back leave the card.
        BackHandler(enabled = selected != null || state.commitReq != null || state.diffReq != null) {
            when {
                state.commitReq != null -> state.closeCommit()
                state.diffReq != null -> state.closeDiff()
                else -> state.detailPath = null
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layout = GitLayout.from(maxWidth.value, maxHeight.value)
            val commitReq = state.commitReq
            val diffReq = state.diffReq
            when {
                commitReq != null -> GitCommitPage(
                    ui = state.commitUi,
                    onBack = { state.closeCommit() },
                )
                diffReq != null -> GitDiffPage(
                    req = diffReq,
                    ui = state.diffUi,
                    onBack = { state.closeDiff() },
                )
                selected != null -> GitDetail(
                    repo = selected,
                    roomy = layout == GitLayout.ROOMY,
                    onBack = { state.detailPath = null },
                    onRefresh = { state.refreshTick++ },
                    onOpenDiff = { entry -> state.openDiff(selected.path, entry) },
                    onOpenTerminal = { context.nav.openTerminal() },
                    onOpenLinuxShell = { context.nav.openLinuxShell() },
                )
                else -> GitOverview(
                    ui = state.ui,
                    repos = repos,
                    paneRepo = paneRepo,
                    layout = layout,
                    onRefresh = { state.refreshTick++ },
                    onSelectRepo = { state.selectedPath = it.path },
                    onOpenDetail = { state.detailPath = it.path },
                    onOpenCommit = { repo, entry -> state.openCommit(repo.path, entry.hash) },
                    onOpenTerminal = { context.nav.openTerminal() },
                    onOpenLinuxShell = { context.nav.openLinuxShell() },
                    onOpenDiagnostics = { context.nav.openDiagnostics() },
                )
            }
        }
    }

    /**
     * The real exec: the sanctioned non-PTY guest path — the SAME
     * buildLaunchSpec + background-runner pairing AlpinePackageManager.runApk
     * uses (single exec infrastructure, no duplicate proot logic). The
     * minimal PACKAGE_OPERATION profile is the right mount configuration for
     * an offline read-only probe, and it is the device-proven safe one. No
     * DNS/workspace repair: this probe is read-only and never mutates the
     * rootfs from Home.
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
 * M8.4.2 — the GIT application's process-scoped state, owned by the
 * HomeAppStateStore: the probe instance — its idle gate and last snapshot
 * ARE the cache — the last scan's ui, the open detail page, the selected
 * repository and the manual-refresh counter. Nothing here needs to
 * survive process death (a fresh process re-probes honestly), so no
 * DataStore is involved. M8.4.3 adds the inspection pages (commit detail,
 * file diff) with their requests, loading/failed states, last-viewed
 * caches and the one serial guard both pages share.
 */
internal class GitState(val probe: GitProbe) {
    var ui by mutableStateOf<GitUi>(GitUi.Probing)
    var detailPath by mutableStateOf<String?>(null)
    var selectedPath by mutableStateOf<String?>(null)
    var refreshTick by mutableStateOf(0)

    // The open inspection requests. Every request (open OR close) bumps
    // [reqSerial]; an exec captures the serial it was started under and
    // its result is applied only if the serial is still current — the
    // stale-result guard that makes one tap = one exec honest even when
    // navigation abandons an in-flight guest command.
    var commitReq by mutableStateOf<CommitReq?>(null)
    var commitUi by mutableStateOf<CommitUi>(CommitUi.Idle)
    var commitServed by mutableStateOf<CommitReq?>(null)
    var diffReq by mutableStateOf<DiffReq?>(null)
    var diffUi by mutableStateOf<DiffUi>(DiffUi.Idle)
    var diffServed by mutableStateOf<DiffReq?>(null)
    var reqSerial by mutableStateOf(0)

    fun openCommit(repoPath: String, hash: String) {
        commitReq = CommitReq(repoPath = repoPath, hash = hash, serial = ++reqSerial)
    }

    /** Routes one porcelain entry to its diff: index side → `--cached`. */
    fun openDiff(repoPath: String, entry: GitStatusParser.PorcelainEntry) {
        diffReq = DiffReq(
            repoPath = repoPath,
            path = entry.path,
            staged = entry.x != ' ' && entry.x != '?',
            untracked = entry.untracked,
            serial = ++reqSerial,
        )
    }

    /** Leaving a page abandons any in-flight exec's write-back. */
    fun closeCommit() {
        commitReq = null
        reqSerial++
    }

    fun closeDiff() {
        diffReq = null
        reqSerial++
    }
}

/** One open commit page: repo + hash + the serial that guards its exec. */
internal data class CommitReq(
    val repoPath: String,
    val hash: String,
    val serial: Int,
)

/** One open diff page: repo + path + which side of the change to show. */
internal data class DiffReq(
    val repoPath: String,
    val path: String,
    /** True = the index side (`git diff --cached`); false = worktree. */
    val staged: Boolean,
    /** Untracked paths have no diff — the page states that, no exec. */
    val untracked: Boolean,
    val serial: Int,
)

/** The commit page's state: loading, the parsed facts, or the real error. */
internal sealed interface CommitUi {
    data object Idle : CommitUi
    data object Loading : CommitUi
    data class Done(val detail: CommitDetail) : CommitUi
    data class Failed(val reason: String) : CommitUi
}

/** The diff page's state: loading, the capped text, or an honest absence. */
internal sealed interface DiffUi {
    data object Idle : DiffUi
    data object Loading : DiffUi
    data class Done(val text: DiffText) : DiffUi

    /** An untracked file has no diff — stated, never faked with output. */
    data object Untracked : DiffUi
    data class Failed(val reason: String) : DiffUi
}

/** The application's screen state (probe-driven, never invented). */
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

/**
 * The responsive contract — same geometry as ServersLayout (the card
 * dimensions are identical, so the device-derived thresholds carry over):
 * COMPACT keeps one-line rows; ROOMY adds the status header, per-repo path
 * sublines, the commit sublines, the footer statistics and scrolling rows.
 * Pure + JVM-tested.
 */
internal enum class GitLayout(
    val showsPath: Boolean,
    val showsStatusHeader: Boolean,
) {
    COMPACT(showsPath = false, showsStatusHeader = false),
    ROOMY(showsPath = true, showsStatusHeader = true);

    companion object {
        const val ROOMY_MIN_WIDTH_DP = 420f
        const val ROOMY_MIN_HEIGHT_DP = 200f
        const val DETAIL_MAX_ENTRIES_COMPACT = 8
        const val DETAIL_MAX_ENTRIES_ROOMY = 24

        fun from(widthDp: Float, heightDp: Float): GitLayout =
            if (widthDp >= ROOMY_MIN_WIDTH_DP && heightDp >= ROOMY_MIN_HEIGHT_DP) ROOMY else COMPACT
    }
}

// ------------------------------------------------------------- overview

@Composable
private fun GitOverview(
    ui: GitUi,
    repos: List<RepoSnapshot>,
    paneRepo: RepoSnapshot?,
    layout: GitLayout,
    onRefresh: () -> Unit,
    onSelectRepo: (RepoSnapshot) -> Unit,
    onOpenDetail: (RepoSnapshot) -> Unit,
    onOpenCommit: (RepoSnapshot, LogEntry) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLinuxShell: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val ready = ui as? GitUi.Ready
    Column(modifier = Modifier.fillMaxSize()) {
        // Header — title + the ONE refresh control (icon-first, named),
        // both densities. The repo count is a COMPACT-only fact: ROOMY's
        // status line carries other facts, so no count is ever duplicated.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Git",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = HomeTokens.textPrimary,
            )
            // M8.4.3.1 — version + dirty live WITH the title (one line,
            // never a second status area below, never a second "git" word).
            val headerFacts = listOfNotNull(
                ready?.snapshot?.gitVersion,
                if (repos.isNotEmpty()) "${ready?.snapshot?.dirtyRepos ?: 0} DIRTY" else null,
            )
            if (headerFacts.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = headerFacts.joinToString(" · "),
                    fontFamily = TerminalTheme.mono,
                    fontSize = 12.sp,
                    color = HomeTokens.textDim,
                )
            }
            Spacer(Modifier.weight(1f))
            if (repos.isNotEmpty() && layout == GitLayout.COMPACT) {
                Text(
                    text = "${repos.size} repos",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 11.sp,
                    color = HomeTokens.textDim,
                )
                Spacer(Modifier.width(8.dp))
            }
            RefreshButton(onRefresh = onRefresh)
        }

        // Repo selector — several repositories pick from ONE horizontally-
        // scrollable chip row; selecting swaps the pane below IN PLACE
        // (never a page navigation). A single repository needs no selector.
        if (repos.size > 1) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repos.forEach { repo ->
                    val active = repo.path == paneRepo?.path
                    // Folder-tab look (M8.4.3.1): no box border — the
                    // ACTIVE tab alone carries a bottom rule.
                    Column(modifier = Modifier) {
                        Text(
                            text = repo.name,
                            fontFamily = TerminalTheme.mono,
                            fontSize = 11.sp,
                            color = if (active) HomeTokens.accent else HomeTokens.textDim,
                            maxLines = 1,
                            modifier = Modifier
                                .clickable(
                                    role = Role.Tab,
                                    onClickLabel = "Show repository ${repo.name}",
                                ) { onSelectRepo(repo) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (active) HomeTokens.accent else HomeTokens.hairline),
                        )
                    }
                }
            }
        }

        // The selected repository's pane — the one-glance answer: branch +
        // tracking glyphs + worktree marker on the first line, the mapped
        // path under it (roomy cards), then the changed files grouped the
        // way git's index/worktree split sees them (conflicts above all),
        // the recent commits, the local branches and the remotes — one
        // scrolling column, no tabs hiding anything. Tapping the pane
        // opens the repo's detail page; tapping a commit opens its page.
        if (paneRepo != null) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Git details ${paneRepo.name}",
                        ) { onOpenDetail(paneRepo) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = branchText(paneRepo.status),
                            fontFamily = TerminalTheme.mono,
                            fontSize = 13.sp,
                            color = HomeTokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val glyphs = GitPresentation.trackingGlyphs(
                            ahead = paneRepo.status?.ahead,
                            behind = paneRepo.status?.behind,
                        )
                        if (glyphs.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = glyphs,
                                fontFamily = TerminalTheme.mono,
                                fontSize = 11.sp,
                                // rendered only when nonzero — see
                                // GitPresentation.trackingGlyphs
                                color = HomeTokens.accent,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val dirty = paneRepo.status?.dirty == true
                        Text(
                            text = GitPresentation.worktreeGlyph(dirty).toString(),
                            fontFamily = TerminalTheme.mono,
                            fontSize = 12.sp,
                            color = if (dirty) HomeTokens.accent else HomeTokens.textDim,
                        )
                    }
                    if (layout.showsPath) {
                        Text(
                            text = displayGuestRepoPath(paneRepo.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = HomeTokens.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    if (paneRepo.error != null) {
                        // One unreadable repository degrades alone —
                        // stated, never hidden.
                        Text(
                            text = "git could not read this repository (${paneRepo.error})",
                            style = MaterialTheme.typography.bodySmall,
                            color = HomeTokens.danger,
                        )
                    }
                }

                // The changed files, grouped. Empty groups render nothing;
                // all three empty → the one honest line. Rows stay parser-
                // faithful: the single status letter + the path (renames
                // arrowed). Conflicted paths render ONCE, in CONFLICTS —
                // above staged/unstaged, with the one guidance line.
                val status = paneRepo.status
                if (status != null) {
                    val conflicts = GitPresentation.conflictRows(status.entries)
                    val staged = GitPresentation.stagedRows(status.entries)
                    val unstaged = GitPresentation.unstagedRows(status.entries)
                    if (conflicts.isEmpty() && staged.isEmpty() && unstaged.isEmpty()) {
                        Text(
                            text = "Working tree clean",
                            fontFamily = TerminalTheme.mono,
                            fontSize = 11.sp,
                            color = HomeTokens.textDim,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        if (conflicts.isNotEmpty()) {
                            GitSection("CONFLICTS")
                            conflicts.forEach { GitFileRow(it) }
                            Text(
                                text = "Resolve in a terminal (git status)",
                                style = MaterialTheme.typography.bodySmall,
                                color = HomeTokens.textDim,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (staged.isNotEmpty()) {
                            GitSection("STAGED")
                            staged.forEach { GitFileRow(it) }
                        }
                        if (unstaged.isNotEmpty()) {
                            GitSection("UNSTAGED")
                            unstaged.forEach { GitFileRow(it) }
                        }
                    }
                }

                // RECENT — the repository's last commits (newest first).
                // `hash  subject` on one line; author · time as the
                // ROOMY-only subline. A tap opens the commit page.
                if (paneRepo.log.isNotEmpty()) {
                    GitSection("RECENT")
                    paneRepo.log.forEach { entry ->
                        GitCommitRow(
                            entry = entry,
                            roomy = layout.showsPath,
                            onOpen = { onOpenCommit(paneRepo, entry) },
                        )
                    }
                }

                // BRANCHES — the local branches: the checked-out one first
                // (git's own "*" marker), each with its upstream and the
                // ahead/behind its track string carries. Empty → nothing.
                if (paneRepo.branches.isNotEmpty()) {
                    GitSection("BRANCHES")
                    val current = paneRepo.status?.branch
                    paneRepo.branches
                        .sortedByDescending { current != null && it.name == current }
                        .forEach { branch ->
                            GitBranchRow(
                                branch = branch,
                                isCurrent = current != null && branch.name == current,
                            )
                        }
                }

                // REMOTES — name + shortened URL. Empty → nothing.
                if (paneRepo.remotes.isNotEmpty()) {
                    GitSection("REMOTES")
                    paneRepo.remotes.forEach { remote -> GitRemoteRow(remote) }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        // The honest state line — ONLY for the states that need it
        // (M8.4.3.1: a working card says nothing; "Live from the guest"
        // was noise). Probing/unavailable/empty keep their lines.
        val stateLine = when {
            ui is GitUi.Probing -> "Looking…"
            ui is GitUi.Unavailable -> "Linux not ready"
            ui is GitUi.ProbeFailed -> "Could not probe git"
            ready != null && !ready.snapshot.hasGit -> "Git unavailable"
            ready != null && repos.isEmpty() -> "No repositories"
            else -> null
        }
        if (stateLine != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stateLine,
                style = MaterialTheme.typography.bodySmall,
                color = HomeTokens.textDim,
                maxLines = 1,
            )
        }
        when {
            ui is GitUi.Unavailable -> {
                TextButton(onClick = onOpenDiagnostics, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Diagnostics", color = HomeTokens.accent)
                }
            }
            ui is GitUi.ProbeFailed -> {
                // The reason, verbatim; manual refresh rides the header's
                // refresh button — never a second refresh control.
                Text(
                    text = ui.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ready != null && !ready.snapshot.hasGit -> {
                Text(
                    text = "Git is not installed in the guest — install it from the Linux Shell (apk add git).",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
                TextButton(onClick = onOpenLinuxShell, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Open Linux", color = HomeTokens.accent)
                }
            }
            ready != null && repos.isEmpty() -> {
                Text(
                    text = "Clone or create a repository under ~/ or ~/Projects — it appears here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
                TextButton(onClick = onOpenLinuxShell, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Open Linux", color = HomeTokens.accent)
                }
            }
            // Ready with repositories: the state line says it all; the
            // refresh cadence is the probe's idle gate + the header button.
        }
    }
}

/** A changed-files section label — git's index/worktree vocabulary. */
@Composable
private fun GitSection(label: String) {
    Text(
        text = label,
        fontFamily = TerminalTheme.mono,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = HomeTokens.textDim,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** One changed file: the single status letter + the path (renames arrowed). */
@Composable
private fun GitFileRow(row: GitPresentation.EntryRow) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = row.letter.toString(),
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.textDim,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text = row.label,
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One recent commit: `hash  subject`, author · time as the ROOMY subline. */
@Composable
private fun GitCommitRow(entry: LogEntry, roomy: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "Show commit ${entry.hash}",
            ) { onOpen() }
            .padding(vertical = 1.dp),
    ) {
        Text(
            text = entry.hash,
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.textDim,
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.subject,
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                color = HomeTokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (roomy) {
                Text(
                    text = "${entry.author} · ${entry.relativeTime}",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 10.sp,
                    color = HomeTokens.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One local branch: git's "*" marker for the checked-out one, then facts. */
@Composable
private fun GitBranchRow(branch: Branch, isCurrent: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isCurrent) "*" else "",
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.accent,
            modifier = Modifier.width(12.dp),
        )
        Text(
            text = branch.name,
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (branch.upstream != null) {
            Spacer(Modifier.width(6.dp))
            val gone = if (branch.gone) " (gone)" else ""
            Text(
                text = "→ ${branch.upstream}$gone",
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = HomeTokens.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        val glyphs = GitPresentation.trackingGlyphs(ahead = branch.ahead, behind = branch.behind)
        if (glyphs.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = glyphs,
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                // rendered only when nonzero — see trackingGlyphs
                color = HomeTokens.accent,
            )
        }
    }
}

/** One remote: the name + its URL, shortened for the row, full in git. */
@Composable
private fun GitRemoteRow(remote: Remote) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = remote.name,
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.textPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = GitPresentation.shortUrl(remote.url),
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The one refresh control: compact icon-first, named for accessibility. */
@Composable
private fun RefreshButton(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(role = Role.Button, onClickLabel = "Refresh repositories") { onRefresh() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = "Refresh repositories",
            tint = HomeTokens.accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

// --------------------------------------------------------------- detail

@Composable
private fun GitDetail(
    repo: RepoSnapshot,
    roomy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDiff: (GitStatusParser.PorcelainEntry) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLinuxShell: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        InCardBackHeader(onBack = onBack)
        Text(
            text = repo.name,
            fontFamily = TerminalTheme.mono,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = HomeTokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = displayGuestRepoPath(repo.path),
            style = MaterialTheme.typography.bodySmall,
            color = HomeTokens.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(4.dp))
        if (repo.error != null) {
            // One unreadable repository degrades alone — stated, never hidden.
            Text(
                text = "git could not read this repository (${repo.error})",
                style = MaterialTheme.typography.bodySmall,
                color = HomeTokens.danger,
            )
        }
        val status = repo.status
        GitRow("BRANCH", branchText(status))
        GitRow("UPSTREAM", upstreamText(status))
        if (status != null) {
            GitRow("STATUS", status.summary())
        }

        // The porcelain rows — the real `git status` facts, XY and all.
        // Each row opens that path's diff: a staged change shows the index
        // side (`--cached`), everything else the worktree side.
        if (status != null && status.entries.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            val cap = if (roomy) GitLayout.DETAIL_MAX_ENTRIES_ROOMY else GitLayout.DETAIL_MAX_ENTRIES_COMPACT
            val shown = status.entries.take(cap)
            shown.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Show diff for ${entry.path}",
                        ) { onOpenDiff(entry) }
                        .padding(vertical = 1.dp),
                ) {
                    Text(
                        text = "${entry.x}${entry.y}",
                        fontFamily = TerminalTheme.mono,
                        fontSize = 10.sp,
                        color = HomeTokens.textDim,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = if (entry.origPath != null) "${entry.origPath} -> ${entry.path}" else entry.path,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 10.sp,
                        color = HomeTokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (status.entries.size > shown.size) {
                Text(
                    text = "+${status.entries.size - shown.size} more",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 10.sp,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        // The detail actions stay compact TEXT buttons — glyphs would be
        // ambiguous here — tightened to the 32dp row the card budget allows.
        Row {
            DetailAction("TERMINAL", onOpenTerminal)
            Spacer(Modifier.width(8.dp))
            DetailAction("LINUX", onOpenLinuxShell)
            Spacer(Modifier.width(8.dp))
            DetailAction("REFRESH", onRefresh)
        }
    }
}

// --------------------------------------------------------- commit page

/** The commit page: one read-only `git show --stat`, rendered bounded. */
@Composable
private fun GitCommitPage(ui: CommitUi, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        InCardBackHeader(onBack = onBack)
        when (ui) {
            CommitUi.Idle, CommitUi.Loading -> {
                Text(
                    text = "Loading commit…",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            is CommitUi.Failed -> {
                Text(
                    text = "git show failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.danger,
                )
                Text(
                    text = ui.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            is CommitUi.Done -> {
                val detail = ui.detail
                GitRow("HASH", detail.fullHash)
                GitRow(
                    "AUTHOR",
                    if (detail.email != null) "${detail.author} <${detail.email}>" else detail.author,
                )
                GitRow("DATE", detail.relativeDate)
                Text(
                    text = detail.subject,
                    fontFamily = TerminalTheme.mono,
                    fontSize = 12.sp,
                    color = HomeTokens.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (detail.statLines.isNotEmpty()) {
                    GitSection("FILES")
                    detail.statLines.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = TerminalTheme.mono,
                            fontSize = 10.sp,
                            color = HomeTokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                    if (detail.hiddenStatLines > 0) {
                        Text(
                            text = "+${detail.hiddenStatLines} more lines truncated",
                            fontFamily = TerminalTheme.mono,
                            fontSize = 10.sp,
                            color = HomeTokens.textDim,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------- diff page

/** The diff page: one read-only `git diff [--cached]`, rendered bounded. */
@Composable
private fun GitDiffPage(req: DiffReq, ui: DiffUi, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        InCardBackHeader(onBack = onBack)
        Text(
            text = req.path,
            fontFamily = TerminalTheme.mono,
            fontSize = 12.sp,
            color = HomeTokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (req.staged) "STAGED" else "WORKTREE",
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = HomeTokens.textDim,
            modifier = Modifier.padding(top = 2.dp),
        )
        when (ui) {
            DiffUi.Idle, DiffUi.Loading -> {
                Text(
                    text = "Loading diff…",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            DiffUi.Untracked -> {
                Text(
                    text = "Untracked file — nothing to diff until it is staged in a terminal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            is DiffUi.Failed -> {
                Text(
                    text = "git diff failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.danger,
                )
                Text(
                    text = ui.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            is DiffUi.Done -> {
                if (ui.text.lines.isEmpty()) {
                    Text(
                        text = "(no textual diff)",
                        style = MaterialTheme.typography.bodySmall,
                        color = HomeTokens.textDim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        ui.text.lines.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = TerminalTheme.mono,
                                fontSize = 10.sp,
                                color = HomeTokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (ui.text.hidden > 0) {
                            Text(
                                text = "+${ui.text.hidden} more lines truncated",
                                fontFamily = TerminalTheme.mono,
                                fontSize = 10.sp,
                                color = HomeTokens.textDim,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The in-card back header — the ONLY back is the application's own. */
@Composable
private fun InCardBackHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Back to Git") { onBack() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "←",
            fontFamily = TerminalTheme.mono,
            fontSize = 14.sp,
            color = HomeTokens.accent,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Git",
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            letterSpacing = 1.6.sp,
            color = HomeTokens.textDim,
        )
    }
}

@Composable
private fun DetailAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(32.dp)) {
        Text(label, fontFamily = TerminalTheme.mono, color = HomeTokens.accent)
    }
}

@Composable
private fun GitRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            color = HomeTokens.textDim,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            color = HomeTokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// -------------------------------------------------------------- helpers

private fun branchText(status: GitStatusParser.RepoStatus?): String = when {
    status == null -> "unknown"
    status.noCommits -> "${status.branch ?: "main"} (no commits yet)"
    status.detached -> "detached"
    else -> status.branch ?: "unknown"
}

private fun upstreamText(status: GitStatusParser.RepoStatus?): String {
    if (status == null) return "unknown"
    val upstream = status.upstream ?: return "none set"
    val gone = if (status.upstreamGone) " (gone)" else ""
    val diverge = when {
        status.ahead == null && status.behind == null -> ""
        else -> " · ↑${status.ahead ?: 0} ↓${status.behind ?: 0}"
    }
    return "$upstream$gone$diverge"
}
