package app.pocketshell.widget.git

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * The MINI mode: the card (COMPACT / ROOMY) — the same flat language as the
 * maximized studio, at a glance. It answers "is anything waiting on me?":
 *
 *   Git  2.54.0 · 2 dirty                              ↻
 *   ● pocketshell-agentZ        main ↑47
 *   ● pocketshell               main ↑2
 *   ○ demo                      main
 *   9 repos · 2 dirty · ↑47 to push · +6 more
 *
 * Repositories that need attention come first (unreadable, uncommitted, unpushed),
 * rows are sized to the card's real height with a "+N more" in the footer, a thin
 * indeterminate rule shows while a scan runs, and a tap opens that repository's
 * in-card detail (the old pane, one Back away). No chips, no scrolling strip.
 */
@Composable
internal fun GitMiniCard(
    ui: GitUi,
    repos: List<RepoSnapshot>,
    layout: GitLayout,
    scanning: Boolean,
    detailPath: String?,
    lastScanAtMs: Long,
    onRefresh: () -> Unit,
    onOpenRepo: (String) -> Unit,
    onCloseRepo: () -> Unit,
    onOpenWorkspace: (RepoSnapshot) -> Unit,
    onOpenCommit: (RepoSnapshot, LogEntry) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLinuxShell: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val detail = repos.firstOrNull { it.path == detailPath }
    if (detail != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            BackHeader(title = "Git", onBack = onCloseRepo)
            Spacer(Modifier.height(2.dp))
            DashboardPane(
                repo = detail,
                roomy = layout.showsPath,
                modifier = Modifier.weight(1f),
                onOpenWorkspace = { onOpenWorkspace(detail) },
                onOpenCommit = { onOpenCommit(detail, it) },
            )
        }
        return
    }

    val ready = ui as? GitUi.Ready
    val ordered = remember(repos) { MiniModel.order(repos) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val roomy = layout.showsPath
        val fit = MiniModel.rowsThatFit(maxHeight.value, roomy, ordered.size)
        val shown = ordered.take(fit)
        val hidden = ordered.size - shown.size

        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Git",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = HomeTokens.textPrimary,
                )
                val facts = listOfNotNull(
                    ready?.snapshot?.gitVersion,
                    if (repos.isNotEmpty()) "${ready?.snapshot?.dirtyRepos ?: 0} DIRTY" else null,
                )
                if (facts.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = facts.joinToString(" · "),
                        fontFamily = TerminalTheme.mono,
                        fontSize = 12.sp,
                        color = HomeTokens.textDim,
                    )
                }
                Spacer(Modifier.weight(1f))
                RefreshButton(onRefresh = onRefresh, loading = scanning)
            }
            LoadingRule(active = scanning)
            Spacer(Modifier.height(4.dp))

            shown.forEachIndexed { index, repo ->
                MiniRepoRow(repo, roomy) { onOpenRepo(repo.path) }
                if (index != shown.lastIndex) HorizontalDivider(color = HomeTokens.hairline.copy(alpha = 0.6f))
            }

            if (repos.isEmpty()) {
                MiniStateBlock(ui, scanning, onOpenLinuxShell, onOpenDiagnostics)
            } else {
                Spacer(Modifier.weight(1f))
                MonoText(
                    text = if (scanning) "SCANNING…" else MiniModel.footer(repos, hidden),
                    color = if (scanning || MiniModel.needsAttention(ordered.first())) HomeTokens.accent else HomeTokens.textDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** One repository: state dot, name, branch + tracking; ROOMY adds its changes and last commit. */
@Composable
private fun MiniRepoRow(repo: RepoSnapshot, roomy: Boolean, onOpen: () -> Unit) {
    val status = repo.status
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open ${repo.name}") { onOpen() }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniDot(repo)
            Spacer(Modifier.width(8.dp))
            MonoText(text = repo.name, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            if (status != null) {
                MonoText(text = branchText(status), color = HomeTokens.textDim, fontSize = 10.sp)
                val glyphs = GitPresentation.trackingGlyphs(status.ahead, status.behind)
                if (glyphs.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 10.sp)
                }
            } else {
                MonoText(text = "UNREADABLE", color = HomeTokens.danger, fontSize = 9.sp)
            }
        }
        if (roomy) {
            val line = when {
                status == null -> repo.error?.let { "git could not read this repository ($it)" } ?: "git could not read this repository"
                status.dirty -> status.summary()
                else -> null
            }
            if (line != null) {
                MonoText(
                    text = line,
                    color = if (status == null) HomeTokens.danger else HomeTokens.accent,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 1.dp),
                )
            }
            repo.log.firstOrNull()?.let { last ->
                MonoText(
                    text = "${last.hash}  ${last.subject}",
                    color = HomeTokens.textDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 1.dp),
                )
            }
        }
    }
}

/** Filled = uncommitted changes, ring = clean (accent when commits await a push/pull), danger = unreadable. */
@Composable
private fun MiniDot(repo: RepoSnapshot) {
    val s = repo.status
    val size = Modifier.size(8.dp)
    when {
        s == null -> Box(size.background(HomeTokens.danger, CircleShape))
        s.dirty -> Box(size.background(HomeTokens.accent, CircleShape))
        (s.ahead ?: 0) > 0 || (s.behind ?: 0) > 0 -> Box(size.border(1.5.dp, HomeTokens.accent, CircleShape))
        else -> Box(size.border(1.5.dp, HomeTokens.textDim, CircleShape))
    }
}

/** The honest non-list states: looking, not ready, failed, no git, no repositories. */
@Composable
private fun MiniStateBlock(ui: GitUi, scanning: Boolean, onOpenLinuxShell: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val ready = ui as? GitUi.Ready
    when {
        ui is GitUi.Probing || (scanning && ready == null) -> StateLine("Looking for repositories…")
        ui is GitUi.Unavailable -> {
            StateLine("Linux not ready")
            TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics", color = HomeTokens.accent) }
        }
        ui is GitUi.ProbeFailed -> {
            StateLine("Could not probe git")
            MonoText(text = ui.reason, color = HomeTokens.textDim, maxLines = 2)
        }
        ready != null && !ready.snapshot.hasGit -> {
            StateLine("Git is not installed")
            MonoText(text = "Install it from the Linux Shell (apk add git).", color = HomeTokens.textDim)
            TextButton(onClick = onOpenLinuxShell) { Text("Open Linux", color = HomeTokens.accent) }
        }
        ready != null -> {
            StateLine("No repositories")
            MonoText(text = "Clone or create a repository under ~/ or ~/Projects — it appears here.", color = HomeTokens.textDim, maxLines = 2)
            TextButton(onClick = onOpenLinuxShell) { Text("Open Linux", color = HomeTokens.accent) }
        }
    }
}
