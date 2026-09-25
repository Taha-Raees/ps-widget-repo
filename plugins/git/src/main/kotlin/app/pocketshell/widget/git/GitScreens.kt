package app.pocketshell.widget.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * The Git application's screens: the DASHBOARD (every repository at a
 * glance — a chips+pane card on phones, a list on the maximized page) and
 * the REPOSITORY WORKSPACE (six focused tabs). Screens own layout and
 * intent only: every read goes through a ReadSlot + [ReadEffect], every
 * mutation through [GitState.requestOp] (the confirm policy lives in
 * GitOps, not here), and every number on screen comes from git.
 */

// ------------------------------------------------------------- dashboard

@Composable
internal fun GitDashboard(
    ui: GitUi,
    repos: List<RepoSnapshot>,
    layout: GitLayout,
    selectedPath: String?,
    lastScanAtMs: Long,
    onRefresh: () -> Unit,
    onSelectRepo: (String) -> Unit,
    onOpenWorkspace: (RepoSnapshot) -> Unit,
    onOpenCommit: (RepoSnapshot, LogEntry) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLinuxShell: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val ready = ui as? GitUi.Ready
    Column(modifier = Modifier.fillMaxSize()) {
        // Header — title + the ONE refresh control, both densities. The
        // version and dirty count live WITH the title, never a second
        // status area below.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Git",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = HomeTokens.textPrimary,
            )
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
            if (repos.isNotEmpty() && layout != GitLayout.COMPACT) {
                MonoText(text = "scanned ${GitPresentation.ageLabel(System.currentTimeMillis(), lastScanAtMs)}", color = HomeTokens.textDim, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
            }
            RefreshButton(onRefresh = onRefresh)
        }

        when {
            // The maximized workstation: every repository is one row.
            layout.isWorkspace && repos.isNotEmpty() -> Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                GitSection("REPOSITORIES")
                repos.forEach { repo -> DashboardRepoRow(repo, onOpen = { onOpenWorkspace(repo) }) }
            }

            // The card: repo chips + the selected repository's pane.
            repos.isNotEmpty() -> {
                val paneRepo = repos.firstOrNull { it.path == selectedPath } ?: repos.firstOrNull()
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
                            Column(modifier = Modifier) {
                                MonoText(
                                    text = repo.name,
                                    color = if (active) HomeTokens.accent else HomeTokens.textDim,
                                    modifier = Modifier
                                        .clickable(role = Role.Tab, onClickLabel = "Show repository ${repo.name}") {
                                            onSelectRepo(repo.path)
                                        }
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
                if (paneRepo != null) {
                    DashboardPane(
                        repo = paneRepo,
                        roomy = layout.showsPath,
                        modifier = Modifier.weight(1f),
                        onOpenWorkspace = { onOpenWorkspace(paneRepo) },
                        onOpenCommit = { onOpenCommit(paneRepo, it) },
                    )
                }
            }
        }

        // The honest state line — ONLY for the states that need it.
        val stateLine = when {
            ui is GitUi.Probing -> "Looking…"
            ui is GitUi.Unavailable -> "Linux not ready"
            ui is GitUi.ProbeFailed -> "Could not probe git"
            ready != null && !ready.snapshot.hasGit -> "Git unavailable"
            ready != null && repos.isEmpty() -> "No repositories"
            else -> null
        }
        if (stateLine != null) StateLine(stateLine)
        when {
            ui is GitUi.Unavailable -> TextButton(onClick = onOpenDiagnostics) {
                Text("Diagnostics", color = HomeTokens.accent)
            }
            ui is GitUi.ProbeFailed -> MonoText(
                text = ui.reason,
                color = HomeTokens.textDim,
                maxLines = 2,
            )
            ready != null && !ready.snapshot.hasGit -> {
                MonoText(
                    text = "Git is not installed in the guest — install it from the Linux Shell (apk add git).",
                    color = HomeTokens.textDim,
                )
                TextButton(onClick = onOpenLinuxShell) {
                    Text("Open Linux", color = HomeTokens.accent)
                }
            }
            ready != null && repos.isEmpty() -> {
                MonoText(
                    text = "Clone or create a repository under ~/ or ~/Projects — it appears here.",
                    color = HomeTokens.textDim,
                )
                TextButton(onClick = onOpenLinuxShell) {
                    Text("Open Linux", color = HomeTokens.accent)
                }
            }
        }
    }
}

/** One repository row on the maximized dashboard. */
@Composable
private fun DashboardRepoRow(repo: RepoSnapshot, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open ${repo.name}") { onOpen() }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(text = repo.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            val glyphs = GitPresentation.trackingGlyphs(repo.status?.ahead, repo.status?.behind)
            if (glyphs.isNotEmpty()) MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            val dirty = repo.status?.dirty == true
            MonoText(
                text = GitPresentation.worktreeGlyph(dirty).toString(),
                color = if (dirty) HomeTokens.accent else HomeTokens.textDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            MonoText(text = branchText(repo.status), color = HomeTokens.textDim, fontSize = 10.sp)
        }
        MonoText(
            text = displayGuestRepoPath(repo.path),
            color = HomeTokens.textDim,
            fontSize = 10.sp,
        )
        if (repo.status != null && repo.status.dirty) {
            MonoText(text = repo.status.summary(), color = HomeTokens.textDim, fontSize = 10.sp)
        }
        if (repo.error != null) {
            MonoText(text = "git could not read this repository (${repo.error})", color = HomeTokens.danger, fontSize = 10.sp)
        }
    }
}

/** The card pane for one repository (the old overview, kept + tightened). */
@Composable
private fun DashboardPane(
    repo: RepoSnapshot,
    roomy: Boolean,
    modifier: Modifier = Modifier,
    onOpenWorkspace: () -> Unit,
    onOpenCommit: (LogEntry) -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open ${repo.name}") { onOpenWorkspace() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    text = branchText(repo.status),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                val glyphs = GitPresentation.trackingGlyphs(repo.status?.ahead, repo.status?.behind)
                if (glyphs.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                val dirty = repo.status?.dirty == true
                MonoText(
                    text = GitPresentation.worktreeGlyph(dirty).toString(),
                    color = if (dirty) HomeTokens.accent else HomeTokens.textDim,
                    fontSize = 12.sp,
                )
            }
            if (roomy) {
                MonoText(
                    text = displayGuestRepoPath(repo.path),
                    color = HomeTokens.textDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (repo.error != null) {
                MonoText(
                    text = "git could not read this repository (${repo.error})",
                    color = HomeTokens.danger,
                    fontSize = 10.sp,
                )
            }
        }

        val status = repo.status
        if (status != null) {
            val conflicts = GitPresentation.conflictRows(status.entries)
            val staged = GitPresentation.stagedRows(status.entries)
            val unstaged = GitPresentation.unstagedRows(status.entries)
            if (conflicts.isEmpty() && staged.isEmpty() && unstaged.isEmpty()) {
                MonoText(
                    text = "Working tree clean",
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                if (conflicts.isNotEmpty()) {
                    GitSection("CONFLICTS")
                    conflicts.forEach { FileRow(letter = it.letter, label = it.label) }
                    MonoText(
                        text = "Resolve in a terminal (git status)",
                        color = HomeTokens.textDim,
                        fontSize = 10.sp,
                    )
                }
                if (staged.isNotEmpty()) {
                    GitSection("STAGED")
                    staged.forEach { FileRow(letter = it.letter, label = it.label) }
                }
                if (unstaged.isNotEmpty()) {
                    GitSection("UNSTAGED")
                    unstaged.forEach { FileRow(letter = it.letter, label = it.label) }
                }
            }
        }

        if (repo.log.isNotEmpty()) {
            GitSection("RECENT")
            repo.log.forEach { entry -> CommitRow(entry = entry, roomy = roomy, onOpen = { onOpenCommit(entry) }) }
        }

        if (repo.branches.isNotEmpty()) {
            GitSection("BRANCHES")
            val current = repo.status?.branch
            repo.branches
                .sortedByDescending { current != null && it.name == current }
                .forEach { branch -> BranchRow(branch = branch, isCurrent = current != null && branch.name == current) }
        }

        if (repo.remotes.isNotEmpty()) {
            GitSection("REMOTES")
            repo.remotes.forEach { remote -> RemoteRow(remote) }
        }

        TextButton(onClick = onOpenWorkspace, modifier = Modifier.padding(top = 4.dp)) {
            Text("OPEN WORKSPACE", fontFamily = TerminalTheme.mono, fontSize = 11.sp, color = HomeTokens.accent)
        }
    }
}

// ------------------------------------------------------------- workspace

@Composable
internal fun GitWorkspace(
    repo: RepoSnapshot?,
    tab: RepoTab,
    state: GitState,
    onBack: () -> Unit,
    onTab: (RepoTab) -> Unit,
    onOpenDiff: (DiffTarget, String) -> Unit,
    onOpenCommit: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOp: (GitOp) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    if (repo == null) {
        // retain() unwinds the stack before this renders; the guard is the
        // honest fallback for the one frame where the scan has not landed.
        Column(modifier = Modifier.fillMaxSize()) {
            BackHeader(title = "Git", onBack = onBack)
            StateLine("Repository not listed — refreshing.")
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(title = "Git", onBack = onBack)
        MonoText(
            text = repo.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        MonoText(text = displayGuestRepoPath(repo.path), color = HomeTokens.textDim, fontSize = 10.sp)
        TabBar(tabs = RepoTab.entries.toList(), selected = tab, onSelect = onTab)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            when (tab) {
                RepoTab.OVERVIEW -> OverviewTab(repo, onOpenCommit = onOpenCommit, onOpenTerminal = onOpenTerminal)
                RepoTab.CHANGES -> ChangesTab(repo, state, onOpenDiff, onOp, onOpenTerminal)
                RepoTab.HISTORY -> HistoryTab(repo, state, onOpenCommit)
                RepoTab.BRANCHES -> BranchesTab(repo, state, onOp)
                RepoTab.FILES -> FilesTab(repo, state, onOpenDiff, onOpenFile)
                RepoTab.REMOTES -> RemotesTab(repo, state, onOp)
                RepoTab.REPO -> RepositoryTab(repo, state, onOp)
            }
        }
    }
}

// --------------------------------------------------------- overview tab

@Composable
private fun OverviewTab(repo: RepoSnapshot, onOpenCommit: (String) -> Unit, onOpenTerminal: () -> Unit) {
    val status = repo.status
    Row(modifier = Modifier.padding(top = 6.dp)) {
        MonoText(text = "BRANCH", color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.width(84.dp))
        MonoText(text = branchText(status))
        val glyphs = GitPresentation.trackingGlyphs(status?.ahead, status?.behind)
        if (glyphs.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 10.sp)
        }
        val dirty = status?.dirty == true
        Spacer(Modifier.width(8.dp))
        MonoText(
            text = GitPresentation.worktreeGlyph(dirty).toString(),
            color = if (dirty) HomeTokens.accent else HomeTokens.textDim,
            fontSize = 10.sp,
        )
    }
    LabelRow("UPSTREAM", upstreamText(status))
    if (status != null) LabelRow("STATUS", status.summary())
    if (repo.error != null) {
        MonoText(
            text = "git could not read this repository (${repo.error})",
            color = HomeTokens.danger,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (repo.log.isNotEmpty()) {
        GitSection("RECENT")
        repo.log.forEach { entry -> CommitRow(entry, roomy = true, onOpen = { onOpenCommit(entry.hash) }) }
    }
    if (repo.branches.isNotEmpty()) {
        GitSection("BRANCHES")
        val current = status?.branch
        repo.branches
            .sortedByDescending { current != null && it.name == current }
            .forEach { branch -> BranchRow(branch, isCurrent = current != null && branch.name == current) }
    }
    if (repo.remotes.isNotEmpty()) {
        GitSection("REMOTES")
        repo.remotes.forEach { remote -> RemoteRow(remote) }
    }
    Spacer(Modifier.height(4.dp))
    Row {
        TextAction("TERMINAL", onClick = onOpenTerminal)
    }
}

@Composable
internal fun LabelRow(label: String, value: String) {
    Row(modifier = Modifier.padding(top = 2.dp)) {
        MonoText(text = label, color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.width(84.dp))
        MonoText(text = value, fontSize = 10.sp)
    }
}

// ---------------------------------------------------------- changes tab

/** One pending change paired with its rendered row facts. */
private data class ChangePair(
    val entry: GitStatusParser.PorcelainEntry,
    val letter: Char,
    val label: String,
)

private fun displayLabel(entry: GitStatusParser.PorcelainEntry): String =
    if (entry.origPath != null) "${entry.origPath} -> ${entry.path}" else entry.path

private fun conflictPairs(entries: List<GitStatusParser.PorcelainEntry>): List<ChangePair> =
    entries.filter { it.conflict }.map { ChangePair(it, 'U', displayLabel(it)) }

private fun stagedPairs(entries: List<GitStatusParser.PorcelainEntry>): List<ChangePair> =
    entries.filter { !it.conflict && it.x != ' ' && it.x != '?' }
        .map { ChangePair(it, it.x, displayLabel(it)) }

private fun unstagedPairs(entries: List<GitStatusParser.PorcelainEntry>): List<ChangePair> =
    entries.filter { !it.conflict && (it.y != ' ' || it.untracked) }
        .map { ChangePair(it, GitPresentation.statusLetter(it), displayLabel(it)) }

@Composable
private fun ChangesTab(
    repo: RepoSnapshot,
    state: GitState,
    onOpenDiff: (DiffTarget, String) -> Unit,
    onOp: (GitOp) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val status = repo.status ?: return
    val entries = status.entries

    // The per-file action sheet (the ⋯ trigger), if one is open.
    var sheetEntry by remember(repo.path) { mutableStateOf<GitStatusParser.PorcelainEntry?>(null) }
    sheetEntry?.let { entry ->
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when {
            entry.untracked -> {
                actions += "Stage" to { onOp(GitOp.Stage(listOf(entry.path))) }
                actions += "Delete file" to {
                    onOp(GitOp.DeleteUntracked(absolutePath = "${repo.path}/${entry.path}"))
                }
            }
            entry.conflict -> {
                actions += "Stage (mark resolved)" to { onOp(GitOp.Stage(listOf(entry.path))) }
            }
            else -> {
                if (entry.x != ' ') actions += "Unstage" to { onOp(GitOp.Unstage(listOf(entry.path))) }
                if (entry.y != ' ') {
                    actions += "Stage" to { onOp(GitOp.Stage(listOf(entry.path))) }
                    actions += "Discard worktree changes" to { onOp(GitOp.DiscardFile(path = entry.path)) }
                }
            }
        }
        ActionSheet(title = entry.path, actions = actions, onDismiss = { sheetEntry = null })
    }

    // The stash action sheet.
    var sheetStash by remember(repo.path) { mutableStateOf<StashEntry?>(null) }
    val stashSlot = state.stashes
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = stashSlot.req
        if (cur == null || cur.repoPath != repo.path || stashSlot.servedEpoch < state.readEpoch) {
            stashSlot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = stashSlot, epoch = state.readEpoch) { req ->
        state.reader.stashes(req.repoPath)
    }
    sheetStash?.let { stash ->
        ActionSheet(
            title = stash.ref,
            actions = listOf(
                "Pop (reapply + drop when clean)" to { onOp(GitOp.StashPop(index = stash.index)) },
                "Drop" to { onOp(GitOp.StashDrop(index = stash.index)) },
            ),
            onDismiss = { sheetStash = null },
        )
    }

    // Bulk actions — the whole worktree's fate in one row.
    Row(modifier = Modifier.padding(top = 4.dp)) {
        TextAction("STAGE ALL", onClick = { onOp(GitOp.StageAll) })
        TextAction("UNSTAGE ALL", onClick = { onOp(GitOp.UnstageAll(unbornHead = status.noCommits)) })
        TextAction("DISCARD ALL", onClick = { onOp(GitOp.DiscardTrackedAll) })
        TextAction("STASH", onClick = { onOp(GitOp.StashPush()) })
    }

    val conflicts = conflictPairs(entries)
    val staged = stagedPairs(entries)
    val unstaged = unstagedPairs(entries)
    if (conflicts.isEmpty() && staged.isEmpty() && unstaged.isEmpty()) {
        MonoText(
            text = "Working tree clean — nothing to commit",
            color = HomeTokens.textDim,
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        if (conflicts.isNotEmpty()) {
            GitSection("CONFLICTS")
            conflicts.forEach { pair ->
                FileRow(
                    letter = pair.letter,
                    label = pair.label,
                    onClick = { onOpenDiff(DiffTarget.Worktree(pair.entry.path), pair.entry.path) },
                    onActions = { sheetEntry = pair.entry },
                )
            }
            MonoText(
                text = "Resolve the markers in a terminal, then STAGE the file to mark it resolved.",
                color = HomeTokens.textDim,
                fontSize = 10.sp,
            )
        }
        if (staged.isNotEmpty()) {
            GitSection("STAGED")
            staged.forEach { pair ->
                FileRow(
                    letter = pair.letter,
                    label = pair.label,
                    onClick = { onOpenDiff(DiffTarget.Index(pair.entry.path), pair.entry.path) },
                    onActions = { sheetEntry = pair.entry },
                )
            }
        }
        if (unstaged.isNotEmpty()) {
            GitSection("UNSTAGED")
            unstaged.forEach { pair ->
                FileRow(
                    letter = pair.letter,
                    label = pair.label,
                    onClick = if (!pair.entry.untracked) {
                        { onOpenDiff(DiffTarget.Worktree(pair.entry.path), pair.entry.path) }
                    } else {
                        null
                    },
                    onActions = { sheetEntry = pair.entry },
                )
            }
        }
    }

    // The commit composer — the Changes tab's reason to exist.
    var composing by remember(repo.path) { mutableStateOf(false) }
    Spacer(Modifier.height(6.dp))
    Row {
        TextAction("COMMIT", onClick = { composing = true })
    }
    if (composing) {
        CommitDialog(
            onCommit = { subject, body, amend ->
                composing = false
                onOp(GitOp.Commit(subject = subject, body = body, amend = amend))
            },
            onDismiss = { composing = false },
        )
    }

    // The stashes, below the pending changes (Changes tab owns stash).
    when (val stashUi = stashSlot.ui) {
        is ReadUi.Done -> {
            val stashes = stashUi.value
            if (!stashes.isEmpty) {
                GitSection("STASHES")
                stashes.items.forEach { stash ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "Stash actions") { sheetStash = stash }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText(text = stash.ref, color = HomeTokens.textDim, modifier = Modifier.width(84.dp))
                        MonoText(text = stash.subject, modifier = Modifier.weight(1f))
                    }
                }
                CapFooter(hidden = stashes.hidden, what = "stashes")
            }
        }
        is ReadUi.Failed -> FailedRead("stash list failed", stashUi.reason)
        else -> Unit
    }

    Spacer(Modifier.height(4.dp))
    Row {
        TextAction("TERMINAL", onClick = onOpenTerminal)
    }
}

// ---------------------------------------------------------- history tab

@Composable
private fun HistoryTab(repo: RepoSnapshot, state: GitState, onOpenCommit: (String) -> Unit) {
    val slot = state.history
    var window by remember(repo.path) { mutableStateOf(GitReader.HISTORY_WINDOW_STEP) }
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(HistoryReq(repoPath = repo.path, window = window, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req ->
        state.reader.history(req.repoPath, req.window)
    }

    // The graph and the reflog are opt-in reads — one tap, one bounded exec.
    var showGraph by remember(repo.path) { mutableStateOf(false) }
    var showReflog by remember(repo.path) { mutableStateOf(false) }
    if (showGraph) {
        GraphSection(repo, state)
    }
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> StateLine("Loading history…")
        is ReadUi.Failed -> FailedRead("git log failed", ui.reason)
        is ReadUi.Done -> {
            val commits = ui.value
            if (commits.isEmpty()) {
                StateLine("No commits yet.")
            } else {
                GitSection("COMMITS")
                commits.forEach { entry -> CommitRow(entry, roomy = true, onOpen = { onOpenCommit(entry.hash) }) }
                // A full window means there MAY be more — the honest offer
                // is another bounded read, never a fake total.
                if (commits.size >= window && window < GitReader.MAX_HISTORY_WINDOW) {
                    TextAction(
                        "LOAD +${GitReader.HISTORY_WINDOW_STEP} MORE",
                        onClick = {
                            window += GitReader.HISTORY_WINDOW_STEP
                            slot.open(HistoryReq(repo.path, window, serial = state.navSerial))
                        },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Row {
        TextAction(if (showGraph) "HIDE GRAPH" else "SHOW GRAPH", onClick = { showGraph = !showGraph })
        TextAction(if (showReflog) "HIDE REFLOG" else "SHOW REFLOG", onClick = { showReflog = !showReflog })
    }
    if (showReflog) {
        ReflogSection(repo, state)
    }
}

// --------------------------------------------------------- branches tab

@Composable
private fun BranchesTab(repo: RepoSnapshot, state: GitState, onOp: (GitOp) -> Unit) {
    val status = repo.status
    val current = status?.branch
    var sheetBranch by remember(repo.path) { mutableStateOf<Branch?>(null) }
    var sheetRemote by remember(repo.path) { mutableStateOf<Branch?>(null) }
    var creating by remember(repo.path) { mutableStateOf(false) }
    var renaming by remember(repo.path) { mutableStateOf<String?>(null) }

    val remoteSlot = state.remoteBranches
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = remoteSlot.req
        if (cur == null || cur.repoPath != repo.path || remoteSlot.servedEpoch < state.readEpoch) {
            remoteSlot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = remoteSlot, epoch = state.readEpoch) { req ->
        state.reader.remoteBranches(req.repoPath)
    }

    Row(modifier = Modifier.padding(top = 4.dp)) {
        TextAction("NEW BRANCH", onClick = { creating = true })
    }
    if (creating) {
        PromptDialog(
            title = "Create branch",
            label = "Branch name",
            confirmWord = "Create",
            onConfirm = { name -> creating = false; onOp(GitOp.CreateBranch(name = name)) },
            onDismiss = { creating = false },
        )
    }

    GitSection("LOCAL")
    if (repo.branches.isEmpty()) StateLine("No local branches reported.")
    repo.branches
        .sortedByDescending { current != null && it.name == current }
        .forEach { branch ->
            BranchRow(
                branch = branch,
                isCurrent = current != null && branch.name == current,
                onClick = { if (branch.name != current) sheetBranch = branch },
            )
        }

    when (val remoteUi = remoteSlot.ui) {
        is ReadUi.Done -> {
            if (!remoteUi.value.isEmpty) {
                GitSection("REMOTE")
                remoteUi.value.items.forEach { branch ->
                    BranchRow(branch = branch, isCurrent = false, onClick = { sheetRemote = branch })
                }
                CapFooter(hidden = remoteUi.value.hidden, what = "remote branches")
            }
        }
        is ReadUi.Failed -> FailedRead("git branch -r failed", remoteUi.reason)
        else -> Unit
    }

    sheetBranch?.let { branch ->
        ActionSheet(
            title = branch.name,
            actions = listOf(
                "Switch to it" to { onOp(GitOp.SwitchBranch(name = branch.name)) },
                "Merge into \"${current ?: "current"}\"" to { onOp(GitOp.MergeBranch(name = branch.name)) },
                "Rebase current branch onto it" to { onOp(GitOp.RebaseOnto(name = branch.name)) },
                "Rename…" to {
                    renaming = branch.name
                },
                "Delete (safe — git refuses unmerged)" to { onOp(GitOp.DeleteBranch(name = branch.name)) },
            ),
            onDismiss = { sheetBranch = null },
        )
    }
    renaming?.let { from ->
        PromptDialog(
            title = "Rename branch \"$from\"",
            label = "New name",
            confirmWord = "Rename",
            onConfirm = { to -> onOp(GitOp.RenameBranch(from = from, to = to)); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    sheetRemote?.let { branch ->
        val remote = branch.name.substringBefore('/')
        val remoteBranch = branch.name.substringAfter('/')
        ActionSheet(
            title = branch.name,
            actions = listOf(
                "Fetch $remote" to { onOp(GitOp.Fetch(remote = remote)) },
                "Pull (fast-forward only)" to { onOp(GitOp.Pull(remote = remote, branch = remoteBranch)) },
                "Merge into \"${current ?: "current"}\"" to { onOp(GitOp.MergeBranch(name = branch.name)) },
                "Rebase current branch onto it" to { onOp(GitOp.RebaseOnto(name = branch.name)) },
            ),
            onDismiss = { sheetRemote = null },
        )
    }

    TagsSection(repo, state, onOp)
}

// ---------------------------------------------------------- tags section

@Composable
private fun TagsSection(repo: RepoSnapshot, state: GitState, onOp: (GitOp) -> Unit) {
    val slot = state.tags
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req -> state.reader.tags(req.repoPath) }

    var sheetTag by remember(repo.path) { mutableStateOf<TagRow?>(null) }
    var creating by remember(repo.path) { mutableStateOf(false) }
    sheetTag?.let { tag ->
        ActionSheet(
            title = tag.name,
            actions = listOf(
                "Delete tag" to { onOp(GitOp.TagDelete(name = tag.name)) },
            ),
            onDismiss = { sheetTag = null },
        )
    }
    if (creating) {
        PromptDialog(
            title = "Create tag at HEAD",
            label = "Tag name",
            confirmWord = "Tag",
            onConfirm = { name -> creating = false; onOp(GitOp.TagCreate(name = name)) },
            onDismiss = { creating = false },
        )
    }

    GitSection("TAGS")
    Row {
        TextAction("NEW TAG", onClick = { creating = true })
    }
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> FailedRead("tag list failed", ui.reason)
        is ReadUi.Done -> {
            val tags = ui.value
            if (tags.isEmpty) {
                StateLine("No tags.")
            } else {
                tags.items.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "Tag ${tag.name}") { sheetTag = tag }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText(text = tag.name, modifier = Modifier.weight(1f))
                        MonoText(text = "${tag.hash} · ${tag.date}", color = HomeTokens.textDim, fontSize = 10.sp)
                    }
                }
                CapFooter(hidden = tags.hidden, what = "tags")
            }
        }
    }
}

// --------------------------------------------------- history extras

@Composable
private fun GraphSection(repo: RepoSnapshot, state: GitState) {
    val slot = state.graph
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req -> state.reader.graph(req.repoPath) }
    GitSection("GRAPH")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> StateLine("Drawing…")
        is ReadUi.Failed -> FailedRead("git log --graph failed", ui.reason)
        is ReadUi.Done -> {
            ui.value.lines.forEach { line ->
                MonoText(text = line, fontSize = 10.sp, maxLines = 1)
            }
            CapFooter(hidden = ui.value.hidden, what = "graph lines")
        }
    }
}

@Composable
private fun ReflogSection(repo: RepoSnapshot, state: GitState) {
    val slot = state.reflog
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req -> state.reader.reflog(req.repoPath) }
    GitSection("REFLOG")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> StateLine("Reading…")
        is ReadUi.Failed -> FailedRead("git reflog failed", ui.reason)
        is ReadUi.Done -> {
            val entries = ui.value
            if (entries.isEmpty()) {
                StateLine("No reflog entries.")
            } else {
                entries.forEach { entry ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        MonoText(text = entry.hash, color = HomeTokens.textDim, modifier = Modifier.width(64.dp))
                        MonoText(text = entry.subject, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------- files tab

@Composable
private fun FilesTab(
    repo: RepoSnapshot,
    state: GitState,
    onOpenDiff: (DiffTarget, String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val slot = state.files
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req -> state.reader.files(req.repoPath) }

    var dir by remember(repo.path) { mutableStateOf("") }
    var query by remember(repo.path) { mutableStateOf("") }

    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> StateLine("Listing files…")
        is ReadUi.Failed -> FailedRead("git ls-files failed", ui.reason)
        is ReadUi.Done -> {
            val fileList = ui.value
            val listing = GitFiles.listing(
                tracked = fileList.paths,
                untracked = GitFiles.untrackedRefs(repo.status?.entries ?: emptyList()),
                statusByPath = GitPresentation.statusByPath(repo.status?.entries ?: emptyList()),
                dir = dir,
                query = query,
            )
            // Breadcrumbs (root → current) — tap any crumb to jump there.
            if (dir.isNotEmpty() || query.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText(
                        text = "repo root",
                        color = if (dir.isEmpty()) HomeTokens.accent else HomeTokens.accent,
                        fontSize = 10.sp,
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Repository root") {
                            dir = ""
                            query = ""
                        },
                    )
                    var acc = ""
                    GitFiles.crumbs(dir).forEach { crumb ->
                        acc = if (acc.isEmpty()) crumb else "$acc/$crumb"
                        val target = acc
                        MonoText(text = " / ", color = HomeTokens.textDim, fontSize = 10.sp)
                        MonoText(
                            text = crumb,
                            color = HomeTokens.accent,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open $crumb") {
                                dir = target
                                query = ""
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search every tracked path", fontFamily = TerminalTheme.mono, fontSize = 10.sp) },
                textStyle = TextStyle(fontFamily = TerminalTheme.mono, fontSize = 12.sp, color = HomeTokens.textPrimary),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            if (listing.unindexed) {
                MonoText(
                    text = "Inside an untracked directory — git has not listed its contents.",
                    color = HomeTokens.textDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (listing.isEmpty) {
                StateLine(if (query.isBlank()) "No tracked files here." else "No matching paths.")
            } else {
                listing.dirs.forEach { entry ->
                    FileRow(
                        letter = entry.status ?: ' ',
                        label = "${GitFiles.dirLabel(entry.path)}/" + if (entry.fileCount > 0) "  (${entry.fileCount})" else "",
                        onClick = { dir = entry.path; query = "" },
                    )
                }
                listing.files.forEach { entry ->
                    val label = if (listing.searching) entry.path else GitFiles.fileName(entry.path)
                    FileRow(
                        letter = entry.status ?: ' ',
                        label = label,
                        onClick = { onOpenFile(entry.path) },
                    )
                }
                CapFooter(hidden = listing.moreEntries)
            }
        }
    }
}

// ------------------------------------------------------------ remotes tab

@Composable
private fun RemotesTab(repo: RepoSnapshot, state: GitState, onOp: (GitOp) -> Unit) {
    val status = repo.status
    val current = status?.branch
    GitSection("REMOTE URLS")
    if (repo.remotes.isEmpty()) {
        StateLine("No remotes configured — add one in a terminal (git remote add).")
    }
    repo.remotes.forEach { remote ->
        Column(modifier = Modifier.padding(top = 4.dp)) {
            MonoText(text = remote.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            MonoText(
                text = remote.fetchUrl?.let { GitPresentation.displayUrl(it) } ?: "—",
                color = HomeTokens.textDim,
                fontSize = 10.sp,
            )
            if (remote.pushUrlDiffers && remote.pushUrl != null) {
                MonoText(
                    text = "pushes to ${GitPresentation.displayUrl(remote.pushUrl)}",
                    color = HomeTokens.textDim,
                    fontSize = 10.sp,
                )
            }
            Row {
                TextAction("FETCH", onClick = { onOp(GitOp.Fetch(remote = remote.name)) })
                if (current != null) {
                    TextAction("PUSH", onClick = {
                        onOp(GitOp.Push(remote = remote.name, branch = current, setUpstream = status?.upstream == null))
                    })
                    TextAction("PULL", onClick = {
                        val upstream = status?.upstream
                        if (upstream != null && upstream.contains('/')) {
                            onOp(GitOp.Pull(remote = remote.name, branch = upstream.substringAfter('/')))
                        } else {
                            onOp(GitOp.Pull(remote = remote.name, branch = current))
                        }
                    })
                }
            }
        }
    }
    if (current != null && status?.upstreamGone == true) {
        MonoText(
            text = "The upstream of \"$current\" is gone — push with a new upstream or fix the remote.",
            color = HomeTokens.textDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ------------------------------------------------------------ repo tab

/** The repository tab: identity, worktrees, submodules, LFS, sparse cone. */
@Composable
private fun RepositoryTab(repo: RepoSnapshot, state: GitState, onOp: (GitOp) -> Unit) {
    IdentitySection(repo, state, onOp)
    WorktreesSection(repo, state, onOp)
    SubmodulesSection(repo, state)
    LfsSection(repo, state)
    SparseSection(repo, state)
}

@Composable
private fun IdentitySection(repo: RepoSnapshot, state: GitState, onOp: (GitOp) -> Unit) {
    val slot = state.identity
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req -> state.reader.identity(req.repoPath) }

    var editing by remember(repo.path) { mutableStateOf<String?>(null) }
    editing?.let { key ->
        PromptDialog(
            title = "Set $key",
            label = key,
            confirmWord = "Set",
            onConfirm = { value -> onOp(GitOp.ConfigSet(key = key, value = value)); editing = null },
            onDismiss = { editing = null },
        )
    }

    GitSection("IDENTITY")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> StateLine("Reading…")
        is ReadUi.Failed -> FailedRead("git config failed", ui.reason)
        is ReadUi.Done -> {
            val identity = ui.value
            LabelRow("NAME", identity.name ?: "not set")
            LabelRow("EMAIL", identity.email ?: "not set")
            if (!identity.complete) {
                MonoText(
                    text = "Commits need an identity — set it here or in a terminal.",
                    color = HomeTokens.textDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row {
                TextAction("SET NAME", onClick = { editing = "user.name" })
                TextAction("SET EMAIL", onClick = { editing = "user.email" })
            }
        }
    }
}

@Composable
private fun WorktreesSection(repo: RepoSnapshot, state: GitState, onOp: (GitOp) -> Unit) {
    val slot = state.worktrees
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req -> state.reader.worktrees(req.repoPath) }

    var adding by remember(repo.path) { mutableStateOf(false) }
    var sheetTree by remember(repo.path) { mutableStateOf<WorktreeRow?>(null) }
    if (adding) {
        PromptDialog(
            title = "Add worktree",
            label = "Directory name (a sibling of the repository)",
            confirmWord = "Add",
            onConfirm = { name ->
                adding = false
                val parent = repo.path.substringBeforeLast('/')
                onOp(GitOp.WorktreeAdd(path = "$parent/$name"))
            },
            onDismiss = { adding = false },
        )
    }
    sheetTree?.let { tree ->
        ActionSheet(
            title = tree.path,
            actions = if (tree.path == repo.path) {
                listOf("This is the main worktree" to {})
            } else {
                listOf("Remove it" to { onOp(GitOp.WorktreeRemove(path = tree.path)) })
            },
            onDismiss = { sheetTree = null },
        )
    }

    GitSection("WORKTREES")
    Row {
        TextAction("ADD WORKTREE", onClick = { adding = true })
    }
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> FailedRead("git worktree list failed", ui.reason)
        is ReadUi.Done -> {
            val trees = ui.value
            if (trees.isEmpty()) {
                StateLine("No worktrees reported.")
            } else {
                trees.forEach { tree ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "Worktree ${tree.path}") { sheetTree = tree }
                            .padding(vertical = 2.dp),
                    ) {
                        MonoText(
                            text = displayGuestRepoPath(tree.path),
                            modifier = Modifier.weight(1f),
                        )
                        MonoText(
                            text = when {
                                tree.bare -> "bare"
                                tree.detached -> "detached"
                                tree.branch != null -> tree.branch
                                else -> "?"
                            },
                            color = HomeTokens.textDim,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmodulesSection(repo: RepoSnapshot, state: GitState) {
    val slot = state.submodules
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req -> state.reader.submodules(req.repoPath) }
    GitSection("SUBMODULES")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> StateLine("No submodules (or git could not list them).")
        is ReadUi.Done -> {
            val subs = ui.value
            if (subs.isEmpty()) {
                StateLine("No submodules.")
            } else {
                subs.forEach { sub ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        MonoText(
                            text = sub.status.toString(),
                            color = if (sub.inSync) HomeTokens.runningGreen else HomeTokens.danger,
                            modifier = Modifier.width(16.dp),
                        )
                        MonoText(text = sub.path, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        if (sub.describe.isNotEmpty()) {
                            MonoText(text = sub.describe, color = HomeTokens.textDim, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LfsSection(repo: RepoSnapshot, state: GitState) {
    val slot = state.lfs
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req -> state.reader.lfsFiles(req.repoPath) }
    GitSection("GIT LFS")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> StateLine("git-lfs is not installed (or this repository does not use it).")
        is ReadUi.Done -> {
            val page = ui.value
            if (page.lines.isEmpty()) {
                StateLine("No LFS-tracked files.")
            } else {
                page.lines.forEach { line -> MonoText(text = line, fontSize = 10.sp) }
                CapFooter(hidden = page.hidden, what = "LFS files")
            }
        }
    }
}

@Composable
private fun SparseSection(repo: RepoSnapshot, state: GitState) {
    val slot = state.sparse
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req -> state.reader.sparseCheckout(req.repoPath) }
    GitSection("SPARSE CHECKOUT")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> StateLine("Not using sparse checkout.")
        is ReadUi.Done -> {
            val page = ui.value
            if (page.lines.isEmpty()) {
                StateLine("Sparse checkout enabled — empty cone.")
            } else {
                page.lines.forEach { line -> MonoText(text = line, fontSize = 10.sp) }
            }
        }
    }
}

// --------------------------------------------------------------- helpers

/**
 * The commit composer: subject (one line), optional body, and the amend
 * toggle. Amend is history-rewriting and lands on the confirm dialog
 * before anything runs — the dialog only ASSEMBLES the request.
 */
@Composable
private fun CommitDialog(
    onCommit: (subject: String, body: String, amend: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var amend by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onCommit(subject, body, amend) }, enabled = subject.isNotBlank()) {
                Text("Commit", fontFamily = TerminalTheme.mono, color = HomeTokens.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = TerminalTheme.mono, color = HomeTokens.textDim)
            }
        },
        title = { MonoText(text = "Commit staged changes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Subject", fontFamily = TerminalTheme.mono, fontSize = 11.sp) },
                    textStyle = TextStyle(fontFamily = TerminalTheme.mono, fontSize = 12.sp, color = HomeTokens.textPrimary),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Body (optional)", fontFamily = TerminalTheme.mono, fontSize = 11.sp) },
                    textStyle = TextStyle(fontFamily = TerminalTheme.mono, fontSize = 12.sp, color = HomeTokens.textPrimary),
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox, onClickLabel = "Amend the last commit") { amend = !amend }
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText(
                        text = if (amend) "[x]" else "[ ]",
                        color = if (amend) HomeTokens.accent else HomeTokens.textDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    MonoText(text = "Amend the last commit (rewrites it)", color = HomeTokens.textDim, fontSize = 11.sp)
                }
            }
        },
    )
}

/** The workspace's branch facts for a possibly-null status. */
internal fun branchText(status: GitStatusParser.RepoStatus?): String = when {
    status == null -> "unknown"
    status.noCommits -> "${status.branch ?: "main"} (no commits yet)"
    status.detached -> "detached"
    else -> status.branch ?: "unknown"
}

internal fun upstreamText(status: GitStatusParser.RepoStatus?): String {
    if (status == null) return "unknown"
    val upstream = status.upstream ?: return "none set"
    val gone = if (status.upstreamGone) " (gone)" else ""
    val diverge = when {
        status.ahead == null && status.behind == null -> ""
        else -> " · ↑${status.ahead ?: 0} ↓${status.behind ?: 0}"
    }
    return "$upstream$gone$diverge"
}
