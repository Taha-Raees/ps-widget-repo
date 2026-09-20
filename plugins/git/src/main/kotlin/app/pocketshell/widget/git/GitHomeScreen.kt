package app.pocketshell.widget.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * HOME — the launch surface. It answers exactly the Home questions and
 * nothing more: which repositories exist, which are dirty/diverged, and
 * where to go next (tap = the repository's own surface). The LIST scrolls
 * when it is long (LazyColumn — many repositories cost what they render);
 * the whole screen never does. Honest whole-card states (probing / Linux
 * not ready / git absent / probe failed / no repositories) own the body
 * with their one real action.
 */
@Composable
internal fun GitHomeScreen(
    state: GitState,
    layout: GitLayout,
    onOpenLinuxShell: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val ready = state.ui as? GitUi.Ready
    val repos = ready?.snapshot?.repos.orEmpty()

    // The header: title + live facts + the icon controls. The search filter
    // appears when the list is long enough to need it (or the user asks).
    var filterActive by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val showFilterIcon = repos.size >= 6 || filterActive

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            if (showFilterIcon) {
                GitHomeIcon(
                    icon = if (filterActive) Icons.Outlined.Close else Icons.Outlined.FilterList,
                    label = if (filterActive) "Hide filter" else "Filter repositories",
                ) {
                    filterActive = !filterActive
                    if (!filterActive) filter = ""
                }
            }
            GitHomeIcon(icon = Icons.Outlined.Refresh, label = "Refresh repositories") {
                state.refreshTick++
            }
            GitMenu(
                items = listOf(
                    "Linux shell" to onOpenLinuxShell,
                    "Diagnostics" to onOpenDiagnostics,
                ),
            )
        }

        if (filterActive) {
            androidx.compose.material3.OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = {
                    Text(
                        "Filter by name, branch, path",
                        fontFamily = TerminalTheme.mono,
                        fontSize = 11.sp,
                        color = HomeTokens.textDim,
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = TerminalTheme.mono,
                    fontSize = 12.sp,
                    color = HomeTokens.textPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }

        val filtered = if (filter.isBlank()) {
            repos
        } else {
            val needle = filter.trim().lowercase()
            repos.filter { repo ->
                repo.name.lowercase().contains(needle) ||
                    repo.path.lowercase().contains(needle) ||
                    (repo.status?.branch ?: "").lowercase().contains(needle)
            }
        }

        when {
            ready == null && state.ui is GitUi.Probing -> StateBody(line = "Looking…")
            state.ui is GitUi.Unavailable -> StateBody(
                line = "Linux not ready",
                actionLabel = "Diagnostics",
                onAction = onOpenDiagnostics,
            )
            state.ui is GitUi.ProbeFailed -> StateBody(
                line = "Could not probe git",
                detail = (state.ui as GitUi.ProbeFailed).reason,
            )
            ready != null && !ready.snapshot.hasGit -> StateBody(
                line = "Git unavailable",
                detail = "Git is not installed in the guest — install it from the Linux Shell (apk add git).",
                actionLabel = "Open Linux",
                onAction = onOpenLinuxShell,
            )
            ready != null && repos.isEmpty() -> StateBody(
                line = "No repositories",
                detail = "Clone or create a repository under ~/ or ~/Projects — it appears here.",
                actionLabel = "Open Linux",
                onAction = onOpenLinuxShell,
            )
            filtered.isEmpty() -> StateBody(line = "No repositories match \"${filter.trim()}\"")
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.path }) { repo ->
                    GitRepoRow(repo = repo, roomy = layout.showsSublines) {
                        state.push(GitScreen.Repo(repo.path))
                    }
                }
            }
        }
    }
}

/** One repository row: name + dirty dot + glyphs, branch · summary below. */
@Composable
private fun GitRepoRow(repo: RepoSnapshot, roomy: Boolean, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open ${repo.name}") { onOpen() }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = repo.name,
                fontFamily = TerminalTheme.mono,
                fontSize = 13.sp,
                color = HomeTokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
        }
        val summary = repo.status?.summary()
        if (roomy && repo.error == null) {
            Text(
                text = displayGuestRepoPath(repo.path),
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = HomeTokens.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = when {
                repo.error != null -> "unreadable: ${repo.error}"
                summary != null -> "${branchFacts(repo.status).substringBefore(" →")} · $summary"
                else -> branchFacts(repo.status)
            },
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            color = if (repo.error != null) HomeTokens.danger else HomeTokens.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The 28dp icon hit area — the house standard, always named. */
@Composable
private fun GitHomeIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = HomeTokens.accent,
            modifier = Modifier
                .size(28.dp)
                .clickable(role = Role.Button, onClickLabel = label) { onClick() }
                .padding(5.dp),
        )
    }
}
