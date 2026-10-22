package app.pocketshell.widget.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * GIT STUDIO — the maximized page, laid out as a stand-alone Git app.
 *
 * The card (COMPACT/ROOMY) is a glance: chips + a pane. The maximized page
 * is a different product shape, not the same list stretched: a large app
 * bar, summary tiles, one card per repository; inside a repository an app
 * bar with the branch, one-thumb quick actions (fetch / pull / push /
 * stash) and real navigation — a bottom bar on a phone, a side rail on a
 * wide page — instead of a scrolling strip of text tabs.
 *
 * Presentation only: every read and mutation still goes through the same
 * [GitState] seams the card uses, so nothing here can show a state git did
 * not report.
 */

private val CardShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(50)

/** The bottom bar's slots; the rest sit behind "More". */
private val PRIMARY_TABS = listOf(RepoTab.OVERVIEW, RepoTab.CHANGES, RepoTab.HISTORY, RepoTab.BRANCHES, RepoTab.FILES)
private val MORE_TABS = RepoTab.entries.filter { it !in PRIMARY_TABS }

internal val RepoTab.icon: ImageVector
    get() = when (this) {
        RepoTab.OVERVIEW -> Icons.Outlined.Dashboard
        RepoTab.CHANGES -> Icons.Outlined.Edit
        RepoTab.HISTORY -> Icons.Outlined.History
        RepoTab.BRANCHES -> Icons.Outlined.AccountTree
        RepoTab.FILES -> Icons.Outlined.Folder
        RepoTab.REMOTES -> Icons.Outlined.Cloud
        RepoTab.REPO -> Icons.Outlined.Settings
        RepoTab.HOST -> Icons.Outlined.Code
    }

// ------------------------------------------------------------ primitives

@Composable
private fun StudioIconButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) HomeTokens.accent else HomeTokens.textDim,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun Pill(text: String, color: Color, filled: Boolean = false) {
    Box(
        modifier = Modifier
            .background(if (filled) color.copy(alpha = 0.16f) else Color.Transparent, PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        MonoText(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier, hot: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        modifier = modifier
            .background(HomeTokens.surfaceRaised, CardShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show $label") { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = value,
            fontFamily = TerminalTheme.mono,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (hot) HomeTokens.accent else HomeTokens.textPrimary,
        )
        MonoText(text = label.uppercase(), color = HomeTokens.textDim, fontSize = 9.sp)
    }
}

// ------------------------------------------------------------------ home

@Composable
internal fun StudioHome(
    ui: GitUi,
    repos: List<RepoSnapshot>,
    lastScanAtMs: Long,
    onRefresh: () -> Unit,
    onOpenWorkspace: (RepoSnapshot) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLinuxShell: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val ready = ui as? GitUi.Ready
    var query by remember { mutableStateOf("") }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = if (GitLayout.usesSideRail(maxWidth.value)) 2 else 1
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Repositories",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = HomeTokens.textPrimary,
                    )
                    val facts = listOfNotNull(
                        ready?.snapshot?.gitVersion?.let { "git $it" },
                        if (repos.isNotEmpty()) "scanned ${GitPresentation.ageLabel(System.currentTimeMillis(), lastScanAtMs)}" else null,
                    )
                    if (facts.isNotEmpty()) MonoText(text = facts.joinToString(" · "), color = HomeTokens.textDim, fontSize = 11.sp)
                }
                StudioIconButton(Icons.Outlined.Terminal, "Open terminal", onClick = onOpenTerminal)
                StudioIconButton(Icons.Outlined.Refresh, "Refresh repositories", onClick = onRefresh)
            }

            if (repos.isNotEmpty()) {
                val ahead = repos.sumOf { it.status?.ahead ?: 0 }
                val behind = repos.sumOf { it.status?.behind ?: 0 }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile("${repos.size}", "repos", Modifier.weight(1f))
                    StatTile("${ready?.snapshot?.dirtyRepos ?: 0}", "dirty", Modifier.weight(1f), hot = (ready?.snapshot?.dirtyRepos ?: 0) > 0)
                    StatTile("↑$ahead", "to push", Modifier.weight(1f), hot = ahead > 0)
                    StatTile("↓$behind", "to pull", Modifier.weight(1f), hot = behind > 0)
                }
                if (repos.size > 3) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { MonoText("Filter repositories", color = HomeTokens.textDim, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
                val shown = repos.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    shown.chunked(columns).forEach { rowRepos ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            rowRepos.forEach { repo ->
                                RepoCard(repo, Modifier.weight(1f)) { onOpenWorkspace(repo) }
                            }
                            if (rowRepos.size < columns) Spacer(Modifier.weight((columns - rowRepos.size).toFloat()))
                        }
                    }
                    if (shown.isEmpty()) StateLine("No repository matches “${query.trim()}”")
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                Column(modifier = Modifier.weight(1f).padding(top = 24.dp)) {
                    StudioEmpty(ui, onOpenLinuxShell, onOpenDiagnostics)
                }
            }
        }
    }
}

@Composable
private fun StudioEmpty(ui: GitUi, onOpenLinuxShell: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val ready = ui as? GitUi.Ready
    when {
        ui is GitUi.Probing -> StateLine("Looking for repositories…")
        ui is GitUi.Unavailable -> {
            StateLine("Linux is not ready")
            TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics", color = HomeTokens.accent) }
        }
        ui is GitUi.ProbeFailed -> {
            StateLine("Could not probe git")
            MonoText(text = ui.reason, color = HomeTokens.textDim, maxLines = 3)
        }
        ready != null && !ready.snapshot.hasGit -> {
            StateLine("Git is not installed")
            MonoText("Install it from the Linux Shell (apk add git).", color = HomeTokens.textDim)
            TextButton(onClick = onOpenLinuxShell) { Text("Open Linux", color = HomeTokens.accent) }
        }
        else -> {
            StateLine("No repositories yet")
            MonoText("Clone or create a repository under ~/ or ~/Projects — it appears here.", color = HomeTokens.textDim, maxLines = 3)
            TextButton(onClick = onOpenLinuxShell) { Text("Open Linux", color = HomeTokens.accent) }
        }
    }
}

@Composable
private fun RepoCard(repo: RepoSnapshot, modifier: Modifier, onOpen: () -> Unit) {
    val status = repo.status
    val dirty = status?.dirty == true
    Column(
        modifier = modifier
            .background(HomeTokens.surfaceRaised, CardShape)
            .clickable(role = Role.Button, onClickLabel = "Open ${repo.name}") { onOpen() }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = repo.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = HomeTokens.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Pill(
                text = if (dirty) status!!.summary() else "clean",
                color = if (dirty) HomeTokens.accent else HomeTokens.textDim,
                filled = dirty,
            )
        }
        Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MonoText(text = branchText(status), color = HomeTokens.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            val glyphs = GitPresentation.trackingGlyphs(status?.ahead, status?.behind)
            if (glyphs.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 12.sp)
            }
        }
        MonoText(text = displayGuestRepoPath(repo.path), color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        repo.log.firstOrNull()?.let { last ->
            Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(HomeTokens.hairline))
            Row {
                MonoText(text = last.hash, color = HomeTokens.textDim, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                MonoText(text = last.subject, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            MonoText(text = "${last.author} · ${last.relativeTime}", color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (repo.error != null) {
            MonoText(text = "git could not read this repository (${repo.error})", color = HomeTokens.danger, fontSize = 10.sp, maxLines = 2)
        }
    }
}

// ------------------------------------------------------------- workspace

@Composable
internal fun StudioWorkspace(
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
        Column(modifier = Modifier.fillMaxSize()) {
            BackHeader(title = "Git", onBack = onBack)
            StateLine("Repository not listed — refreshing.")
        }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rail = GitLayout.usesSideRail(maxWidth.value)
        Column(modifier = Modifier.fillMaxSize()) {
            StudioTopBar(repo, onBack, onRefresh = { state.refreshTick++ }, onOpenTerminal = onOpenTerminal)
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (rail) StudioRail(tab, onTab)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = if (rail) 12.dp else 0.dp, top = 4.dp),
                ) {
                    QuickActions(repo, busy = state.inFlightOp != null, onOp = onOp)
                    Spacer(Modifier.height(8.dp))
                    if (tab == RepoTab.OVERVIEW) {
                        StudioOverview(repo, onTab = onTab, onOpenCommit = onOpenCommit)
                    } else {
                        WorkspaceTabBody(repo, tab, state, onOpenDiff, onOpenCommit, onOpenFile, onOp, onOpenTerminal)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (!rail) StudioBottomBar(tab, onTab)
        }
    }
}

@Composable
private fun StudioTopBar(repo: RepoSnapshot, onBack: () -> Unit, onRefresh: () -> Unit, onOpenTerminal: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StudioIconButton(Icons.Outlined.ArrowBack, "Back to repositories", onClick = onBack)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = repo.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = HomeTokens.textPrimary,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(text = branchText(repo.status), color = HomeTokens.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                val glyphs = GitPresentation.trackingGlyphs(repo.status?.ahead, repo.status?.behind)
                if (glyphs.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 12.sp)
                }
            }
        }
        StudioIconButton(Icons.Outlined.Terminal, "Open terminal", onClick = onOpenTerminal)
        StudioIconButton(Icons.Outlined.Refresh, "Refresh repositories", onClick = onRefresh)
    }
}

@Composable
private fun QuickActions(repo: RepoSnapshot, busy: Boolean, onOp: (GitOp) -> Unit) {
    val hasRemote = repo.remotes.isNotEmpty()
    val status = repo.status
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile(Icons.Outlined.Sync, "Fetch", enabled = hasRemote && !busy, modifier = Modifier.weight(1f)) { onOp(GitOp.Fetch()) }
        ActionTile(Icons.Outlined.ArrowDownward, "Pull", badge = status?.behind, enabled = hasRemote && !busy, modifier = Modifier.weight(1f)) { onOp(GitOp.Pull()) }
        ActionTile(Icons.Outlined.ArrowUpward, "Push", badge = status?.ahead, enabled = hasRemote && !busy, modifier = Modifier.weight(1f)) { onOp(GitOp.Push()) }
        ActionTile(Icons.Outlined.Archive, "Stash", enabled = status?.dirty == true && !busy, modifier = Modifier.weight(1f)) { onOp(GitOp.StashPush()) }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, badge: Int? = null, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) HomeTokens.accent else HomeTokens.textDim
    Column(
        modifier = modifier
            .heightIn(min = 60.dp)
            .background(HomeTokens.surfaceRaised, CardShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        MonoText(
            text = if (badge != null && badge > 0) "$label $badge" else label,
            color = if (enabled) HomeTokens.textPrimary else HomeTokens.textDim,
            fontSize = 11.sp,
        )
    }
}

// ------------------------------------------------------------ navigation

@Composable
private fun NavItem(tab: RepoTab, selected: Boolean, label: String = tab.label, icon: ImageVector = tab.icon, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (selected) HomeTokens.accent else HomeTokens.textDim
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clickable(role = Role.Tab, onClickLabel = "Show $label") { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .background(if (selected) HomeTokens.accent.copy(alpha = 0.16f) else Color.Transparent, PillShape)
                .padding(horizontal = 18.dp, vertical = 4.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        MonoText(text = label, color = tint, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else null)
    }
}

@Composable
private fun StudioBottomBar(tab: RepoTab, onTab: (RepoTab) -> Unit) {
    var moreOpen by remember { mutableStateOf(false) }
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(HomeTokens.hairline))
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            PRIMARY_TABS.forEach { t -> NavItem(t, t == tab, modifier = Modifier.weight(1f)) { onTab(t) } }
            Box(modifier = Modifier.weight(1f)) {
                val inMore = tab in MORE_TABS
                NavItem(
                    tab = tab,
                    selected = inMore,
                    label = if (inMore) tab.label else "More",
                    icon = if (inMore) tab.icon else Icons.Outlined.MoreHoriz,
                    modifier = Modifier.fillMaxWidth(),
                ) { moreOpen = true }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    MORE_TABS.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.label, fontFamily = TerminalTheme.mono, color = if (t == tab) HomeTokens.accent else HomeTokens.textPrimary) },
                            leadingIcon = { Icon(t.icon, contentDescription = null, tint = HomeTokens.accent) },
                            onClick = {
                                moreOpen = false
                                onTab(t)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioRail(tab: RepoTab, onTab: (RepoTab) -> Unit) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .fillMaxSize()
            .background(HomeTokens.surfaceRaised, CardShape)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RepoTab.entries.forEach { t -> NavItem(t, t == tab, modifier = Modifier.fillMaxWidth()) { onTab(t) } }
    }
}

// -------------------------------------------------------------- overview

@Composable
private fun StudioOverview(repo: RepoSnapshot, onTab: (RepoTab) -> Unit, onOpenCommit: (String) -> Unit) {
    val status = repo.status
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HomeTokens.surfaceRaised, CardShape)
            .padding(14.dp),
    ) {
        MonoText(text = "BRANCH", color = HomeTokens.textDim, fontSize = 10.sp)
        MonoText(text = branchText(status), color = HomeTokens.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        MonoText(text = upstreamText(status), color = HomeTokens.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        MonoText(text = displayGuestRepoPath(repo.path), color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
    if (status != null) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("${status.stagedCount}", "staged", Modifier.weight(1f), hot = status.stagedCount > 0) { onTab(RepoTab.CHANGES) }
            StatTile("${status.changedCount}", "changed", Modifier.weight(1f), hot = status.changedCount > 0) { onTab(RepoTab.CHANGES) }
            StatTile("${status.untrackedCount}", "new", Modifier.weight(1f), hot = status.untrackedCount > 0) { onTab(RepoTab.CHANGES) }
            StatTile("${status.conflictCount}", "conflicts", Modifier.weight(1f), hot = status.conflictCount > 0) { onTab(RepoTab.CHANGES) }
        }
    }
    if (repo.error != null) {
        MonoText(
            text = "git could not read this repository (${repo.error})",
            color = HomeTokens.danger,
            fontSize = 10.sp,
            maxLines = 3,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (repo.log.isNotEmpty()) {
        GitSection("RECENT COMMITS", Modifier.padding(top = 10.dp))
        repo.log.forEach { entry -> CommitRow(entry, roomy = true, onOpen = { onOpenCommit(entry.hash) }) }
        TextAction("ALL HISTORY") { onTab(RepoTab.HISTORY) }
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
}
