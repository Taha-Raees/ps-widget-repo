package app.pocketshell.widget.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Archive
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * GIT STUDIO — the maximized page, laid out as a stand-alone Git app.
 *
 * Same visual language as the rest of PocketShell: flat, no filled cards, no
 * rounded surfaces — hairline rules, mono small-caps section labels, accent
 * text, and a rule (not a pill) marking the active item. What differs from
 * the card is the PRODUCT SHAPE, not the paint: a large title, one summary
 * strip, one row per repository; inside a repository an app bar, thumb-sized
 * quick actions (fetch / pull / push / stash) and real navigation — a bottom
 * bar on a phone, a side rail on a wide page — instead of a scrolling strip
 * of text tabs. Everything is scaled up for a full screen (LocalGitFull).
 *
 * Presentation only: every read and mutation still goes through the same
 * [GitState] seams the card uses, so nothing here can show a state git did
 * not report.
 */

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
private fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(HomeTokens.hairline))
}

@Composable
private fun StudioIconButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
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

private data class StripItem(
    val value: String,
    val label: String,
    val hot: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** A flat row of big numbers over small-caps labels, ruled above and below. */
@Composable
private fun StatStrip(items: List<StripItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            items.forEachIndexed { i, item ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(HomeTokens.hairline))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (item.onClick != null) {
                                Modifier.clickable(role = Role.Button, onClickLabel = "Show ${item.label}") { item.onClick.invoke() }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = item.value,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (item.hot) HomeTokens.accent else HomeTokens.textPrimary,
                    )
                    MonoText(text = item.label.uppercase(), color = HomeTokens.textDim, fontSize = 9.sp)
                }
            }
        }
        Hairline()
    }
}

// ------------------------------------------------------------------ home

@Composable
internal fun StudioHome(
    ui: GitUi,
    repos: List<RepoSnapshot>,
    lastScanAtMs: Long,
    scanning: Boolean,
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
                        fontWeight = FontWeight.SemiBold,
                        color = HomeTokens.textPrimary,
                    )
                    val facts = listOfNotNull(
                        ready?.snapshot?.gitVersion?.let { "git $it" },
                        if (scanning) "scanning…" else if (repos.isNotEmpty()) "scanned ${GitPresentation.ageLabel(System.currentTimeMillis(), lastScanAtMs)}" else null,
                    )
                    if (facts.isNotEmpty()) MonoText(text = facts.joinToString(" · "), color = HomeTokens.textDim, fontSize = 10.sp)
                }
                StudioIconButton(Icons.Outlined.Terminal, "Open terminal", onClick = onOpenTerminal)
                StudioIconButton(Icons.Outlined.Refresh, "Refresh repositories", enabled = !scanning, onClick = onRefresh)
            }
            LoadingRule(active = scanning)

            if (repos.isNotEmpty()) {
                val ahead = repos.sumOf { it.status?.ahead ?: 0 }
                val behind = repos.sumOf { it.status?.behind ?: 0 }
                val dirty = ready?.snapshot?.dirtyRepos ?: 0
                StatStrip(
                    items = listOf(
                        StripItem("${repos.size}", "repos"),
                        StripItem("$dirty", "dirty", hot = dirty > 0),
                        StripItem("↑$ahead", "to push", hot = ahead > 0),
                        StripItem("↓$behind", "to pull", hot = behind > 0),
                    ),
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (repos.size > 3) FilterField(query) { query = it }
                val shown = repos.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    shown.chunked(columns).forEach { rowRepos ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            rowRepos.forEach { repo ->
                                Column(Modifier.weight(1f)) {
                                    RepoRow(repo) { onOpenWorkspace(repo) }
                                    Hairline()
                                }
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

/** A plain underlined field — no outlined box. */
@Composable
private fun FilterField(query: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = TerminalTheme.mono, fontSize = 14.sp, color = HomeTokens.textPrimary),
        cursorBrush = SolidColor(HomeTokens.accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Column {
                Box(modifier = Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) MonoText("FILTER REPOSITORIES", color = HomeTokens.textDim, fontSize = 10.sp)
                    inner()
                }
                Hairline()
            }
        },
    )
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

/**
 * One repository: a 3dp state rule on the left (accent = pending changes,
 * danger = git could not read it), name + state on one line, then branch,
 * path and the last commit. An unreadable repository says so — it is never
 * shown as "clean".
 */
@Composable
private fun RepoRow(repo: RepoSnapshot, onOpen: () -> Unit) {
    val status = repo.status
    val unreadable = status == null
    val dirty = status?.dirty == true
    val rule = when {
        unreadable -> HomeTokens.danger
        dirty -> HomeTokens.accent
        else -> HomeTokens.hairline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(role = Role.Button, onClickLabel = "Open ${repo.name}") { onOpen() },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(rule))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = repo.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HomeTokens.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                MonoText(
                    text = when {
                        unreadable -> "UNREADABLE"
                        dirty -> status.summary().uppercase()
                        else -> "CLEAN"
                    },
                    color = when {
                        unreadable -> HomeTokens.danger
                        dirty -> HomeTokens.accent
                        else -> HomeTokens.textDim
                    },
                    fontSize = 9.sp,
                )
            }
            if (status != null) {
                Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoText(text = branchText(status), color = HomeTokens.accent, fontSize = 12.sp)
                    val glyphs = GitPresentation.trackingGlyphs(status.ahead, status.behind)
                    if (glyphs.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 12.sp)
                    }
                }
            }
            MonoText(text = displayGuestRepoPath(repo.path), color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            repo.log.firstOrNull()?.let { last ->
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    MonoText(text = last.hash, color = HomeTokens.textDim, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    MonoText(text = last.subject, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
                MonoText(text = "${last.author} · ${last.relativeTime}", color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (repo.error != null) {
                MonoText(text = "git could not read this repository (${repo.error})", color = HomeTokens.danger, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            }
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
            StudioTopBar(repo, onBack, scanning = state.scanning, onRefresh = { if (!state.scanning) state.refreshTick++ }, onOpenTerminal = onOpenTerminal)
            LoadingRule(active = state.scanning)
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (rail) {
                    StudioRail(tab, onTab)
                    Box(Modifier.width(1.dp).fillMaxHeight().background(HomeTokens.hairline))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = if (rail) 12.dp else 0.dp),
                ) {
                    QuickActions(repo, busy = state.inFlightOp != null, onOp = onOp)
                    if (tab == RepoTab.OVERVIEW) {
                        StudioOverview(repo, onTab = onTab, onOpenCommit = onOpenCommit)
                    } else {
                        Spacer(Modifier.height(8.dp))
                        WorkspaceTabBody(repo, tab, state, onOpenDiff, onOpenCommit, onOpenFile, onOp, onOpenTerminal)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (!rail) StudioBottomBar(tab, onTab)
        }
    }
}

@Composable
private fun StudioTopBar(repo: RepoSnapshot, onBack: () -> Unit, scanning: Boolean, onRefresh: () -> Unit, onOpenTerminal: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StudioIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back to repositories", onClick = onBack)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp, bottom = 4.dp)) {
            Text(
                text = repo.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = HomeTokens.textPrimary,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(text = branchText(repo.status), color = HomeTokens.accent, fontSize = 12.sp)
                val glyphs = GitPresentation.trackingGlyphs(repo.status?.ahead, repo.status?.behind)
                if (glyphs.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 12.sp)
                }
            }
        }
        StudioIconButton(Icons.Outlined.Terminal, "Open terminal", onClick = onOpenTerminal)
        StudioIconButton(Icons.Outlined.Refresh, "Refresh repositories", enabled = !scanning, onClick = onRefresh)
    }
}

/** Fetch / Pull / Push / Stash — icon over label, ruled, no boxes. */
@Composable
private fun QuickActions(repo: RepoSnapshot, busy: Boolean, onOp: (GitOp) -> Unit) {
    val hasRemote = repo.remotes.isNotEmpty()
    val status = repo.status
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ActionItem(Icons.Outlined.Sync, "Fetch", enabled = hasRemote && !busy, modifier = Modifier.weight(1f)) { onOp(GitOp.Fetch()) }
            ActionItem(Icons.Outlined.ArrowDownward, "Pull", badge = status?.behind, enabled = hasRemote && !busy, modifier = Modifier.weight(1f)) { onOp(GitOp.Pull()) }
            ActionItem(Icons.Outlined.ArrowUpward, "Push", badge = status?.ahead, enabled = hasRemote && !busy, modifier = Modifier.weight(1f)) { onOp(GitOp.Push()) }
            ActionItem(Icons.Outlined.Archive, "Stash", enabled = status?.dirty == true && !busy, modifier = Modifier.weight(1f)) { onOp(GitOp.StashPush()) }
        }
        Hairline()
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, modifier: Modifier, badge: Int? = null, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) HomeTokens.accent else HomeTokens.textDim,
            modifier = Modifier.size(24.dp),
        )
        MonoText(
            text = (if (badge != null && badge > 0) "$label $badge" else label).uppercase(),
            color = if (enabled) HomeTokens.textPrimary else HomeTokens.textDim,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ------------------------------------------------------------ navigation

/** The active item carries a 2dp accent rule (top on the bar, left on the rail) — never a pill. */
@Composable
private fun NavItem(
    tab: RepoTab,
    selected: Boolean,
    ruleOnTop: Boolean,
    modifier: Modifier = Modifier,
    label: String = tab.label,
    icon: ImageVector = tab.icon,
    onClick: () -> Unit,
) {
    val tint = if (selected) HomeTokens.accent else HomeTokens.textDim
    Box(
        modifier = modifier
            .heightIn(min = 60.dp)
            .clickable(role = Role.Tab, onClickLabel = "Show $label") { onClick() },
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(if (ruleOnTop) Alignment.TopCenter else Alignment.CenterStart)
                    .then(if (ruleOnTop) Modifier.fillMaxWidth().height(2.dp) else Modifier.fillMaxHeight().width(3.dp))
                    .background(HomeTokens.accent),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            MonoText(
                text = label.uppercase(),
                color = tint,
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun StudioBottomBar(tab: RepoTab, onTab: (RepoTab) -> Unit) {
    var moreOpen by remember { mutableStateOf(false) }
    Column {
        Hairline()
        Row(modifier = Modifier.fillMaxWidth()) {
            PRIMARY_TABS.forEach { t -> NavItem(t, t == tab, ruleOnTop = true, modifier = Modifier.weight(1f)) { onTab(t) } }
            Box(modifier = Modifier.weight(1f)) {
                val inMore = tab in MORE_TABS
                NavItem(
                    tab = tab,
                    selected = inMore,
                    ruleOnTop = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = if (inMore) tab.label else "More",
                    icon = if (inMore) tab.icon else Icons.Outlined.MoreHoriz,
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
            .width(88.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
    ) {
        RepoTab.entries.forEach { t -> NavItem(t, t == tab, ruleOnTop = false, modifier = Modifier.fillMaxWidth()) { onTab(t) } }
    }
}

// -------------------------------------------------------------- overview

@Composable
private fun StudioOverview(repo: RepoSnapshot, onTab: (RepoTab) -> Unit, onOpenCommit: (String) -> Unit) {
    val status = repo.status
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(text = "BRANCH", color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.width(96.dp))
            MonoText(text = branchText(status), color = HomeTokens.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val glyphs = GitPresentation.trackingGlyphs(status?.ahead, status?.behind)
            if (glyphs.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        LabelRow("UPSTREAM", upstreamText(status))
        LabelRow("PATH", displayGuestRepoPath(repo.path))
        if (status != null) LabelRow("STATUS", status.summary())
    }
    if (status != null) {
        StatStrip(
            items = listOf(
                StripItem("${status.stagedCount}", "staged", status.stagedCount > 0) { onTab(RepoTab.CHANGES) },
                StripItem("${status.changedCount}", "changed", status.changedCount > 0) { onTab(RepoTab.CHANGES) },
                StripItem("${status.untrackedCount}", "new", status.untrackedCount > 0) { onTab(RepoTab.CHANGES) },
                StripItem("${status.conflictCount}", "conflicts", status.conflictCount > 0) { onTab(RepoTab.CHANGES) },
            ),
        )
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
        GitSection("RECENT COMMITS", Modifier.padding(top = 12.dp))
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
