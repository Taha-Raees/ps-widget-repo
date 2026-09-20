package app.pocketshell.widget.git

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The REPOSITORY surface — a focused app for ONE repository: a compact
 * header (back · name · path · tracking glyphs · dirty dot · menu), five
 * tabs, and the tab's own content. Nothing here is a section of one big
 * scroll: CHANGES owns staging, HISTORY owns the log, BRANCHES owns
 * switch/merge/delete, FILES owns the worktree browser, REMOTES owns the
 * endpoints and the sync verbs. The ⋮ menu carries the repo-wide verbs
 * (terminal, fetch/pull/push, stash, refresh, copy path).
 */
@Composable
internal fun GitRepoScreen(
    state: GitState,
    repoPath: String,
    layout: GitLayout,
    onOpenTerminal: () -> Unit,
) {
    val ready = state.ui as? GitUi.Ready
    val repo = ready?.snapshot?.repos?.firstOrNull { it.path == repoPath }
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current

    // A vanished repository degrades home — never a stale page for a
    // deleted directory.
    LaunchedEffect(repo == null) { if (repo == null) state.pop() }
    if (repo == null) return

    // The dialogs' pending state (one of each, cleared on use).
    val opQueue = remember { GitOpQueue() }
    var commitDialog by remember { mutableStateOf(false) }
    var createBranchDialog by remember { mutableStateOf(false) }
    var branchChoicesFor by remember { mutableStateOf<String?>(null) }

    // The one op entry point for this screen: dialogs where the verb is
    // risky, straight queueing where it is quiet and reversible.
    val runOp: (OpKind, String?) -> Unit = { kind, arg ->
        opQueue.run(state, repoPath, kind, arg)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GitTopBar(
            title = repo.name,
            subtitle = displayGuestRepoPath(repoPath),
            onBack = { state.pop() },
        ) {
            val glyphs = GitPresentation.trackingGlyphs(
                ahead = repo.status?.ahead,
                behind = repo.status?.behind,
            )
            if (glyphs.isNotEmpty()) {
                Text(
                    text = glyphs,
                    fontFamily = TerminalTheme.mono,
                    fontSize = 11.sp,
                    color = HomeTokens.accent,
                )
                Spacer(Modifier.width(6.dp))
            }
            val dirty = repo.status?.dirty == true
            Text(
                text = GitPresentation.worktreeGlyph(dirty).toString(),
                fontFamily = TerminalTheme.mono,
                fontSize = 12.sp,
                color = if (dirty) HomeTokens.accent else HomeTokens.textDim,
            )
            GitMenu(
                items = listOf(
                    "Open terminal" to onOpenTerminal,
                    "Fetch" to { runOp(OpKind.FETCH, null) },
                    "Pull" to { runOp(OpKind.PULL, null) },
                    "Push" to { runOp(OpKind.PUSH, null) },
                    "Stash" to { state.push(GitScreen.Stash(repoPath)) },
                    "Refresh" to { state.refreshTick++ },
                    "Copy path" to {
                        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("path", repoPath))
                        state.opResult = GitOps.OpResult.Ok("Path copied — $repoPath")
                    },
                ),
            )
        }
        GitTabRow(
            tabs = RepoTab.entries.toList(),
            active = state.repoTab(repoPath),
            onSelect = { state.setRepoTab(repoPath, it) },
        )
        Box(modifier = Modifier.weight(1f)) {
            when (state.repoTab(repoPath)) {
                RepoTab.CHANGES -> ChangesTab(
                    state = state,
                    repo = repo,
                    onOpenFileDiff = { target, siblings, index ->
                        state.push(
                            GitScreen.Diff(
                                repoPath = repoPath,
                                path = target.path,
                                staged = target.staged,
                                untracked = target.untracked,
                                atCommit = null,
                                siblings = siblings,
                                index = index,
                            ),
                        )
                    },
                    onCommit = { commitDialog = true },
                    runOp = runOp,
                )
                RepoTab.HISTORY -> HistoryTab(state = state, repoPath = repoPath, scope = scope)
                RepoTab.BRANCHES -> BranchesTab(
                    state = state,
                    repoPath = repoPath,
                    repo = repo,
                    onCreateBranch = { createBranchDialog = true },
                    onBranchChoices = { branchChoicesFor = it },
                    runOp = runOp,
                )
                RepoTab.FILES -> FilesTab(
                    state = state,
                    repoPath = repoPath,
                    repo = repo,
                    scope = scope,
                    onOpenTerminal = onOpenTerminal,
                )
                RepoTab.REMOTES -> RemotesTab(repo = repo, runOp = runOp)
            }
        }
    }

    // ---- dialogs ----
    GitOpConfirmDialog(queue = opQueue, state = state, repoPath = repoPath)
    if (commitDialog) {
        GitInput(
            title = "Commit ${repo.status?.stagedCount ?: 0} staged file(s)",
            confirmLabel = "Commit",
            placeholder = "Commit message",
            singleLine = false,
            onConfirm = { message ->
                commitDialog = false
                state.queueOp(OpKind.COMMIT, repoPath, message)
            },
            onDismiss = { commitDialog = false },
        )
    }
    if (createBranchDialog) {
        GitInput(
            title = "New branch (creates and switches)",
            confirmLabel = "Create",
            placeholder = "branch name",
            onConfirm = { name ->
                createBranchDialog = false
                state.queueOp(OpKind.CREATE_BRANCH, repoPath, name.trim())
            },
            onDismiss = { createBranchDialog = false },
        )
    }
    val branchFor = branchChoicesFor
    if (branchFor != null) {
        val isLocal = repo.branches.any { it.name == branchFor }
        GitChoices(
            title = if (isLocal) "Branch" else "Remote branch",
            subtitle = branchFor,
            choices = buildList {
                add("Checkout" to { runOp(OpKind.CHECKOUT, branchFor) })
                if (isLocal && branchFor != repo.status?.branch) {
                    add("Merge into current" to { runOp(OpKind.MERGE, branchFor) })
                    add("Delete" to { runOp(OpKind.DELETE_BRANCH, branchFor) })
                }
            },
            onDismiss = { branchChoicesFor = null },
        )
    }
}

/** One pending confirmation: the verb, its argument, what the dialog says. */
// (PendingConfirm lives in GitScreens.kt, shared with the detail screens' op queues.)

// ---------------------------------------------------------------- CHANGES

/** One changed file in the Changes tab: letter · path · its one verb icon. */
private data class ChangeRow(val path: String, val letter: Char, val staged: Boolean, val untracked: Boolean)

@Composable
private fun ChangesTab(
    state: GitState,
    repo: RepoSnapshot,
    onOpenFileDiff: (ChangeRow, List<GitScreen.Sibling>, Int) -> Unit,
    onCommit: () -> Unit,
    runOp: (OpKind, String?) -> Unit,
) {
    val status = repo.status
    val rows: List<ChangeRow> = if (status == null) {
        emptyList()
    } else {
        GitPresentation.conflictRows(status.entries).map {
            ChangeRow(displayToPath(it.label), it.letter, staged = false, untracked = false)
        } + GitPresentation.stagedRows(status.entries).map {
            ChangeRow(displayToPath(it.label), it.letter, staged = true, untracked = false)
        } + GitPresentation.unstagedRows(status.entries).map {
            ChangeRow(displayToPath(it.label), it.letter, staged = false, untracked = it.letter == '?')
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // The tab's action line: the verbs that matter, only when usable.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hasUnstaged = status != null &&
                (status.changedCount > 0 || status.untrackedCount > 0)
            TextAction("STAGE ALL", enabled = hasUnstaged) { runOp(OpKind.STAGE_ALL, null) }
            TextAction("UNSTAGE ALL", enabled = (status?.stagedCount ?: 0) > 0) {
                runOp(OpKind.UNSTAGE_ALL, null)
            }
            Spacer(Modifier.weight(1f))
            TextAction(
                "COMMIT ${status?.stagedCount ?: 0}",
                enabled = (status?.stagedCount ?: 0) > 0,
            ) { onCommit() }
        }
        Text(
            text = branchFacts(status),
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            color = HomeTokens.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (status == null) {
            StateBody(line = "git could not read this repository", detail = repo.error)
            return
        }
        if (rows.isEmpty()) {
            StateBody(
                line = "Working tree clean",
                detail = "Everything committed — nothing to stage.",
            )
            return
        }
        val siblings = rows.map { GitScreen.Sibling(it.path, it.staged, it.untracked) }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows) { index, row ->
                ChangeRowComposable(
                    row = row,
                    onOpen = { onOpenFileDiff(row, siblings, index) },
                    onStage = { runOp(OpKind.STAGE, row.path) },
                    onUnstage = { runOp(OpKind.UNSTAGE, row.path) },
                )
            }
        }
    }
}

/** Rename labels render "old -> new" — the diff opens on the NEW path. */
private fun displayToPath(label: String): String =
    if (label.contains(" -> ")) label.substringAfterLast(" -> ") else label

@Composable
private fun ChangeRowComposable(
    row: ChangeRow,
    onOpen: () -> Unit,
    onStage: () -> Unit,
    onUnstage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Show diff for ${row.path}") { onOpen() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.letter.toString(),
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.textDim,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text = row.path,
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.staged) {
            GitRowIcon(Icons.Outlined.Remove, "Unstage ${row.path}", onClick = onUnstage)
        } else {
            GitRowIcon(Icons.Outlined.Add, "Stage ${row.path}", onClick = onStage)
        }
    }
}

/** The 26dp row-level icon action — always named, always one verb. */
@Composable
private fun GitRowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (enabled) HomeTokens.accent else HomeTokens.textDim,
        modifier = Modifier
            .size(26.dp)
            .clickable(role = Role.Button, onClickLabel = label, enabled = enabled) { onClick() }
            .padding(4.dp),
    )
}

// ---------------------------------------------------------------- HISTORY

@Composable
private fun HistoryTab(state: GitState, repoPath: String, scope: CoroutineScope) {
    val historyUi = state.history[repoPath]
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (historyUi == null) {
            item { TabLine("Loading history…") }
        } else {
            if (historyUi.entries.isEmpty() && !historyUi.loading && historyUi.error == null) {
                item { TabLine("No commits yet") }
            }
            items(historyUi.entries, key = { it.hash }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Show commit ${entry.hash}",
                        ) { state.push(GitScreen.Commit(repoPath, entry.hash)) }
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        text = entry.subject,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 11.sp,
                        color = HomeTokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${entry.hash} · ${entry.author} · ${entry.relativeTime}",
                        fontFamily = TerminalTheme.mono,
                        fontSize = 10.sp,
                        color = HomeTokens.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (historyUi.hasMore) {
                item {
                    TextAction("LOAD MORE") {
                        state.loadHistoryPage(repoPath, append = true)
                        scope.launch { state.runHistoryExec(repoPath) }
                    }
                }
            }
            if (historyUi.loading) {
                item { TabLine("Loading…") }
            }
            if (historyUi.error != null) {
                item { TabLine("git log failed — ${historyUi.error}", danger = true) }
            }
        }
    }
}

// --------------------------------------------------------------- BRANCHES

/** The Branches tab's remote-branch facts for one repository. */
internal data class BranchesUi(
    val remoteNames: List<String>? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@Composable
private fun BranchesTab(
    state: GitState,
    repoPath: String,
    repo: RepoSnapshot,
    onCreateBranch: () -> Unit,
    onBranchChoices: (String) -> Unit,
    runOp: (OpKind, String?) -> Unit,
) {
    val status = repo.status
    val current = status?.branch
    // Remote branches load once per data generation, lazily — the scan
    // lists LOCAL branches only.
    LaunchedEffect(repoPath, state.dataTick) {
        if (state.branches[repoPath] == null) {
            state.branches = state.branches + (repoPath to BranchesUi(loading = true))
            val result = withContext(Dispatchers.IO) { state.probe.remoteBranches(repoPath) }
            state.branches = state.branches + (repoPath to when (result) {
                is RemoteBranchesResult.Done -> BranchesUi(remoteNames = result.names)
                is RemoteBranchesResult.Failed -> BranchesUi(error = result.reason)
            })
        }
    }
    val branchesUi = state.branches[repoPath]
    val locals = repo.branches
    val remotes = branchesUi?.remoteNames.orEmpty()
        .filter { name -> locals.none { it.name == name } }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "CURRENT",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 6.dp, bottom = 1.dp),
                )
                Spacer(Modifier.weight(1f))
                TextAction("NEW BRANCH") { onCreateBranch() }
            }
            BranchRow(
                name = when {
                    current != null -> current
                    status?.detached == true -> "(detached HEAD)"
                    else -> "unknown"
                },
                detail = status?.upstream?.let { upstream ->
                    "→ $upstream" + (if (status.upstreamGone) " (gone)" else "")
                },
                isCurrent = true,
                glyphs = GitPresentation.trackingGlyphs(status?.ahead, status?.behind),
            ) { }
        }
        val others = locals.filter { it.name != current }
        if (others.isNotEmpty()) {
            item {
                Text(
                    text = "LOCAL",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 6.dp, bottom = 1.dp),
                )
            }
            items(others, key = { "local-${it.name}" }) { branch ->
                BranchRow(
                    name = branch.name,
                    detail = branch.upstream?.let { u -> "→ $u" + (if (branch.gone) " (gone)" else "") },
                    isCurrent = false,
                    glyphs = GitPresentation.trackingGlyphs(branch.ahead, branch.behind),
                ) { onBranchChoices(branch.name) }
            }
        }
        item {
            Text(
                text = "REMOTE",
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = HomeTokens.textDim,
                modifier = Modifier.padding(top = 6.dp, bottom = 1.dp),
            )
        }
        if (branchesUi == null || branchesUi.loading) {
            item { TabLine("Loading remote branches…") }
        } else if (branchesUi.error != null) {
            item { TabLine("git branch -r failed — ${branchesUi.error}", danger = true) }
        } else if (remotes.isEmpty()) {
            item { TabLine("No remote branches (fetch first?)") }
        } else {
            items(remotes, key = { "remote-$it" }) { name ->
                BranchRow(name = name, detail = null, isCurrent = false, glyphs = "") {
                    onBranchChoices(name)
                }
            }
        }
    }
}

@Composable
private fun BranchRow(
    name: String,
    detail: String?,
    isCurrent: Boolean,
    glyphs: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "Branch actions for $name",
                enabled = !isCurrent,
            ) { onOpen() }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isCurrent) "✓" else "",
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.accent,
            modifier = Modifier.width(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                color = if (isCurrent) HomeTokens.accent else HomeTokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    fontFamily = TerminalTheme.mono,
                    fontSize = 10.sp,
                    color = HomeTokens.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (glyphs.isNotEmpty()) {
            Text(
                text = glyphs,
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = HomeTokens.accent,
            )
        }
    }
}

// ------------------------------------------------------------------ FILES

@Composable
private fun FilesTab(
    state: GitState,
    repoPath: String,
    repo: RepoSnapshot,
    scope: CoroutineScope,
    onOpenTerminal: () -> Unit,
) {
    val appContext = LocalContext.current
    val filesUi = state.files[repoPath]
    val dir = filesUi?.dir ?: repoPath
    val relDir = dir.removePrefix(repoPath).removePrefix("/")
    val changedByPath = repo.status?.entries?.associateBy { it.path }.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        // The directory bar: where we are, up, copy path, terminal.
        Row(verticalAlignment = Alignment.CenterVertically) {
            GitRowIcon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                "Up one directory",
                enabled = relDir.isNotEmpty(),
            ) {
                val parent = dir.substringBeforeLast('/', missingDelimiterValue = "")
                scope.launch { state.runListExec(repoPath, parent.ifEmpty { repoPath }) }
                Unit
            }
            Text(
                text = if (relDir.isEmpty()) "/" else relDir,
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                color = HomeTokens.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            GitRowIcon(Icons.Outlined.ContentCopy, "Copy path") {
                val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("path", dir))
                state.opResult = GitOps.OpResult.Ok("Path copied — $dir")
            }
            GitRowIcon(Icons.Outlined.Terminal, "Open terminal") {
                onOpenTerminal()
            }
        }
        when {
            filesUi == null || (filesUi.loading && filesUi.entries == null) -> TabLine("Listing…")
            filesUi.error != null -> TabLine("Could not list — ${filesUi.error}", danger = true)
            filesUi.entries?.isEmpty() == true -> TabLine("(empty directory)")
            else -> {
                val entries = filesUi?.entries.orEmpty()
                    .sortedWith(
                        compareByDescending<DirEntry> { it.isDir }.thenBy { it.name.lowercase() },
                    )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { "${if (it.isDir) "d" else "f"}-${it.name}" }) { entry ->
                        val childRel = if (relDir.isEmpty()) entry.name else "$relDir/${entry.name}"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = if (entry.isDir) "Open ${entry.name}" else "Preview ${entry.name}",
                                ) {
                                    if (entry.isDir) {
                                        scope.launch { state.runListExec(repoPath, "$repoPath/$childRel") }
                                    } else {
                                        state.push(GitScreen.Viewer(repoPath, childRel))
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (entry.isDir) "d" else "f",
                                fontFamily = TerminalTheme.mono,
                                fontSize = 10.sp,
                                color = HomeTokens.textDim,
                                modifier = Modifier.width(16.dp),
                            )
                            Text(
                                text = entry.name,
                                fontFamily = TerminalTheme.mono,
                                fontSize = 11.sp,
                                color = HomeTokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            val statusEntry = changedByPath[childRel]
                            if (statusEntry != null) {
                                val letter = if (statusEntry.untracked) '?' else statusEntry.y
                                Text(
                                    text = letter.toString(),
                                    fontFamily = TerminalTheme.mono,
                                    fontSize = 10.sp,
                                    color = HomeTokens.accent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- REMOTES

@Composable
private fun RemotesTab(repo: RepoSnapshot, runOp: (OpKind, String?) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("FETCH") { runOp(OpKind.FETCH, null) }
            TextAction("PULL") { runOp(OpKind.PULL, null) }
            TextAction("PUSH") { runOp(OpKind.PUSH, null) }
            Spacer(Modifier.weight(1f))
            Text(
                text = branchFacts(repo.status),
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = HomeTokens.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (repo.remotes.isEmpty()) {
            StateBody(
                line = "No remotes",
                detail = "Add one in a terminal (git remote add origin …) — it appears here.",
            )
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(repo.remotes, key = { it.name }) { remote ->
                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = remote.name,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 11.sp,
                        color = HomeTokens.textPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = GitPresentation.shortUrl(remote.url),
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
}

// ------------------------------------------------------------- shared bits

@Composable
private fun TabLine(text: String, danger: Boolean = false) {
    Text(
        text = text,
        fontFamily = TerminalTheme.mono,
        fontSize = 10.sp,
        color = if (danger) HomeTokens.danger else HomeTokens.textDim,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}
