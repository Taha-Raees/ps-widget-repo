package app.pocketshell.widget.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * The summary the MINI card and the maximized page share, so they say the same
 * thing in the same words:
 *
 *   Repositories                                   [terminal] [refresh]
 *   git 2.54.0 · scanned 2m ago
 *   ───────────────────────────────────────────────────────────────
 *    9 REPOS    2 DIRTY    ↑49 TO PUSH    ↓0 TO PULL
 *   ───────────────────────────────────────────────────────────────
 */
@Composable
internal fun GitSummaryHeader(
    ui: GitUi,
    repos: List<RepoSnapshot>,
    lastScanAtMs: Long,
    scanning: Boolean,
    titleSp: Int,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = ui as? GitUi.Ready
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Repositories",
                    fontSize = titleSp.sp,
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
    }
}

/** The four numbers: repositories, how many are dirty, commits to push, commits to pull. */
@Composable
internal fun GitSummaryStrip(ui: GitUi, repos: List<RepoSnapshot>, modifier: Modifier = Modifier) {
    val ahead = repos.sumOf { it.status?.ahead ?: 0 }
    val behind = repos.sumOf { it.status?.behind ?: 0 }
    val dirty = (ui as? GitUi.Ready)?.snapshot?.dirtyRepos ?: 0
    StatStrip(
        items = listOf(
            StripItem("${repos.size}", "repos"),
            StripItem("$dirty", "dirty", hot = dirty > 0),
            StripItem("↑$ahead", "to push", hot = ahead > 0),
            StripItem("↓$behind", "to pull", hot = behind > 0),
        ),
        modifier = modifier,
    )
}

internal data class StripItem(
    val value: String,
    val label: String,
    val hot: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** A flat row of big numbers over small-caps labels, ruled above and below. */
@Composable
internal fun StatStrip(items: List<StripItem>, modifier: Modifier = Modifier) {
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

