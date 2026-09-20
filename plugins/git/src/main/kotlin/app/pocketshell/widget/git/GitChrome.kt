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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * The compact CHROME the redesigned Git application is built from — one
 * implementation of each small-screen primitive, tuned for the Home
 * Application Card's ~200dp of height. Material's default bars, tabs and
 * dialogs are sized for full screens; these are the card-sized versions:
 *
 *   [GitTopBar]     — 24dp: ← title … icon actions (Material's is 64dp)
 *   [GitTabRow]     — 26dp scrollable folder-tabs (the card's own language)
 *   [GitMenu]       — the ⋮ overflow, items of icon-free words
 *   [OpBanner]      — the one honest line about a running/finished operation
 *   [GitConfirm]    — confirmation dialog (destructive tone when it is)
 *   [GitInput]      — one-field dialog (commit message, branch name, label)
 *
 * Every token comes from the shared theme; nothing here owns a color.
 */

/** The card-sized top bar: back when [onBack] != null, title, trailing actions. */
@Composable
internal fun GitTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = HomeTokens.accent,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(role = Role.Button, onClickLabel = "Back") { onBack() }
                    .padding(5.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = TerminalTheme.mono,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = HomeTokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontFamily = TerminalTheme.mono,
                    fontSize = 10.sp,
                    color = HomeTokens.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/**
 * The repository screen's tab row — the same folder-tab language as the
 * old repo selector: the ACTIVE tab carries the accent + underline, the
 * rest stay dim words. Horizontally scrollable when five tabs outrun a
 * narrow card; tappable only (NO swipe — the carousel outside owns
 * horizontal drags).
 */
@Composable
internal fun GitTabRow(
    tabs: List<RepoTab>,
    active: RepoTab,
    onSelect: (RepoTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEach { tab ->
            val label = tab.name
            val isActive = tab == active
            Column {
                Text(
                    text = label,
                    fontFamily = TerminalTheme.mono,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                    color = if (isActive) HomeTokens.accent else HomeTokens.textDim,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(role = Role.Tab, onClickLabel = "Show $label") { onSelect(tab) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (isActive) HomeTokens.accent else Color.Transparent),
                )
            }
        }
    }
}

/**
 * The ONE op flow a screen uses: a verb goes straight to the queue when it
 * is quiet and reversible, or through a confirmation dialog when
 * [opConfirmation] says it is risky — a dirty checkout gets its warning
 * appended. Every screen (repo, diff, stash) shares this so the
 * destructive-op gating exists in exactly one place.
 */
internal class GitOpQueue {
    var pending by mutableStateOf<PendingConfirm?>(null)

    fun run(state: GitState, repoPath: String, kind: OpKind, arg: String?) {
        val confirmation = opConfirmation(kind, arg)
        if (confirmation == null) {
            state.queueOp(kind, repoPath, arg)
            return
        }
        val body = if (kind == OpKind.CHECKOUT && state.repoDirty(repoPath)) {
            confirmation.body +
                " This repository has uncommitted changes — git may refuse or carry them along."
        } else {
            confirmation.body
        }
        pending = PendingConfirm(kind, arg, confirmation.copy(body = body))
    }
}

/** The screens' confirmation dialog — render it next to the screen content. */
@Composable
internal fun GitOpConfirmDialog(queue: GitOpQueue, state: GitState, repoPath: String) {
    val pending = queue.pending ?: return
    GitConfirm(
        confirmation = pending.confirmation,
        onConfirm = {
            state.queueOp(pending.kind, repoPath, pending.arg)
            queue.pending = null
        },
        onDismiss = { queue.pending = null },
    )
}

/** The ⋮ overflow: anchored to its own icon, items are plain words. */
@Composable
internal fun GitMenu(items: List<Pair<String, () -> Unit>>, enabled: Boolean = true) {
    androidx.compose.foundation.layout.Box {
        var open by remember { mutableStateOf(false) }
        Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = "More options",
            tint = HomeTokens.accent,
            modifier = Modifier
                .size(28.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "More options",
                    enabled = enabled,
                ) { open = true }
                .padding(5.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            fontFamily = TerminalTheme.mono,
                            fontSize = 13.sp,
                            color = HomeTokens.textPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

/** The ONE honest line about an operation: running, ok, or the real failure. */
@Composable
internal fun OpBanner(
    running: String?,
    result: GitOps.OpResult?,
    onDismiss: () -> Unit,
) {
    if (running != null) {
        BannerLine(text = "… $running", color = HomeTokens.textDim, onDismiss = null)
    } else if (result != null) {
        when (result) {
            is GitOps.OpResult.Ok ->
                BannerLine(text = "✓ ${result.summary}", color = HomeTokens.textDim, onDismiss = onDismiss)
            is GitOps.OpResult.Failed ->
                BannerLine(text = "✗ ${result.reason}", color = HomeTokens.danger, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun BannerLine(text: String, color: Color, onDismiss: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HomeTokens.surfaceBanner)
            .clickable(role = Role.Button, onClickLabel = "Dismiss", enabled = onDismiss != null) {
                onDismiss?.invoke()
            }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The one-field dialog: commit messages, branch names, stash labels. */
@Composable
internal fun GitInput(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontFamily = TerminalTheme.mono,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) {
                Text(confirmLabel, color = if (value.isNotBlank()) HomeTokens.accent else HomeTokens.textDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HomeTokens.textDim)
            }
        },
    )
}

/** The confirmation dialog: the operation's real verb + consequence. */
@Composable
internal fun GitConfirm(confirmation: OpConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = confirmation.title,
                fontFamily = TerminalTheme.mono,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = confirmation.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmation.confirmLabel,
                    color = if (confirmation.destructive) HomeTokens.danger else HomeTokens.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HomeTokens.textDim)
            }
        },
    )
}

/**
 * The choice dialog — one branch (or ref), a few verbs: the row tap opens
 * this instead of guessing which action the user wanted.
 */
@Composable
internal fun GitChoices(
    title: String,
    subtitle: String?,
    choices: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontFamily = TerminalTheme.mono,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                choices.forEach { (label, action) ->
                    Text(
                        text = label,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 13.sp,
                        color = HomeTokens.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = label) {
                                action()
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                    )
                }
                Text(
                    text = "Cancel",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Cancel") { onDismiss() }
                        .padding(vertical = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

/**
 * The honest full-body state (probing / unavailable / failed / empty):
 * one dim line, a real reason when there is one, and at most one action.
 */
@Composable
internal fun StateBody(
    line: String,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = HomeTokens.textDim,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = HomeTokens.textDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.padding(top = 2.dp)) {
                Text(actionLabel, color = HomeTokens.accent)
            }
        }
    }
}

/** The compact branch fact line: `main → origin/main ↑2 ↓1`. */
internal fun branchFacts(status: GitStatusParser.RepoStatus?): String = when {
    status == null -> "unknown"
    status.detached -> "detached HEAD"
    else -> buildString {
        append(status.branch ?: "unknown")
        if (status.noCommits) append(" (no commits yet)")
        if (status.upstream != null) {
            append(" → ").append(status.upstream)
            if (status.upstreamGone) append(" (gone)")
        }
        if ((status.ahead ?: 0) > 0) append(" ↑").append(status.ahead)
        if ((status.behind ?: 0) > 0) append(" ↓").append(status.behind)
    }
}

/** The compact action-row word (STAGE ALL / COMMIT / LOAD MORE class). */
@Composable
internal fun TextAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        fontFamily = TerminalTheme.mono,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp,
        color = if (enabled) HomeTokens.accent else HomeTokens.textDim,
        maxLines = 1,
        modifier = Modifier
            .clickable(role = Role.Button, onClickLabel = label, enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    )
}
