package app.pocketshell.widget.git

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pocketshell.ui.home.HomeTokens

/**
 * The MINI mode: the card shows ONLY the summary — the same header and four
 * numbers the maximized page opens with (repositories, dirty, to push, to
 * pull). No repository list, no folders: a glance, not a browser. The
 * repositories themselves live one maximize away.
 *
 * It is also cheap: a pure function of the cached snapshot. Nothing here scans;
 * the scan policy (once at start, on refresh, after a git action) lives in
 * GitApp.
 */
@Composable
internal fun GitMiniCard(
    ui: GitUi,
    repos: List<RepoSnapshot>,
    scanning: Boolean,
    lastScanAtMs: Long,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLinuxShell: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        GitSummaryHeader(ui, repos, lastScanAtMs, scanning, titleSp = 24, onRefresh = onRefresh, onOpenTerminal = onOpenTerminal)
        if (repos.isNotEmpty()) {
            GitSummaryStrip(ui, repos, modifier = Modifier.padding(top = 8.dp))
        } else {
            MiniStateBlock(ui, scanning, onOpenLinuxShell, onOpenDiagnostics)
        }
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
            MonoText(text = "Install it from the Linux Shell (apt install git).", color = HomeTokens.textDim)
            TextButton(onClick = onOpenLinuxShell) { Text("Open Linux", color = HomeTokens.accent) }
        }
        ready != null -> {
            StateLine("No repositories")
            MonoText(text = "Clone or create a repository under ~/ or ~/Projects — it appears here.", color = HomeTokens.textDim, maxLines = 2)
            TextButton(onClick = onOpenLinuxShell) { Text("Open Linux", color = HomeTokens.accent) }
        }
    }
}
